package ru.spgsroot.peachy.documents.office.engine

import java.security.MessageDigest

/**
 * Limits for materialising the `assets/unpack` tree into app storage before native init.
 *
 * The pinned `unpack` tree is small (`program/{udkapi,offapi,oovbaapi}.rdb`,
 * `program/sofficerc`, `user/fonts/` fonts, `etc/fonts/fonts.conf`; see
 * `android/source/build.gradle.kts` `copyUnpackAssets`), but the extractor treats the asset tree as
 * untrusted input: an oversized or hostile tree is refused instead of filling the device.
 */
object OfficeUnpackLimits {
    const val MAX_FILES = 4_096
    const val MAX_FILE_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_DEPTH = 8
    const val MAX_NAME_LENGTH = 200
    const val MAX_RELATIVE_PATH_LENGTH = 512
    const val MAX_MARKER_BYTES = 4_096
}

/** Names and marker format of the app-owned unpack store. */
object OfficeUnpackLayout {
    const val ASSET_ROOT = "unpack"
    const val OWNED_ROOT = "office-engine"
    const val MARKER_NAME = ".office-unpack-complete"
    const val MARKER_FORMAT = 1
    const val COMMIT_PREFIX = 12
    const val FINGERPRINT_PREFIX = 16

    /** `$APP_DATA_DIR/cache`, referenced by the generated `program/sofficerc` (`HOME=`). */
    const val CACHE_DIRECTORY = "cache"
}

/** Per-file admission for the copy loop; pure so the caps are testable without a device. */
object OfficeUnpackBudget {
    fun allowsFile(filesCopied: Int, bytesCopied: Long, fileBytes: Long): Boolean =
        filesCopied >= 0 &&
            filesCopied < OfficeUnpackLimits.MAX_FILES &&
            fileBytes in 0..OfficeUnpackLimits.MAX_FILE_BYTES &&
            bytesCopied >= 0 &&
            bytesCopied <= OfficeUnpackLimits.MAX_TOTAL_BYTES - fileBytes
}

/** Completion marker, written last; a missing or mismatching marker forces a fresh extraction. */
data class OfficeUnpackMarker(
    val format: Int,
    val commit: String,
    val fingerprint: String,
    val files: Int,
) {
    fun render(): String = buildString {
        appendLine("format=$format")
        appendLine("commit=$commit")
        appendLine("fingerprint=$fingerprint")
        appendLine("files=$files")
    }

    companion object {
        fun parse(text: String?): OfficeUnpackMarker? {
            if (text == null || text.length > OfficeUnpackLimits.MAX_MARKER_BYTES) return null
            val values = HashMap<String, String>(8)
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val separator = line.indexOf('=')
                if (separator <= 0) return null
                if (values.put(line.substring(0, separator), line.substring(separator + 1)) != null) {
                    return null
                }
            }
            val format = values["format"]?.toIntOrNull() ?: return null
            val commit = values["commit"] ?: return null
            val fingerprint = values["fingerprint"] ?: return null
            val files = values["files"]?.toIntOrNull() ?: return null
            if (format != OfficeUnpackLayout.MARKER_FORMAT) return null
            if (!commit.all { it.isDigit() || it in 'a'..'f' }) return null
            if (!fingerprint.all { it.isDigit() || it in 'a'..'f' }) return null
            if (files < 0) return null
            return OfficeUnpackMarker(format, commit, fingerprint, files)
        }
    }
}

fun markerMatches(actual: OfficeUnpackMarker?, expected: OfficeUnpackMarker): Boolean =
    actual != null &&
        actual.format == expected.format &&
        actual.commit == expected.commit &&
        actual.fingerprint == expected.fingerprint &&
        actual.files == expected.files

/**
 * One asset path segment as returned by `AssetManager.list`. Traversal, separators, control
 * characters and leading dots (which would collide with the marker) are refused.
 */
fun isSafeAssetName(name: String): Boolean =
    name.isNotEmpty() &&
        name.length <= OfficeUnpackLimits.MAX_NAME_LENGTH &&
        !name.startsWith('.') &&
        !name.startsWith(' ') &&
        !name.endsWith(' ') &&
        name.none { it == '/' || it == '\\' || it == '\u0000' || it.code < 0x20 || it.code == 0x7F }

/** Validates an extracted relative path (no asset prefix) and returns it, or `null` to refuse it. */
fun safeRelativePath(raw: String): String? {
    if (raw.isEmpty()) return null
    if (raw.length > OfficeUnpackLimits.MAX_RELATIVE_PATH_LENGTH) return null
    if (raw.startsWith('/') || raw.endsWith('/')) return null
    val segments = raw.split('/')
    if (segments.size > OfficeUnpackLimits.MAX_DEPTH) return null
    if (segments.any { !isSafeAssetName(it) }) return null
    return raw
}

/**
 * Directory version stamp: sha256 over the sorted relative paths plus the APK size and mtime.
 * A rebuilt APK or a changed asset list therefore yields a different directory instead of silently
 * reusing stale files.
 */
fun unpackFingerprint(relativePaths: Collection<String>, apkBytes: Long, apkModified: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("office-unpack-v1\n".toByteArray(Charsets.UTF_8))
    for (path in relativePaths.sorted()) {
        digest.update(path.toByteArray(Charsets.UTF_8))
        digest.update('\n'.code.toByte())
    }
    digest.update("apk=$apkBytes:$apkModified".toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** `unpack-<commit12>-<fingerprint16>`: the only directory names this worker may create or delete. */
fun unpackDirectoryName(commit: String, fingerprint: String): String {
    val commitPart = commit.lowercase().filter { it.isDigit() || it in 'a'..'f' }
        .take(OfficeUnpackLayout.COMMIT_PREFIX)
        .ifEmpty { "unknown" }
    val fingerprintPart = fingerprint.lowercase().filter { it.isDigit() || it in 'a'..'f' }
        .take(OfficeUnpackLayout.FINGERPRINT_PREFIX)
    return "unpack-$commitPart-$fingerprintPart"
}

private val OWNED_UNPACK = Regex("unpack-[0-9a-f]{12}-[0-9a-f]{16}")
private val OWNED_UNPACK_STAGING = Regex("unpack-[0-9a-f]{12}-[0-9a-f]{16}\\.tmp")

/**
 * True only for directories this worker owns. Used as the guard before any deletion, so a stray
 * name can never make the bridge remove something it did not extract.
 */
fun isOwnedUnpackName(name: String): Boolean = OWNED_UNPACK.matches(name)

/** True only for this worker's crash-leftover staging directories (`unpack-…-….tmp`). */
fun isOwnedUnpackStagingName(name: String): Boolean = OWNED_UNPACK_STAGING.matches(name)
