package ru.spgsroot.peachy.documents

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.data.db.AppDatabase

/**
 * The HUB implementation of the shared contract: private session storage under `filesDir/documents`,
 * SAF delivery into a `CreateDocument` uri, and a folder-tree export for multi-file outputs.
 *
 * No URI grant is kept and nothing here talks to the network: the imported bytes are copied once and
 * every editor works on the private working copy.
 */
@Singleton
class DocumentSessionsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    database: AppDatabase,
) : DocumentSessions {

    private val store = DocumentStore(
        root = File(context.filesDir, DOCUMENTS_DIRECTORY),
        dao = database.documentSessionDao(),
        availableBytes = { availableBytes(context) },
    )

    override suspend fun get(id: String): DocumentSession? = withContext(Dispatchers.IO) {
        store.get(id)
    }

    override suspend fun markDirty(id: String) {
        withContext(Dispatchers.IO) { store.markDirty(id) }
    }

    override fun exportDir(id: String): File = store.exportDir(id)

    override suspend fun deliver(
        id: String,
        produced: File,
        kind: DocumentKind?,
        target: Uri,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            store.get(id) ?: return@withContext Result.failure(IOException("Unknown document session"))
            val job: Job? = currentCoroutineContext()[Job]
            DocumentDelivery.deliver(
                produced = produced,
                kind = kind,
                openTarget = {
                    // "wt": the provider truncates only now, after the export was proven complete.
                    context.contentResolver.openOutputStream(target, "wt")
                        ?: throw IOException("Cannot open the chosen destination")
                },
                isActive = { job?.isActive != false },
            ).onFailure { deleteTarget(target) }
        }

    override suspend fun deliverMany(id: String, files: List<File>, tree: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            store.get(id) ?: return@withContext Result.failure(IOException("Unknown document session"))
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )
            val failed = mutableListOf<String>()
            files.forEach { file ->
                try {
                    writeInto(resolver, parent, file)
                } catch (failure: Exception) {
                    failed += file.name
                }
            }
            if (failed.isEmpty()) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("${failed.size} of ${files.size} files were not written"))
            }
        }

    override suspend fun saveLastPosition(id: String, page: Int, scroll: Int) {
        withContext(Dispatchers.IO) { store.saveLastPosition(id, page, scroll) }
    }

    internal suspend fun publishImageEdit(
        id: String,
        previous: File,
        history: String,
        writeRaster: (java.io.OutputStream) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        store.publishImageEdit(id, previous, history, writeRaster)
    }

    fun exportFile(id: String, extension: String): File = store.exportFile(id, extension)

    fun sessionDir(id: String): File = store.sessionDir(id)

    override suspend fun discard(id: String): Result<Unit> = store.discard(id)

    /**
     * Imports one picked document. The read grant is used once and not persisted; on any failure the
     * session directory is rolled back and no row survives.
     */
    suspend fun import(uri: Uri): Result<DocumentSession> = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") {
            return@withContext Result.failure(IllegalArgumentException("Only content URIs can be imported"))
        }
        val job: Job? = currentCoroutineContext()[Job]
        val (name, size, mime) = metadata(uri)
        try {
            Result.success(
                store.importSession(
                    displayName = name,
                    sourceMime = mime,
                    declaredBytes = size,
                    open = {
                        context.contentResolver.openInputStream(uri)
                            ?: throw IOException("Cannot read the picked file")
                    },
                    isActive = { job?.isActive != false },
                )
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun metadata(uri: Uri): Triple<String?, Long, String?> {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return Triple(name, size, mime)
    }

    private fun writeInto(
        resolver: android.content.ContentResolver,
        parent: Uri,
        file: File,
    ) {
        val extension = file.extension.lowercase().filter { it.isLetterOrDigit() }.take(8)
        val format = DocumentFormat.fromName(file.name)
        val kind = when (extension) {
            "png", "jpg", "jpeg", "webp", "bmp", "heic" -> DocumentKind.IMAGE
            "pdf" -> DocumentKind.PDF
            else -> null
        }
        val mime = format?.mime ?: "application/octet-stream"
        val name = suggestedDocumentName(file.nameWithoutExtension, format ?: DocumentFormat.PDF)
            .let { if (extension.isEmpty()) it else it.substringBeforeLast('.') + "." + extension }
        val target = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("Cannot create $name")
        try {
            DocumentDelivery.deliver(
                produced = file,
                kind = kind,
                openTarget = { resolver.openOutputStream(target, "wt") ?: throw IOException("Cannot open $name") },
            ).getOrThrow()
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw failure
        }
    }

    private fun deleteTarget(target: Uri) {
        runCatching {
            if (DocumentsContract.isDocumentUri(context, target)) {
                DocumentsContract.deleteDocument(context.contentResolver, target)
            }
        }
    }

    private companion object {
        const val DOCUMENTS_DIRECTORY = "documents"

        fun availableBytes(context: Context): Long = runCatching {
            StatFs(context.filesDir.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }
}
