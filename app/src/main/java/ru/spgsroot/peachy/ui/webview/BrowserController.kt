package ru.spgsroot.peachy.ui.webview

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.PixelCopy
import android.view.View
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import org.json.JSONObject
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.browser.BrowserPolicy
import ru.spgsroot.peachy.browser.BrowserUrls
import ru.spgsroot.peachy.browser.FoundVideo
import ru.spgsroot.peachy.browser.MediaSniffer
import ru.spgsroot.peachy.browser.RequestKinds
import ru.spgsroot.peachy.browser.MediaKind
import ru.spgsroot.peachy.browser.adblock.FilterEngine
import ru.spgsroot.peachy.browser.adblock.Hosts

/** One browser tab. The WebView exists only once the tab has loaded something. */
class BrowserTab(val id: Int) {
    var webView by mutableStateOf<WebView?>(null)
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var blocked by mutableIntStateOf(0)
    var videos by mutableStateOf<List<FoundVideo>>(emptyList())

    /** Host of the page being shown. Read from WebView's request threads, so it is volatile. */
    @Volatile var host: String? = null

    internal fun noteVideo(video: FoundVideo) {
        if (videos.any { it.url == video.url } || videos.size >= BrowserController.MAX_VIDEOS) return
        videos = videos + video
    }
}

/**
 * A top-level move to another site. It is held until the user confirms it, so a redirect or a
 * click handler cannot replace the page with something the user did not choose.
 */
class PendingNavigation(val tabId: Int, val url: String)

/**
 * A window a page asked to open (target=_blank, window.open). Its URL is captured without loading
 * the page; the tab is created only after the user approves it.
 */
class PendingWindow(val url: String)

/**
 * Owns the tabs and their WebViews. Everything here runs on the main thread except the
 * request interception, which only reads volatile state and posts its effects to the main thread.
 */
