package ru.spgsroot.peachy.translate

import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

interface PageTranslator {
    /** Reads one page using [request] and returns translated text; throws [TranslationException]. */
    suspend fun translate(galleryId: Int, page: Int, request: TranslateRequest, image: suspend () -> ByteArray): PageTranslation
}

/** A translated OCR block, identified by its zero-based position in the input. */
data class IndexedTranslation(val index: Int, val kind: LineKind, val translation: String)

/** Identifies the conversation to OpenCode; other hosts do not get the header. */
internal fun sessionHeader(baseUrl: String, galleryId: Int): String? =
    if (ProviderUrl.sendsSessionHeader(baseUrl)) "peachy-$galleryId" else null

/** The test request belongs to no gallery; OpenCode still wants every request to name a session. */
private const val CHECK_SESSION_ID = 0

/** OpenAI-compatible image and OCR-text translation calls sharing the same provider policy. */
class TranslationClient(
    http: OkHttpClient,
    private val settings: () -> ProviderSettings,
    private val userAgent: String
) : PageTranslator {
    // The model can take 100 s on a busy page.
    private val http = http.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // A page must not follow a redirect to a host (or to plain http) the user never named.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    // Non-streaming OCR replies can spend more than two minutes reasoning before the first header.
    private val ocrHttp = this.http.newBuilder().readTimeout(240, TimeUnit.SECONDS).build()
    // A paid speed probe must never be replayed by OkHttp's pooled-connection recovery.
    private val probeHttp = this.http.newBuilder().retryOnConnectionFailure(false).build()

    override suspend fun translate(galleryId: Int, page: Int, request: TranslateRequest, image: suspend () -> ByteArray): PageTranslation {
        val jpeg = image()
        return complete(galleryId, { provider, prior -> pageBody(provider, jpeg, request, prior) }, PageTranslation::parse)
    }

    /** Returns every supplied block exactly once in input order, or fails rather than losing dialogue. */
    suspend fun translateBlocks(
        galleryId: Int,
        blocks: List<OcrBlock>,
        source: String,
        target: String
    ): List<IndexedTranslation> = complete(
        galleryId,
        { provider, prior -> chatBody(provider, PagePrompt.blocks(target, source), withDialogue(prior, PagePrompt.blocksUser(blocks))) },
        { parseBlocks(it, blocks.size) }, ocrHttp
    )

    private suspend fun <T : Any> complete(
        galleryId: Int,
        bodyFor: (ProviderSettings, List<TranslatedLine>) -> JSONObject,
        parse: (String) -> T?,
        client: OkHttpClient = http
    ): T =
        withContext(Dispatchers.IO) {
            // Read at send time, so a host changed or consent withdrawn mid-batch stops the next page.
            val provider = settings()
            if (!provider.isConfigured || !provider.consented) throw TranslationException(TranslationError.NotConfigured)
            val body = bodyFor(provider, coroutineContext[GalleryTurn]?.previousLinesFor(provider).orEmpty())
            // Invalid JSON or incomplete indexed coverage: ask again once with the same bounded input.
            repeat(2) {
                send(client, provider, body, sessionHeader(provider.baseUrl, galleryId), parse)?.let { return@withContext it }
            }
            throw TranslationException(TranslationError.BadResponse)
        }

    /**
     * A tiny text-only request to see whether the address, key and model of [provider] work. It
     * carries no page, so it needs no consent, and [provider] may be a draft that is not saved.
     */
    suspend fun check(provider: ProviderSettings) {
        withContext(Dispatchers.IO) {
            if (!provider.isConfigured) throw TranslationException(TranslationError.NotConfigured)
            val body = JSONObject()
                .put("model", provider.model)
                .put("max_tokens", 8)
                .put("messages", JSONArray().put(message("user", PagePrompt.CHECK)))
            try {
                send(http, provider, body, sessionHeader(provider.baseUrl, CHECK_SESSION_ID)) { Unit }
            } catch (e: TranslationException) {
                // A model that spent its whole small budget thinking still proves address, key and model work.
                if (e.error != TranslationError.Refused && e.error != TranslationError.OutputLimit) throw e
            }
        }
    }

    /** Catalog request uses an unsaved draft, never a page and never the consent gate. */
    suspend fun models(provider: ProviderSettings): List<ProviderModel> = withContext(Dispatchers.IO) {
        mapped {
            // Public catalogs need no key; private providers return the usual Auth error if it is missing.
            val request = providerRequest(provider, "/models", sessionHeader(provider.baseUrl, CHECK_SESSION_ID), keyRequired = false).get().build()
            val raw = execute(http.newCall(request))
            when (ProviderPresets.find(provider.baseUrl)?.id) {
                "opencode-go" -> ProviderModels.parseOpenCode(raw, java.time.Instant.now())
                "openrouter" -> ProviderModels.parseOpenRouter(raw)
                else -> ProviderModels.parseGeneric(raw)
            }
        }
    }

    /** Explicit single-model paid probe. Its generated image is unrelated to any gallery. */
    suspend fun measureModel(provider: ProviderSettings): ModelSpeed = withContext(Dispatchers.IO) {
        mapped {
            if (!provider.isConfigured) throw TranslationException(TranslationError.NotConfigured)
            val user = JSONArray()
                .put(JSONObject().put("type", "text").put("text", "Reply OK."))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", ModelProbeImage.dataUri)))
            val body = JSONObject().put("model", provider.model).put("stream", true).put("max_tokens", 64)
                .put("messages", JSONArray().put(message("user", user)))
            val request = providerRequest(provider, "/chat/completions", sessionHeader(provider.baseUrl, CHECK_SESSION_ID))
                .header("Accept", "text/event-stream")
                .post(adaptLimits(body, provider.baseUrl).toString().toRequestBody(JSON)).build()
            val start = System.nanoTime()
            executeResponse(probeHttp.newCall(request)) { response -> ModelSpeedStream.read(response, start) }
        }
    }

    private fun providerRequest(provider: ProviderSettings, path: String, session: String?, keyRequired: Boolean = true): Request.Builder {
        if (!ProviderUrl.isAllowed(provider.baseUrl) ||
            (keyRequired || provider.apiKey.isNotEmpty()) && !ProviderKey.isValid(provider.apiKey)) {
            throw TranslationException(TranslationError.NotConfigured)
        }
        return Request.Builder().url(ProviderUrl.normalized(provider.baseUrl) + path)
            .header("User-Agent", userAgent)
            .apply {
                if (provider.apiKey.isNotEmpty()) header("Authorization", "Bearer ${provider.apiKey}")
                session?.let { header("x-opencode-session", it) }
            }
    }

    private suspend fun <T> mapped(action: suspend () -> T): T = try {
        action()
    } catch (e: CancellationException) { throw e
    } catch (e: TranslationException) { throw e
    } catch (e: IOException) { throw TranslationException(TranslationError.Network, e)
    } catch (e: JSONException) { throw TranslationException(TranslationError.BadResponse, e)
    } catch (e: IllegalArgumentException) { throw TranslationException(TranslationError.NotConfigured, e) }

    private fun message(role: String, content: Any) = JSONObject().put("role", role).put("content", content)

    private fun withDialogue(prior: List<TranslatedLine>, current: String): String {
        if (prior.isEmpty()) return current
        val lines = JSONArray()
        for (i in (prior.size - 6).coerceAtLeast(0) until prior.size) {
            lines.put(prior[i].translation.take(240))
        }
        return "Last lines of the preceding page, already translated (context only; do not repeat):\n$lines\n\n$current"
    }

    private fun pageBody(provider: ProviderSettings, jpeg: ByteArray, request: TranslateRequest, prior: List<TranslatedLine>): JSONObject {
        val uri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
        val user = JSONArray()
            .put(JSONObject().put("type", "text").put("text", withDialogue(prior, PagePrompt.USER)))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", uri)))
        return chatBody(provider, PagePrompt.system(request.target, request.source), user)
            .put("max_tokens", 4096)
    }

    private fun chatBody(provider: ProviderSettings, system: String, user: Any): JSONObject =
        JSONObject()
            .put("model", provider.model)
            .put("temperature", 0.2)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray().put(message("system", system)).put(message("user", user))
            )

    private suspend fun <T : Any> send(
        client: OkHttpClient, provider: ProviderSettings, body: JSONObject, session: String?, parse: (String) -> T?
    ): T? {
        val text = try {
            val request = providerRequest(provider, "/chat/completions", session)
                .post(adaptLimits(body, provider.baseUrl).toString().toRequestBody(JSON))
                .build()
            execute(client.newCall(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            throw e
        } catch (e: IOException) {
            throw TranslationException(TranslationError.Network, e)
        } catch (e: IllegalArgumentException) {
            throw TranslationException(TranslationError.NotConfigured, e)
        }
        return parseCompletion(text, parse)
    }

    private suspend fun execute(call: Call): String = executeResponse(call) { it.body?.string().orEmpty() }

    private suspend fun <T> executeResponse(call: Call, consume: (Response) -> T): T = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        when {
                            it.code == 401 || it.code == 403 -> cont.resumeWithException(TranslationException(TranslationError.Auth))
                            it.code == 429 -> cont.resumeWithException(
                                TranslationException(TranslationError.RateLimit(retryAfterSeconds(it)))
                            )
                            !it.isSuccessful -> cont.resumeWithException(TranslationException(TranslationError.Server(it.code)))
                            else -> cont.resume(consume(it))
                        }
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
            }
        })
    }

    /** Seconds from a numeric `Retry-After`; the HTTP-date form is not worth parsing for a message. */
    private fun retryAfterSeconds(response: Response): Int? =
        response.header("Retry-After")?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    /** An explicit output limit is terminal: resending the same paid request cannot raise it. */
    private fun <T : Any> parseCompletion(envelope: String, parse: (String) -> T?): T? {
        val root = try {
            JSONObject(envelope)
        } catch (e: JSONException) {
            throw TranslationException(TranslationError.BadResponse, e)
        }
        val choices = root.optJSONArray("choices")
        if (choices == null && root.has("error")) throw TranslationException(TranslationError.BadResponse)
        val choice = choices?.optJSONObject(0)
        if (choice == null || choice.optString("finish_reason") == "content_filter") {
            throw TranslationException(TranslationError.Refused)
        }
        val content = choice.optJSONObject("message")?.takeIf { !it.isNull("content") }?.optString("content").orEmpty()
        if (content.isNotBlank()) parse(content)?.let { return it }
        if (choice.optString("finish_reason") == "length") throw TranslationException(TranslationError.OutputLimit)
        if (content.isBlank()) throw TranslationException(TranslationError.Refused)
        return null
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private fun parseBlocks(raw: String, count: Int): List<IndexedTranslation>? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try {
                val items = JSONObject(raw.substring(start, end + 1)).getJSONArray("lines")
                val translations = arrayOfNulls<IndexedTranslation>(count)
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    // optInt coerces strings and fractions; neither identifies an OCR block.
                    val value = (item.opt("i") as? Number)?.toDouble() ?: continue
                    if (!value.isFinite() || value < Int.MIN_VALUE || value > Int.MAX_VALUE) continue
                    val index = value.toInt()
                    if (index.toDouble() != value || index !in translations.indices || translations[index] != null) continue
                    val translation = (item.opt("translation") as? String)?.trim().orEmpty()
                    if (translation.isEmpty()) continue
                    translations[index] = IndexedTranslation(index, LineKind.fromWire(item.optString("kind")), translation)
                }
                buildList(count) {
                    for (translation in translations) add(translation ?: return null)
                }
            } catch (e: JSONException) {
                null
            }
        }

        /** Renames the output limit and drops the temperature for providers that reject the older fields. */
        internal fun adaptLimits(body: JSONObject, baseUrl: String): JSONObject {
            if (!ProviderUrl.usesCompletionTokens(baseUrl)) return body
            val limit = body.opt("max_tokens")
            body.remove("max_tokens")
            body.remove("temperature")
            if (limit != null) body.put("max_completion_tokens", limit)
            return body
        }
    }
}
