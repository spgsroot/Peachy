package ru.spgsroot.peachy.ui.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.translate.Box
import ru.spgsroot.peachy.translate.LineKind
import ru.spgsroot.peachy.translate.TranslatedLine

@RunWith(AndroidJUnit4::class)
class PlateTextTest {
    private val density=Density(1f)
    private fun plate(text:String,width:Float,height:Float)=layoutPlates(listOf(TranslatedLine(LineKind.Speech,text,text,Box(0f,0f,1f,1f))),Rect(10f,10f,10f+width,10f+height),density).single()
    @Test fun noWordIsSplitAcrossLines() {
        val words="Ordinary words should remain whole while wrapping inside this plate"
        val layout=plate(words,160f,120f).layout
        val lines=(0 until layout.lineCount).map { layout.text.substring(layout.getLineStart(it),layout.getLineEnd(it)).trim() }
        words.split(' ').forEach { word -> assertTrue("Split word: $word in $lines",lines.any { it.split(' ').contains(word) }) }
    }
    @Test fun layoutFitsTheInnerAreaWhenAFontSizeFits() {
        val p=plate("Short translation",200f,100f)
        assertTrue(p.layout.height<=98f)
        assertTrue(p.layout.paint.textSize>=13f)
    }
    @Test fun chineseTranslationIsNotShrunkToTheMinimum() {
        val p=plate("这是一个用于测试中文自动换行和字体大小的句子我们希望文字在气泡里面清晰可读",150f,90f)
        assertTrue(p.layout.paint.textSize>6f)
    }
    @Test fun longTranslationKeepsAReadablePrefixAndFullPanelTextWithoutExpanding() {
        val text = "A longer translation should wrap without covering the surrounding artwork. ".repeat(2)
        val line = TranslatedLine(LineKind.Speech, text, text, Box(0.25f, 0.25f, 0.5f, 0.5f))
        val p = layoutPlates(listOf(line), Rect(0f, 0f, 400f, 300f), density).single()
        assertEquals(Rect(100f, 75f, 200f, 150f), p.rect)
        assertTrue(p.layout.paint.textSize >= 12f)
        assertFittedGlyphs(p)
        assertEquals(text, p.line.translation)
        assertTrue(p.layout.getEllipsisCount(p.layout.lineCount - 1) > 0)
    }
    @Test fun aLongWordWrapsAtActualGlyphBoundariesWithoutHorizontalOverflow() {
        val text = "Donaudampfschifffahrtsgesellschaftskapitaen"
        val p = plate(text, 60f, 100f)
        assertTrue(p.layout.paint.textSize >= 12f)
        assertFittedGlyphs(p)
        assertEquals(text, p.line.translation)
        for (i in 0 until p.layout.lineCount) assertTrue(p.layout.getLineMax(i) <= p.layout.width)
    }
    @Test fun impossibleFitShowsReadableBeginningAndEllipsisInsideOriginalPixels() {
        val text = "Translation keeps its beginning instead of hiding it. ".repeat(60)
        val line = TranslatedLine(LineKind.Speech, text, text, Box(0.25f, 0.25f, 0.5f, 0.5f))
        val p = layoutPlates(listOf(line), Rect(20f, 10f, 260f, 170f), density).single()
        val original = Rect(80f, 50f, 140f, 90f)
        assertDrawsOnlyWithin(p, original)
        assertEquals(original, p.rect)
        assertTrue(p.layout.paint.textSize >= 12f)
        assertFittedGlyphs(p)
        assertEquals(0, p.layout.getLineStart(0))
        assertTrue(p.layout.getEllipsisCount(p.layout.lineCount - 1) > 0)
    }
    @Test fun smallerSafeStripCanHoldTheWholeWordWhenTheLargestStripCannot() {
        val line = TranslatedLine(LineKind.Speech, "Source", "ШИРОКО", Box(0f, 0f, 1f, 1f))
        val neighbor = TranslatedLine(LineKind.Sfx, "Other", "SFX", Box(8f / 90, 15f / 200, 1f, 1f))
        val page = Rect(10f, 10f, 100f, 210f)
        for (lines in listOf(listOf(line, neighbor), listOf(neighbor, line))) {
            val p = layoutPlates(lines, page, density).single()
            assertSame(line, p.line)
            assertEquals(Rect(10f, 10f, 100f, 25f), p.rect)
            assertEquals(line.translation.length, p.visibleEnd)
            assertTrue(p.layout.paint.textSize >= 12f)
            assertFittedGlyphs(p)
        }
    }

    @Test fun aShallowLabelFitsAccentsAndDescendersWithoutClippingInk() {
        val line = TranslatedLine(LineKind.Speech, "Source", "Été déjà", Box(0f, 0f, 1f, 1f))
        for (top in listOf(10f, 10.25f, 10.5f, 10.75f)) {
            val p = layoutPlates(listOf(line), Rect(10f, top, 140f, top + 15f), density).single()
            assertEquals(line.translation.length, p.visibleEnd)
            assertTrue(p.layout.paint.textSize >= 12f)
            assertFittedGlyphs(p)
        }
    }

