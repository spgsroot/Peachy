package ru.spgsroot.peachy.data.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseClientTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun versionsCompareNumericallyWithoutDowngrades() {
        assertTrue(isNewerVersion("3.10.0", "3.9.9"))
        assertFalse(isNewerVersion("3.9.9", "3.10.0"))
        assertFalse(isNewerVersion("3.4.0", "3.4.0"))
        assertFalse(isNewerVersion("not-a-version", "3.3.0"))
    }

    @Test fun automaticChecksRespectOptInAndDailyBoundary() {
        val day = 86_400_000L
        assertFalse(shouldCheckAutomatically(false, 0, day))
        assertTrue(shouldCheckAutomatically(true, 0, day))
        assertFalse(shouldCheckAutomatically(true, day, day * 2 - 1))
        assertTrue(shouldCheckAutomatically(true, day, day * 2))
        assertTrue(shouldCheckAutomatically(true, day * 2, day))
    }

    private fun release(apkUrl: String = "https://github.com/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk", digest: String? = "sha256:" + "a".repeat(64)): JSONObject {
        val apk = JSONObject().put("name", "app-release-unsigned-signed.apk")
            .put("browser_download_url", apkUrl).put("size", 123L).put("state", "uploaded")
        if (digest != null) apk.put("digest", digest)
        return JSONObject().put("tag_name", "v3.4.0").put("draft", false).put("prerelease", false)
            .put("body", "Changes").put("html_url", "https://github.com/spgsroot/Peachy/releases/tag/v3.4.0")
            .put("assets", JSONArray().put(JSONObject().put("name", "app-release.aab").put("size", 10)).put(apk))
    }

    private fun fetch(body: JSONObject): AppRelease = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(body.toString()))
        server.start()
        runBlocking { GitHubReleaseClient(OkHttpClient(), server.url("/latest")).fetchLatest() }
    }

    @Test fun bundleBeforeApkIsNotChosenForInstallation() {
        assertEquals("https://github.com/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk", fetch(release()).apkUrl)
    }

    @Test fun missingDigestCannotAuthorizeAnApkDownload() {
        assertEquals(UpdateError.INVALID_RELEASE, assertThrows(UpdateException::class.java) { fetch(release(digest = null)) }.reason)
    }

    @Test fun foreignRepositoryAndHostCannotSupplyAnUpdate() {
        for (url in listOf(
            "https://example.test/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk",
            "https://github.com/attacker/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk",
            "https://github.com/spgsroot/Peachy/releases/download/v3.3.0/app-release-unsigned-signed.apk"
        )) {
            assertEquals(UpdateError.INVALID_RELEASE, assertThrows(UpdateException::class.java) { fetch(release(apkUrl = url)) }.reason)
        }
    }

    @Test fun prereleaseCannotReplaceTheStableChannel() {
        assertEquals(UpdateError.INVALID_RELEASE, assertThrows(UpdateException::class.java) { fetch(release().put("prerelease", true)) }.reason)
    }

    @Test fun rateLimitIsNotReportedAsUpToDate() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(429))
        server.start()
        val failure = assertThrows(UpdateException::class.java) {
            runBlocking { GitHubReleaseClient(OkHttpClient(), server.url("/latest")).fetchLatest() }
        }
        assertEquals(UpdateError.RATE_LIMIT, failure.reason)
    }

    @Test fun malformedAndOverflowingVersionsCannotTriggerAnUpdate() {
        for (invalid in listOf("", "3.4", "3.4.0.1", "3.4.0-beta", "3.-4.0", " 3.4.0", "3.04.0", "9223372036854775808.0.0")) {
            assertFalse(invalid, isNewerVersion(invalid, "3.3.0"))
            assertFalse(invalid, isNewerVersion("4.0.0", invalid))
        }
    }

    @Test fun automaticChecksHandleClockEdgesWithoutOverflow() {
        assertTrue(shouldCheckAutomatically(true, -1, 0))
        assertTrue(shouldCheckAutomatically(true, 1, Long.MAX_VALUE))
        assertFalse(shouldCheckAutomatically(false, Long.MAX_VALUE, 0))
    }

    @Test fun releasePageMustMatchCanonicalRepositoryAndTag() {
        for (page in listOf(
            "https://example.test/spgsroot/Peachy/releases/tag/v3.4.0",
            "https://github.com/other/Peachy/releases/tag/v3.4.0",
            "https://github.com/spgsroot/Peachy/releases/tag/v3.3.0",
            "https://github.com/spgsroot/Peachy/releases/tag/v3.4.0#fragment",
            "https://user@github.com/spgsroot/Peachy/releases/tag/v3.4.0",
            "https://github.com:444/spgsroot/Peachy/releases/tag/v3.4.0"
        )) {
            assertEquals(page, UpdateError.INVALID_RELEASE,
                assertThrows(UpdateException::class.java) { fetch(release().put("html_url", page)) }.reason)
        }
    }

    @Test fun apkUrlMustNotContainCredentialsQueryFragmentOrAlternatePort() {
        for (url in listOf(
            "http://github.com/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk",
            "https://user@github.com/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk",
            "https://github.com:444/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk",
            "https://github.com/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk?redirect=1",
            "https://github.com/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk#fragment"
        )) {
            assertEquals(url, UpdateError.INVALID_RELEASE,
                assertThrows(UpdateException::class.java) { fetch(release(apkUrl = url)) }.reason)
        }
    }

    @Test fun digestMustBeExactlySha256Hex() {
        for (digest in listOf("", "sha256:" + "a".repeat(63), "sha256:" + "g".repeat(64), "sha512:" + "a".repeat(64))) {
            assertEquals(UpdateError.INVALID_RELEASE,
                assertThrows(UpdateException::class.java) { fetch(release(digest = digest)) }.reason)
        }
        assertEquals("a".repeat(64), fetch(release(digest = "sha256:" + "A".repeat(64))).sha256)
    }

    @Test fun malformedStableMetadataIsRejected() {
        for (body in listOf(
            release().put("draft", true),
            release().put("prerelease", "false"),
            release().also { it.remove("draft") },
            release().put("tag_name", "v3.4.0-beta"),
            release().put("tag_name", "v9223372036854775808.0.0")
        )) {
            assertEquals(UpdateError.INVALID_RELEASE,
                assertThrows(UpdateException::class.java) { fetch(body) }.reason)
        }
    }

    @Test fun knownApkRequiresPositiveIntegralSizeAndUploadedState() {
        for ((key, value) in listOf("size" to 0L, "size" to -1L, "size" to 1.5, "size" to "123", "state" to "new")) {
            val body = release()
            body.getJSONArray("assets").getJSONObject(1).put(key, value)
            assertEquals(UpdateError.INVALID_RELEASE,
                assertThrows(UpdateException::class.java) { fetch(body) }.reason)
        }
    }

    @Test fun absenceOfKnownSignedApkIsAnExplicitError() {
        val body = release()
        body.getJSONArray("assets").getJSONObject(1).put("name", "app-debug.apk")
        assertEquals(UpdateError.NO_APK, assertThrows(UpdateException::class.java) { fetch(body) }.reason)
    }

    @Test fun requestCarriesGitHubHeadersAndDoesNotFollowRedirects() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/unexpected"))
        server.start()
        assertEquals(UpdateError.HTTP_ERROR, assertThrows(UpdateException::class.java) {
            runBlocking { GitHubReleaseClient(OkHttpClient(), server.url("/latest")).fetchLatest() }
        }.reason)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertFalse(request.getHeader("User-Agent").isNullOrBlank())
        assertEquals(1, server.requestCount)
    }

    @Test fun serverAndJsonErrorsAreNotReportedAsUpToDate() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("not json"))
        server.enqueue(MockResponse().setResponseCode(403).addHeader("X-RateLimit-Remaining", "0"))
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        val client = GitHubReleaseClient(OkHttpClient(), server.url("/latest"))
        for (reason in listOf(UpdateError.HTTP_ERROR, UpdateError.INVALID_RELEASE, UpdateError.RATE_LIMIT, UpdateError.HTTP_ERROR)) {
            assertEquals(reason, assertThrows(UpdateException::class.java) {
                runBlocking { client.fetchLatest() }
            }.reason)
        }
    }

    @Test fun cancellingARequestRemainsCoroutineCancellation() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        runBlocking {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                GitHubReleaseClient(OkHttpClient(), server.url("/latest")).fetchLatest()
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            pending.cancel()
            withTimeout(2_000) { pending.join() }
            assertThrows(CancellationException::class.java) { runBlocking { pending.await() } }
            assertTrue(pending.isCancelled)
        }
    }

    @Test fun missingOrMalformedPendingJsonIsSafeAndAutomaticChecksDefaultOff() {
        assertEquals(UpdatePreferencesState(), updatePreferencesState(preferencesOf()))
        for (json in listOf("null", "{", "{}", """{"tagName":null}""")) {
            val state = updatePreferencesState(preferencesOf(
                longPreferencesKey("download_id") to 42L,
                stringPreferencesKey("download_release") to json
            ))
            assertNull(state.downloadId)
            assertNull(state.downloadRelease)
            assertFalse(state.autoCheckEnabled)
        }
    }

    @Test fun persistedPendingMetadataRestoresTheIdAndCompleteReleaseTogether() {
        val release = AppRelease("v3.4.0", "3.4.0", "Notes", "https://github.com/spgsroot/Peachy/releases/tag/v3.4.0",
            "https://github.com/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk", 123L, "a".repeat(64))
        val restored = updatePreferencesState(preferencesOf(
            booleanPreferencesKey("auto_check_enabled") to true,
            longPreferencesKey("last_check_attempt_ms") to 1234L,
            longPreferencesKey("download_id") to 42L,
            stringPreferencesKey("download_release") to Json.encodeToString(release)
        ))
        assertEquals(UpdatePreferencesState(true, 1234L, 42L, release), restored)
    }

    @Test fun pendingMetadataSurvivesClosingAndReopeningThePreferencesStore() = runBlocking {
        val file = temporaryFolder.root.resolve("updates.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val releaseJson = """{"tagName":"v3.4.0","versionName":"3.4.0","notes":"Saved notes","pageUrl":"https://github.com/spgsroot/Peachy/releases/tag/v3.4.0","apkUrl":"https://github.com/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk","apkSize":123,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        try {
            val first = PreferenceDataStoreFactory.create(scope = firstScope) { file }
            first.edit {
                it[longPreferencesKey("download_id")] = 77L
                it[stringPreferencesKey("download_release")] = releaseJson
            }
        } finally {
            firstScope.cancel()
            firstScope.coroutineContext[Job]!!.join()
        }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = PreferenceDataStoreFactory.create(scope = reopenedScope) { file }
            val restored = updatePreferencesState(reopened.data.first())
            assertEquals(77L, restored.downloadId)
            assertEquals("v3.4.0", restored.downloadRelease?.tagName)
            assertEquals("Saved notes", restored.downloadRelease?.notes)
            assertEquals(123L, restored.downloadRelease?.apkSize)
            assertFalse(restored.autoCheckEnabled)
        } finally {
            reopenedScope.cancel()
            reopenedScope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun connectionFailureIsReportedAsNetworkError() = MockWebServer().use { server ->
        server.start()
        val url = server.url("/latest")
        server.shutdown()
        assertEquals(UpdateError.NETWORK, assertThrows(UpdateException::class.java) {
            runBlocking { GitHubReleaseClient(OkHttpClient(), url).fetchLatest() }
        }.reason)
    }
}
