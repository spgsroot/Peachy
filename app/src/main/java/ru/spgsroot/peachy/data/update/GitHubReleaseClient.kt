package ru.spgsroot.peachy.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

private const val RELEASE_ROOT = "https://github.com/spgsroot/Peachy/releases"
private const val SIGNED_APK = "app-release-unsigned-signed.apk"
private val githubDigest = Regex("sha256:([0-9a-fA-F]{64})")

class GitHubReleaseClient(
    client: OkHttpClient = OkHttpClient(),
    private val apiUrl: HttpUrl = "https://api.github.com/repos/spgsroot/Peachy/releases/latest".toHttpUrl()
) {
    private val http = client.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // Do not let even the canonical API redirect this request to another source.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun fetchLatest(): AppRelease = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Peachy-Android-Updater")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            parseRelease(execute(http.newCall(request)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(UpdateError.NETWORK, "Unable to fetch the latest release", e)
        } catch (e: JSONException) {
            throw UpdateException(UpdateError.INVALID_RELEASE, "Invalid release JSON", e)
        }
    }

    private suspend fun execute(call: Call): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        when {
                            it.code == 429 || (it.code == 403 &&
                                (it.header("X-RateLimit-Remaining") == "0" || it.header("Retry-After") != null)) ->
                                throw UpdateException(UpdateError.RATE_LIMIT, "GitHub API rate limit")
                            !it.isSuccessful -> throw UpdateException(UpdateError.HTTP_ERROR, "GitHub HTTP ${it.code}")
                        }
                        val body = it.body ?: throw UpdateException(UpdateError.INVALID_RELEASE, "Missing release body")
                        continuation.resume(body.string())
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                    } catch (e: UpdateException) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private fun parseRelease(body: String): AppRelease {
        val release = JSONObject(body)
        if (release.opt("draft") != false || release.opt("prerelease") != false) invalid("Not a stable release")
        val tag = release.opt("tag_name") as? String ?: invalid("Missing release tag")
        val version = tag.removePrefix("v")
        if (stableVersionParts(version) == null) invalid("Invalid stable version")
        val page = release.opt("html_url") as? String ?: invalid("Missing release page")
        // Exact canonical strings also exclude credentials, ports, query/fragment and encoded path tricks.
        if (page != "$RELEASE_ROOT/tag/$tag") invalid("Noncanonical release page")
        val notes = when (val value = release.opt("body")) {
            null, JSONObject.NULL -> ""
            is String -> value
            else -> invalid("Invalid release notes")
        }
        val assets = release.optJSONArray("assets") ?: invalid("Missing release assets")
        var apk: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: invalid("Invalid release asset")
            if (asset.opt("name") == SIGNED_APK) {
                if (apk != null) invalid("Ambiguous signed APK")
                apk = asset
            }
        }
        val asset = apk ?: throw UpdateException(UpdateError.NO_APK, "Release has no known signed APK")
        val url = asset.opt("browser_download_url") as? String ?: invalid("Missing APK URL")
        if (url != "$RELEASE_ROOT/download/$tag/$SIGNED_APK") invalid("Noncanonical APK URL")
        val size = when (val value = asset.opt("size")) {
            is Int -> value.toLong()
            is Long -> value
            else -> invalid("Invalid APK size")
        }
        if (size <= 0 || asset.opt("state") != "uploaded") invalid("APK is not uploaded with a positive size")
        val digest = asset.opt("digest") as? String ?: invalid("Missing APK digest")
        val sha256 = githubDigest.matchEntire(digest)?.groupValues?.get(1)?.lowercase()
            ?: invalid("Invalid APK SHA256 digest")
        return AppRelease(tag, version, notes, page, url, size, sha256)
    }

    private fun invalid(message: String): Nothing = throw UpdateException(UpdateError.INVALID_RELEASE, message)
}
