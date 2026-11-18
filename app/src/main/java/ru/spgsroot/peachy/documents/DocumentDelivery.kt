package ru.spgsroot.peachy.documents

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The rule for every export: the produced file is proven complete (exists, non-empty, header matches
 * the family) **before** the destination is opened. Opening a `CreateDocument` uri truncates it, so
 * an invalid export must fail while the user's target document is still untouched.
 */
internal object DocumentDelivery {

    private const val MAX_EXPORT_BYTES = 512L * MIB
    private const val COPY_BUFFER = 64 * 1024

    fun requireComplete(produced: File, kind: DocumentKind?) {
        if (!produced.isFile) throw IOException("No exported file at ${produced.name}")
        val length = produced.length()
        if (length <= 0L) throw IOException("Exported file is empty")
        if (length > MAX_EXPORT_BYTES) throw IOException("Exported file is implausibly large")
        if (kind == null) return
        val head = produced.inputStream().use { readAtMost(it, 16) }
        when (kind) {
            DocumentKind.PDF -> if (!head.asciiStartsWith("%PDF-")) {
                throw IOException("Exported file is not a PDF")
            }

            DocumentKind.IMAGE -> if (!isKnownImage(head)) {
                throw IOException("Exported file is not a supported image")
            }

            else -> Unit
        }
    }

    /**
     * Streams [produced] into the target returned by [openTarget]. Callers report the failure; there
     * is no state in which a half-written export is presented as success.
     */
    fun deliver(
        produced: File,
        kind: DocumentKind?,
        openTarget: () -> OutputStream,
        isActive: () -> Boolean = { true },
    ): Result<Unit> = try {
        requireComplete(produced, kind)
        openTarget().use { output ->
            produced.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    if (!isActive() || Thread.currentThread().isInterrupted) {
                        throw IOException("Delivery cancelled")
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                }
            }
        }
        Result.success(Unit)
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private fun isKnownImage(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val png = head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        val jpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        val riff = head.size >= 12 && head.asciiStartsWith("RIFF") && String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
        val bmp = head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte()
        val heif = head.size >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
        return png || jpeg || riff || bmp || heif
    }

    private fun readAtMost(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = stream.read(buffer, total, limit - total)
            if (read < 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    private fun ByteArray.asciiStartsWith(prefix: String): Boolean {
        if (size < prefix.length) return false
        for (index in prefix.indices) if (this[index] != prefix[index].code.toByte()) return false
        return true
    }
}
