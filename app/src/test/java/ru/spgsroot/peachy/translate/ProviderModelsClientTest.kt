package ru.spgsroot.peachy.translate

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProviderModelsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var draft: ProviderSettings
    private val agent = "Peachy/test"
    @Before fun start() {
        server = MockWebServer().also { it.start() }
        draft = ProviderSettings("http://127.0.0.1:${server.port}/draft/v1/", "unsaved", "draft-key", false)
    }
    @After fun stop() { server.shutdown() }
    private fun client(http: OkHttpClient = OkHttpClient()) = TranslationClient(http, { ProviderSettings() }, agent)
    private fun error(block: suspend () -> Any?): TranslationError = runBlocking {
        try { block(); throw AssertionError("expected failure") } catch (e: TranslationException) { e.error }
    }
    private fun stream(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\r\n\r\n" })
    private val content = """{"choices":[{"delta":{"content":"OK"}}]}"""
    private val finish = """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""

    private fun routedClient(): TranslationClient = client(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()).build())
    }.build())

    @Test fun openCodePublicCatalogHasNoEmptyBearerAndHasItsOwnSession() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"vision","modalities":{"input":["image/png"]},"cost":{"text":{"input":{"price":0.15},"output":{"price":0.5}}}}]}"""))
        val model = routedClient().models(ProviderSettings(apiKey = "")).single()
        assertEquals(0.15, model.inputPrice!!, 0.0)
        assertEquals(ModelVision.Yes, model.vision)
        val request = server.takeRequest()
        assertEquals("/inference/go/v1/models", request.path)
        assertEquals("peachy-0", request.getHeader("x-opencode-session"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun openRouterCatalogSelectsItsParserAndDoesNotFetchEndpoints() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"a/b","pricing":{"prompt":"0.000002","completion":"0.000008"},"architecture":{"input_modalities":["image"]}}]}"""))
        val model = routedClient().models(draft.copy(baseUrl = "https://openrouter.ai/api/v1")).single()
        assertEquals(2.0, model.inputPrice!!, 0.0)
        assertEquals(8.0, model.outputPrice!!, 0.0)
        assertEquals("/api/v1/models", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test fun openAiProbeAdaptsTheCompletionBudgetWithoutPageConsent() = runBlocking {
        server.enqueue(stream(content, finish))
        routedClient().measureModel(draft.copy(baseUrl = "https://api.openai.com/v1"))
        val request = server.takeRequest()
        assertNull(request.getHeader("x-opencode-session"))
        val body = JSONObject(request.body.readUtf8())
        assertFalse(body.has("max_tokens"))
        assertTrue(body.getInt("max_completion_tokens") <= 64)
    }

    @Test fun aDisconnectedPaidProbeIsNotReplayed() = runBlocking {
        val client = client()
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        client.models(draft) // prime a pooled connection, where automatic recovery can replay a POST
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(stream(content, finish))
        assertEquals(TranslationError.Network, error { client.measureModel(draft) })
        assertEquals(2, server.requestCount)
    }

    @Test fun catalogUsesUnsavedDraftWithoutPageConsent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"unsaved-model"}]}"""))
        assertEquals("unsaved-model", client().models(draft).single().id)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/draft/v1/models", request.path)
        assertEquals("Bearer draft-key", request.getHeader("Authorization"))
        assertEquals(agent, request.getHeader("User-Agent"))
        assertNull(request.getHeader("x-opencode-session"))
    }

    @Test fun anonymousCatalogLetsTheProviderDecideWhetherAuthenticationIsNeeded() = runBlocking {
        val anonymous = draft.copy(apiKey = "")
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"local-model"}]}"""))
        assertEquals("local-model", client().models(anonymous).single().id)
        assertNull(server.takeRequest().getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(TranslationError.Auth, error { client().models(anonymous) })
    }

    @Test fun unsafeAddressOrHeaderNeverReachesNetwork() {
        assertEquals(TranslationError.NotConfigured, error { client().models(draft.copy(baseUrl = "http://example.com/v1")) })
        assertEquals(TranslationError.NotConfigured, error { client().models(draft.copy(apiKey = "bad\nkey")) })
        assertEquals(TranslationError.NotConfigured, error { client().measureModel(draft.copy(apiKey = "")) })
        assertEquals(0, server.requestCount)
    }

    @Test fun errorsAreShortTypedFailuresWithoutRetries() {
        listOf(401 to TranslationError.Auth, 404 to TranslationError.Server(404), 429 to TranslationError.RateLimit(3), 307 to TranslationError.Server(307)).forEach { (code, expected) ->
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "3").setHeader("Location", server.url("/redirect")))
            assertEquals(expected, error { client().models(draft) })
        }
        server.enqueue(MockResponse().setBody("bad json"))
        assertEquals(TranslationError.BadResponse, error { client().models(draft) })
        assertEquals(5, server.requestCount)
        server.shutdown()
        assertEquals(TranslationError.Network, error { client().models(draft) })
    }

    @Test fun probeSendsSyntheticImageAndTimesContentNotRoleOrReasoning() = runBlocking {
        server.enqueue(stream("""{"choices":[{"delta":{"role":"assistant","reasoning_content":"thinking"}}]}""", content, finish, "[DONE]")
            .throttleBody(40, 30, TimeUnit.MILLISECONDS))
        val speed = client().measureModel(draft)
        assertTrue("first content must follow role/reasoning", speed.firstTokenMs >= 50)
        assertTrue(speed.totalMs >= speed.firstTokenMs)
        assertTrue(speed.measuredAt > 0)
        val request = server.takeRequest()
        assertEquals("/draft/v1/chat/completions", request.path)
        assertEquals("Bearer draft-key", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        assertEquals("unsaved", body.getString("model"))
        assertTrue(body.getInt("max_tokens") <= 64)
        val parts = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val uri = parts.getJSONObject(1).getJSONObject("image_url").getString("url")
        val png = java.util.Base64.getDecoder().decode(uri.substringAfter(','))
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), png.copyOfRange(0, 8))
        val header = java.nio.ByteBuffer.wrap(png, 16, 8)
        assertEquals(32, header.int)
        assertEquals(32, header.int)
        assertTrue(png.size in 100..6000)
        assertEquals(1, server.requestCount)
    }

    @Test fun missingContentMalformedOrTruncatedStreamsAreNotMeasurements() {
        listOf(
            stream("[DONE]"), stream("not-json", "[DONE]"), stream(content),
            stream("""{"error":{"message":"secret provider text"}}""", "[DONE]"),
            stream(content, """{"choices":[{"delta":{},"finish_reason":"content_filter"}]}""", "[DONE]")
        ).forEach { response ->
            server.enqueue(response)
            val failure = error { client().measureModel(draft) }
            assertTrue(failure == TranslationError.BadResponse || failure == TranslationError.Refused)
        }
    }

    @Test fun aFinishEventWithoutDoneIsACompleteStream() = runBlocking {
        server.enqueue(stream(content, finish))
        assertTrue(client().measureModel(draft).totalMs >= 0)
    }

    @Test fun cancellationStopsAStreamingCall() = runBlocking {
        server.enqueue(stream(content, "[DONE]").setBodyDelay(3, TimeUnit.SECONDS))
        val call = async { client().measureModel(draft) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        withTimeout(2000) { call.cancelAndJoin() }
        assertTrue(call.isCancelled)
    }
}
