package ru.spgsroot.peachy.documents.office.engine

import java.io.File
import java.io.IOException
import ru.spgsroot.peachy.documents.DocumentFormat

/** A resolved, already-validated staged export. Only this file may ever reach SAF. */
data class OfficeExportPlan(
    val target: OfficeExportTarget,
    val stagedFile: File,
) {
    /** Value for `Document.saveAs(url, format, options)`; an extension token, not a filter name. */
    val formatArgument: String get() = target.formatArgument

    val optionsArgument: String get() = target.options
}

/**
 * Builds the staged exports. Every file is created inside the session's `exports/` directory with a
 * name the bridge controls; the working copy and `source.<ext>` are never touched, and a failed
 * export is never presented as a file the user may share.
 */
object OfficeExportPlanner {

    /** Export names and tokens are ASCII-only so the LOK file URL needs no escaping surprises. */
    private val TOKEN = Regex("[a-z0-9]{8,64}")

    private const val MAX_STAGED_NAME = 120

    fun plan(
        source: DocumentFormat,
        target: OfficeExportTarget,
        exportDir: File,
        token: String,
    ): Result<OfficeExportPlan> {
        if (!OfficeFormatMatrix.allows(source, target)) {
            return Result.failure(OfficeFailure.ExportFailure(OfficeExportFailure.NOT_ALLOWED))
        }
        if (!TOKEN.matches(token)) {
            return Result.failure(OfficeFailure.ExportFailure(OfficeExportFailure.PATH_ESCAPE))
        }
        if (!exportDir.isAbsolute) {
            return Result.failure(OfficeFailure.ExportFailure(OfficeExportFailure.PATH_ESCAPE))
        }
        val directory = try {
            exportDir.canonicalFile
        } catch (failure: IOException) {
            return Result.failure(OfficeFailure.ExportFailure(OfficeExportFailure.PATH_ESCAPE))
        }
        val name = "office-$token.${target.extension}"
        if (name.length > MAX_STAGED_NAME) {
            return Result.failure(OfficeFailure.ExportFailure(OfficeExportFailure.PATH_ESCAPE))
        }
        val staged = File(directory, name)
        val expected = directory.path + File.separator + name
        val canonical = try {
            staged.canonicalPath
        } catch (failure: IOException) {
            return Result.failure(OfficeFailure.ExportFailure(OfficeExportFailure.PATH_ESCAPE))
        }
        if (canonical != expected) {
            return Result.failure(OfficeFailure.ExportFailure(OfficeExportFailure.PATH_ESCAPE))
        }
        return Result.success(OfficeExportPlan(target, staged))
    }
}

/**
 * Proves the engine actually wrote something of the promised shape. `Document.saveAs` is declared
 * `void` in the pinned Java glue (`android/Bootstrap/.../Document.java`) even though the JNI returns
 * an int, so the file itself is the only trustworthy success signal. This mirrors the app-wide rule
 * in `DocumentDelivery`: validate before the SAF target is opened, never after.
 */
object OfficeExportValidator {

    const val MAX_EXPORT_BYTES = 512L * 1024L * 1024L

    private const val HEAD_BYTES = 64

    fun check(file: File, target: OfficeExportTarget): OfficeExportFailure? {
        if (!file.isFile) return OfficeExportFailure.MISSING_OUTPUT
        val length = file.length()
        if (length <= 0L) return OfficeExportFailure.EMPTY_OUTPUT
        if (length > MAX_EXPORT_BYTES) return OfficeExportFailure.TOO_LARGE
        val head = try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(HEAD_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                buffer.copyOf(total)
            }
        } catch (failure: IOException) {
            return OfficeExportFailure.MISSING_OUTPUT
        }
        if (head.isEmpty()) return OfficeExportFailure.EMPTY_OUTPUT
        return when (target.signature) {
            OfficeSignature.ZIP -> if (isZip(head)) null else OfficeExportFailure.BAD_SIGNATURE
            OfficeSignature.PDF -> if (asciiAt(head, 0, "%PDF-")) null else OfficeExportFailure.BAD_SIGNATURE
            OfficeSignature.RTF -> if (asciiAt(trimLeadingSpace(head), 0, "{\\rtf")) null else OfficeExportFailure.BAD_SIGNATURE
            OfficeSignature.HTML -> if (trimLeadingSpace(head).firstOrNull() == '<'.code.toByte()) {
                null
            } else {
                OfficeExportFailure.BAD_SIGNATURE
            }

            OfficeSignature.TEXT -> if (head.none { it == 0.toByte() }) null else OfficeExportFailure.BAD_SIGNATURE
        }
    }

    private fun isZip(head: ByteArray): Boolean {
        if (head.size < 4) return false
        if (head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) return false
        return head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte()
    }

    private fun trimLeadingSpace(head: ByteArray): ByteArray {
        var start = 0
        while (start < head.size && (head[start] == ' '.code.toByte() ||
                head[start] == '\n'.code.toByte() ||
                head[start] == '\r'.code.toByte() ||
                head[start] == '\t'.code.toByte())
        ) {
            start++
        }
        return if (start == 0) head else head.copyOfRange(start, head.size)
    }

    private fun asciiAt(bytes: ByteArray, offset: Int, prefix: String): Boolean {
        if (bytes.size < offset + prefix.length) return false
        for (index in prefix.indices) {
            if (bytes[offset + index] != prefix[index].code.toByte()) return false
        }
        return true
    }
}
