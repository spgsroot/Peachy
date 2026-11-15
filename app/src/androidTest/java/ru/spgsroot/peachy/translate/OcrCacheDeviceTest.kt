package ru.spgsroot.peachy.translate

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
        try {
            val cache=FileOcrCache(directory)
            val translator=EnginePageTranslator(TranslationClient(OkHttpClient(),{ProviderSettings()},"eval"),MlKitPageOcr(),cache)
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
            assertTrue(cache.get(1,0,"ja")!!.isNotEmpty())
            listOf("fetch","decode","recognize").forEach { stage ->
                assertTrue(traces[0].stageMs.containsKey(stage))
                assertFalse(traces[1].stageMs.containsKey(stage))
            }
            File(eval,"cache-smoke.json").writeText(JSONObject().put("first",JSONObject(traces[0].stageMs)).put("changedTarget",JSONObject(traces[1].stageMs)).toString(2))
        } finally { directory.deleteRecursively() }
    }
}
