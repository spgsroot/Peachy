package ru.spgsroot.peachy.ui.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.spgsroot.peachy.translate.EnginePageTranslator
import ru.spgsroot.peachy.translate.FileOcrCache
import ru.spgsroot.peachy.translate.LineKind
import ru.spgsroot.peachy.translate.MlKitPageOcr
import ru.spgsroot.peachy.translate.OrtBubbleDetector
import ru.spgsroot.peachy.translate.PageTrace
import ru.spgsroot.peachy.translate.PageTraceElement
import ru.spgsroot.peachy.translate.PageTranslation
import ru.spgsroot.peachy.translate.Region
import ru.spgsroot.peachy.translate.TranslationClient
import ru.spgsroot.peachy.translate.fitModelBubbles

class MangaTextRegionsTest {
    @get:Rule val compose = createComposeRule()
    private val balloons = mapOf(
        "WAIT" to RectF(24f, 28f, 302f, 170f),
        "STAY" to RectF(307f, 32f, 532f, 173f),
        "TURN" to RectF(553f, 44f, 683f, 390f)
    )
    private val positions = mapOf("WAIT" to (216 to 110), "STAY" to (377 to 110), "TURN" to (618 to 215), "AFTER RAIN" to (247 to 360))
    private val translations = mapOf("WAIT" to "Подожди меня здесь", "STAY" to "Останься", "TURN" to "За поворотом начинается новая глава. ".repeat(28), "AFTER RAIN" to "После дождя")

