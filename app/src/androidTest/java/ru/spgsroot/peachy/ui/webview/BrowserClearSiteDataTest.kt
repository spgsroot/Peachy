package ru.spgsroot.peachy.ui.webview

import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.MainActivity
import ru.spgsroot.peachy.R

@RunWith(AndroidJUnit4::class)
class BrowserClearSiteDataTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val origin = "https://browser-qa-${UUID.randomUUID()}.example.invalid/"
    private var restoreUpdateTime: (() -> Unit)? = null

    @org.junit.After
    fun restoreFixtureClock() { restoreUpdateTime?.invoke() }

    @Test
    fun cancelKeepsSiteDataAndDraft_confirmClearsBoth() {
        // The browser clears the entire WebView profile. Never run against the regular app ID.
        assumeTrue("Requires the isolated browserqa app ID", context.packageName == "ru.spgsroot.peachy.browserqa")
        // Clearing WebView data does not require contacting any filter publisher.
        val shield = context.getSharedPreferences("browser_shield", android.content.Context.MODE_PRIVATE)
        val attempt = shield.getLong("attempt", -1L)
        restoreUpdateTime = { shield.edit().apply { if (attempt < 0) remove("attempt") else putLong("attempt", attempt) }.commit(); Unit }
        check(shield.edit().putLong("attempt", System.currentTimeMillis()).commit())
        val manualLaunch = InstrumentationRegistry.getArguments().getString("manualLaunch") == "true"
        if (manualLaunch) {
            report("Waiting for the QA app to be opened from the device launcher")
        } else {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        openBrowser(if (manualLaunch) 180_000 else 10_000)
        val draft = "Unsubmitted QA search"
        assertEquals("", address())
        report("Browser start page opened")
        var fullClearSupported = false
        instrumentation.runOnMainSync {
            fullClearSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
        }
        assumeTrue("WebView does not support full site-data clearing", fullClearSupported)
        val seeded = "cookie=1,local=1,idb=1,cache=1"
        assertEquals(seeded, readSiteData("Seed", seed = true))
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
        }
        waitForNode("address field") { it.isEditable }.also {
            assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        }
        waitForNode("address field showing the draft") { it.isEditable && it.text?.toString() == draft }

        openClearDialog()
        click(R.string.btn_cancel)
        waitForClearDialogToClose()
        assertEquals(draft, address())
        assertEquals(seeded, readSiteData("Cancel"))

        openClearDialog()
        click(R.string.browser_clear_site_data_confirm)
        waitForClearDialogToClose()
        val clearedAddress = address()
        val clearedSiteData = readSiteData("Confirm")
        report("Address after confirm=${if (clearedAddress.isEmpty()) "empty" else "not empty"}")
        assertEquals("Address draft", "", clearedAddress)
        assertEquals("cookie=0,local=0,idb=0,cache=0", clearedSiteData)

        click(R.string.browser_menu)
        click(R.string.browser_close_browser)
    }

    private fun openBrowser(launchTimeoutMs: Long) {
        val setup = context.getString(R.string.btn_set_password)
        val login = context.getString(R.string.btn_login)
        val browser = context.getString(R.string.browser_open)
        val pin = "6084"
        val entry = waitFor("onboarding, unlock, player, or restored browser", launchTimeoutMs) {
            findNode { it.isEditable || it.matchesLabel(setup) || it.matchesLabel(login) || it.matchesLabel(browser) }
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
        val screen = waitForNode("player or restored browser") { it.isEditable || it.matchesLabel(browser) }
        if (screen.isEditable) return
        click(R.string.browser_open)
    }

    private fun openClearDialog() {
        click(R.string.browser_menu)
        click(R.string.browser_clear_site_data)
    }

    private fun waitForClearDialogToClose() {
        val title = context.getString(R.string.browser_clear_site_data)
        waitFor("clear-site-data dialog to close") { (findNode { it.matchesLabel(title) } == null).takeIf { it } }
    }

    private fun address(): String = waitForNode("address field") { it.isEditable }.text?.toString().orEmpty()

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

    private fun <T : Any> waitFor(label: String, timeoutMs: Long = 10_000, condition: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            condition()?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $label")
    }

    private fun readSiteData(step: String, seed: Boolean = false): String {
        // Every read uses a new WebView in the same default profile; no HTTP requests or JS bridge.
        val result = ArrayBlockingQueue<String>(1)
        lateinit var fixture: WebView
        instrumentation.runOnMainSync {
            fixture = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.blockNetworkLoads = true
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (!message.message().startsWith("browserqa:")) return false
                        result.offer(message.message().removePrefix("browserqa:"))
                        return true
                    }
                }
                loadDataWithBaseURL(origin, fixtureHtml(seed), "text/html", "UTF-8", null)
            }
        }
        try {
            val markers = result.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("$step: WebView did not report storage")
            report("$step: $markers")
            return markers
        } finally {
            instrumentation.runOnMainSync { fixture.destroy() }
        }
    }

    private fun report(message: String) = instrumentation.sendStatus(2, Bundle().apply {
        putString("stream", "\n$message\n")
    })

    private fun fixtureHtml(seed: Boolean) = """
        <!doctype html><meta charset="utf-8"><script>
        (async () => {
            if ($seed) {
                document.cookie = 'browserqa=synthetic; Secure';
                localStorage.browserqa = 'synthetic';
                await new Promise((resolve, reject) => {
                    const request = indexedDB.open('browserqa', 1);
                    request.onupgradeneeded = () => request.result.createObjectStore('fixture');
                    request.onsuccess = () => { request.result.close(); resolve(); };
                    request.onerror = () => reject(request.error);
                });
                await (await caches.open('browserqa')).put('/fixture', new Response('synthetic'));
            }
            const cookie = document.cookie.split('; ').includes('browserqa=synthetic');
            const local = localStorage.browserqa === 'synthetic';
            const idb = (await indexedDB.databases()).some(db => db.name === 'browserqa');
            const cache = (await (await caches.match('/fixture'))?.text()) === 'synthetic';
            console.log('browserqa:cookie=' + Number(cookie) + ',local=' + Number(local) +
                ',idb=' + Number(idb) + ',cache=' + Number(cache));
        })().catch(error => console.log('browserqa:fixture error: ' + error));
        </script>
    """.trimIndent()
}
