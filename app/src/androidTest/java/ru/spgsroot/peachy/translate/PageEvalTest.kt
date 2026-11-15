package ru.spgsroot.peachy.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the real Task 8 pipeline, stopping before any configured provider/network request. */
@RunWith(AndroidJUnit4::class)
class PageEvalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val mode get() = args.getString("mode") ?: "on"
    private val run get() = args.getString("run") ?: "1"
    private val source get() = args.getString("source") ?: "auto"
    private val root get() = File(context.getExternalFilesDir("eval"), "pages")
    private val output get() = File(context.getExternalFilesDir("eval"), "phase2-final/$mode/run-$run").apply { mkdirs() }

    @Test fun prepareFolders() {
        check(context.packageName.startsWith("ru.spgsroot.peachy."))
        File(root, "jp").mkdirs()
        File(root, "eng").mkdirs()
        output.mkdirs()
    }

    @Test fun evaluatePages() = runBlocking {
        check(mode in setOf("off", "on"))
        val files = root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("webp", "jpg", "png") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        assertEquals("Expected the fixed 14 Japanese + 16 English pages", 30, files.size)
        val reader = RecordingOcr(if (mode == "off") WholePageAutoOcr() else MlKitPageOcr())
        val native = OrtBubbleDetector(context)
        val detector = RecordingDetector(native, mode == "off")
        val pages = JSONArray()
        try {
            for ((index, file) in files.withIndex()) {
                val name = file.relativeTo(root).invariantSeparatorsPath
                val result = evaluate(name, file.readBytes(), index, reader, detector, run == "1" || args.getString("overlays") == "true")
                pages.put(result)
                File(output, "report.json").writeText(report(pages).toString(2))
            }
        } finally { native.release() }
    }

    @Test fun evaluateStrip() = runBlocking {
        val bitmap = Bitmap.createBitmap(800, 12000, Bitmap.Config.ARGB_8888)
        val words = listOf("ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL")
        val bytes = try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 110f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            words.forEachIndexed { i, word -> canvas.drawText(word, 60f, 1000f + i * 1400f, paint) }
            ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        val native = OrtBubbleDetector(context)
        try {
            val reader = RecordingOcr(if (mode == "off") WholePageAutoOcr() else MlKitPageOcr())
            val result = evaluate("synthetic-strip", bytes, 0, reader, RecordingDetector(native, mode == "off"), true)
            result.put("expectedWords", JSONArray(words))
            File(output, "strip.json").writeText(result.toString(2))
        } finally { native.release() }
    }

    @Test fun evaluateHistoricalStrip() = runBlocking {
        val bytes = File(root.parentFile, "tall-strip.png").readBytes()
        val native = OrtBubbleDetector(context)
        try {
            val reader = RecordingOcr(if (mode == "off") WholePageAutoOcr() else MlKitPageOcr())
            val result = evaluate("historical-strip", bytes, 0, reader, RecordingDetector(native, mode == "off"), true)
            File(output, "historical-strip.json").writeText(result.toString(2))
        } finally { native.release() }
    }

    private suspend fun evaluate(name: String, bytes: ByteArray, page: Int, reader: RecordingOcr, detector: RecordingDetector, overlay: Boolean): JSONObject {
        reader.reset()
        detector.regions = null
        val cache = CaptureCache()
        val engine = EnginePageTranslator(TranslationClient(OkHttpClient(), { ProviderSettings() }, "phase2-eval"), reader, detector, cache)
        val trace = PageTrace()
        val started = System.nanoTime()
        withContext(PageTraceElement(trace)) {
            try {
                val translation = engine.translate(1, page, TranslateRequest(TranslateEngine.MlKit, source, "ru")) { bytes }
                assertTrue("A text page must reach the unconfigured provider", translation.lines.isEmpty())
            } catch (failure: TranslationException) {
                assertEquals(TranslationError.NotConfigured, failure.error)
            } catch (failure: Exception) {
                val partial = JSONObject().apply {
                    put("file", name); put("mode", mode); put("run", run); put("incomplete", true)
                    put("error", "${failure.javaClass.name}: ${failure.message}")
                    put("stageMs", JSONObject(trace.stageMs)); put("heapAfterMb", JSONObject(trace.heapAfterMb))
                    put("partialWallMs", (System.nanoTime() - started) / 1_000_000)
                    put("peakSampledHeapMb", trace.heapAfterMb.values.maxOrNull() ?: defaultHeapMb())
                    put("firstPassBlocks", reader.first.size); put("firstPass", blocksJson(reader.first))
                    put("regions", detector.regions?.size ?: JSONObject.NULL)
                    put("secondPassRegions", JSONArray(reader.secondRegions.map(::boxJson)))
                    put("cacheWritten", cache.scan != null)
                }
                File(output, "${name.replace('/', '_')}.failure.json").writeText(partial.toString(2))
                throw failure
            }
        }
        val wallMs = (System.nanoTime() - started) / 1_000_000
        val (width, height) = PageDecode.size(bytes)
        val scan = cache.scan ?: PageScan(reader.first, emptyList(), width.toFloat() / height)
        // Engine already merged inside the wall measurement. Replay only the pure merge for its
        // isolated stage cost/diagnostic output; do not add this replay to pipeline wall time.
        val units = withContext(PageTraceElement(trace)) { traced("merge") { mergeBlocks(scan) } }
        val secondPassBlocks = scan.blocks.size - reader.first.size
        val peak = trace.heapAfterMb.values.maxOrNull() ?: defaultHeapMb()
        val row = JSONObject().apply {
            put("file", name); put("mode", mode); put("run", run); put("source", source)
            put("width", width); put("height", height)
            put("stageMs", JSONObject(trace.stageMs)); put("heapAfterMb", JSONObject(trace.heapAfterMb))
            put("ocrDetectWallMs", wallMs); put("firstPassWallMs", reader.firstWallMs)
            put("peakSampledHeapMb", peak); put("detectorNull", detector.regions == null)
            put("cacheWritten", cache.scan != null)
            put("firstPassBlocks", reader.first.size); put("blocks", scan.blocks.size)
            put("regions", scan.regions.size); put("units", units.size)
            put("joinedUnits", units.count { it !in scan.blocks }); put("blocksRemovedByJoining", scan.blocks.size - units.size)
            put("secondPassRegions", reader.secondRegions.size); put("secondPassRawBlocks", reader.second.size)
            put("secondPassBlocks", secondPassBlocks)
            put("ocr2MsPerRequestedRegion", if (reader.secondRegions.isEmpty()) JSONObject.NULL else (trace.stageMs["ocr2"] ?: 0).toDouble() / reader.secondRegions.size)
            put("firstPass", blocksJson(reader.first)); put("blocksDetail", blocksJson(scan.blocks))
            put("unitsDetail", blocksJson(units)); put("secondPassRaw", blocksJson(reader.second))
            put("regionsDetail", JSONArray(scan.regions.map { JSONObject().put("box", boxJson(it.box)).put("score", it.score) }))
            put("mergeStageIsPureReplay", true)
        }
        val stem = name.replace('/', '_')
        File(output, "$stem.json").writeText(row.toString(2))
        if (overlay) drawOverlay(bytes, scan, units, File(output, "$stem.png"), name)
        Log.i("Phase2Eval", "$mode/$run $name first=${reader.first.size} blocks=${scan.blocks.size} regions=${scan.regions.size} units=${units.size} ocr2=$secondPassBlocks wall=$wallMs heap=$peak")
        return row
    }

    private fun report(pages: JSONArray): JSONObject {
        val rows = (0 until pages.length()).map { pages.getJSONObject(it) }
        val stages = rows.flatMap { it.getJSONObject("stageMs").keys().asSequence().toList() }.toSortedSet()
        return JSONObject().apply {
            put("mode", mode); put("run", run); put("source", source); put("model", Build.MODEL); put("android", Build.VERSION.RELEASE)
            put("runtime", "ORT 1.32.0 minimal CPU-4, exceptions enabled; yolo26n 960")
            put("ocrTiled", mode != "off")
            put("pages", pages)
            put("summary", JSONObject().apply {
                put("stageMs", JSONObject().apply { for (stage in stages) {
                    val values = rows.map { it.getJSONObject("stageMs").optLong(stage, 0) }
                    put(stage, JSONObject().put("p50", percentile(values, .5)).put("p95", percentile(values, .95)))
                } })
                val walls = rows.map { it.getLong("ocrDetectWallMs") }
                put("wallMs", JSONObject().put("p50", percentile(walls, .5)).put("p95", percentile(walls, .95)))
                put("peakSampledHeapMb", rows.maxOf { it.getLong("peakSampledHeapMb") })
                for (field in listOf("firstPassBlocks", "blocks", "regions", "units", "joinedUnits", "secondPassBlocks", "secondPassRegions")) put(field, rows.sumOf { it.getInt(field) })
                put("pagesWithSecondPassBlocks", rows.count { it.getInt("secondPassBlocks") > 0 })
                put("detectorNullPages", rows.count { it.getBoolean("detectorNull") })
            })
        }
    }

    private fun drawOverlay(bytes: ByteArray, scan: PageScan, units: List<OcrBlock>, file: File, name: String) {
        val decoded = PageDecode.decode(bytes, 1000)
        try {
            val (w, h) = PageImage.scaledSize(decoded.width, decoded.height, 1000)
            val sheet = Bitmap.createBitmap(w * 3, h + 50, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(sheet)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f }
                val groups = listOf(scan.regions.map { it.box }, scan.blocks.map { it.box }, units.map { it.box })
                val colours = listOf(Color.rgb(25, 90, 255), Color.rgb(0, 160, 40), Color.rgb(235, 30, 50))
                val labels = listOf("regions", "blocks", "units")
                for (panel in 0..2) {
                    canvas.drawBitmap(decoded, null, Rect(panel * w, 50, (panel + 1) * w, h + 50), null)
                    paint.style = Paint.Style.FILL; paint.color = colours[panel]
                    canvas.drawText("$name ${labels[panel]} ${groups[panel].size}", panel * w + 5f, 30f, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                    for (box in groups[panel]) canvas.drawRect(panel * w + box.left * w, 50 + box.top * h, panel * w + box.right * w, 50 + box.bottom * h, paint)
                }
                file.outputStream().use { assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { sheet.recycle() }
        } finally { decoded.recycle() }
    }

    /** Phase-1 auto path: one long-side-sampled bitmap, with the same filtering/grouping. */
    private class WholePageAutoOcr : PageOcr {
        private val recognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
        override suspend fun recognize(image: ByteArray, source: String): List<OcrBlock> = withContext(Dispatchers.Default) {
            check(source == "auto") { "The phase-1 comparator is defined for source=auto" }
            val bitmap = traced("decode") { PageDecode.decode(image, OCR_WORK_SIDE) }
            val width = bitmap.width
            val height = bitmap.height
            val task = try { recognizer.process(InputImage.fromBitmap(bitmap, 0)) }
            catch (failure: Throwable) { bitmap.recycle(); throw failure }
            val text = traced("recognize") { awaitOcrTask(task) { bitmap.recycle() } }
            val blocks = text.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                RawOcrBlock(block.lines.mapNotNull { line ->
                    val rect = line.boundingBox ?: return@mapNotNull null
                    RawOcrLine(line.text, PixelRect(rect.left, rect.top, rect.right, rect.bottom), line.confidence)
                }, PixelRect(box.left, box.top, box.right, box.bottom))
            }
            traced("group") { groupOcrBlocks(blocks, width, height, source) }
        }
        override suspend fun recognizeRegions(image: ByteArray, source: String, regions: List<Box>): List<OcrBlock> =
            error("The phase-1 comparator has no second pass")
    }

    private class CaptureCache : OcrCache {
        var scan: PageScan? = null
        override suspend fun get(galleryId: Int, page: Int, source: String): PageScan? = null
        override suspend fun put(galleryId: Int, page: Int, source: String, scan: PageScan) { this.scan = scan }
    }

    private class RecordingDetector(private val delegate: BubbleDetector, private val forcedOff: Boolean) : BubbleDetector {
        var regions: List<Region>? = null
        override suspend fun detect(image: ByteArray): List<Region>? = (if (forcedOff) null else delegate.detect(image)).also { regions = it }
        override fun release() = delegate.release()
    }

    private class RecordingOcr(private val delegate: PageOcr) : PageOcr {
        var first = emptyList<OcrBlock>()
        var second = emptyList<OcrBlock>()
        var secondRegions = emptyList<Box>()
        var firstWallMs = 0L
        fun reset() { first = emptyList(); second = emptyList(); secondRegions = emptyList(); firstWallMs = 0 }
        override suspend fun recognize(image: ByteArray, source: String): List<OcrBlock> {
            val start = System.nanoTime()
            return delegate.recognize(image, source).also { first = it; firstWallMs = (System.nanoTime() - start) / 1_000_000 }
        }
        override suspend fun recognizeRegions(image: ByteArray, source: String, regions: List<Box>): List<OcrBlock> {
            secondRegions = regions
            return delegate.recognizeRegions(image, source, regions).also { second = it }
        }
    }

    private fun boxJson(box: Box) = JSONArray(listOf(box.left, box.top, box.right, box.bottom))
    private fun blocksJson(blocks: List<OcrBlock>) = JSONArray(blocks.map { JSONObject().put("text", it.text).put("box", boxJson(it.box)) })
    private fun percentile(values: List<Long>, fraction: Double): Long = values.sorted()[(kotlin.math.ceil(values.size * fraction).toInt() - 1).coerceAtLeast(0)]
}
