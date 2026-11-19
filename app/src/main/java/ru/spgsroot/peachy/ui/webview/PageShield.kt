package ru.spgsroot.peachy.ui.webview

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.spgsroot.peachy.browser.adblock.BrowserFilter

/** One exact HTTPS origin at a time. Never expose an Android object, page URL lookup, or network API to JS. */
internal class PageShield(private val view: WebView, private val engine: BrowserFilter, private val runtime: String, private val genericEnabled: () -> Boolean = { true }, private val disabled: (String?) -> Boolean) {
    private var start: ScriptHandler? = null
    private var listenerOrigin: String? = null
    private var genericExceptions: String? = null
    private var registeredUrl: String? = null
    private var fallback: String? = null
    private var generation = -1L
    private var genericAtStart = true
    private val stylePath = "/.well-known/peachy-${java.util.UUID.randomUUID()}.css"
    private data class Style(val url: String, val bytes: ByteArray)
    @Volatile private var style: Style? = null
    fun stylesheet(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
        if (request.url.encodedPath != stylePath) return null
        val active = style
        val bytes = if (active != null && !request.isForMainFrame && request.method == "GET" && active.url == request.url.toString()) active.bytes else byteArrayOf()
        return android.webkit.WebResourceResponse("text/css", "UTF-8", 200, "OK",
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), bytes.inputStream())
    }
    fun close() {
        style = null
        start?.remove(); start = null
        removeListener()
        fallback = null; currentDocument = null; registeredUrl = null
    }
    private fun removeListener() {
        genericExceptions = null
        if (listenerOrigin != null) WebViewCompat.removeWebMessageListener(view, BRIDGE)
        listenerOrigin = null
    }
    fun prepare(url: String) {
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps || disabled(parsed.host)) { close(); return }
        if (registeredUrl == url && generation == engine.generation && genericAtStart == genericEnabled()) return
        genericAtStart = genericEnabled()
        start?.remove(); start = null
        fallback = null
        val origin = parsed.newBuilder().username("").password("").encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
        val payload = JSONObject(engine.page(url))
        val scripts = payload.remove("injected_script") as String
        val exceptions = payload.getJSONArray("exceptions").toString()
        payload.remove("exceptions")
        val selectors = payload.getJSONArray("hide_selectors")
        val css = buildString { for (i in 0 until selectors.length()) append(selectors.getString(i)).append("{display:none!important}\n") }
        style = Style(origin + stylePath, css.toByteArray())
        payload.remove("hide_selectors")
        payload.put("stylesheet", origin + stylePath)
        if (!genericAtStart) payload.put("generichide", true)
        val needsListener = !payload.getBoolean("generichide") && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (listenerOrigin != origin || !needsListener) removeListener()
        genericExceptions = if (needsListener) exceptions else null
        // Keep the live bridge for same-origin navigations: its old page can still have messages
        // queued while WebView is deciding whether to load the new document.
        if (needsListener && listenerOrigin == null) {
            WebViewCompat.addWebMessageListener(view, BRIDGE, setOf(origin)) { _, message, source, main, reply ->
                val currentExceptions = genericExceptions ?: return@addWebMessageListener
                if (!main || listenerOrigin != origin || source.toString().trimEnd('/') != origin || disabled(parsed.host)) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                if (data.length > 24_000) return@addWebMessageListener
                val valid = runCatching {
                    val groups = JSONArray(data); require(groups.length() == 2)
                    (0..1).forEach { i -> val a = groups.getJSONArray(i); require(a.length() <= 96); for (j in 0 until a.length()) require(a.get(j) is String && a.getString(j).length <= 128) }
                }.isSuccess
                if (valid) reply.postMessage(engine.generic(data, currentExceptions))
            }
            listenerOrigin = origin
        }
        val js = "(()=>{if(window!==top || location.origin!==${JSONObject.quote(origin)})return;const scriptletGlobals={};\n$scripts\n;($runtime)($payload);})()"
        currentDocument = js
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            start = WebViewCompat.addDocumentStartJavaScript(view, js, setOf(origin))
        } else fallback = js
        registeredUrl = url
        generation = engine.generation
    }
    fun committed(url: String) {
        val previouslyPrepared = registeredUrl == url && generation == engine.generation
        prepare(url)
        if (fallback != null) view.evaluateJavascript(fallback!!, null)
        else if (!previouslyPrepared && registeredUrl == url) {
            // The provider committed a destination with no preceding navigation callback (e.g. redirect).
            // Registration cannot be retroactive. Apply to this committed page, then retain the start hook.
            currentDocument?.let { view.evaluateJavascript(it, null) }
        }
    }
    private var currentDocument: String? = null
    companion object { private const val BRIDGE = "PeachyGeneric" }
}
