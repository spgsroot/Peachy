package ru.spgsroot.peachy.ui.webview

import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.ClientCertRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.MainActivity
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.data.browser.BrowserSessionStore
import ru.spgsroot.peachy.data.session.AppSessionStore

/**
 * Real browser activity, controller and WebView; only HTTP responses are synthetic. Requires the
 * isolated debug browserqa APK. A fresh QA profile uses PIN 6084; an existing profile must use
 * the same PIN. The test runs with a blank tab snapshot and restores the prior snapshot.
 * Run with AndroidJUnitRunner -e class ru.spgsroot.peachy.ui.webview.BrowserNavigationTest.
 * Optional -e manualLaunch true waits up to three minutes for a launcher tap (not a Back workaround).
 * Optional -e edgeGesture true uses a real left-edge swipe; requires gestural navigation and an
 * emulator/device that permits UiAutomation input injection. The default injects KEYCODE_BACK.
 */
@RunWith(AndroidJUnit4::class)
class BrowserNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val origin = "https://navigation-${UUID.randomUUID()}.example.invalid"
    private val pageA = "$origin/a"
    private val pageB = "$origin/b"

    @Test
    fun pageHistoryAndFreshTabBack_keepBrowserAndExistingContents_thenSingleRootExits() {
        assumeTrue("Requires the isolated browserqa app ID", context.packageName == "ru.spgsroot.peachy.browserqa")
        // Avoid automatic subscription downloads; the test never requires an external server.
        val shield = context.getSharedPreferences("browser_shield", Context.MODE_PRIVATE)
        val hadAttempt = shield.contains("attempt")
        val oldAttempt = shield.getLong("attempt", 0L)
        check(shield.edit().putLong("attempt", System.currentTimeMillis()).commit())
        val session = BrowserSessionStore.forApp(context)
        val prior = session.load()
        val blank = BrowserSessionStore.Snapshot(listOf(BrowserSessionStore.Tab(1, "", "", 0)), 1)
        session.save(blank)
        val appSession = AppSessionStore(context)
        val previouslyOpen = appSession.read().browserOpen
        appSession.setBrowserOpen(true)
        try {
            val manualLaunch = InstrumentationRegistry.getArguments().getString("manualLaunch") == "true"
            if (manualLaunch) report("Open Peachy Browser QA from the device launcher")
            else context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            openBrowser(if (manualLaunch) 180_000 else 15_000)
            val activity = waitFor("resumed browser") { resumedActivity() as? WebViewActivity }
            // The blank start page has no WebView in its decor yet. Reflection is limited to fixture
            // setup so the app-created view can receive an interceptor before its first request.
            // All displayed-page assertions independently obtain the WebView from the activity decor.
            val controller = onMain {
                WebViewActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
                    .get(activity) as BrowserController
            }
            val existing = onMain {
                assertEquals("Requires one blank QA tab; do not clear a personal app", 1, controller.tabs.size)
                controller.active.also {
                    assertEquals("Requires a blank QA browser session", "", it.url)
                    assertFalse(it.canGoBack)
                    assertFalse(it.canGoForward)
                }
            }
            val web = onMain {
                controller.webViewOf(existing).also { view ->
                    view.webViewClient = FixtureClient(view.webViewClient)
                    controller.navigate(existing, pageA)
                }
            }
            awaitPage(activity, controller, existing, web, pageA, "A")
            evaluate(web, "document.querySelector('a').click()")
            awaitPage(activity, controller, existing, web, pageB, "B")
            waitFor("Back enabled on page B") { onMain { existing.canGoBack.takeIf { it } } }
            assertEquals(listOf(pageA, pageB), onMain {
                web.copyBackForwardList().let { history -> (0 until history.size).map { history.getItemAtIndex(it).url } }
            })

            systemBack()
            awaitPage(activity, controller, existing, web, pageA, "A")
            waitFor("Forward enabled after system Back") { onMain { existing.canGoForward.takeIf { it } } }
            click(R.string.browser_forward)
            awaitPage(activity, controller, existing, web, pageB, "B")
            // Exercise toolbar Back as well: state flags alone must not masquerade as navigation.
            click(R.string.browser_back)
            awaitPage(activity, controller, existing, web, pageA, "A")
            click(R.string.browser_forward)
            awaitPage(activity, controller, existing, web, pageB, "B")
            // A single-page site adds and then renames an entry without loading another document.
            evaluate(web, "document.querySelector('button').click()")
            awaitPage(activity, controller, existing, web, "$pageB#section", "section")
            systemBack()
            awaitPage(activity, controller, existing, web, pageB, "B")
            click(R.string.browser_forward)
            awaitPage(activity, controller, existing, web, "$pageB#section", "section")
            click(R.string.browser_back)
            awaitPage(activity, controller, existing, web, pageB, "B")
            assertEquals("kept in the existing tab", evaluate(web,
                "document.getElementById('draft').value = 'kept in the existing tab'"))

            click(R.string.browser_menu)
            click(R.string.browser_new_tab)
            val fresh = waitFor("new blank tab") {
                onMain { controller.active.takeIf { it !== existing && it.url.isEmpty() && it.webView == null } }
            }
            val tabIds = onMain { controller.tabs.map { it.id } }
            assertEquals(2, tabIds.size)
            assertFalse(onMain { fresh.canGoBack })
            systemBack()
            awaitPage(activity, controller, existing, web, pageB, "B")
            assertEquals("Root Back must not delete either tab", tabIds, onMain { controller.tabs.map { it.id } })
            assertEquals("Root Back must preserve the existing page's live form contents",
                "kept in the existing tab", evaluate(web, "document.getElementById('draft').value"))
            assertSame("The existing tab must retain its WebView", web, onMain { existing.webView })
            report("PASS: A→B; system Back→A; toolbar Forward→B; toolbar Back/Forward; fresh root→previous tab with form retained")

            // Reduce to the test-owned original root for the no-previous-tab fallback. Closing the
            // fresh fixture tab is setup, not an implementation of the Back transition under test.
            onMain { controller.close(fresh) }
            click(R.string.browser_menu)
            click(R.string.browser_home)
            waitFor("single blank root") {
                onMain { (controller.tabs.size == 1 && existing.webView == null && existing.url.isEmpty()).takeIf { it } }
            }
            systemBack()
            waitFor("root Back returns to the main app, not the launcher") { resumedActivity() as? MainActivity }
            assertTrue("Root fallback finishes the browser activity", onMain { activity.isFinishing || activity.isDestroyed })
            report("PASS: single root Back returns to the main app")
        } finally {
            onMain { (resumedActivityOnMain() as? WebViewActivity)?.finish() }
            instrumentation.waitForIdleSync()
            session.save(prior ?: blank.copy(tabs = emptyList()))
            AppSessionStore(context).setBrowserOpen(previouslyOpen)
            check(shield.edit().apply {
                if (hadAttempt) putLong("attempt", oldAttempt) else remove("attempt")
            }.commit())
        }
    }

    private fun awaitPage(activity: WebViewActivity, controller: BrowserController, tab: BrowserTab,
                          web: WebView, url: String, marker: String) {
        try {
            waitFor("live page $marker") {
                onMain {
                    (resumedActivityOnMain() === activity && !activity.isFinishing && controller.active === tab &&
                        tab.webView === web && web.isAttachedToWindow && !tab.loading && tab.url == url && web.url == url)
                        .takeIf { it }
                }
            }
        } catch (failure: AssertionError) {
            val state = onMain {
                "resumed=${resumedActivityOnMain() === activity}, active=${controller.active === tab}, " +
                    "attached=${web.isAttachedToWindow}, loading=${tab.loading}, model=${tab.url.removePrefix(origin)}, " +
                    "view=${web.url?.removePrefix(origin)}, confirmation=${controller.pendingNavigation != null}"
            }
            throw AssertionError("Page $marker: $state", failure)
        }
        waitFor("rendered fixture $marker") {
            (evaluate(web, "document.body && document.body.dataset.page") == marker).takeIf { it }
        }
        waitForNode("browser address $url") { it.isEditable && it.text?.toString() == url }
        assertSame("The displayed view is the app's WebView", web, onMain { findWebView(activity.window.decorView) })
    }

    private fun evaluate(web: WebView, script: String): String? {
        val result = ArrayBlockingQueue<String>(1)
        onMain { web.evaluateJavascript(script) { result.offer(it) } }
        val json = result.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("WebView JavaScript did not finish")
        return JSONTokener(json).nextValue().let { if (it == org.json.JSONObject.NULL) null else it.toString() }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun systemBack() {
        // A dismissed dropdown can still own the system Back callback during its exit animation.
        waitFor("browser window focused for Back") {
            onMain { (resumedActivityOnMain() as? WebViewActivity)?.takeIf { it.hasWindowFocus() } }
        }
        waitForNode("browser toolbar in the active window") {
            it.matchesLabel(context.getString(R.string.browser_menu))
        }
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .waitForIdle(400, 5_000)
        if (InstrumentationRegistry.getArguments().getString("edgeGesture") == "true") {
            edgeBack()
        } else {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        instrumentation.waitForIdleSync()
    }

    private fun edgeBack() {
        val bounds = onMain {
            val activity = resumedActivityOnMain() ?: throw AssertionError("No resumed activity for Back gesture")
            val resources = activity.resources
            val modeId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
            assertTrue("edgeGesture=true requires gestural navigation (config_navBarInteractionMode=2)",
                modeId != 0 && resources.getInteger(modeId) == 2)
            val decor = activity.window.decorView
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            val window = Rect(location[0], location[1], location[0] + decor.width, location[1] + decor.height)
            val content = Rect()
            val web = findWebView(decor)
            // Read fresh geometry for every transition, including blank roots with no WebView.
            if (web == null || !web.getGlobalVisibleRect(content)) decor.getWindowVisibleDisplayFrame(content)
            val y = content.centerY().coerceIn(window.top + 1, window.bottom - 2).toFloat()
            val startX = (window.left + 1).toFloat()
            val distance = minOf(window.width() * 0.4f, 240f * resources.displayMetrics.density)
            Triple(startX, startX + distance, y)
        }
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, x: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, bounds.third, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("Inject left-edge Back gesture action=$action", automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        inject(MotionEvent.ACTION_DOWN, bounds.first)
        for (step in 1..16) {
            SystemClock.sleep(20)
            inject(MotionEvent.ACTION_MOVE, bounds.first + (bounds.second - bounds.first) * step / 16f)
        }
        inject(MotionEvent.ACTION_UP, bounds.second)
    }

    private fun resumedActivity(): Activity? = onMain { resumedActivityOnMain() }
    private fun resumedActivityOnMain(): Activity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun openBrowser(timeoutMs: Long) {
        val setup = context.getString(R.string.btn_set_password)
        val login = context.getString(R.string.btn_login)
        val pin = "6084"
        val entry = waitFor("onboarding, unlock, or restored browser", timeoutMs) {
            findNode { it.isEditable || it.matchesLabel(setup) || it.matchesLabel(login) }
        }
        if (entry.isEditable) return
        when {
            entry.matchesLabel(setup) -> {
                click(setup)
                pin.forEach { click(it.toString()) }
                click(R.string.label_confirm_password)
                pin.forEach { click(it.toString()) }
                click(R.string.btn_set)
            }
            entry.matchesLabel(login) -> {
                pin.forEach { click(it.toString()) }
                click(login)
            }
        }
        waitForNode("browser address") { it.isEditable }
    }

    private fun click(resId: Int) = click(context.getString(resId))
    private fun click(label: String) {
        val button = waitFor("enabled control '$label'") {
            generateSequence(findNode { it.matchesLabel(label) }) { it.parent }
                .firstOrNull { it.packageName == context.packageName && it.isClickable && it.isEnabled }
        }
        assertTrue("Click '$label'", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun AccessibilityNodeInfo.matchesLabel(label: String): Boolean =
        text?.toString() == label || contentDescription?.toString() == label

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.packageName != context.packageName) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { search(it)?.let { match -> return match } }
            }
            return null
        }
        return instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .rootInActiveWindow?.let(::search)
    }

    private fun waitForNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        waitFor(label) { findNode(predicate) }

    private fun <T : Any> waitFor(label: String, timeoutMs: Long = 15_000, condition: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            condition()?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $label")
    }

    private fun report(message: String) = instrumentation.sendStatus(2, Bundle().apply {
        putString("stream", "\n$message\n")
    })

    /** Preserve the production navigation/history lifecycle; substitute only network responses. */
    private inner class FixtureClient(private val delegate: WebViewClient) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            delegate.shouldOverrideUrlLoading(view, request)
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = delegate.onPageStarted(view, url, favicon)
        override fun onPageCommitVisible(view: WebView, url: String) = delegate.onPageCommitVisible(view, url)
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) =
            delegate.doUpdateVisitedHistory(view, url, isReload)
        override fun onPageFinished(view: WebView, url: String) = delegate.onPageFinished(view, url)
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) =
            delegate.onReceivedSslError(view, handler, error)
        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) =
            delegate.onReceivedClientCertRequest(view, request)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
            delegate.onRenderProcessGone(view, detail)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            delegate.shouldInterceptRequest(view, request)?.let { return it }
            val marker = when (request.url.toString()) {
                pageA -> "A"
                pageB -> "B"
                // No request, including favicon/subresources, can fall through to the network.
                else -> return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), null)
            }
            val html = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>QA page $marker</title><link rel="icon" href="data:,"></head>
                <body data-page="$marker"><h1>QA page $marker</h1>
                <a href="$pageB">QA open page B</a>
                <button onclick="history.pushState({}, '', '#draft'); history.replaceState({}, '', '#section'); render()">QA open section</button>
                <script>
                    function render() { document.body.dataset.page = location.hash ? 'section' : '$marker'; }
                    addEventListener('popstate', render);
                </script>
                <label>QA draft <input id="draft" value="original $marker"></label></body></html>
            """.trimIndent()
            return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
    }
}