    @Test fun drawingClipsEvenOversizedLayoutsAndItalicOverhangs() {
        val rect = Rect(20f, 20f, 80f, 60f)
        val layout = plateLayout("ffff ".repeat(100), LineKind.Thought, 22f, 120)
        val p = PlacedPlate(rect, layout, rect.top - 10f, TranslatedLine(LineKind.Thought, "ffff", "ffff", null))
        assertTrue(layout.height > rect.height)
        assertDrawsOnlyWithin(p, rect)
    }
    @Test fun noBlankPlateReplacesTextWhenNotEvenOneLineFits() {
        val line = TranslatedLine(LineKind.Speech, "Original", "Translation ".repeat(30), Box(0f, 0f, 1f, 1f))
        assertTrue(layoutPlates(listOf(line), Rect(10f, 10f, 70f, 14f), density).isEmpty())
        assertTrue(layoutPlates(listOf(line), Rect(10f, 10f, 14f, 50f), density).isEmpty())
    }
    @Test fun shallowCaptionShowsThePrefixBeforeATallGlyphAtReadableSize() {
        val text = "xxxxxxÉgj ".repeat(15)
        val paint = plateLayout(text, LineKind.Speech, 12f, 100).paint
        val ink = android.graphics.Rect().also { paint.getTextBounds("xxxxxx…", 0, 7, it) }
        val p = plate(text, kotlin.math.ceil(paint.measureText("xxxxxxÉgj…")) + 2f, ink.height().toFloat())
        assertEquals(12f, p.layout.paint.textSize, 0f)
        assertTrue(p.visibleEnd in 1..6)
        assertTrue(p.layout.getEllipsisCount(0) > 0)
        assertEquals(text, p.line.translation)
        assertFittedGlyphs(p)
    }
    @Test fun repeatedOcrGroupReleasesOnlyItsIndividualRepliesAndNeverAnSfx() {
        val left = TranslatedLine(LineKind.Speech, "Left window", "Слева", Box(.05f,.2f,.4f,.5f))
        val right = TranslatedLine(LineKind.Speech, "Right window", "Справа", Box(.6f,.2f,.95f,.5f))
        val group = TranslatedLine(LineKind.Speech, "Left windox! Right window.", "Общее чтение", Box(.05f,.2f,.95f,.5f))
        val page = Rect(0f,0f,300f,200f)
        val plates = layoutPlates(listOf(right, group, left), page, density)
        assertEquals(listOf(right, left), plates.map { it.line })
        plates.forEach { assertFittedGlyphs(it) }
        assertTrue(plates.none { it.rect.left < 151f && it.rect.right > 149f })
        val other = group.copy(text = "A genuinely separate reply")
        assertTrue(layoutPlates(listOf(left, other, right), page, density).none { it.line == left || it.line == right })
        assertTrue(layoutPlates(listOf(left, group.copy(kind = LineKind.Sfx), right), page, density).isEmpty())
    }
    @Test fun platesKeepTheirSourceWhenEarlierLinesAreOmitted() {
        val omitted = TranslatedLine(LineKind.Speech, "Tiny", "Translation ".repeat(30), Box(0f, 0f, 0.01f, 0.01f))
        val visible = TranslatedLine(LineKind.Speech, "Hello", "Hello", Box(0.1f, 0.1f, 0.9f, 0.9f))
        val plates = layoutPlates(listOf(omitted, visible), Rect(0f, 0f, 200f, 200f), density)
        assertEquals(1, plates.size)
        assertSame(visible, plates.single().line)
    }
    @Test fun platesAtPageEdgesRemainInsideSourceAndPage() {
        val line = TranslatedLine(LineKind.Speech, "Yes", "Yes", Box(0.9f, 0.8f, 1.2f, 1.1f))
        val p = layoutPlates(listOf(line), Rect(20f, 10f, 220f, 210f), density).single()
        assertEquals(Rect(200f, 170f, 220f, 210f), p.rect)
        assertDrawsOnlyWithin(p, p.rect)
    }
    private fun assertFittedGlyphs(p: PlacedPlate) {
        val glyphs = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(glyphs)
            canvas.translate(p.rect.center.x - p.layout.width * 0.5f, p.textTop)
            p.layout.draw(canvas)
            var ink = 0
            for (y in 0 until glyphs.height) for (x in 0 until glyphs.width) {
                if (Color.alpha(glyphs.getPixel(x, y)) == 0) continue
                assertTrue("Clip would cut a glyph at $x,$y", x + 0.5f >= p.rect.left && x + 0.5f < p.rect.right &&
                    y + 0.5f >= p.rect.top && y + 0.5f < p.rect.bottom)
                ink++
            }
            assertTrue(ink > 0)
        } finally { glyphs.recycle() }
    }

    private fun assertDrawsOnlyWithin(plate: PlacedPlate, original: Rect) {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            drawPlate(Canvas(bitmap), plate)
            var glyphPixels = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (x < original.left || x >= original.right || y < original.top || y >= original.bottom) {
                    assertEquals("Pixel escaped original text bounds at $x,$y", 0, pixel)
                } else if (Color.alpha(pixel) > 0 && Color.red(pixel) < 128) {
                    glyphPixels++
                }
            }
            assertTrue("Expected translated glyphs, not just a blank background", glyphPixels > 0)
        } finally {
            bitmap.recycle()
        }
    }
}
