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

    @Suppress("DEPRECATION")
    @Test fun selectedBubbleCropExcludesSurroundingPixelsForEachFormatAndFallback() {
        val page = Bitmap.createBitmap(800, 1600, Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.BLUE)
        Canvas(page).drawRect(200f, 400f, 600f, 800f, Paint().apply { color = Color.RED })
        val box = Box(0.25f, 0.25f, 0.75f, 0.5f)
        try {
            for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)) {
                val source = encode(page, format)
                for (regionDecoder in listOf(true, false)) {
                    val crop = PageDecode.decodeRegion(source, box, PageImage.MAX_SIDE, regionDecoder)
                    try {
                        assertEquals(400, crop.width)
                        assertEquals(400, crop.height)
                        for ((x, y) in listOf(20 to 20, 200 to 200, 380 to 380)) {
                            assertTrue("$format/$regionDecoder selected red", Color.red(crop.getPixel(x, y)) > 220)
                            assertTrue("$format/$regionDecoder outside blue excluded", Color.blue(crop.getPixel(x, y)) < 30)
                        }
                    } finally { crop.recycle() }
                }
                val jpeg = PageImage.crop(source, box)
                assertEquals(400 to 400, PageDecode.size(jpeg))
                val uploaded = PageDecode.decode(jpeg, PageImage.MAX_SIDE)
                try {
                    assertTrue(Color.red(uploaded.getPixel(200, 200)) > 220)
                    assertTrue(Color.blue(uploaded.getPixel(200, 200)) < 30)
                } finally { uploaded.recycle() }
            }
        } finally { page.recycle() }
    }

    @Test fun bubbleCropKeepsOriginalResolutionOnTallPage() {
        val page = Bitmap.createBitmap(1024, 8192, Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.WHITE)
        Canvas(page).drawRect(256f, 4096f, 768f, 4352f, Paint().apply { color = Color.BLACK })
        val source = try { encode(page, Bitmap.CompressFormat.PNG) } finally { page.recycle() }
        val jpeg = PageImage.crop(source, Box(0.25f, 0.5f, 0.75f, 0.53125f))
        assertEquals(512 to 256, PageDecode.size(jpeg))
        val crop = PageDecode.decode(jpeg, PageImage.MAX_SIDE)
        try { assertTrue(Color.red(crop.getPixel(256, 128)) < 30) } finally { crop.recycle() }
    }

    @Test fun largeBubbleCropIsBoundedToUploadSize() {
        val page = Bitmap.createBitmap(1024, 4096, Bitmap.Config.ARGB_8888)
        val source = try { encode(page, Bitmap.CompressFormat.PNG) } finally { page.recycle() }
        val jpeg = PageImage.crop(source, Box(0.25f, 0f, 0.75f, 1f))
        assertEquals(200 to 1600, PageDecode.size(jpeg))
    }

    @Test fun corruptBytesThrowIoException() {
        val bytes = byteArrayOf(1, 2, 3)
        for (operation in listOf<() -> Any>(
            { PageDecode.size(bytes) },
            { PageDecode.decode(bytes, 2000) },
            { PageDecode.decodeTile(bytes, PageTile(0, 100), 2000) },
            { PageDecode.decodeTile(bytes, PageTile(0, 100), 2000, false) },
            { PageDecode.decodeRegion(bytes, Box(0f, 0f, 0.5f, 0.5f), 1600) },
            { PageImage.crop(bytes, Box(0f, 0f, 0.5f, 0.5f)) },
        )) {
            try { operation(); fail("Corrupt image accepted") } catch (_: IOException) { }
        }
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 100, it)) }.toByteArray()
}
