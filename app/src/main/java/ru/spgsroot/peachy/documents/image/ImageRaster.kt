package ru.spgsroot.peachy.documents.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.graphics.ImageDecoder
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

enum class ImageExportFormat(val mime: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
}

enum class ImageLoadError { HEIC_UNSUPPORTED, DECODE_FAILED, TOO_LARGE }

class ImageLoadException(
    val reason: ImageLoadError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal data class LoadedImage(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    val downscaled: Boolean
        get() = bitmap.width != sourceWidth || bitmap.height != sourceHeight
}

/**
 * Platform raster work: decode with EXIF orientation applied, replay the operation list, encode an
 * export. Every bitmap that leaves this object is owned by the caller.
 *
 * Decode is capped at [MAX_EDIT_DIMENSION] on the long side. Full-resolution export then means the
 * cap, not the raw sensor size, and the editor says so; nothing here exports a downscaled preview.
 */
internal object ImageRaster {

    const val MAX_EDIT_DIMENSION = 4096
    private const val MAX_SOURCE_DIMENSION = 20_000
    private const val JPEG_QUALITY = 92

    private fun heicSupported(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ImageDecoder.isMimeTypeSupported("image/heif")
        Build.VERSION.SDK_INT == Build.VERSION_CODES.P -> true // Actual support is checked by decoding.
        else -> false
    }

    fun isAnimatedWebp(file: File): Boolean = runCatching {
        val head = ByteArray(64)
        val read = file.inputStream().use { it.read(head) }
        if (read < 21) return false
        val riff = String(head, 0, 4, Charsets.US_ASCII) == "RIFF"
        val webp = String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
        val vp8x = String(head, 12, 4, Charsets.US_ASCII) == "VP8X"
        riff && webp && vp8x && (head[20].toInt() and 0x02) != 0
    }.getOrDefault(false)

    fun load(file: File, format: String): LoadedImage {
        if (format == "heic" && !heicSupported()) {
            throw ImageLoadException(
                ImageLoadError.HEIC_UNSUPPORTED,
                "HEIC is not supported on this device"
            )
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            val reason = if (format == "heic" && Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
                ImageLoadError.HEIC_UNSUPPORTED
            } else {
                ImageLoadError.DECODE_FAILED
            }
            throw ImageLoadException(reason, "The image cannot be decoded")
        }
        if (bounds.outWidth > MAX_SOURCE_DIMENSION || bounds.outHeight > MAX_SOURCE_DIMENSION) {
            throw ImageLoadException(
                ImageLoadError.TOO_LARGE,
                "${bounds.outWidth}x${bounds.outHeight} exceeds the decode limit"
            )
        }
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(file, sample)
            } else {
                decodeWithFactory(file, sample)
            }
        } catch (failure: ImageLoadException) {
            throw failure
        } catch (failure: Throwable) {
            // A HEIC the platform advertises but cannot allocate (10-bit, weird tiling) lands here.
            if (format == "heic") {
                throw ImageLoadException(ImageLoadError.HEIC_UNSUPPORTED, "HEIC decode failed", failure)
            }
            throw ImageLoadException(ImageLoadError.DECODE_FAILED, "The image cannot be decoded", failure)
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            throw ImageLoadException(ImageLoadError.DECODE_FAILED, "The image has no pixels")
        }
        return LoadedImage(bitmap, bounds.outWidth, bounds.outHeight)
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_EDIT_DIMENSION) sample *= 2
        return sample
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(file: File, sample: Int): Bitmap {
        val source = ImageDecoder.createSource(file)
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // ImageDecoder also applies the EXIF orientation, so no matrix is needed here.
            decoder.setTargetSampleSize(sample)
            decoder.isMutableRequired = false
        }
    }

    private fun decodeWithFactory(file: File, sample: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw ImageLoadException(ImageLoadError.DECODE_FAILED, "The image cannot be decoded")
        return applyOrientation(decoded, exifOrientation(file))
    }

    private fun exifOrientation(file: File): Int = try {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: IOException) {
        // BMP and other formats without EXIF are simply already upright.
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** Replays [ops] over [base]; [base] is not recycled and the result is a new bitmap. */
    fun render(base: Bitmap, ops: List<ImageOp>): Bitmap {
        var current = base.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IOException("Cannot copy the image")
        try {
            for (op in ops) current = apply(current, op)
            return current
        } catch (failure: Throwable) {
            current.recycle()
            throw failure
        }
    }

    private fun apply(source: Bitmap, op: ImageOp): Bitmap = when (op) {
        is ImageOp.Crop -> {
            val rect = op.rect.clampedTo(source.width, source.height)
                ?: throw IOException("Crop is outside the image")
            transform(source) { Bitmap.createBitmap(it, rect.left, rect.top, rect.width, rect.height) }
        }

        is ImageOp.RotateQuarter -> {
            val matrix = Matrix().apply { postRotate(90f * op.turns) }
            transform(source) {
                Bitmap.createBitmap(it, 0, 0, it.width, it.height, matrix, true)
            }
        }

        is ImageOp.RotateFree -> {
            val (targetWidth, targetHeight) =
                ImageGeometry.freeRotateSize(source.width, source.height, op.degrees)
            // freeRotateSize is the upright box around a rotation about the image centre, so the
            // matrix has to pivot there too: rotating about (0,0) and translating by half the size
            // delta shifts the content up-left and clips it at the left/bottom edges.
            val matrix = Matrix().apply {
                setRotate(
                    ImageGeometry.normalizeDegrees(op.degrees),
                    source.width / 2f,
                    source.height / 2f,
                )
                postTranslate((targetWidth - source.width) / 2f, (targetHeight - source.height) / 2f)
            }
            transform(source) { bitmap ->
                Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { target ->
                    Canvas(target).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
        }

        is ImageOp.Adjust -> {
            val matrix = ColorMatrix(ColorMath.matrix(op.brightness, op.contrast, op.saturation))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
            val target = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(target).drawBitmap(source, 0f, 0f, paint)
            source.recycle()
            target
        }

        is ImageOp.Stroke -> {
            val target = source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IOException("Cannot copy the image for drawing")
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = op.width
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = op.color
            }
            val path = Path()
            if (op.size > 0) {
                path.moveTo(op.xs[0], op.ys[0])
                if (op.size == 1) path.lineTo(op.xs[0], op.ys[0])
                for (index in 1 until op.size) path.lineTo(op.xs[index], op.ys[index])
            }
            Canvas(target).drawPath(path, paint)
            source.recycle()
            target
        }
    }

    private inline fun transform(source: Bitmap, create: (Bitmap) -> Bitmap): Bitmap {
        val result = create(source)
        if (result !== source) source.recycle()
        return result
    }

    /**
     * Writes [bitmap] to [target]. PNG keeps alpha; JPEG composites onto white. No EXIF is written:
     * orientation is already baked into the pixels and GPS tags are gone.
     */
    fun write(bitmap: Bitmap, format: ImageExportFormat, target: File) {
        withExportBitmap(bitmap, format) { export ->
            FileOutputStream(target).use { stream ->
                encode(export, format, stream)
                stream.fd.sync()
            }
        }
    }

    fun write(bitmap: Bitmap, format: ImageExportFormat, stream: OutputStream) {
        withExportBitmap(bitmap, format) { export -> encode(export, format, stream) }
    }

    private inline fun withExportBitmap(
        bitmap: Bitmap,
        format: ImageExportFormat,
        write: (Bitmap) -> Unit,
    ) {
        if (format != ImageExportFormat.JPEG || !bitmap.hasAlpha()) {
            write(bitmap)
            return
        }

        val width = bitmap.width
        val height = bitmap.height
        // The opaque ARGB_8888 target is sRGB, like the previous JPEG pixel buffer.
        val export = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false)
        try {
            val canvas = Canvas(export)
            canvas.drawColor(Color.WHITE)
            // An explicit destination rect ignores bitmap density and keeps the draw 1:1.
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), null)
            write(export)
        } finally {
            export.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, format: ImageExportFormat, stream: OutputStream) {
        if (!bitmap.compress(format.compressFormat, JPEG_QUALITY, stream)) {
            throw IOException("Cannot encode the image")
        }
    }

    private val ImageExportFormat.compressFormat: Bitmap.CompressFormat
        get() = if (this == ImageExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
}
