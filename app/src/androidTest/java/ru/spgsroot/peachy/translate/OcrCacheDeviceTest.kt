package ru.spgsroot.peachy.translate

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrCacheDeviceTest {
    @Test fun targetChangeSkipsFetchDecodeAndRecognition() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val eval=context.getExternalFilesDir("eval")!!
        val page=File(eval,"pages/jp").listFiles()!!.first { it.extension=="webp" }
        val directory=File(context.cacheDir,"ocr-device-smoke-${System.nanoTime()}")
        val detector=OrtBubbleDetector(context)
        try {
            val cache=FileOcrCache(directory)
            val translator=EnginePageTranslator(TranslationClient(OkHttpClient(),{ProviderSettings()},"eval"),MlKitPageOcr(),detector,cache)
            val traces=listOf(PageTrace(),PageTrace())
            listOf("ru","en").forEachIndexed { i,target ->
                withContext(PageTraceElement(traces[i])) {
                    try {
                        translator.translate(1,0,TranslateRequest(TranslateEngine.MlKit,"ja",target)) {
                            traced("fetch") { page.readBytes() }
                        }
                        fail("Fixture has OCR text and provider must remain unconfigured")
                    } catch (e:TranslationException) { assertEquals(TranslationError.NotConfigured,e.error) }
                }
            }
            assertTrue(cache.get(1,0,"ja")!!.blocks.isNotEmpty())
            listOf("fetch","decode","recognize").forEach { stage ->
                assertTrue(traces[0].stageMs.containsKey(stage))
                assertFalse(traces[1].stageMs.containsKey(stage))
            }
            File(eval,"cache-smoke.json").writeText(JSONObject().put("first",JSONObject(traces[0].stageMs)).put("changedTarget",JSONObject(traces[1].stageMs)).toString(2))
        } finally { detector.release(); directory.deleteRecursively() }
    }

    @Test fun pageScanPipelineSmoke() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = InstrumentationRegistry.getArguments().getString("page") ?: "1.webp"
        val page = File(context.getExternalFilesDir("eval"), "pages/$name")
        assertTrue("Push fixture $page", page.isFile)
        val directory = File(context.cacheDir, "pipeline-smoke-${System.nanoTime()}")
        val detector = OrtBubbleDetector(context)
        try {
            val cache = FileOcrCache(directory)
            val translator = EnginePageTranslator(
                TranslationClient(OkHttpClient(), { ProviderSettings() }, "eval"), MlKitPageOcr(), detector, cache
            )
            val trace = PageTrace()
            withContext(PageTraceElement(trace)) {
                try {
                    translator.translate(1, 0, TranslateRequest(TranslateEngine.MlKit, "en", "ru")) { page.readBytes() }
                    fail("Page has text and translation provider is intentionally unconfigured")
                } catch (failure: TranslationException) {
                    assertEquals(TranslationError.NotConfigured, failure.error)
                }
            }
            val scan = FileOcrCache(directory).get(1, 0, "en")!!
            val units = mergeBlocks(scan)
            assertTrue(scan.regions.isNotEmpty())
            assertTrue(units.size <= scan.blocks.size)
            Log.i("PageScanSmoke", "$name blocks=${scan.blocks.size} regions=${scan.regions.size} units=${units.size} stages=${trace.stageMs}")
        } finally { detector.release(); directory.deleteRecursively() }
    }
}
