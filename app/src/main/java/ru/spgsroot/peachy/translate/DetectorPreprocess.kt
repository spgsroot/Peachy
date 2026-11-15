package ru.spgsroot.peachy.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/** Mutable staging storage, used only while the detector mutex is held. Does not own input bitmaps. */
internal class DetectorPreprocess : AutoCloseable {
    private val area = DETECTOR_SIDE * DETECTOR_SIDE
    private val pixels = IntArray(area)
    private val values = FloatArray(3 * area)
    val tensor = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val padded = Bitmap.createBitmap(DETECTOR_SIDE, DETECTOR_SIDE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(padded)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    var scale: Double = 1.0
        private set
    var padX: Int = 0
        private set
    var padY: Int = 0
        private set

    fun prepare(bitmap: Bitmap, width: Int, height: Int) {
        scale = min(DETECTOR_SIDE.toDouble() / width, DETECTOR_SIDE.toDouble() / height)
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        padX = (DETECTOR_SIDE - targetWidth) / 2
        padY = (DETECTOR_SIDE - targetHeight) / 2
        canvas.drawColor(Color.rgb(114, 114, 114))
        destination.set(padX, padY, padX + targetWidth, padY + targetHeight)
        canvas.drawBitmap(bitmap, null, destination, paint)
        padded.getPixels(pixels, 0, DETECTOR_SIDE, 0, 0, DETECTOR_SIDE, DETECTOR_SIDE)
        for (i in 0 until area) {
            val pixel = pixels[i]
            values[i] = ((pixel ushr 16) and 255) / 255f
            values[i + area] = ((pixel ushr 8) and 255) / 255f
            values[i + 2 * area] = (pixel and 255) / 255f
        }
        tensor.clear()
        tensor.put(values)
        tensor.rewind()
    }

    override fun close() = padded.recycle()
}
