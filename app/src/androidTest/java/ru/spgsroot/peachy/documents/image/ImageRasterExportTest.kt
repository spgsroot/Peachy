package ru.spgsroot.peachy.documents.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ImageRasterExportTest {

    @Test
    fun jpegCompositesOverWhiteForFileAndStreamWithoutChangingSource() {
        val source = colorBands(ColorSpace.Named.DISPLAY_P3)
        try {
            val originalColors = bandCenters.map { source.getPixel(it, HEIGHT / 2) }
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val target = File.createTempFile("raster-", ".jpg", cache)
            try {
                ImageRaster.write(source, ImageExportFormat.JPEG, target)
                assertJpegBands("file", BitmapFactory.decodeFile(target.absolutePath))
                assertSourceIntact("file", source, originalColors)
            } finally {
                target.delete()
            }

            val bytes = writeToBytes(source, ImageExportFormat.JPEG)
            assertJpegBands("stream", BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertSourceIntact("stream", source, originalColors)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun pngKeepsAlphaAndColors() {
        val source = colorBands(ColorSpace.Named.SRGB)
        try {
            val bytes = writeToBytes(source, ImageExportFormat.PNG)
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertEquals(WIDTH, decoded.width)
                assertEquals(HEIGHT, decoded.height)
                assertTrue(decoded.hasAlpha())
                assertEquals(Color.TRANSPARENT, decoded.getPixel(bandCenters[0], HEIGHT / 2))
                assertEquals(HALF_RED, decoded.getPixel(bandCenters[1], HEIGHT / 2))
                assertEquals(Color.GREEN, decoded.getPixel(bandCenters[2], HEIGHT / 2))
            } finally {
                decoded.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun writeToBytes(source: Bitmap, format: ImageExportFormat): ByteArray =
        ByteArrayOutputStream().also { ImageRaster.write(source, format, it) }.toByteArray()

    private fun colorBands(colorSpace: ColorSpace.Named): Bitmap {
        val source = Bitmap.createBitmap(
            WIDTH, HEIGHT, Bitmap.Config.ARGB_8888, true, ColorSpace.get(colorSpace),
        )
        // The export must map pixels 1:1 even when the source density differs from the destination.
        source.density = SOURCE_DENSITY
        val canvas = Canvas(source)
        val paint = Paint().apply { color = HALF_RED }
        canvas.drawRect(BAND_WIDTH.toFloat(), 0f, (2 * BAND_WIDTH).toFloat(), HEIGHT.toFloat(), paint)
        paint.color = Color.GREEN
        canvas.drawRect((2 * BAND_WIDTH).toFloat(), 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        return source
    }

    private fun assertJpegBands(destination: String, bitmap: Bitmap?) {
        val decoded = checkNotNull(bitmap) { "$destination: JPEG did not decode" }
        try {
            assertEquals("$destination width", WIDTH, decoded.width)
            assertEquals("$destination height", HEIGHT, decoded.height)
            assertFalse("$destination retained alpha", decoded.hasAlpha())
            assertJpegColor("$destination transparent", Color.WHITE, decoded.getPixel(bandCenters[0], HEIGHT / 2))
            assertJpegColor("$destination half-red", Color.rgb(255, 127, 127), decoded.getPixel(bandCenters[1], HEIGHT / 2))
            assertJpegColor("$destination opaque green", Color.GREEN, decoded.getPixel(bandCenters[2], HEIGHT / 2))
        } finally {
            decoded.recycle()
        }
    }

    private fun assertSourceIntact(destination: String, source: Bitmap, originalColors: List<Int>) {
        assertFalse("$destination recycled source", source.isRecycled)
        assertTrue("$destination cleared source alpha", source.hasAlpha())
        assertEquals("$destination changed source density", SOURCE_DENSITY, source.density)
        assertEquals("$destination changed source color space", ColorSpace.get(ColorSpace.Named.DISPLAY_P3), source.colorSpace)
        assertEquals("$destination changed source pixels", originalColors, bandCenters.map { source.getPixel(it, HEIGHT / 2) })
    }

    private fun assertJpegColor(label: String, expected: Int, actual: Int) {
        val distance = maxOf(
            abs(Color.red(expected) - Color.red(actual)),
            abs(Color.green(expected) - Color.green(actual)),
            abs(Color.blue(expected) - Color.blue(actual)),
        )
        assertTrue("$label: expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}", distance <= 6)
    }

    private companion object {
        const val BAND_WIDTH = 64
        const val WIDTH = 3 * BAND_WIDTH
        const val HEIGHT = 96
        const val SOURCE_DENSITY = 1
        val HALF_RED = Color.argb(128, 255, 0, 0)
        val bandCenters = listOf(32, 96, 160)
    }
}
