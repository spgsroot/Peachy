package ru.spgsroot.peachy.documents.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Run each method in a separate instrumentation process: VmHWM is a lifetime RSS peak. */
@RunWith(AndroidJUnit4::class)
class ImageRasterJpegMemoryTest {

    @Test
    fun legacyArray4096() = measure("legacy", ::writeLegacyJpeg)

    @Test
    fun canvas4096() = measure("canvas") { bitmap, target ->
        ImageRaster.write(bitmap, ImageExportFormat.JPEG, target)
    }

    private fun measure(variant: String, writeJpeg: (Bitmap, File) -> Unit) {
        assumeTrue("Requires -e imageRasterMemory true", InstrumentationRegistry.getArguments().getString("imageRasterMemory") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File.createTempFile("raster-memory-", ".jpg", instrumentation.targetContext.cacheDir)
        try {
            val source = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.argb(128, 255, 0, 0))
                val before = peakRssKiB()
                writeJpeg(source, target)
                val peak = peakRssKiB()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(target.absolutePath, bounds)
                assertEquals("image/jpeg", bounds.outMimeType)
                assertEquals(SIZE, bounds.outWidth)
                assertEquals(SIZE, bounds.outHeight)
                instrumentation.addResults(Bundle().apply {
                    putString("imageRasterVariant", variant)
                    putString("imageRasterVmHwmBeforeKiB", before.toString())
                    putString("imageRasterVmHwmAfterKiB", peak.toString())
                })
            } finally {
                source.recycle()
            }
        } finally {
            target.delete()
        }
    }

    private fun peakRssKiB(): Long = File("/proc/self/status").useLines { lines ->
        lines.first { it.startsWith("VmHWM:") }.substringAfter(':').trim().substringBefore(' ').toLong()
    }

    // Previous file-writer path, kept here only for measurement in the same APK and process setup.
    private fun writeLegacyJpeg(bitmap: Bitmap, target: File) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val argb = pixels[index]
            val alpha = argb ushr 24
            if (alpha == 255) continue
            if (alpha == 0) {
                pixels[index] = Color.WHITE
                continue
            }
            val white = 255 * (255 - alpha)
            val red = (Color.red(argb) * alpha + white) / 255
            val green = (Color.green(argb) * alpha + white) / 255
            val blue = (Color.blue(argb) * alpha + white) / 255
            pixels[index] = Color.rgb(red, green, blue)
        }
        val export = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(target).use { stream ->
                if (!export.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                    throw IOException("Cannot encode the image")
                }
                stream.fd.sync()
            }
        } finally {
            export.recycle()
        }
    }

    private companion object {
        const val SIZE = 4096
    }
}
