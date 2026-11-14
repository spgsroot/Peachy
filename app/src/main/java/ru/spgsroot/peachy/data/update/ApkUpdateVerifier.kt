package ru.spgsroot.peachy.data.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class ApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int
)

/** Checks content integrity, not the APK signing certificate. Android verifies signatures on install. */
fun verifyApkSha256(file: File, expectedSha256: String) {
    val expected = decodeSha256(expectedSha256)
    val actual = try {
        file.inputStream().use { digestStream(it) }
    } catch (exception: IOException) {
        throw UpdateException(UpdateError.INTEGRITY, "Cannot read the downloaded APK", exception)
    }
    if (!MessageDigest.isEqual(expected, actual)) {
        throw UpdateException(UpdateError.INTEGRITY, "APK digest does not match the release")
    }
}

fun validateApkIdentity(
    identity: ApkIdentity,
    release: AppRelease,
    installedPackage: String,
    installedVersionCode: Long,
    sdkInt: Int
) {
    if (identity.packageName != installedPackage || identity.versionName != release.versionName) {
        throw UpdateException(UpdateError.INVALID_APK, "APK package or version does not match the release")
    }
    if (identity.versionCode <= installedVersionCode || identity.minSdk > sdkInt) {
        throw UpdateException(UpdateError.INCOMPATIBLE, "APK is not a supported upgrade")
    }
}

/** The caller supplies a private temporary file; no partial file survives an unsuccessful copy. */
internal suspend fun copyApkWithIntegrityCheck(source: InputStream, target: File, release: AppRelease) {
    var verified = false
    try {
        val expected = decodeSha256(release.sha256)
        if (release.apkSize <= 0L) throw UpdateException(UpdateError.INTEGRITY, "Invalid APK size")
        val digest = MessageDigest.getInstance("SHA-256")
        val coroutineContext = currentCoroutineContext()
        var copied = 0L
        source.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count.toLong() > release.apkSize - copied) {
                        throw UpdateException(UpdateError.INTEGRITY, "APK exceeds the advertised size")
                    }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    copied += count
                }
            }
        }
        coroutineContext.ensureActive()
        if (copied != release.apkSize || !MessageDigest.isEqual(expected, digest.digest())) {
            throw UpdateException(UpdateError.INTEGRITY, "APK size or digest does not match the release")
        }
        verified = true
    } finally {
        if (!verified) target.delete()
    }
}

internal suspend fun verifyCachedApkIntegrity(file: File, release: AppRelease) {
    val expected = decodeSha256(release.sha256)
    if (release.apkSize <= 0L || !file.isFile || file.length() != release.apkSize) {
        throw UpdateException(UpdateError.INTEGRITY, "Cached APK size does not match the release")
    }
    val coroutineContext = currentCoroutineContext()
    val actual = file.inputStream().use { input ->
        digestStream(input) { coroutineContext.ensureActive() }
    }
    coroutineContext.ensureActive()
    if (!MessageDigest.isEqual(expected, actual)) {
        throw UpdateException(UpdateError.INTEGRITY, "Cached APK digest does not match the release")
    }
}

private fun digestStream(input: InputStream, checkCancellation: () -> Unit = {}): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        checkCancellation()
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest()
}

private fun decodeSha256(value: String): ByteArray {
    if (value.length != 64) throw UpdateException(UpdateError.INTEGRITY, "Invalid SHA-256 digest")
    return ByteArray(32) { index ->
        val high = value[index * 2].digitToIntOrNull(16)
        val low = value[index * 2 + 1].digitToIntOrNull(16)
        if (high == null || low == null) throw UpdateException(UpdateError.INTEGRITY, "Invalid SHA-256 digest")
        ((high shl 4) or low).toByte()
    }
}
