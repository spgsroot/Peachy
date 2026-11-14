package ru.spgsroot.peachy.data.update

import kotlinx.serialization.Serializable

@Serializable
data class AppRelease(
    val tagName: String,
    val versionName: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String
)

enum class UpdateError {
    NETWORK, RATE_LIMIT, INVALID_RELEASE, NO_APK, HTTP_ERROR, DOWNLOAD_FAILED,
    INTEGRITY, INVALID_APK, INCOMPATIBLE, INSTALL_UNAVAILABLE
}

class UpdateException(
    val reason: UpdateError,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

private val stableVersion = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

/** Stable major.minor.patch only: qualifiers, signs, whitespace and overflowing parts are invalid. */
internal fun stableVersionParts(version: String): List<Long>? {
    val match = stableVersion.matchEntire(version) ?: return null
    return match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
}

fun isNewerVersion(candidate: String, installed: String): Boolean {
    val next = stableVersionParts(candidate) ?: return false
    val current = stableVersionParts(installed) ?: return false
    for (index in next.indices) {
        if (next[index] != current[index]) return next[index] > current[index]
    }
    return false
}

/** A clock rollback permits a fresh attempt; never/invalid timestamps are not a daily lockout. */
fun shouldCheckAutomatically(enabled: Boolean, lastAttemptMs: Long, nowMs: Long): Boolean {
    if (!enabled) return false
    if (lastAttemptMs <= 0 || lastAttemptMs > nowMs) return true
    return nowMs - lastAttemptMs >= 86_400_000L
}
