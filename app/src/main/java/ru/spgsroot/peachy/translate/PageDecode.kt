package ru.spgsroot.peachy.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.IOException

/** Decodes original page bytes; strip fallback bounds the whole-page working bitmap. */
object PageDecode {
    private const val MAX_FALLBACK_PIXELS = 16_000_000L

    fun size(image: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw unreadable()
        return bounds.outWidth to bounds.outHeight
    }

    fun decode(image: ByteArray, maxSide: Int): Bitmap {
        require(maxSide > 0)
        val (width, height) = size(image)
        return BitmapFactory.decodeByteArray(image, 0, image.size, options(PageImage.sampleSize(width, height, maxSide)))
            ?: throw unreadable()
    }

    @Suppress("DEPRECATION")
    fun decodeTile(image: ByteArray, tile: PageTile, maxSide: Int, useRegionDecoder: Boolean = true): Bitmap {
        require(maxSide > 0)
        val (width, height) = size(image)
        require(tile.top >= 0 && tile.top < tile.bottom && tile.bottom <= height)
        val sample = PageImage.sampleSize(width, tile.bottom - tile.top, maxSide)
        if (useRegionDecoder) {
            val decoder = try {
                BitmapRegionDecoder.newInstance(image, 0, image.size, false)
            } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
            if (decoder != null) {
                try {
                    val bitmap = try {
                        decoder.decodeRegion(Rect(0, tile.top, width, tile.bottom), options(sample))
                    } catch (_: IllegalArgumentException) { null }
                    if (bitmap != null) return bitmap
                } finally { decoder.recycle() }
            }
        }
        var fallbackSample = 1
        while (((width.toLong() + fallbackSample - 1) / fallbackSample) *
            ((height.toLong() + fallbackSample - 1) / fallbackSample) > MAX_FALLBACK_PIXELS) {
            fallbackSample *= 2
        }
        val whole = BitmapFactory.decodeByteArray(image, 0, image.size, options(fallbackSample)) ?: throw unreadable()
        try {
            val step = maxOf(sample, fallbackSample)
            val targetWidth = ((width.toLong() + step - 1) / step).toInt().coerceAtLeast(1)
            val targetHeight = ((tile.bottom.toLong() - tile.top + step - 1) / step).toInt().coerceAtLeast(1)
            val top = (tile.top.toLong() * whole.height / height).toInt()
            val bottom = ((tile.bottom.toLong() * whole.height + height - 1) / height).toInt()
            val crop = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            try {
                Canvas(crop).drawBitmap(whole, Rect(0, top, whole.width, bottom), Rect(0, 0, targetWidth, targetHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                return crop
            } catch (failure: Throwable) {
                crop.recycle()
                throw failure
            }
        } finally { whole.recycle() }
    }

    private fun options(sample: Int) = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    private fun unreadable() = IOException("Cannot decode the page image")
}
