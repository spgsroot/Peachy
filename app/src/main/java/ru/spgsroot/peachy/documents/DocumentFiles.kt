package ru.spgsroot.peachy.documents

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.UUID

/**
 * The filesystem half of a document session: `filesDir/documents/<uuid>/source.<ext>`,
 * `working.<ext>` and `exports/`. Every session id is a UUID and every path stays inside its own
 * directory (canonical containment), so no stored or imported string can reach another session or
 * the rest of app storage.
 *
 * Pure JVM on purpose: the import, atomic-replace and rollback rules are unit-testable without an
 * Android runtime.
 */
internal class DocumentFiles(
    private val root: File,
    private val availableBytes: () -> Long = { Long.MAX_VALUE },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun dir(id: String): File {
        requireSessionId(id)
        return File(root, id)
    }

    fun sessionDir(id: String): File = dir(id).also { file ->
        if (!file.isDirectory && !file.mkdirs() && !file.isDirectory) {
            throw IOException("Cannot create the document directory")
        }
    }

    fun exportDir(id: String): File = File(sessionDir(id), EXPORTS).also { file ->
        if (!file.isDirectory && !file.mkdirs() && !file.isDirectory) {
            throw IOException("Cannot create the export directory")
        }
    }

    fun newSessionId(): String = UUID.randomUUID().toString()

    /** Free space is checked against the copy plus the reserve, like InternalStorageManager does. */
    fun requireFreeSpace(bytes: Long) {
        val available = availableBytes()
        if (available == Long.MAX_VALUE) return
        val needed = bytes.coerceAtLeast(0) + MIN_FREE_BYTES
        if (needed > available) {
            throw DocumentFormatException(
                DocumentFormatError.NOT_ENOUGH_SPACE,
                "need $needed bytes free, $available available"
            )
        }
    }

    /** Copies the picked source into `source.pending`, checking cancellation between chunks. */
    fun copyPending(
        id: String,
        source: InputStream,
        declaredBytes: Long,
        maxBytes: Long,
        isActive: () -> Boolean = { true },
    ): File {
        val directory = sessionDir(id)
        // A provider that reports a size is refused before a byte is copied.
        if (declaredBytes > maxBytes) {
            throw DocumentFormatException(
                DocumentFormatError.TOO_LARGE,
                "declared $declaredBytes bytes, the limit is $maxBytes"
            )
        }
        requireFreeSpace(declaredBytes.coerceAtLeast(0))
        val pending = File(directory, PENDING_NAME)
        try {
            source.use { input ->
                FileOutputStream(pending).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var copied = 0L
                    while (true) {
                        if (!isActive() || Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("Import cancelled")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        if (copied > maxBytes) {
                            throw DocumentFormatException(
                                DocumentFormatError.TOO_LARGE,
                                "file exceeds $maxBytes bytes"
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (pending.length() == 0L) throw DocumentFormatException(DocumentFormatError.EMPTY, "empty file")
            return pending
        } catch (failure: Throwable) {
            pending.delete()
            throw failure
        }
    }

    fun rename(from: File, to: File): File {
        if (!from.renameTo(to)) {
            from.delete()
            throw IOException("Cannot finish the copied document")
        }
        return to
    }

    /** Copies a complete file into a new one atomically (temp + rename). */
    fun copyAtomically(source: File, target: File) {
        val length = source.length()
        requireFreeSpace(length)
        writeAtomically(target) { output ->
            source.inputStream().use { it.copyTo(output, COPY_BUFFER) }
        }
    }

    /**
     * Writes [target] through a sibling temp file and renames it into place. A reader therefore
     * sees the previous complete file or the new complete file, never a truncated one.
     */
    fun writeAtomically(target: File, write: (OutputStream) -> Unit): File {
        val directory = target.parentFile ?: throw IOException("No directory for ${target.name}")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create ${directory.name}")
        }
        val temp = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                write(output)
                output.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("Cannot replace ${target.name}")
            return target
        } catch (failure: Throwable) {
            temp.delete()
            throw failure
        }
    }

    /** Exports are scratch files: created under `exports/` only, never next to the working copy. */
    fun createExport(id: String, extension: String): File {
        val directory = exportDir(id)
        val safe = extension.lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        return File.createTempFile("export_${clock()}_", ".$safe", directory)
    }

    /** Deletes the session directory recursively; returns false if anything survived. */
    fun deleteSession(id: String): Boolean {
        val directory = dir(id)
        if (!contains(directory) || !directory.exists()) return !directory.exists()
        if (!directory.isDirectory) return directory.delete()
        var allDeleted = true
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                if (!child.deleteRecursively()) allDeleted = false
            } else if (!child.delete()) {
                allDeleted = false
            }
        }
        val removed = directory.delete()
        return removed && allDeleted
    }

    /** Canonical containment: the app never deletes or writes outside the documents root. */
    fun contains(file: File): Boolean {
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return target.path.startsWith(base.path + File.separator)
    }

    companion object {
        const val EXPORTS = "exports"
        const val PENDING_NAME = "source.pending"
        private const val COPY_BUFFER = 64 * 1024

        private val SESSION_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun isSessionId(id: String?): Boolean = id != null && SESSION_ID.matches(id)

        fun requireSessionId(id: String) {
            if (!isSessionId(id)) throw IllegalArgumentException("Not a document session id")
        }

        fun sourceName(format: DocumentFormat): String = "source.${format.extension}"
    }
}
