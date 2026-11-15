package ru.spgsroot.peachy.ui.manga

import android.graphics.Bitmap
import android.graphics.Canvas
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
    @Test fun overlongTextIsClippedToThePlate() {
        val p=plate("abcdefghij ".repeat(60),60f,40f)
        assertEquals(6f,p.layout.paint.textSize,0f)
        assertTrue(p.layout.height>38f)
        val bitmap=Bitmap.createBitmap(100,80,Bitmap.Config.ARGB_8888)
        try {
            drawPlate(Canvas(bitmap),p,density)
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                if(x<10 || x>=70 || y<10 || y>=50) assertEquals("Pixel escaped plate at $x,$y",0,bitmap.getPixel(x,y))
            }
        } finally { bitmap.recycle() }
    }
}