    @Test fun nearbyBalloonsAndCaptionHaveSeparateHitsAndBoundedInk() = runBlocking<Unit> {
        val page = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        val pen = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(170, 204, 205))
        pen.color = Color.rgb(136, 178, 181)
        for (x in -480 until 720 step 23) canvas.drawLine(x.toFloat(), 0f, x + 480f, 480f, pen)
        for (oval in balloons.values) {
            pen.style = Paint.Style.FILL; pen.color = Color.WHITE
            canvas.drawOval(oval, pen)
            pen.style = Paint.Style.STROKE; pen.color = Color.BLACK; pen.strokeWidth = 4f
            canvas.drawOval(oval, pen)
        }
        pen.style = Paint.Style.FILL; pen.color = Color.BLACK
        pen.typeface = Typeface.DEFAULT_BOLD; pen.textSize = 32f
        val ink = positions.mapValues { (text, position) ->
            val x = position.first - pen.measureText(text) / 2f
            canvas.drawText(text, x, position.second.toFloat(), pen)
            android.graphics.Rect().also { pen.getTextBounds(text, 0, text.length, it) }.let {
                RectF(x + it.left, position.second + it.top.toFloat(), x + it.right, position.second + it.bottom.toFloat())
            }
        }
        val bytes = ByteArrayOutputStream().also { page.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheDir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "region-scan").toFile()
        val detector = OrtBubbleDetector(context)
        val trace = PageTrace()
        val cache = FileOcrCache(cacheDir)
        var regions: List<Region>? = null
        val blocks = try {
            val engine = EnginePageTranslator(TranslationClient(OkHttpClient(), { error("No provider in this test") }, "qa"),
                MlKitPageOcr(), detector, cache)
            withContext(PageTraceElement(trace)) { engine.bubbleBlocks(1, 0, "en") { bytes } }.also {
                regions = cache.get(1, 0, "en")?.regions
            }
        } finally { detector.release(); cacheDir.deleteRecursively() }
        val lines = blocks.map { b ->
            val kind = if (b.text == "AFTER RAIN") LineKind.Narration else LineKind.Speech
            b.translated(kind, translations.getValue(b.text))
        }
        val sourceLines = lines.mapIndexed { i, line -> line.copy(box = blocks[i].box) }
        val model = fitModelBubbles(bytes, PageTranslation("en", sourceLines))
        val plates = layoutPlates(lines, Rect(0f, 0f, 720f, 480f), Density(1f))
        val rendered = page.copy(Bitmap.Config.ARGB_8888, true)
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "manga-regions").apply { mkdirs() }
        File(output, "source.png").writeBytes(bytes)
        val evidence = StringBuilder("scan stages=${trace.stageMs.keys}\n")
        evidence.appendLine("YOLO regions=${regions?.size ?: "unavailable"}")
        for (block in blocks) evidence.appendLine("OCR ${block.text} ${block.box} interior=${block.interior}")
        var escapes = 0
        val target = Canvas(rendered)
        for (plate in plates) {
            val layer = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
            drawPlate(Canvas(layer), plate)
            val text = plate.line.text
            val oval = balloons[text]
            val caption = ink.getValue("AFTER RAIN")
            var glyphs = 0; var background = 0; var outsideGlyphs = 0; var outsideFill = 0
            val pixels = IntArray(720 * 480)
            layer.getPixels(pixels, 0, 720, 0, 0, 720, 480)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                if (Color.alpha(pixel) == 0) continue
                val x = i % 720 + 0.5f; val y = i / 720 + 0.5f
                val allowed = if (oval != null) {
                    val dx = (x - oval.centerX()) / (oval.width() / 2 - 2); val dy = (y - oval.centerY()) / (oval.height() / 2 - 2)
                    dx * dx + dy * dy < 1f
                } else text == "AFTER RAIN" && x in caption.left - 3..caption.right + 3 && y in caption.top - 3..caption.bottom + 3
                if (Color.red(pixel) < 128) { glyphs++; if (!allowed) outsideGlyphs++ }
                else { background++; if (!allowed) outsideFill++ }
            }
            evidence.appendLine("PLATE $text ${plate.rect} fontPx=${plate.layout.paint.textSize} lines=${plate.layout.lineCount} ellipsis=${plate.layout.getEllipsisCount(plate.layout.lineCount - 1)} glyphs=$glyphs background=$background outsideGlyphs=$outsideGlyphs outsideFill=$outsideFill")
            escapes += outsideGlyphs + outsideFill
            assertTrue("No translated glyphs for $text", glyphs > 0 && background > 0)
            target.drawBitmap(layer, 0f, 0f, null)
            layer.recycle()
        }
        File(output, "render.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(positions.keys, blocks.map { it.text }.toSet())
        assertEquals(positions.size, blocks.size)
        for ((text, expected) in ink) {
            val actual = blocks.single { it.text == text }.box
            val cx = expected.centerX() / 720; val cy = expected.centerY() / 480
            assertEquals(text, blocks.single { cx in it.box.left..it.box.right && cy in it.box.top..it.box.bottom }.text)
            val rect = RectF(actual.left * 720, actual.top * 480, actual.right * 720, actual.bottom * 480)
            val overlap = RectF(rect)
            assertTrue(overlap.intersect(expected))
            val intersection = overlap.width() * overlap.height()
            val iou = intersection / (rect.width() * rect.height() + expected.width() * expected.height() - intersection)
            evidence.appendLine("HIT $text OCR IoU=$iou YOLO=${regions?.any { cx in it.box.left..it.box.right && cy in it.box.top..it.box.bottom }}")
            assertTrue("$text source IoU=$iou", iou >= 0.7f)
            val local = lines.single { it.text == text }.box!!
            val vision = model.lines.single { it.text == text }.box!!
            if (text in balloons) {
                for (box in listOf(local, vision)) {
                    assertTrue("No usable bubble interior for $text: $box", (box.right - box.left) * (box.bottom - box.top) >
                        1.5f * (actual.right - actual.left) * (actual.bottom - actual.top))
                    val oval = balloons.getValue(text)
                    for (x in listOf(box.left, box.right)) for (y in listOf(box.top, box.bottom)) {
                        val dx = (x * 720 - oval.centerX()) / (oval.width() / 2 - 2)
                        val dy = (y * 480 - oval.centerY()) / (oval.height() / 2 - 2)
                        assertTrue("Box corner outside $text", dx * dx + dy * dy <= 1)
                    }
                }
            } else { assertEquals(actual, local); assertEquals(actual, vision) }
        }
        assertTrue("Hit in the gap between bubbles", lines.none { it.box!!.let { b -> 304f / 720 in b.left..b.right && 100f / 480 in b.top..b.bottom } })
        File(output, "measurements.txt").writeText(evidence.toString())
        assertEquals(positions.keys, plates.map { it.line.text }.toSet())
        assertEquals(evidence.toString(), 0, escapes)
        val long = plates.single { it.line.text == "TURN" }
        assertTrue(long.layout.paint.textSize >= 12f && long.layout.lineCount >= 2)
        assertTrue(long.layout.getEllipsisCount(long.layout.lineCount - 1) > 0)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val session = remember { BubbleTapSession(scope, mutableStateMapOf(), { blocks }, { _, _ -> error("Already translated") }, { false }) }
            Column {
                ReaderPage(page, 0, true, remember { ReaderZoom() }, false, false, false, Modifier.size(360.dp, 240.dp).testTag("page"),
                    240.dp, 0f, 0, {}, { _, _ -> }, {}, lines, { at, miss -> session.tap(0, at, lines, { true }, miss) },
                    onTap = { _, _ -> error("Missed translated region") })
                session.panel?.let { TranslationPanel(it, null, null, {}, {}, session::dismiss, bubble = true) }
            }
        }
        val surface = compose.onNodeWithTag("page")
        // The page and overlay load independently. Wait for translated ink in previously blank balloon space.
        compose.waitUntil(10_000) {
            val image = surface.captureToImage().toPixelMap()
            image[image.width / 2, image.height - 1].green in 0.65f..0.85f &&
                image[image.width * 159 / 720, image.height * 340 / 480].red > 0.95f &&
                (image.height * 70 / 480 until image.height * 128 / 480 step 2).any { y ->
                    (image.width * 88 / 720 until image.width * 174 / 720 step 2).any { x -> image[x, y].red < 0.5f }
                }
        }
        File(output, "reader.png").outputStream().use { surface.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        surface.performTouchInput { click(Offset(width * 618f / 720, height * 203f / 480)) }
        val full = translations.getValue("TURN")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(full).fetchSemanticsNodes().isNotEmpty() }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(full).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val body = layouts.single()
        assertEquals(full.length, body.getLineEnd(body.lineCount - 1))
        assertTrue(!body.isLineEllipsized(body.lineCount - 1))
        compose.onNodeWithText("TURN").performScrollTo().assertIsDisplayed()
    }
}
