package ru.spgsroot.peachy.translate

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageDecodeDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun tileOfEachFormatHasExpectedSizeAndBand() {
        val page = Bitmap.createBitmap(800, 6000, Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.WHITE)
        Canvas(page).drawRect(0f, 2400f, 800f, 2600f, Paint().apply { color = Color.RED })
        try {
            for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)) {
                val image = encode(page, format)
                assertEquals(800 to 6000, PageDecode.size(image))
                // Measure support directly: PageDecode deliberately hides rejected formats behind fallback.
                val decoder = BitmapRegionDecoder.newInstance(image, 0, image.size, false)
                val probe = decoder?.decodeRegion(Rect(0, 2000, 800, 3200), null)
                Log.i("PageDecodeDeviceTest", "regionDecoder $format accepted=${probe != null}")
                probe?.recycle()
                decoder?.recycle()
                for (regionDecoder in listOf(true, false)) {
                    val tile = PageDecode.decodeTile(image, PageTile(2000, 3200), 2000, regionDecoder)
                    try {
                        assertEquals(800, tile.width)
                        assertEquals(1200, tile.height)
                        assertEquals(Bitmap.Config.ARGB_8888, tile.config)
                        assertTrue("$format/$regionDecoder band start", Color.green(tile.getPixel(400, 410)) < 30)
                        assertTrue("$format/$regionDecoder band end", Color.green(tile.getPixel(400, 590)) < 30)
                        assertTrue(Color.green(tile.getPixel(400, 390)) > 220)
                        assertTrue(Color.green(tile.getPixel(400, 610)) > 220)
                    } finally { tile.recycle() }
                }
                val whole = PageDecode.decode(image, 2000)
                try {
                    assertEquals(400, whole.width)
                    assertEquals(3000, whole.height)
                    assertEquals(Bitmap.Config.ARGB_8888, whole.config)
                } finally { whole.recycle() }
            }
        } finally { page.recycle() }
    }

    @Test fun wholeStripDecodeKeepsWidth() {
        val page = Bitmap.createBitmap(800, 12000, Bitmap.Config.ARGB_8888)
        val image = try { encode(page, Bitmap.CompressFormat.PNG) } finally { page.recycle() }
        val tile = PageDecode.decodeTile(image, PageTile(0, 1200), 2000, false)
        try { assertTrue(tile.width >= 800) } finally { tile.recycle() }
    }

    @Test fun corruptBytesThrowIoException() {
        val bytes = byteArrayOf(1, 2, 3)
        for (operation in listOf<() -> Any>(
            { PageDecode.size(bytes) },
            { PageDecode.decode(bytes, 2000) },
            { PageDecode.decodeTile(bytes, PageTile(0, 100), 2000) },
            { PageDecode.decodeTile(bytes, PageTile(0, 100), 2000, false) }
        )) {
            try { operation(); fail("Corrupt image accepted") } catch (_: IOException) { }
        }
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 100, it)) }.toByteArray()
}
