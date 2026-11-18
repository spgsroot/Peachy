package ru.spgsroot.peachy.perfqa

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import ru.spgsroot.peachy.translate.DETECTOR_SIDE
import ru.spgsroot.peachy.translate.DetectorDecode
import ru.spgsroot.peachy.translate.DetectorPreprocess
import ru.spgsroot.peachy.translate.Region
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

internal object DetectorProbe {
    private const val width = 900
    private const val height = 1400
    private const val side = DETECTOR_SIDE
    private const val area = side * side

    // Same resize, pad, RGB conversion and layout as DetectorPreprocess; only the tensor writes differ.
    private class Direct : AutoCloseable {
        val tensor = ByteBuffer.allocateDirect(3 * area * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val pixels = IntArray(area)
        private val padded = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(padded)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val destination = Rect()
        fun prepare(bitmap: Bitmap, width: Int, height: Int) {
            val scale = min(side.toDouble() / width, side.toDouble() / height)
            val w = (width * scale).roundToInt().coerceAtLeast(1)
            val h = (height * scale).roundToInt().coerceAtLeast(1)
            val x = (side - w) / 2; val y = (side - h) / 2
            canvas.drawColor(Color.rgb(114, 114, 114))
            destination.set(x, y, x + w, y + h)
            canvas.drawBitmap(bitmap, null, destination, paint)
            padded.getPixels(pixels, 0, side, 0, 0, side, side)
            for (i in pixels.indices) {
                val p = pixels[i]
                tensor.put(i, ((p ushr 16) and 255) / 255f)
                tensor.put(i + area, ((p ushr 8) and 255) / 255f)
                tensor.put(i + 2 * area, (p and 255) / 255f)
            }
        }
        override fun close() = padded.recycle()
    }

    private fun fixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 29f }
            repeat(6) { i ->
                val dx = (i % 2) * 445f + 12f; val dy = (i / 2) * 462f + 10f
                paint.color = Color.rgb(93 + 23 * i, 205 - i * 21, 171 + i * 12)
                canvas.drawRect(dx, dy, dx + 428, dy + 448, paint)
                paint.color = Color.DKGRAY
                repeat(32) { k -> canvas.drawLine(dx + k * 11, dy, dx + k * 12, dy + 430, paint) }
                paint.color = Color.WHITE; canvas.drawOval(dx + 25, dy + 88, dx + 405, dy + 310, paint)
                paint.color = Color.BLACK
                repeat(3) { row -> canvas.drawText("Chapter ${i + 1}: panel ${row + 1}", dx + 68, dy + 164 + row * 42, paint) }
            }
            return ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun decode(image: ByteArray): Bitmap {
        val decoder = BitmapRegionDecoder.newInstance(image, 0, image.size, false)!!
        return try { decoder.decodeRegion(Rect(0, 0, width, height), BitmapFactory.Options().apply {
            inSampleSize = 1; inPreferredConfig = Bitmap.Config.ARGB_8888
        })!! } finally { decoder.recycle() }
    }

    fun measure(ctx: Context, emit: (String) -> Unit) {
        val image = fixture()
        val model = File(ctx.cacheDir, "detector.ort")
        ctx.assets.open("detector/yolo26n.ort").use { input -> model.outputStream().use { input.copyTo(it) } }
        val env = OrtEnvironment.getEnvironment()
        val session = OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(4); opts.setInterOpNumThreads(1); opts.setMemoryPatternOptimization(false)
            env.createSession(model.absolutePath, opts)
        }
        session.use { DetectorPreprocess().use { bulk -> Direct().use { direct ->
            val bitmap = decode(image)
            try { bulk.prepare(bitmap, width, height); direct.prepare(bitmap, width, height) } finally { bitmap.recycle() }
            for (i in 0 until 3 * area) check(bulk.tensor.get(i).toRawBits() == direct.tensor.get(i).toRawBits()) { "CHW mismatch at $i" }
            val shape = longArrayOf(1, 3, side.toLong(), side.toLong())
            OnnxTensor.createTensor(env, bulk.tensor, shape).use { a -> OnnxTensor.createTensor(env, direct.tensor, shape).use { b ->
                val inputs = listOf(mapOf("images" to a), mapOf("images" to b)); val rows = FloatArray(300 * 6)
                var expected: List<Region>? = null
                // AB, BA alternating adjacent pairs, both variants share the same warmed CPU session.
                repeat(24) { pair -> repeat(2) { pos ->
                    val variant = (pair + pos) % 2
                    val t0 = System.nanoTime(); val tile = decode(image); val t1 = System.nanoTime()
                    val t2 = try {
                        if (variant == 0) bulk.prepare(tile, width, height) else direct.prepare(tile, width, height)
                        System.nanoTime()
                    } finally { tile.recycle() }
                    val runStart = System.nanoTime()
                    val result = session.run(inputs[variant]); val t3 = System.nanoTime()
                    val found = result.use {
                        (it[0] as OnnxTensor).floatBuffer.get(rows)
                        DetectorDecode.yolo(rows, bulk.padX, bulk.padY, bulk.scale, width, height)
                    }
                    val t4 = System.nanoTime()
                    if (expected == null) expected = found else check(found == expected) { "Decoded result changed" }
                    if (pair >= 4) emit("ocr ${if (variant == 0) "bulk" else "direct"} pair=${pair - 3} ns pre,infer,e2e=${t2-t1},${t3-runStart},${t4-t0}")
                } }
                emit("ocr equal_chw=${3 * area} decoded_regions=${expected?.size} warmups_per_arm=4 samples_per_arm=20")
            } }
        } } }
    }
}
