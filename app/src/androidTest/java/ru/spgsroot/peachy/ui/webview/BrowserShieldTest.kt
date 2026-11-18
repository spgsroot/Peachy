package ru.spgsroot.peachy.ui.webview

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.data.browser.AdblockService
import ru.spgsroot.peachy.data.browser.FilterDownload

@RunWith(AndroidJUnit4::class)
class BrowserShieldTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    @Test fun subscriptionRollbackColdStartAndDocumentStart() = runBlocking {
        val application = inst.targetContext
        assumeTrue(application.packageName == "ru.spgsroot.peachy.browserqa")
        val id = UUID.randomUUID().toString()
        val dir = File(application.cacheDir, id).apply { mkdir() }
        val context = object : ContextWrapper(application) {
            override fun getNoBackupFilesDir() = dir
            override fun getFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa-$id-$name", mode)
        }
        val source = "https://shield-fixture.example.invalid/"
        var broken = false
        val downloads = FilterDownload(OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            check(req.url.host != "shield-fixture.example.invalid" && req.headers["Referer"] == null && req.headers["Cookie"] == null)
            val child = req.url.encodedPath.endsWith("/child.txt")
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if (broken && child) 503 else 200).message("fixture")
                .body((if (child) "||blocked.example.invalid^\n@@||blocked.example.invalid/keep\nshield-fixture.example.invalid##.local-hide\n##.late-hide\nshield-fixture.example.invalid##article:has-text(marker)\nshield-fixture.example.invalid##+js(set-constant, peachyFixture, true)" else "! Fixture\n!#include child.txt").toResponseBody()).build()
        }.build())
        try {
            val service = AdblockService(context, downloads)
            service.ensureLoaded()
            val blockedUrl = "https://blocked.example.invalid/asset"
            val allowedUrl = "https://blocked.example.invalid/keep.js"
            assertNull(service.engine.decide(blockedUrl, source, "script", "get"))
            service.update(emptySet()).getOrThrow()
            assertNotNull(service.engine.decide(blockedUrl, source, "script", "get"))
            assertNull(service.engine.decide(allowedUrl, source, "script", "get"))
            broken = true
            assertTrue(service.update(emptySet()).isFailure)
            val privateUrl = "https://custom.example.invalid/marker"
            val profile = ru.spgsroot.peachy.data.browser.PrivateFilterProfile("||custom.example.invalid^")
            service.update(emptySet(), custom = profile, edit = true).getOrThrow()
            assertNotNull(service.engine.decide(privateUrl, source, "script", "get"))
            assertFalse(dir.walkTopDown().filter { it.isFile }.any { it.readBytes().toString(Charsets.ISO_8859_1).contains("custom.example.invalid") })
            service.countBlock(); service.saveCount()
            val cold = AdblockService(context, downloads); cold.ensureLoaded()
            assertEquals(1L, cold.blocked.value)
            assertEquals(profile, cold.profile.value)
            assertNotNull(cold.engine.decide(privateUrl, source, "script", "get"))
            assertNotNull(cold.engine.decide(blockedUrl, source, "script", "get"))
            assertNull(cold.engine.decide(allowedUrl, source, "script", "get"))
            val queue = ArrayBlockingQueue<String>(1)
            lateinit var web: WebView
            lateinit var controller: BrowserController
            var disabled = false
            val html = """<!doctype html><script>window.ranEarly=window.peachyFixture===true</script><script src="$blockedUrl"></script><script src="$allowedUrl"></script><div class="local-hide">static</div><article>marker</article><script>setTimeout(()=>{let e=document.createElement('p');e.className='late-hide';document.body.appendChild(e)},80)</script>"""
            fun load() = inst.runOnMainSync { controller.navigate(controller.active, source) }
            fun observe(expected: String) {
                val deadline=SystemClock.elapsedRealtime()+12_000; var actual=""
                while (SystemClock.elapsedRealtime()<deadline) {
                    inst.runOnMainSync { web.evaluateJavascript("""(()=>{let n=['.local-hide','article','.late-hide'].map(s=>document.querySelector(s)); if(n.some(x=>!x)) return '';return [window.ranEarly,...n.map(x=>getComputedStyle(x).display==='none')].join(',')})()""") { queue.offer(it) } }
                    actual = queue.poll(2,TimeUnit.SECONDS).orEmpty().trim('"'); if(actual==expected) return
                    SystemClock.sleep(100)
                }; assertEquals(expected,actual)
            }
            inst.runOnMainSync {
                check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                // Attach a real Activity context for an offscreen WebView. No activity is launched over the user's UI.
                val activity = inst.newActivity(android.app.Activity::class.java, application, android.os.Binder(),
                    application.applicationContext as android.app.Application, android.content.Intent(application, android.app.Activity::class.java),
                    android.content.pm.ActivityInfo().apply { applicationInfo = application.applicationInfo; packageName = application.packageName; name = android.app.Activity::class.java.name },
                    "Shield fixture", null, null, null)
                controller = BrowserController(activity, cold.engine, cold::countBlock, { disabled }, { cold.mode.value },
                    { _, _ -> }, ru.spgsroot.peachy.data.browser.BrowserSessionStore(File(dir, "fixture-session")))
                web=controller.webViewOf(controller.active)
                val real = web.webViewClient
                web.webViewClient=object:WebViewClient(){
                    override fun onPageStarted(v: WebView, url: String, icon: android.graphics.Bitmap?) = real.onPageStarted(v, url, icon)
                    override fun onPageCommitVisible(v: WebView, url: String) = real.onPageCommitVisible(v, url)
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        real.shouldInterceptRequest(view, request) ?: if (request.isForMainFrame) WebResourceResponse("text/html","UTF-8",html.byteInputStream())
                        else WebResourceResponse("application/javascript", "UTF-8",
                            (if(request.url.toString() == blockedUrl) "window.fixtureBlockedLoaded=true" else "window.fixtureAllowedLoaded=true").byteInputStream())
                }
            }
            try {
                load(); observe("true,true,true,true")
                inst.runOnMainSync { assertEquals(1,controller.active.blocked); assertEquals(2L,cold.blocked.value) }
                disabled=true; load(); observe("false,false,false,false")
                inst.runOnMainSync { assertEquals(0,controller.active.blocked); assertEquals(2L,cold.blocked.value) }
            } finally { inst.runOnMainSync { controller.destroyAll() } }
        } finally { dir.deleteRecursively(); application.deleteSharedPreferences("qa-$id-browser_shield") }
    }
}
