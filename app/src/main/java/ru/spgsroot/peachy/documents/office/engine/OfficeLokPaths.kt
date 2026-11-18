package ru.spgsroot.peachy.documents.office.engine

import java.io.File

/**
 * LOK receives URLs, not files: `desktop/source/lib/init.cxx` resolves every load/save argument with
 * `getAbsoluteURL()` (`rtl::Uri::convertRelToAbs`) and the upstream Android client passes
 * `"file://" + path`. The bridge therefore hands the engine a `file://` URL of an absolute, managed
 * path — never a content://, http:// or other ambient URL — and refuses paths it would have to guess
 * how to escape.
 */
object OfficeLokPaths {

    private const val HEX = "0123456789ABCDEF"

    /** Absolute POSIX path built only from unreserved characters; keeps URL escaping a no-op. */
    private val MANAGED_PATH = Regex("/[A-Za-z0-9._/-]{1,400}")

    fun isManaged(absolutePath: String): Boolean = MANAGED_PATH.matches(absolutePath)

    fun lokUri(file: File): Result<String> = lokUri(file.absolutePath)

    fun lokUri(absolutePath: String): Result<String> {
        if (!absolutePath.startsWith('/') || !isManaged(absolutePath)) {
            return Result.failure(OfficeFailure.Refused(OfficeRefusal.UNMANAGED_SOURCE))
        }
        return Result.success("file://" + encode(absolutePath))
    }

    /**
     * Percent-encodes everything outside the URI unreserved set, keeping `/` as a separator. Managed
     * names are ASCII already, so this only matters for the defensive tests and future callers.
     */
    fun encode(absolutePath: String): String {
        val encoded = StringBuilder(absolutePath.length + 8)
        var index = 0
        while (index < absolutePath.length) {
            val codePoint = absolutePath.codePointAt(index)
            index += if (codePoint >= 0x10000) 2 else 1
            if (codePoint == '/'.code || isUnreserved(codePoint)) {
                encoded.appendCodePoint(codePoint)
            } else {
                for (byte in utf8(codePoint)) {
                    val value = byte.toInt() and 0xFF
                    encoded.append('%').append(HEX[value ushr 4]).append(HEX[value and 0x0F])
                }
            }
        }
        return encoded.toString()
    }

    private fun isUnreserved(codePoint: Int): Boolean =
        codePoint in 'a'.code..'z'.code ||
            codePoint in 'A'.code..'Z'.code ||
            codePoint in '0'.code..'9'.code ||
            codePoint == '-'.code || codePoint == '.'.code ||
            codePoint == '_'.code || codePoint == '~'.code

    private fun utf8(codePoint: Int): ByteArray = when {
        codePoint < 0x80 -> byteArrayOf(codePoint.toByte())
        codePoint < 0x800 -> byteArrayOf(
            (0xC0 or (codePoint shr 6)).toByte(),
            (0x80 or (codePoint and 0x3F)).toByte(),
        )

        codePoint < 0x10000 -> byteArrayOf(
            (0xE0 or (codePoint shr 12)).toByte(),
            (0x80 or ((codePoint shr 6) and 0x3F)).toByte(),
            (0x80 or (codePoint and 0x3F)).toByte(),
        )

        else -> byteArrayOf(
            (0xF0 or (codePoint shr 18)).toByte(),
            (0x80 or ((codePoint shr 12) and 0x3F)).toByte(),
            (0x80 or ((codePoint shr 6) and 0x3F)).toByte(),
            (0x80 or (codePoint and 0x3F)).toByte(),
        )
    }
}
