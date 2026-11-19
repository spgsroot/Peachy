package ru.spgsroot.peachy.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

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

    fun decodeTile(image: ByteArray, tile: PageTile, maxSide: Int, useRegionDecoder: Boolean = true): Bitmap {
        require(maxSide > 0)
        val (width, height) = size(image)
        return decodeRegion(image, PixelRect(0, tile.top, width, tile.bottom), width, height, maxSide, useRegionDecoder)
    }

    internal fun decodeRegion(image: ByteArray, box: Box, maxSide: Int, useRegionDecoder: Boolean = true): Bitmap {
        require(maxSide > 0)
        require(box.left >= 0f && box.top >= 0f && box.right <= 1f && box.bottom <= 1f &&
            box.left < box.right && box.top < box.bottom) { "Invalid text bounds" }
        val (width, height) = size(image)
        val rect = PixelRect(
            floor(box.left.toDouble() * width).toInt(),
            floor(box.top.toDouble() * height).toInt(),
            ceil(box.right.toDouble() * width).toInt(),
            ceil(box.bottom.toDouble() * height).toInt()
        )
        return decodeRegion(image, rect, width, height, maxSide, useRegionDecoder)
    }

    @Suppress("DEPRECATION")
    private fun decodeRegion(
        image: ByteArray,
        rect: PixelRect,
        width: Int,
        height: Int,
        maxSide: Int,
        useRegionDecoder: Boolean
    ): Bitmap {
        require(rect.left >= 0 && rect.left < rect.right && rect.right <= width)
        require(rect.top >= 0 && rect.top < rect.bottom && rect.bottom <= height)
        val sample = PageImage.sampleSize(rect.right - rect.left, rect.bottom - rect.top, maxSide)
        if (useRegionDecoder) {
            val decoder = try {
                BitmapRegionDecoder.newInstance(image, 0, image.size, false)
            } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
            if (decoder != null) {
                try {
                    val bitmap = try {
                        decoder.decodeRegion(Rect(rect.left, rect.top, rect.right, rect.bottom), options(sample))
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
            val targetWidth = ((rect.right.toLong() - rect.left + step - 1) / step).toInt().coerceAtLeast(1)
            val targetHeight = ((rect.bottom.toLong() - rect.top + step - 1) / step).toInt().coerceAtLeast(1)
            val left = (rect.left.toLong() * whole.width / width).toInt()
            val top = (rect.top.toLong() * whole.height / height).toInt()
            val right = ((rect.right.toLong() * whole.width + width - 1) / width).toInt()
            val bottom = ((rect.bottom.toLong() * whole.height + height - 1) / height).toInt()
            val crop = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            try {
                Canvas(crop).drawBitmap(whole, Rect(left, top, right, bottom), Rect(0, 0, targetWidth, targetHeight), Paint(Paint.FILTER_BITMAP_FLAG))
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