class BrowserController(
    private val activity: Activity,
    private val engine: () -> FilterEngine,
    private val blockerOff: (String?) -> Boolean,
    private val onVisit: (url: String, title: String) -> Unit
) {
    val tabs = mutableStateListOf<BrowserTab>()
    var activeId by mutableIntStateOf(0)
        private set

    /** A page element (usually a video) the page asked to show full screen. */
    var fullscreenView by mutableStateOf<View?>(null)
        private set

    /** Cross-site navigation waiting for the user's confirmation. */
    var pendingNavigation by mutableStateOf<PendingNavigation?>(null)
        private set

    /** A new window the page requested; held until the user approves it. */
    var pendingWindow by mutableStateOf<PendingWindow?>(null)
        private set

    /**
     * Last picture of each tab, captured while it was on screen. Composables read this map through
     * Compose snapshots; bitmaps are dropped, not recycled, because a preview may still be drawn.
     */
    private val thumbStates = mutableStateMapOf<Int, Bitmap>()
    val thumbnails: Map<Int, Bitmap> get() = thumbStates

    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    /** Temporary WebView that captures a popup's URL instead of loading it. */
    private var popupProbe: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1

    val active: BrowserTab get() = tabs.first { it.id == activeId }

    init {
        newTab()
    }

    fun newTab(input: String? = null): BrowserTab {
        val tab = addTab(activate = true) ?: return active
        if (input != null) navigate(tab, input)
        return tab
    }

    /** Adds a tab; [activate] false keeps the current page on screen. */
    private fun addTab(activate: Boolean): BrowserTab? {
        if (tabs.size >= MAX_TABS) {
            toast(R.string.browser_too_many_tabs)
            return null
        }
        val tab = BrowserTab(nextId++)
        tabs += tab
        if (activate) activeId = tab.id
        return tab
    }

    fun select(tab: BrowserTab) {
        if (tab.id != activeId) tabs.firstOrNull { it.id == activeId }?.webView?.onPause()
        activeId = tab.id
        tab.webView?.onResume()
        main.postDelayed({
            if (tab.id == activeId) captureActiveThumbnail()
        }, THUMB_CAPTURE_DELAY_MS)
    }

    fun close(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tab.webView?.let(::destroy)
        tab.webView = null
        dropTabState(tab)
        tabs.remove(tab)
        if (tabs.isEmpty()) {
            newTab()
        } else if (tab.id == activeId) {
            select(tabs[(index - 1).coerceAtLeast(0)])
        }
    }

    /** Loads what was typed in the address bar: an address, or a search. */
    fun navigate(tab: BrowserTab, input: String) {
        val target = BrowserUrls.resolve(input)
        if (target == null) {
            toast(R.string.error_webview_https)
            return
        }
        tab.url = target
        tab.host = Hosts.hostOf(target)
        webViewOf(tab).loadUrl(target)
    }

    /** Back to the start page of the tab. */
    fun showStart(tab: BrowserTab) {
        tab.webView?.let(::destroy)
        tab.webView = null
        tab.url = ""
        tab.title = ""
        tab.host = null
        tab.videos = emptyList()
        tab.blocked = 0
        tab.loading = false
        tab.canGoBack = false
        tab.canGoForward = false
        dropTabState(tab)
    }

    /** Picks up [pendingNavigation] after the user said yes; loading does not re-enter the gate. */
    fun continuePendingNavigation() {
        val pending = pendingNavigation ?: return
        pendingNavigation = null
        val tab = tabs.firstOrNull { it.id == pending.tabId } ?: return
        navigate(tab, pending.url)
    }

    fun dismissPendingNavigation() {
        val pending = pendingNavigation ?: return
        pendingNavigation = null
        // A blocked redirect may have moved the WebView's URL property; show the committed page.
        val tab = tabs.firstOrNull { it.id == pending.tabId } ?: return
        tab.url = tab.webView?.url ?: tab.url
        tab.loading = false
    }

    /** Opens the window the user approved as a new tab, so its visit is recorded normally. */
    fun openPendingWindow() {
        val pending = pendingWindow ?: return
        pendingWindow = null
        newTab(pending.url)
    }

    fun dismissPendingWindow() {
        pendingWindow = null
    }

    /**
     * Copies what the active tab shows into its preview slot. PixelCopy reads the app's own window
     * surface, so the page is captured as rendered; video drawn on its own surface can stay black.
     * FLAG_SECURE keeps system screenshots and adb capture out without affecting this in-process
     * copy (verified on API 37), so it stays on. Only the active, attached tab can be captured; a
     * tab that was never shown keeps no preview.
     */
    fun captureActiveThumbnail(onDone: () -> Unit = {}) {
        val tab = active
        val view = tab.webView
        if (view == null || tab.id != activeId || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
            onDone()
            return
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val decor = activity.window.decorView
        val left = location[0].coerceAtLeast(0)
        val top = location[1].coerceAtLeast(0)
        val right = (location[0] + view.width).coerceAtMost(decor.width)
        val bottom = (location[1] + view.height).coerceAtMost(decor.height)
        if (right <= left || bottom <= top) {
            onDone()
            return
        }
        // PixelCopy scales the window rect into the destination, so the preview buffer is capped.
        val scale = minOf(
            THUMB_MAX_WIDTH / (right - left).toFloat(),
            THUMB_MAX_HEIGHT / (bottom - top).toFloat(),
            1f
        )
        val width = (((right - left) * scale).roundToInt()).coerceAtLeast(1)
        val height = (((bottom - top) * scale).roundToInt()).coerceAtLeast(1)
        val thumb = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            onDone()
            return
        }
        fun submit(retry: Boolean) {
            try {
                PixelCopy.request(
                    activity.window,
                    Rect(left, top, right, bottom),
                    thumb,
                    { result ->
                        when {
                            result == PixelCopy.SUCCESS -> {
                                // A tab closed while the copy ran must not get its preview back.
                                if (tabs.any { it.id == tab.id }) {
                                    thumbStates[tab.id] = thumb
                                } else {
                                    thumb.recycle()
                                }
                                onDone()
                            }
                            // The window may not have queued a frame yet; retry once after one.
                            result == PixelCopy.ERROR_SOURCE_NO_DATA && retry ->
                                main.postDelayed({ submit(false) }, THUMB_RETRY_DELAY_MS)
                            else -> {
                                thumb.recycle()
                                onDone()
                            }
                        }
                    },
                    main
                )
            } catch (e: RuntimeException) {
                thumb.recycle()
                onDone()
            }
        }
        submit(true)
    }

    private fun dropTabState(tab: BrowserTab) {
        thumbStates.remove(tab.id)
        if (pendingNavigation?.tabId == tab.id) pendingNavigation = null
    }

    fun goBack(tab: BrowserTab) {
        tab.webView?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward(tab: BrowserTab) {
        tab.webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload(tab: BrowserTab) {
        tab.webView?.reload()
    }

    fun stop(tab: BrowserTab) {
        tab.webView?.stopLoading()
    }

    /** Videos the page has loaded, plus the ones its `<video>` elements point at right now. */
    fun refreshVideos(tab: BrowserTab) {
        tab.webView?.evaluateJavascript(MediaSniffer.COLLECT_SCRIPT) { raw ->
            MediaSniffer.parseCollected(raw).forEach(tab::noteVideo)
        }
    }

    /** Headers a download of [url] needs to look like the page's own request. */
    fun downloadHeaders(tab: BrowserTab, url: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (tab.url.isNotEmpty()) out["Referer"] = tab.url
        tab.webView?.settings?.userAgentString?.let { out["User-Agent"] = it }
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { out["Cookie"] = it }
        return out
    }

    fun hideFullscreen() {
        val view = fullscreenView ?: return
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        (view.parent as? android.view.ViewGroup)?.removeView(view)
    }

    fun destroyAll() {
        hideFullscreen()
        tabs.forEach { tab -> tab.webView?.let(::destroy); tab.webView = null }
        thumbStates.clear()
        pendingNavigation = null
        pendingWindow = null
        popupProbe?.let(::destroy)
        popupProbe = null
    }

    private fun destroy(view: WebView) {
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.webChromeClient = null
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.destroy()
    }

    private fun toast(resId: Int) = Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()

    private fun tabOf(view: WebView?): BrowserTab? = tabs.firstOrNull { it.webView === view }

    fun webViewOf(tab: BrowserTab): WebView = tab.webView ?: createWebView(tab).also { tab.webView = it }

    private fun createWebView(tab: BrowserTab): WebView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Every window request goes through onCreateWindow, which refuses unattended ones.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setGeolocationEnabled(false)
        settings.safeBrowsingEnabled = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { url, _, _, mimeType, _ ->
            if (mimeType.startsWith("video/") && MediaSniffer.isAllowedDownload(url, tab.host)) {
                tab.noteVideo(FoundVideo(url, MediaSniffer.classify(url) ?: MediaKind.FILE))
                toast(R.string.browser_download_offered)
            } else {
                toast(R.string.browser_download_unsupported)
            }
        }
        webViewClient = Client()
        webChromeClient = Chrome()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val tab = tabOf(view) ?: return true
            val target = request.url?.toString().orEmpty()
            if (!request.isForMainFrame) {
                // Frames may use HTTPS or page-generated content, never plain HTTP or local files.
                return !BrowserPolicy.isAllowedSubframe(target)
            }
            if (!BrowserPolicy.isWebTarget(target)) {
                // file:, content:, javascript:, intent: and friends never reach a page.
                toast(R.string.error_webview_https)
                return true
            }
            val secure = BrowserPolicy.httpsOnly(target)
            val get = request.method.equals("GET", ignoreCase = true)
            if (!get) {
                // A form post cannot be replayed as a GET: an insecure one is simply refused.
                if (secure != target) {
                    toast(R.string.browser_insecure_navigation_blocked)
                    return true
                }
                return false
            }
            if (BrowserPolicy.requiresConfirmation(tab.host, secure)) {
                pendingNavigation = PendingNavigation(tab.id, secure)
                // A server redirect can already be in flight; returning true alone does not
                // always abort it, so stop the load before asking.
                view.stopLoading()
                return true
            }
            if (secure != target) {
                view.loadUrl(secure)
                return true
            }
            return false
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Certificate problems are final: the page is never continued past them.
            handler.cancel()
            toast(R.string.browser_insecure_connection)
        }

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
            request.cancel()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url?.toString() ?: return null
            val tab = tabOf(view)
            if (!request.isForMainFrame && tab != null && !blockerOff(tab.host)) {
                val kind = RequestKinds.guess(url, request.requestHeaders, false)
                if (engine().shouldBlock(url, tab.host, kind)) {
                    main.post { tab.blocked++ }
                    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }
            val media = MediaSniffer.classify(url)
            if (media != null && tab != null) main.post { tab.noteVideo(FoundVideo(url, media)) }
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // The URL is adopted only when the page commits: a blocked redirect must not become
            // the host that the next cross-site check compares against.
            val tab = tabOf(view) ?: return
            tab.loading = true
            tab.blocked = 0
            tab.videos = emptyList()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val tab = tabOf(view) ?: return
            tab.url = url
            tab.host = Hosts.hostOf(url)
            hideAds(view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val tab = tabOf(view) ?: return
            tab.url = url
            tab.host = Hosts.hostOf(url)
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
        }

        override fun onPageFinished(view: WebView, url: String) {
            val tab = tabOf(view) ?: return
            tab.loading = false
            tab.url = url
            tab.host = Hosts.hostOf(url)
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            hideAds(view)
            refreshVideos(tab)
            onVisit(url, view.title.orEmpty())
            if (tab.id == activeId) {
                main.postDelayed({
                    if (tab.id == activeId && tab.webView === view) captureActiveThumbnail()
                }, THUMB_CAPTURE_DELAY_MS)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's process died; drop the view so the tab can be reloaded instead of crashing the app.
            val tab = tabOf(view) ?: return true
            tab.webView = null
            tab.loading = false
            dropTabState(tab)
            destroy(view)
            toast(R.string.browser_page_crashed)
            return true
        }
    }

    /** Drops the popup probe and turns its captured URL into a window the user may approve. */
    private fun finishPopupProbe(url: String?) {
        val probe = popupProbe ?: return
        popupProbe = null
        probe.stopLoading()
        // Destroying a WebView inside its own client callback is unsafe; do it on the next loop.
        main.post { destroy(probe) }
        if (url.isNullOrBlank()) return
        if (!BrowserPolicy.isWebTarget(url)) {
            toast(R.string.browser_popup_blocked)
            return
        }
        pendingWindow = PendingWindow(BrowserPolicy.httpsOnly(url))
    }

    private fun hideAds(view: WebView) {
        val tab = tabOf(view) ?: return
        val host = tab.host ?: return
        if (blockerOff(host)) return
        val css = engine().cosmeticCss(host)
        if (css.isEmpty()) return
        view.evaluateJavascript(
            "(function(){var s=document.getElementById('peachy-hide');if(!s){s=document.createElement('style');" +
                "s.id='peachy-hide';(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent=${JSONObject.quote(css)};})()",
            null
        )
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tabOf(view)?.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tabOf(view)?.title = title.orEmpty()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            // A scripted or background window is refused outright. A window the user's tap asked
            // for is not loaded here: an unattached, scriptless probe captures its URL, then the
            // user decides whether the tab opens.
            if (!isUserGesture) return false
            if (popupProbe != null || pendingWindow != null) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val probe = WebView(activity).apply {
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        finishPopupProbe(request.url?.toString())
                        return true
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        // Some popups start without a shouldOverride; capture before any load.
                        finishPopupProbe(url)
                    }
                }
            }
            popupProbe = probe
            transport.webView = probe
            resultMsg.sendToTarget()
            main.postDelayed({
                if (popupProbe === probe) finishPopupProbe(null)
            }, POPUP_PROBE_TIMEOUT_MS)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Camera, microphone and geolocation are not part of this browser.
            request.deny()
        }

        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
            params: FileChooserParams
        ): Boolean {
            // Local files are never offered to a page.
            filePathCallback.onReceiveValue(null)
            return false
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            hideFullscreen()
            fullscreenView = view
            fullscreenCallback = callback
        }

        override fun onHideCustomView() {
            hideFullscreen()
        }
    }

    companion object {
        const val MAX_TABS = 10
        const val MAX_VIDEOS = 40

        /** Preview budget: at most MAX_TABS pictures of ~320×640 px, about 8 MB in total. */
        private const val THUMB_MAX_WIDTH = 320
        private const val THUMB_MAX_HEIGHT = 640
        private const val THUMB_CAPTURE_DELAY_MS = 400L
        private const val THUMB_RETRY_DELAY_MS = 120L

        /** A popup that has not revealed its URL by then is dropped. */
        private const val POPUP_PROBE_TIMEOUT_MS = 2000L
    }
}
