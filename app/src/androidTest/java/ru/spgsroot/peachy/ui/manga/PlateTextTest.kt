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
    @Test fun longTranslationShrinksWithinItsOriginalBoxWithoutTruncation() {
        val text = "A longer translation should wrap without covering the surrounding artwork. ".repeat(2)
        val line = TranslatedLine(LineKind.Speech, text, text, Box(0.25f, 0.25f, 0.5f, 0.5f))
        val p = layoutPlates(listOf(line), Rect(0f, 0f, 400f, 300f), density).single()
        assertEquals(Rect(100f, 75f, 200f, 150f), p.rect)
        assertTrue(p.layout.paint.textSize in 6f..12.99f)
        assertTrue(p.layout.height <= 73f)
        assertEquals(text, p.layout.text.toString())
        for (i in 0 until p.layout.lineCount) assertEquals(0, p.layout.getEllipsisCount(i))
    }
    @Test fun aLongWordCanWrapAtTheMinimumRatherThanLoseTheTranslation() {
        val text = "Donaudampfschifffahrtsgesellschaftskapitaen"
        val p = plate(text, 60f, 100f)
        assertEquals(6f, p.layout.paint.textSize, 0f)
        assertTrue(p.layout.height <= 98f)
        assertEquals(text, p.layout.text.toString())
        for (i in 0 until p.layout.lineCount) assertEquals(0, p.layout.getEllipsisCount(i))
    }
    @Test fun impossibleFitShowsReadableBeginningAndEllipsisInsideOriginalPixels() {
        val text = "Translation keeps its beginning instead of hiding it. ".repeat(60)
        val line = TranslatedLine(LineKind.Speech, text, text, Box(0.25f, 0.25f, 0.5f, 0.5f))
        val p = layoutPlates(listOf(line), Rect(20f, 10f, 260f, 170f), density).single()
        val original = Rect(80f, 50f, 140f, 90f)
        assertDrawsOnlyWithin(p, original)
        assertEquals(original, p.rect)
        assertEquals(13f, p.layout.paint.textSize, 0f)
        assertTrue(p.layout.height <= 38f)
        assertEquals(0, p.layout.getLineStart(0))
        assertTrue(p.layout.getEllipsisCount(p.layout.lineCount - 1) > 0)
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
    private fun assertDrawsOnlyWithin(plate: PlacedPlate, original: Rect) {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            drawPlate(Canvas(bitmap), plate, density)
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
