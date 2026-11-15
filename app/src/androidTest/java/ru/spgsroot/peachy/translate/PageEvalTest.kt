package ru.spgsroot.peachy.translate

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.ui.manga.fittedPage
import ru.spgsroot.peachy.ui.manga.plateFor

@RunWith(AndroidJUnit4::class)
class PageEvalTest {
    @Test fun evaluatePages() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val source = args.getString("source") ?: "auto"
        val root = File(ctx.getExternalFilesDir("eval"), "pages")
        // Create these as the app: Android 17 denies app access to shell-owned child directories.
        File(root, "jp").mkdirs()
        File(root, "eng").mkdirs()
        val folder = when (source) { "ja" -> File(root, "jp"); "en" -> File(root, "eng"); else -> root }
        val files = folder.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("webp", "jpg", "png") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        assertTrue("Push evaluation images to ${folder.absolutePath}", files.isNotEmpty())
        val ocr = MlKitPageOcr()
        val density = Density(ctx)
        val measurer = TextMeasurer(createFontFamilyResolver(ctx), density, LayoutDirection.Ltr)
        val pages = JSONArray()
        val samples = linkedMapOf<String, MutableList<Long>>()
        var peakHeap = 0L
        var overlapTotal = 0
        var smallTotal = 0
        for (file in files) {
            val trace = PageTrace()
            val started = System.nanoTime()
            withContext(PageTraceElement(trace)) {
                val bytes = traced("fetch") { file.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val blocks = ocr.recognize(bytes, source)
                val page = fittedPage(1080f, 1920f, bounds.outWidth.toFloat() / bounds.outHeight)
                val plates = traced("layout") {
                    blocks.map { plateFor(TranslatedLine(LineKind.Speech, it.text, it.text, it.box), page, measurer, density, TextStyle.Default) }
                }
                val overlaps = plates.indices.sumOf { i -> (i + 1 until plates.size).count { j -> overlaps(plates[i].rect, plates[j].rect) } }
                val small = plates.count { it.fontSize < 13f }
                overlapTotal += overlaps
                smallTotal += small
                trace.stageMs.forEach { (key, ms) -> samples.getOrPut(key) { mutableListOf() }.add(ms) }
                peakHeap = maxOf(peakHeap, trace.heapAfterMb.values.maxOrNull() ?: 0L)
                pages.put(JSONObject().apply {
                    put("file", file.relativeTo(root).invariantSeparatorsPath)
                    put("width", bounds.outWidth); put("height", bounds.outHeight)
                    put("stageMs", JSONObject(trace.stageMs)); put("heapAfterMb", JSONObject(trace.heapAfterMb))
                    put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                    put("blocks", blocks.size)
                    put("blockBoxes", JSONArray(blocks.map { listOf(it.box.left, it.box.top, it.box.right, it.box.bottom) }))
                    put("oldPlatePairsOverlapping", overlaps); put("oldPlatesBelowReadable", small)
                })
            }
        }
        val report = JSONObject().apply {
            put("source", source); put("model", Build.MODEL); put("android", Build.VERSION.RELEASE)
            put("pages", pages)
            put("summary", JSONObject().apply {
                put("stageMs", JSONObject().apply { samples.forEach { (key, values) ->
                    put(key, JSONObject().put("p50", percentile(values, .5)).put("p95", percentile(values, .95)))
                } })
                put("peakHeapMb", peakHeap); put("oldPlatePairsOverlapping", overlapTotal); put("oldPlatesBelowReadable", smallTotal)
            })
        }
        File(root.parentFile, "report.json").writeText(report.toString(2))
    }

    private fun overlaps(a: Rect, b: Rect) = minOf(a.right, b.right) > maxOf(a.left, b.left) && minOf(a.bottom, b.bottom) > maxOf(a.top, b.top)
    private fun percentile(values: List<Long>, fraction: Double): Long = values.sorted()[(kotlin.math.ceil(values.size * fraction).toInt() - 1).coerceAtLeast(0)]
}
