package ru.spgsroot.peachy.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitTilesDeviceTest {
    @Test fun stripTextOnEveryTileIsRead() = runBlocking {
        val words = listOf("ALPHA" to 300, "BRAVO" to 1500, "CHARLIE" to 2900, "DELTA" to 4300, "ECHO" to 5700, "SEAM" to 1245)
        val blocks = MlKitPageOcr().recognize(page(800, 6000, words), "en")
        val found = blocks.flatMap { it.text.split(Regex("\\s+")) }
        assertEquals(words.map { it.first }.sorted(), found.sorted())
        assertEquals(blocks.map { it.box.top }.sorted(), blocks.map { it.box.top })
    }

    @Test fun recognizeRegionsReadsOnlyThePaddedRegion() = runBlocking {
        val image = page(1600, 1000, listOf("LEFT" to 500, "RIGHT" to 500), listOf(50f, 1000f))
        val region = Box(0.65f, 0.42f, 0.90f, 0.50f)
        val blocks = MlKitPageOcr().recognizeRegions(image, "en", listOf(region))
        assertEquals(listOf("RIGHT"), blocks.map { it.text })
        val box = blocks.single().box
        assertTrue(box.left >= 0 && box.right <= 1 && box.top >= 0 && box.bottom <= 1)
        assertTrue(box.left < region.right && box.right > region.left && box.top < region.bottom && box.bottom > region.top)
    }

    @Test fun recognizeRegionsOfNothingIsEmpty() = runBlocking {
        assertEquals(emptyList<OcrBlock>(), MlKitPageOcr().recognizeRegions(byteArrayOf(1, 2, 3), "en", emptyList()))
    }

    @Test fun cancellingStripOcrReleasesTheLock() = runBlocking {
        val ocr = MlKitPageOcr()
        val strip = page(800, 6000, listOf("ALPHA" to 300, "ECHO" to 5700))
        var decodes = 0
        var recognitions = 0
        lateinit var first: Job
        val trace = PageTrace(sink = { stage ->
            if (stage.startsWith("decode:")) decodes++
            if (stage.startsWith("recognize:")) {
                recognitions++
                first.cancel() // Real first native task has completed; no timing-based sleep.
            }
        })
        first = launch(PageTraceElement(trace), start = CoroutineStart.LAZY) { ocr.recognize(strip, "en") }
        first.start()
        withTimeout(10_000) { first.join() }
        assertTrue(first.isCancelled)
        assertEquals(1, recognitions)
        assertEquals("No next tile may be decoded after cancellation", 1, decodes)
        val second = withTimeout(10_000) { ocr.recognize(page(800, 800, listOf("NEXT" to 400)), "en") }
        assertEquals(listOf("NEXT"), second.map { it.text })
    }

    private fun page(width: Int, height: Int, words: List<Pair<String, Int>>, xs: List<Float> = List(words.size) { 80f }): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 120f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
            val canvas = Canvas(bitmap)
            words.forEachIndexed { i, (word, y) -> canvas.drawText(word, xs[i], y.toFloat(), paint) }
            return ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
}
