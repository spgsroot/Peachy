package ru.spgsroot.peachy.documents

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.data.db.DocumentSessionDao
import ru.spgsroot.peachy.data.db.DocumentSessionEntity

internal fun imageHistoryFile(raster: File): File =
    File(raster.parentFile, if (raster.name.startsWith("working-")) "${raster.nameWithoutExtension}.json" else "ops.json")

/**
 * Session state that has no Android dependency: the directory layout, the import transaction, the
 * dirty flag and the discard rules. [DocumentSessionsImpl] adds SAF delivery on top.
 *
 * Import is a transaction: the picked bytes become `source.<ext>`, then `working.<ext>` is copied
 * from it, then the row is inserted. Any failure deletes the directory again, so a failed import
 * never leaves a listed session, and a listed session never has a missing working copy.
 */
internal class DocumentStore(
    root: File,
    private val dao: DocumentSessionDao,
    availableBytes: () -> Long = { Long.MAX_VALUE },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val files = DocumentFiles(root, availableBytes, clock)

    fun exportDir(id: String): File = files.exportDir(id)

    fun sessionDir(id: String): File = files.dir(id)

    fun exportFile(id: String, extension: String): File = files.createExport(id, extension)

    suspend fun get(id: String): DocumentSession? {
        if (!DocumentFiles.isSessionId(id)) return null
        val entity = dao.getById(id) ?: return null
        val format = DocumentFormat.fromId(entity.format) ?: return null
        val kind = DocumentKind.entries.firstOrNull { it.name == entity.kind } ?: return null
        val working = File(entity.workingPath)
        // A row pointing outside its own directory is corrupt, not a document to open.
        if (!files.contains(working) || working.parentFile?.name != id) return null
        return DocumentSession(
            id = entity.id,
            displayName = entity.displayName,
            format = format.id,
            kind = kind,
            workingFile = working,
            dirty = entity.dirty,
        )
    }

    /**
     * Copies [open]'s stream in, sniffs it, then commits source + working copy + row.
     * Typed [DocumentFormatException] failures reach the caller unchanged.
     */
    suspend fun importSession(
        displayName: String?,
        sourceMime: String?,
        declaredBytes: Long,
        open: () -> InputStream,
        isActive: () -> Boolean = { true },
    ): DocumentSession {
        val id = files.newSessionId()
        var inserted = false
        try {
            // A provider that reports a size is refused before its stream is opened or a byte copied.
            if (declaredBytes > ABSOLUTE_MAX_DOCUMENT_BYTES) {
                throw DocumentFormatException(
                    DocumentFormatError.TOO_LARGE,
                    "declared $declaredBytes bytes, the import limit is $ABSOLUTE_MAX_DOCUMENT_BYTES"
                )
            }
            val pending = files.copyPending(id, open(), declaredBytes, ABSOLUTE_MAX_DOCUMENT_BYTES, isActive)
            if (!isActive()) throw InterruptedIOException("Import cancelled")
            val detection = DocumentSniffer.sniff(pending, displayName)
            val directory = files.dir(id)
            val source = files.rename(pending, File(directory, DocumentFiles.sourceName(detection.format)))
            val working = File(directory, "working.${detection.format.extension}")
            files.copyAtomically(source, working)
            val name = cleanDisplayName(displayName) ?: "Document.${detection.format.extension}"
            val now = clock()
            val row = DocumentSessionEntity(
                id = id,
                displayName = name,
                sourceMime = sourceMime,
                format = detection.format.id,
                kind = detection.format.kind.name,
                workingPath = working.absolutePath,
                dirty = false,
                createdAt = now,
                updatedAt = now,
                lastPage = null,
                lastScroll = null,
            )
            dao.insert(row)
            inserted = true
            return DocumentSession(id, name, detection.format.id, detection.format.kind, working, false)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (inserted) {
                    runCatching { dao.deleteById(id) }.onFailure { failure.addSuppressed(it) }
                }
                if (!files.deleteSession(id)) {
                    failure.addSuppressed(IOException("Cannot roll back the imported document"))
                }
            }
            throw failure
        }
    }

    suspend fun markDirty(id: String) {
        if (!DocumentFiles.isSessionId(id)) return
        dao.markDirty(id, clock())
    }

    suspend fun saveLastPosition(id: String, page: Int, scroll: Int) {
        if (!DocumentFiles.isSessionId(id)) return
        dao.saveLastPosition(id, page.coerceAtLeast(0), scroll.coerceAtLeast(0), clock())
    }

    /** Publish a complete raster and its history with one pointer update. */
    suspend fun publishImageEdit(
        id: String,
        previous: File,
        history: String,
        writeRaster: (OutputStream) -> Unit,
    ): File {
        val directory = files.dir(id)
        if (!files.contains(previous) || previous.parentFile?.canonicalFile != directory.canonicalFile) {
            throw IOException("Working file must exist inside the session directory")
        }
        val raster = File(directory, "working-${UUID.randomUUID()}.png")
        val ops = imageHistoryFile(raster)
        try {
            files.writeAtomically(raster, writeRaster)
            files.writeAtomically(ops) { it.write(history.toByteArray()) }
            if (dao.publishWorkingPath(id, previous.absolutePath, raster.absolutePath, clock()) != 1) {
                throw IOException("Image working copy changed before publication")
            }
        } catch (failure: Throwable) {
            ops.delete()
            raster.delete()
            throw failure
        }
        previous.delete()
        imageHistoryFile(previous).delete()
        return raster
    }

    /**
     * Deletes the directory first, then the row. A directory that survives keeps its row, so the
     * session stays visible and the user can retry instead of losing the files silently.
     */
    suspend fun discard(id: String): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        if (!DocumentFiles.isSessionId(id)) {
            return@withContext Result.failure(IllegalArgumentException("Not a document session id"))
        }
        if (!files.deleteSession(id)) {
            return@withContext Result.failure(IOException("Cannot delete the document files"))
        }
        try {
            dao.deleteById(id)
            Result.success(Unit)
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun cleanDisplayName(displayName: String?): String? {
        val name = displayName
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.map { if (it.isISOControl()) ' ' else it }
            ?.joinToString("")
            ?.replace(Regex("\\s+"), " ")
            ?.trim(' ', '.')
            ?.take(200)
            ?: return null
        return name.takeUnless { it.isBlank() || it.all { char -> char == '.' } }
    }
}
