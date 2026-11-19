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
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.browser.BrowserPolicy
import ru.spgsroot.peachy.browser.BrowserUrls
import ru.spgsroot.peachy.browser.FoundVideo
import ru.spgsroot.peachy.browser.MediaSniffer
import ru.spgsroot.peachy.browser.RequestKinds
import ru.spgsroot.peachy.browser.MediaKind
import ru.spgsroot.peachy.browser.adblock.BrowserFilter
import ru.spgsroot.peachy.browser.adblock.Hosts
import ru.spgsroot.peachy.data.browser.BrowserSessionStore

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
    @Volatile internal var filterSource = ""

    /** Changes on every page start so queued request effects cannot mutate a newer page. */
    @Volatile internal var pageGeneration = 0L

    /** Page offset in pixels, remembered for the next process and used to restore this one. */
    var scrollY by mutableIntStateOf(0)

    /** Offset to apply once a restored page finishes loading; -1 while nothing is pending. */
    internal var pendingScrollY = -1

    /** True while a restored offset is being applied, so that jump is not read as a user scroll. */
    internal var scrollRestoring = false

    /** Top chrome is out of the way because the page was scrolled down. */
    var chromeHidden by mutableStateOf(false)

    /** Signed scroll travel in pixels since the last chrome change; hysteresis, not raw direction. */
    internal var scrollTravel = 0

    /** A restored tab whose page is opened when the tab is first shown; keeps the restore lazy. */
    internal var pendingLazyLoad = false

    /**
     * Addresses this tab visited, oldest first, bounded by [BrowserSessionStore.MAX_HISTORY]. Only
     * addresses are kept — never form values or page state — so everything restored from disk is
     * fetched again with a plain GET.
     */
    internal val history = mutableStateListOf<String>()

    /** Where the page on screen sits in [history]; -1 while the tab shows its start page. */
    internal var historyIndex by mutableIntStateOf(-1)

    /** Our own load is in flight: its commit settles [historyIndex] instead of appending an entry. */
    internal var expectCommit = false

    /** Logical position of the first live history entry; negative once its prefix is trimmed. */
    internal var liveHistoryStart = 0

    /** A restored forward tail survives until the live WebView starts a new branch. */
    internal var keepStoredForward = false

    /** Exact native Back/Forward destination approved by the user's toolbar or phone action. */
    internal var pendingHistoryUrl: String? = null

    /** Explicit load waiting for its first start/history callback; older loads may still finish. */
    internal var pendingLoadUrl: String? = null

    /** Clear the WebView's own history once this page finishes, so it cannot disagree with [history]. */
    internal var clearLiveHistory = false

    /**
     * Addresses this session reached with a POST (or another non-GET) request. They stay in the
     * in-memory list so Back/Forward tracking stays aligned with the WebView, but they are not
     * written as walkable history: a stored entry is always re-fetched with a GET.
     */
    internal val posted = mutableSetOf<String>()

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
    private val engine: BrowserFilter,
    private val onBlocked: () -> Unit,
    private val blockerOff: (String?) -> Boolean,
    private val mode: () -> ru.spgsroot.peachy.data.browser.AdblockService.Mode,
    private val onVisit: (url: String, title: String) -> Unit,
    private val session: BrowserSessionStore,
    restored: BrowserSessionStore.Snapshot? = null
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
    private val persistRunnable = Runnable { persist() }
    private val shields = HashMap<WebView, PageShield>()
    private val cosmeticRuntime by lazy { activity.assets.open("adblock/cosmetic.js").bufferedReader().use { it.readText() } }
    private var nextId = 1
    // Most recently left tab last; system Back consumes this trail without recording the return.
    private val previousTabs = ArrayDeque<BrowserTab>()

    fun previousTabIds() = IntArray(previousTabs.size) { previousTabs[it].id }

    fun restorePreviousTabs(ids: IntArray) {
        previousTabs.clear()
        for (id in ids) {
            tabs.firstOrNull { it.id == id && id != activeId }?.let { previousTabs.addLast(it) }
        }
    }

    val active: BrowserTab get() = tabs.first { it.id == activeId }

    init {
        if (restored == null || !restore(restored)) newTab()
    }

    /**
     * Rebuilds the tab strip from the previous process. Stable ids keep tab identity (and so the
     * active tab and its preview slot) across the restart; only the active tab opens its page, the
     * others stay lazy until the user selects them, so a restored session does not load every site
     * at once. Each tab keeps its stored address history: Back and Forward walk those addresses
     * (GET only) once the live WebView has no history of its own.
     */
    private fun restore(snapshot: BrowserSessionStore.Snapshot): Boolean {
        if (snapshot.tabs.isEmpty()) return false
        snapshot.tabs.take(MAX_TABS).forEach { saved ->
            if (saved.id <= 0 || tabs.any { it.id == saved.id }) return@forEach
            val history = saved.history.filter { it.isNotEmpty() && BrowserUrls.isWebUrl(it) }
                .takeLast(BrowserSessionStore.MAX_HISTORY)
            val index = if (history.isEmpty()) -1 else saved.historyIndex.coerceIn(0, history.lastIndex)
            tabs += BrowserTab(saved.id).apply {
                url = if (index >= 0) history[index] else saved.url
                title = saved.title
                host = Hosts.hostOf(url)
                scrollY = saved.scrollY
                this.history.addAll(history)
                historyIndex = index
                if (this.history.isEmpty() && url.isNotEmpty()) {
                    // A tab that only knows its address still gets that one history entry.
                    this.history.add(url)
                    historyIndex = 0
                }
                // Only the active tab is fetched now; the rest open their page when selected.
                pendingLazyLoad = url.isNotEmpty()
            }
            nextId = maxOf(nextId, saved.id + 1)
        }
        if (tabs.isEmpty()) return false
        activeId = tabs.firstOrNull { it.id == snapshot.activeId }?.id ?: tabs.first().id
        val tab = active
        if (tab.url.isNotEmpty()) {
            tab.pendingLazyLoad = false
            openRestored(tab)
        }
        return true
    }

    /** Opens a restored tab at the page it was left on, as a GET of its stored address. */
    private fun openRestored(tab: BrowserTab) {
        val target = tab.history.getOrNull(tab.historyIndex) ?: tab.url.takeIf { it.isNotEmpty() } ?: return
        tab.expectCommit = true
        tab.keepStoredForward = true
        tab.pendingHistoryUrl = null
        tab.clearLiveHistory = true
        tab.pendingScrollY = tab.scrollY
        tab.loading = true
        loadFiltered(tab, target)
        refreshHistoryFlags(tab)
    }

    fun newTab(input: String? = null): BrowserTab {
        if (tabs.size >= MAX_TABS) {
            toast(R.string.browser_too_many_tabs)
            return active
        }
        val tab = BrowserTab(nextId++)
        tabs += tab
        select(tab)
        if (input != null) navigate(tab, input)
        return tab
    }

    fun select(tab: BrowserTab) {
        if (tab.id != activeId) {
            tabs.firstOrNull { it.id == activeId }?.let { previousTabs.addLast(it) }
            previousTabs.remove(tab)
            activate(tab)
        }
    }

    fun returnToPreviousTab(): Boolean {
        val previous = previousTabs.removeLastOrNull() ?: return false
        activate(previous)
        return true
    }

    private fun activate(tab: BrowserTab) {
        tabs.firstOrNull { it.id == activeId }?.webView?.onPause()
        activeId = tab.id
        tab.chromeHidden = false
        tab.scrollTravel = 0
        if (tab.webView == null && tab.pendingLazyLoad) {
            tab.pendingLazyLoad = false
            if (tab.url.isNotEmpty()) openRestored(tab)
        }
        tab.webView?.onResume()
        main.postDelayed({
            if (tab.id == activeId) captureActiveThumbnail()
        }, THUMB_CAPTURE_DELAY_MS)
        persist()
    }

    fun close(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tab.webView?.let(::destroy)
        tab.webView = null
        dropTabState(tab)
        previousTabs.remove(tab)
        tabs.remove(tab)
        if (tabs.isEmpty()) {
            newTab()
        } else if (tab.id == activeId) {
            if (!returnToPreviousTab()) activate(tabs[(index - 1).coerceAtLeast(0)])
        }
        persist()
    }

    /** Loads what was typed in the address bar: an address, or a search. */
    fun navigate(tab: BrowserTab, input: String) {
        val target = BrowserUrls.resolve(input)
        if (target == null) {
            toast(R.string.error_webview_https)
            return
        }
        // A new navigation starts a new branch: whatever the tab could have gone forward to is gone.
        if (tab.webView == null) tab.liveHistoryStart = tab.historyIndex + 1
        tab.keepStoredForward = false
        recordEntry(tab, target)
        tab.url = target
        tab.host = Hosts.hostOf(target)
        tab.expectCommit = true
        tab.pendingHistoryUrl = null
        // Preserve a pending restore/fallback reset: its live list still belongs to the old page.
        tab.pendingScrollY = -1
        tab.pendingLazyLoad = false
        tab.scrollY = 0
        tab.chromeHidden = false
        tab.scrollTravel = 0
        loadFiltered(tab, target)
        refreshHistoryFlags(tab)
        persist()
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
        tab.history.clear()
        tab.posted.clear()
        tab.historyIndex = -1
        tab.expectCommit = false
        tab.liveHistoryStart = 0
        tab.keepStoredForward = false
        tab.pendingHistoryUrl = null
        tab.clearLiveHistory = false
        tab.scrollY = 0
        tab.pendingScrollY = -1
        tab.pendingLazyLoad = false
        tab.chromeHidden = false
        tab.scrollTravel = 0
        dropTabState(tab)
        persist()
    }

    /**
     * Explicitly clears WebView-owned site data and every open tab/history. This is intentionally
     * separate from clearing the recorded visit list: bookmarks and that visit list stay untouched.
     */
    fun clearSiteData(onComplete: () -> Unit) {
        val fullClear = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
        tabs.forEach { tab ->
            tab.webView?.apply {
                clearFormData()
                clearHistory()
                clearSslPreferences()
            }
        }
        if (!fullClear) {
            // The legacy HTTP cache is shared by all the WebViews in this profile.
            val view = tabs.firstNotNullOfOrNull { it.webView }
            if (view != null) {
                view.clearCache(true)
            } else {
                WebView(activity).apply {
                    clearCache(true)
                    destroy()
                }
            }
        }
        destroyAll()
        tabs.clear()
        // Never reuse tab IDs: Compose and pending callbacks still know the old ones.
        newTab()

        val storage = WebStorage.getInstance()
        if (fullClear) {
            WebStorageCompat.deleteBrowsingData(storage) { onComplete() }
        } else {
            storage.deleteAllData()
            CookieManager.getInstance().apply {
                removeAllCookies {
                    flush()
                    onComplete()
                }
            }
        }
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

    /**
     * Writes the tab strip for the next process: ids, addresses, titles, scroll offsets, each tab's
     * address history and its current position, and the active tab. Called on navigation/tab
     * transitions and once scrolling settles — never only from onDestroy, which a killed process
     * does not run.
     */
    fun persist() {
        main.removeCallbacks(persistRunnable)
        session.save(
            BrowserSessionStore.Snapshot(
                tabs = tabs.map { tab ->
                    // Walkable history is addresses fetched with GET only; the page on screen is
                    // always kept, even if a POST put it there, because its address is what the
                    // tab restores to (and it is fetched with GET too).
                    val entries = ArrayList<String>(tab.history.size)
                    var current = -1
                    tab.history.forEachIndexed { at, entry ->
                        if (at != tab.historyIndex && entry in tab.posted) return@forEachIndexed
                        if (at == tab.historyIndex) current = entries.size
                        entries += entry
                    }
                    BrowserSessionStore.Tab(
                        id = tab.id,
                        url = tab.url.trim(),
                        title = tab.title,
                        scrollY = tab.scrollY.coerceAtLeast(0),
                        history = entries,
                        historyIndex = current
                    )
                },
                activeId = activeId
            )
        )
    }

    /**
     * Appends [url] to the tab's history and drops whatever was ahead of the current page: a new
     * navigation starts a new branch, exactly like the WebView's own history list.
     */
    private fun recordEntry(tab: BrowserTab, url: String) {
        if (!BrowserUrls.isWebUrl(url)) return
        while (tab.history.size > tab.historyIndex + 1) tab.history.removeAt(tab.history.lastIndex)
        tab.history.add(url)
        while (tab.history.size > BrowserSessionStore.MAX_HISTORY) {
            tab.history.removeAt(0)
            tab.liveHistoryStart--
        }
        tab.historyIndex = tab.history.lastIndex
        refreshHistoryFlags(tab)
    }

    /**
     * The page at [tab.historyIndex] is now the committed one; a redirect may have landed on a
     * different address, and that resolved address is what the next visit should request.
     */
    private fun settleEntry(tab: BrowserTab, url: String) {
        if (!BrowserUrls.isWebUrl(url)) return
        val index = tab.historyIndex
        if (index in tab.history.indices) tab.history[index] = url
    }

    /**
     * Native entries, not callback count or URL equality, define live history: redirects and
     * replaceState rewrite entries, while pushState can add the same URL more than once.
     */
    private fun syncLiveHistory(tab: BrowserTab, view: WebView): String? {
        val live = view.copyBackForwardList()
        val current = live.currentIndex
        val url = live.currentItem?.url ?: return null
        if (current < 0 || !BrowserUrls.isWebUrl(url)) return null
        tab.pendingHistoryUrl = null
        if (tab.clearLiveHistory) {
            // Fallback temporarily appends to the old live list. Never adopt that stale list.
            settleEntry(tab, url)
            tab.expectCommit = false
            return url
        }
        tab.expectCommit = false
        val start = tab.liveHistoryStart
        tab.keepStoredForward = tab.keepStoredForward && live.size == 1
        val end = if (tab.keepStoredForward) maxOf(tab.history.size, start + live.size) else start + live.size
        // The native list can outlive our bounded cache. Keep its current page inside the window
        // even when Back reaches a native entry already trimmed out of the stored addresses.
        val windowStart = minOf(start + current, (end - BrowserSessionStore.MAX_HISTORY).coerceAtLeast(0))
        val windowEnd = minOf(end, windowStart + BrowserSessionStore.MAX_HISTORY)
        repeat(windowStart.coerceIn(0, tab.history.size)) { tab.history.removeAt(0) }
        val mappedStart = start - windowStart
        for (index in (-mappedStart).coerceAtLeast(0) until minOf(live.size, windowEnd - start)) {
            val at = mappedStart + index
            val entry = checkNotNull(live.getItemAtIndex(index)).url
            if (at < tab.history.size) {
                if (tab.history[at] != entry) tab.history[at] = entry
            } else {
                tab.history.add(entry)
            }
        }
        while (tab.history.size > windowEnd - windowStart) tab.history.removeAt(tab.history.lastIndex)
        tab.liveHistoryStart = mappedStart
        tab.historyIndex = mappedStart + current
        return url
    }

    /** Back/Forward availability covers the live WebView and the stored backlog behind it. */
    private fun refreshHistoryFlags(tab: BrowserTab) {
        val view = tab.webView
        val useLive = !tab.clearLiveHistory && !tab.expectCommit
        tab.canGoBack = (useLive && view?.canGoBack() == true) || tab.historyIndex > 0
        tab.canGoForward = (useLive && view?.canGoForward() == true) || tab.historyIndex < tab.history.lastIndex
    }

    private fun schedulePersist() {
        main.removeCallbacks(persistRunnable)
        main.postDelayed(persistRunnable, PERSIST_DELAY_MS)
    }

    /**
     * Scroll feedback from the active tab's WebView. Besides remembering the offset it slides the
     * top chrome: collapsing needs a clear downward travel, revealing a shorter upward one, and a
     * page near its top always shows the chrome. The travel accumulator resets whenever the
     * direction changes, so jitter at one spot cannot toggle the toolbar.
     */
    fun onScroll(tab: BrowserTab, scrollY: Int, delta: Int) {
        tab.scrollY = scrollY.coerceAtLeast(0)
        // A load that never finishes (error page) must not freeze the chrome logic forever.
        if (tab.pendingScrollY >= 0 && !tab.loading) tab.pendingScrollY = -1
        if (tab.scrollRestoring || tab.pendingScrollY >= 0) return
        schedulePersist()
        // A jump larger than the viewport is programmatic (restore, anchor, in-page jump), not a
        // gesture; letting it count would slam the chrome shut.
        val viewport = tab.webView?.height ?: 0
        if (viewport > 0 && kotlin.math.abs(delta) >= viewport) return
        val density = activity.resources.displayMetrics.density
        if (scrollY <= CHROME_PAGE_TOP_DP * density) {
            if (tab.chromeHidden) tab.chromeHidden = false
            tab.scrollTravel = 0
            return
        }
        tab.scrollTravel = if (tab.scrollTravel != 0 && (tab.scrollTravel > 0) != (delta > 0)) {
            delta
        } else {
            tab.scrollTravel + delta
        }
        when {
            !tab.chromeHidden && tab.scrollTravel >= CHROME_COLLAPSE_TRAVEL_DP * density -> {
                tab.chromeHidden = true
                tab.scrollTravel = 0
            }
            tab.chromeHidden && tab.scrollTravel <= -CHROME_REVEAL_TRAVEL_DP * density -> {
                tab.chromeHidden = false
                tab.scrollTravel = 0
            }
        }
    }

    /** The address bar is in use: put the top chrome back, whatever the page is doing. */
    fun showChrome(tab: BrowserTab) {
        if (!tab.chromeHidden) return
        tab.chromeHidden = false
        tab.scrollTravel = 0
    }

    /**
     * Puts a restored tab back where it was. The WebView's own offset is moved first; a page that
     * keeps its scroll position in script gets one direct fallback. Both are suppressed from the
     * chrome logic through [BrowserTab.scrollRestoring], so the jump does not slide the toolbar.
     */
    private fun applyPendingScroll(view: WebView, tab: BrowserTab) {
        val target = tab.pendingScrollY
        tab.pendingScrollY = -1
        if (target <= 0 || tab.webView !== view) return
        tab.scrollRestoring = true
        view.scrollTo(0, target)
        view.postDelayed({
            // Ask the page where it actually is; only a page that did not take the offset gets the
            // direct script call, so a working WebView is not scrolled twice.
            if (tab.webView === view) {
                view.evaluateJavascript("window.scrollY") { value ->
                    val current = value?.trim()?.toFloatOrNull() ?: 0f
                    if (current + 1f < target) {
                        view.evaluateJavascript("window.scrollTo(0,$target)", null)
                    }
                }
            }
            tab.scrollRestoring = false
            tab.scrollY = view.scrollY.coerceAtLeast(tab.scrollY)
        }, SCROLL_RESTORE_SETTLE_MS)
    }

    /**
     * Back: the live WebView history first (it keeps page state and needs no request), then the
     * stored addresses once the live list is exhausted — that is the case after a process restart,
     * and after walking back past the point where the restored page was opened. The stored address
     * is always loaded as a plain GET, so a form submission is never replayed.
     */
    fun goBack(tab: BrowserTab) {
        val view = tab.webView
        if (view != null && !tab.clearLiveHistory && !tab.expectCommit && view.canGoBack()) {
            tab.pendingHistoryUrl = view.copyBackForwardList().let { it.getItemAtIndex(it.currentIndex - 1)?.url }
            tab.pendingHistoryUrl?.let { prepare(tab, view, it) }
            view.goBack()
            refreshHistoryFlags(tab)
            persist()
            return
        }
        loadHistoryEntry(tab, tab.historyIndex - 1)
    }

    fun goForward(tab: BrowserTab) {
        val view = tab.webView
        if (view != null && !tab.clearLiveHistory && !tab.expectCommit && view.canGoForward()) {
            tab.pendingHistoryUrl = view.copyBackForwardList().let { it.getItemAtIndex(it.currentIndex + 1)?.url }
            tab.pendingHistoryUrl?.let { prepare(tab, view, it) }
            view.goForward()
            refreshHistoryFlags(tab)
            persist()
            return
        }
        loadHistoryEntry(tab, tab.historyIndex + 1)
    }

    /**
     * Walks the stored history. The WebView's own list is cleared once the page finishes, because
     * it only holds the pages this session opened from here; leaving it would let a second Back go
     * forward in the stored list, which is the one thing a history fallback must not do.
     */
    private fun loadHistoryEntry(tab: BrowserTab, target: Int) {
        if (target !in tab.history.indices) return
        val url = BrowserPolicy.httpsOnly(tab.history[target])
        if (!BrowserUrls.isWebUrl(url)) return
        tab.historyIndex = target
        tab.url = url
        tab.host = Hosts.hostOf(url)
        tab.expectCommit = true
        tab.keepStoredForward = true
        tab.pendingHistoryUrl = null
        tab.clearLiveHistory = true
        tab.pendingScrollY = -1
        tab.loading = true
        tab.chromeHidden = false
        tab.scrollTravel = 0
        loadFiltered(tab, url)
        refreshHistoryFlags(tab)
        persist()
    }

    fun reload(tab: BrowserTab) {
        tab.webView?.let { prepare(tab, it, tab.url); it.reload() }
    }

    fun stop(tab: BrowserTab) {
        tab.webView?.stopLoading()
    }

    /** Videos the page has loaded, plus the ones its `<video>` elements point at right now. */
    fun refreshVideos(tab: BrowserTab) {
        val view = tab.webView ?: return
        val generation = tab.pageGeneration
        view.evaluateJavascript(MediaSniffer.COLLECT_SCRIPT) { raw ->
            if (tab.webView !== view || tab.pageGeneration != generation) return@evaluateJavascript
            MediaSniffer.parseCollected(raw).forEach(tab::noteVideo)
        }
    }

    /** Headers a download of [url] needs to look like the page's own request. */
    fun downloadHeaders(tab: BrowserTab, url: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val page = tab.url.toHttpUrlOrNull()
        val target = url.toHttpUrlOrNull()
        tab.webView?.settings?.userAgentString?.let { out["User-Agent"] = it }
        if (page != null && target != null && page.scheme == target.scheme &&
            page.host == target.host && page.port == target.port
        ) {
            out["Referer"] = page.newBuilder().fragment(null).build().toString()
            CookieManager.getInstance().getCookie(url)?.takeIf(String::isNotBlank)?.let { out["Cookie"] = it }
        }
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
        engine.endSession()
        main.removeCallbacks(persistRunnable)
        hideFullscreen()
        tabs.forEach { tab -> tab.webView?.let(::destroy); tab.webView = null }
        previousTabs.clear()
        thumbStates.clear()
        pendingNavigation = null
        pendingWindow = null
        popupProbe?.let(::destroy)
        popupProbe = null
    }

    private fun destroy(view: WebView) {
        shields.remove(view)?.close()
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
            if (mimeType?.startsWith("video/") == true && MediaSniffer.isAllowedDownload(url, tab.host)) {
                tab.noteVideo(FoundVideo(url, MediaSniffer.classify(url) ?: MediaKind.FILE))
                toast(R.string.browser_download_offered)
            } else {
                toast(R.string.browser_download_unsupported)
            }
        }
        val shield = PageShield(this, engine, cosmeticRuntime, { mode().generic }) { blockerOff(it) || !mode().cosmetic }
        shields[this] = shield
        webViewClient = Client(tab, shield)
        webChromeClient = Chrome()
    }

    private fun prepare(tab: BrowserTab, view: WebView, url: String) {
        tab.filterSource = url
        shields[view]?.prepare(url)
    }

    private fun loadFiltered(tab: BrowserTab, url: String) {
        val view = webViewOf(tab)
        prepare(tab, view, url)
        tab.pendingLoadUrl = url
        view.loadUrl(url)
    }

    private inner class Client(private val owner: BrowserTab, private val shield: PageShield) : WebViewClient() {
        private val filterSession = engine.sessionEpoch
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
            val get = request.method?.equals("GET", ignoreCase = true) == true
            if (!get) {
                // A form post cannot be replayed as a GET: an insecure one is simply refused.
                if (secure != target) {
                    toast(R.string.browser_insecure_navigation_blocked)
                    return true
                }
                if (BrowserPolicy.requiresConfirmation(tab.host, secure)) {
                    // WebView gives no way to resume this exact request after a dialog. Converting
                    // it to GET would change its meaning, so cross-site non-GET is honestly blocked.
                    toast(R.string.browser_non_get_navigation_blocked)
                    return true
                }
                // The address is remembered for tracking only; it is never stored as a walkable
                // history entry, because that would mean re-requesting a POST result as a GET.
                tab.posted += target
                prepare(tab, view, secure)
                return false
            }
            val historyRequest = secure == tab.pendingHistoryUrl
            tab.pendingHistoryUrl = null
            if (!historyRequest && BrowserPolicy.requiresConfirmation(tab.host, secure)) {
                pendingNavigation = PendingNavigation(tab.id, secure)
                // A server redirect can already be in flight; returning true alone does not
                // always abort it, so stop the load before asking.
                view.stopLoading()
                return true
            }
            prepare(tab, view, secure)
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
            val tab = owner
            val generation = tab.pageGeneration
            if (request.isForMainFrame && request.method.equals("POST", ignoreCase = true)) {
                // WebView does not call shouldOverrideUrlLoading for form POSTs.
                if (!BrowserPolicy.isSecureWebTarget(url) || BrowserPolicy.isCrossSite(tab.host, url)) {
                    main.post {
                        if (tab.webView === view) toast(R.string.browser_non_get_navigation_blocked)
                    }
                    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), null)
                }
                main.post { if (tab.webView === view) tab.posted += url }
            }
            shield.stylesheet(request)?.let { return it }
            val source = tab.filterSource
            if (!request.isForMainFrame && !blockerOff(Hosts.hostOf(source))) {
                val kind = RequestKinds.guess(url, request.requestHeaders, false)
                val result = engine.decide(url, source, kind, request.method.lowercase(), filterSession)
                if (result != null) {
                    main.post { if (tab.webView === view && tab.pageGeneration == generation) { tab.blocked++; onBlocked() } }
                    return replacement(if (request.url.scheme.equals("https", true)) result else "")
                }
            }
            val media = MediaSniffer.classify(url)
            if (media != null) main.post {
                if (tab.webView === view && tab.pageGeneration == generation) tab.noteVideo(FoundVideo(url, media))
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // The URL is adopted only when the page commits: a blocked redirect must not become
            // the host that the next cross-site check compares against.
            val tab = tabOf(view) ?: return
            tab.pendingLoadUrl = null
            tab.pageGeneration++
            tab.filterSource = url
            tab.loading = true
            tab.blocked = 0
            tab.videos = emptyList()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val tab = tabOf(view) ?: return
            if (tab.pendingLoadUrl != null || url != view.url) return
            tab.url = url
            tab.host = Hosts.hostOf(url)
            shields[view]?.committed(url)
            refreshHistoryFlags(tab)
            persist()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val tab = tabOf(view) ?: return
            if (url != view.url || (tab.pendingLoadUrl != null && url != tab.pendingLoadUrl)) return
            tab.pendingLoadUrl = null
            val committed = syncLiveHistory(tab, view) ?: return
            tab.url = committed
            tab.host = Hosts.hostOf(committed)
            refreshHistoryFlags(tab)
            persist()
        }

        override fun onPageFinished(view: WebView, url: String) {
            val tab = tabOf(view) ?: return
            // Superseded loads can finish after the next navigation has already started.
            if (tab.pendingLoadUrl != null || url != view.url) return
            if (tab.expectCommit || tab.clearLiveHistory) {
                syncLiveHistory(tab, view)
                tab.expectCommit = false
                settleEntry(tab, url)
            }
            tab.pendingHistoryUrl = null
            tab.loading = false
            tab.url = url
            tab.host = Hosts.hostOf(url)
            if (tab.clearLiveHistory) {
                tab.clearLiveHistory = false
                view.clearHistory()
                tab.liveHistoryStart = tab.historyIndex
                tab.keepStoredForward = true
            }
            refreshHistoryFlags(tab)
            applyPendingScroll(view, tab)
            refreshVideos(tab)
            onVisit(url, view.title.orEmpty())
            persist()
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
            tab.pendingScrollY = -1
            tab.scrollRestoring = false
            tab.pendingLazyLoad = false
            tab.expectCommit = false
            tab.liveHistoryStart = tab.historyIndex
            tab.keepStoredForward = true
            tab.pendingHistoryUrl = null
            tab.clearLiveHistory = false
            tab.canGoBack = tab.historyIndex > 0
            tab.canGoForward = tab.historyIndex < tab.history.lastIndex
            dropTabState(tab)
            destroy(view)
            toast(R.string.browser_page_crashed)
            return true
        }
    }

    /** Drops the popup probe and turns its captured URL into a window the user may approve. */
    private fun finishPopupProbe(url: String?, opener: BrowserTab) {
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
        val secure = BrowserPolicy.httpsOnly(url)
        if (!blockerOff(opener.host) && engine.decide(secure, opener.filterSource, "popup", "get") != null) {
            opener.blocked++; onBlocked()
            toast(R.string.browser_popup_blocked)
            return
        }
        pendingWindow = PendingWindow(secure)
    }

    private fun replacement(result: String): WebResourceResponse {
        val comma = result.indexOf(',')
        if (result.startsWith("data:") && comma > 5 && result.substring(5, comma).endsWith(";base64")) {
            val bytes = android.util.Base64.decode(result.substring(comma + 1), android.util.Base64.DEFAULT)
            return WebResourceResponse(result.substring(5, comma).substringBefore(';'), null, 200, "OK",
                mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(bytes))
        }
        return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), null)
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
            val opener = tabOf(view) ?: return false
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
                        finishPopupProbe(request.url?.toString(), opener)
                        return true
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        // Some popups start without a shouldOverride; capture before any load.
                        finishPopupProbe(url, opener)
                    }
                }
            }
            popupProbe = probe
            transport.webView = probe
            resultMsg.sendToTarget()
            main.postDelayed({
                if (popupProbe === probe) finishPopupProbe(null, opener)
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

        /** Scrolling settles before the session file is rewritten. */
        private const val PERSIST_DELAY_MS = 500L

        /** Time for a restored page to accept its scroll offset before the script fallback runs. */
        private const val SCROLL_RESTORE_SETTLE_MS = 200L

        /** Chrome stays visible while the page is within this distance of its top. */
        private const val CHROME_PAGE_TOP_DP = 36f

        /** Downward travel that collapses the top chrome, and the shorter upward travel that returns it. */
        private const val CHROME_COLLAPSE_TRAVEL_DP = 56f
        private const val CHROME_REVEAL_TRAVEL_DP = 20f
    }
}
