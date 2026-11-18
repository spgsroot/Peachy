package ru.spgsroot.peachy.documents.image

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The image editor's document is an operation list, not a pixels snapshot: undo/redo replays a few
 * hundred numbers instead of holding a second full-resolution bitmap per step.
 *
 * Coordinates inside an operation are expressed in the raster space that exists when the operation
 * is committed (i.e. after every earlier operation), so replaying the list in order always
 * reproduces what the user saw.
 */
internal sealed interface ImageOp {
    data class Crop(val rect: PxRect) : ImageOp

    /** Clockwise quarter turns, 1..3. */
    data class RotateQuarter(val turns: Int) : ImageOp

    data class RotateFree(val degrees: Float) : ImageOp

    /** Each value is in -1..1, 0 = unchanged. */
    data class Adjust(val brightness: Float, val contrast: Float, val saturation: Float) : ImageOp

    /** Freehand stroke; points are pixels in the current raster, split x/y in [Stroke.xs]/[Stroke.ys]. */
    data class Stroke(val xs: FloatArray, val ys: FloatArray, val width: Float, val color: Int) : ImageOp {
        val size: Int get() = xs.size

        // FloatArray in a data class: equality is identity, so compare content explicitly.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Stroke) return false
            return xs.contentEquals(other.xs) && ys.contentEquals(other.ys) &&
                width == other.width && color == other.color
        }

        override fun hashCode(): Int {
            var result = xs.contentHashCode()
            result = 31 * result + ys.contentHashCode()
            result = 31 * result + width.hashCode()
            result = 31 * result + color
            return result
        }
    }
}

data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun clampedTo(imageWidth: Int, imageHeight: Int): PxRect? {
        if (imageWidth < MIN_CROP || imageHeight < MIN_CROP) return null
        val l = left.coerceIn(0, imageWidth - MIN_CROP)
        val t = top.coerceIn(0, imageHeight - MIN_CROP)
        val r = right.coerceIn(l + MIN_CROP, imageWidth)
        val b = bottom.coerceIn(t + MIN_CROP, imageHeight)
        if (r - l < MIN_CROP || b - t < MIN_CROP) return null
        return PxRect(l, t, r, b)
    }

    companion object {
        const val MIN_CROP = 16

        /** Normalises a drag in any direction into a rect inside the image. */
        fun fromDrag(x0: Float, y0: Float, x1: Float, y1: Float, imageWidth: Int, imageHeight: Int): PxRect {
            val left = minOf(x0, x1).coerceIn(0f, imageWidth.toFloat())
            val top = minOf(y0, y1).coerceIn(0f, imageHeight.toFloat())
            val right = maxOf(x0, x1).coerceIn(0f, imageWidth.toFloat())
            val bottom = maxOf(y0, y1).coerceIn(0f, imageHeight.toFloat())
            // A tap-sized selection is expanded around the touch, then clamped to the image.
            val l = max(0, minOf(left.toInt(), imageWidth - MIN_CROP))
            val t = max(0, minOf(top.toInt(), imageHeight - MIN_CROP))
            val r = minOf(imageWidth, max(right.toInt(), l + MIN_CROP))
            val b = minOf(imageHeight, max(bottom.toInt(), t + MIN_CROP))
            return PxRect(l, t, r, b)
        }
    }
}

internal object ImageGeometry {

    /** Dimensions after [turns] clockwise quarter turns. */
    fun rotateQuarterSize(width: Int, height: Int, turns: Int): Pair<Int, Int> =
        if (normalizeTurns(turns) % 2 == 0) width to height else height to width

    /** Smallest upright box that contains a [degrees] rotation of the image. */
    fun freeRotateSize(width: Int, height: Int, degrees: Float): Pair<Int, Int> {
        val radians = Math.toRadians(normalizeDegrees(degrees).toDouble())
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        val w = (width * c + height * s).toInt()
        val h = (width * s + height * c).toInt()
        return max(1, w) to max(1, h)
    }

    /** Where a point lands after [turns] clockwise quarter turns. */
    fun mapPointAfterQuarterTurns(x: Float, y: Float, width: Int, height: Int, turns: Int): Pair<Float, Float> =
        when (normalizeTurns(turns)) {
            1 -> (height - y) to x // 90° clockwise
            2 -> (width - x) to (height - y)
            3 -> y to (width - x)
            else -> x to y
        }

    fun normalizeTurns(turns: Int): Int = ((turns % 4) + 4) % 4

    fun normalizeDegrees(degrees: Float): Float {
        val wrapped = ((degrees % 360f) + 360f) % 360f
        return if (wrapped > 180f) wrapped - 360f else wrapped
    }

    /** Raster size after replaying [ops] over a [width]x[height] base image. */
    fun dimensions(width: Int, height: Int, ops: List<ImageOp>): Pair<Int, Int> {
        var w = width
        var h = height
        for (op in ops) {
            when (op) {
                is ImageOp.Crop -> {
                    w = op.rect.width
                    h = op.rect.height
                }

                is ImageOp.RotateQuarter -> {
                    val next = rotateQuarterSize(w, h, op.turns)
                    w = next.first
                    h = next.second
                }

                is ImageOp.RotateFree -> {
                    val next = freeRotateSize(w, h, op.degrees)
                    w = next.first
                    h = next.second
                }

                is ImageOp.Adjust, is ImageOp.Stroke -> Unit
            }
        }
        return w to h
    }
}

/** Undo/redo over the committed operation list; nothing is ever copied but the list itself. */
internal class OperationHistory(committed: List<ImageOp> = emptyList()) {
    private val applied = committed.toMutableList()
    private val undone = ArrayDeque<ImageOp>()

    val ops: List<ImageOp> get() = applied
    val canUndo: Boolean get() = applied.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()
    val nextRedo: ImageOp? get() = undone.firstOrNull()

    fun commit(op: ImageOp) {
        applied += op
        undone.clear()
    }

    fun undo(): Boolean {
        if (applied.isEmpty()) return false
        undone.addFirst(applied.removeAt(applied.lastIndex))
        return true
    }

    fun redo(): Boolean {
        val next = undone.removeFirstOrNull() ?: return false
        applied += next
        return true
    }
}

/** Brightness/contrast/saturation as one 4x5 colour matrix (row-major, as ColorMatrix expects). */
internal object ColorMath {
    private const val ROWS = 4
    private const val COLS = 5

    fun matrix(brightness: Float, contrast: Float, saturation: Float): FloatArray {
        val saturationMatrix = saturationMatrix(saturation.coerceIn(-1f, 1f))
        val brightnessMatrix = brightnessMatrix(brightness.coerceIn(-1f, 1f))
        val contrastMatrix = contrastMatrix(contrast.coerceIn(-1f, 1f))
        return multiply(contrastMatrix, multiply(brightnessMatrix, saturationMatrix))
    }

    private fun saturationMatrix(saturation: Float): FloatArray {
        val s = 1f + saturation
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        return floatArrayOf(
            lr + s * (1 - lr), lg * (1 - s), lb * (1 - s), 0f, 0f,
            lr * (1 - s), lg + s * (1 - lg), lb * (1 - s), 0f, 0f,
            lr * (1 - s), lg * (1 - s), lb + s * (1 - lb), 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    private fun brightnessMatrix(brightness: Float): FloatArray {
        val offset = brightness * 255f
        return floatArrayOf(
            1f, 0f, 0f, 0f, offset,
            0f, 1f, 0f, 0f, offset,
            0f, 0f, 1f, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    }

    private fun contrastMatrix(contrast: Float): FloatArray {
        val factor = 1f + contrast
        val offset = 128f * (1f - factor)
        return floatArrayOf(
            factor, 0f, 0f, 0f, offset,
            0f, factor, 0f, 0f, offset,
            0f, 0f, factor, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /** Result applies [after] first, then [before] (standard matrix concatenation). */
    private fun multiply(before: FloatArray, after: FloatArray): FloatArray {
        val out = FloatArray(ROWS * COLS)
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                var value = 0f
                for (k in 0 until ROWS) {
                    value += before[row * COLS + k] * after[k * COLS + col]
                }
                // The translation column of the outer matrix is added after the inner translation.
                if (col == COLS - 1) value += before[row * COLS + COLS - 1]
                out[row * COLS + col] = value
            }
        }
        return out
    }
}

/**
 * The operation list is persisted next to the working raster so undo survives process death.
 * Unknown or malformed operations invalidate the whole list: a half-applied history would show a
 * different image than the working file.
 */
internal object ImageOpsCodec {
    private const val VERSION = 1
    private const val MAX_OPS = 200
    private const val MAX_POINTS = 400_000

    fun encode(ops: List<ImageOp>): String {
        val array = JSONArray()
        for (op in ops) {
            array.put(
                when (op) {
                    is ImageOp.Crop -> JSONObject()
                        .put("t", "crop")
                        .put("l", op.rect.left).put("o", op.rect.top)
                        .put("r", op.rect.right).put("b", op.rect.bottom)

                    is ImageOp.RotateQuarter -> JSONObject().put("t", "r90").put("n", op.turns)
                    is ImageOp.RotateFree -> JSONObject().put("t", "rfree").put("d", op.degrees.toDouble())
                    is ImageOp.Adjust -> JSONObject()
                        .put("t", "adj")
                        .put("b", op.brightness.toDouble())
                        .put("c", op.contrast.toDouble())
                        .put("s", op.saturation.toDouble())

                    is ImageOp.Stroke -> {
                        val points = JSONArray()
                        for (index in 0 until op.size) {
                            points.put(op.xs[index].toDouble())
                            points.put(op.ys[index].toDouble())
                        }
                        JSONObject()
                            .put("t", "stroke")
                            .put("w", op.width.toDouble())
                            .put("c", op.color)
                            .put("p", points)
                    }
                }
            )
        }
        return JSONObject().put("v", VERSION).put("ops", array).toString()
    }

    fun decode(text: String?): List<ImageOp>? {
        if (text.isNullOrBlank()) return null
        return try {
            val root = JSONObject(text)
            if (root.optInt("v") != VERSION) return null
            val array = root.getJSONArray("ops")
            if (array.length() > MAX_OPS) return null
            var points = 0
            val ops = ArrayList<ImageOp>(array.length())
            for (index in 0 until array.length()) {
                val op = decodeOp(array.getJSONObject(index)) ?: return null
                if (op is ImageOp.Stroke) {
                    points += op.size
                    if (points > MAX_POINTS) return null
                }
                ops += op
            }
            ops
        } catch (_: JSONException) {
            null
        }
    }

    private fun decodeOp(json: JSONObject): ImageOp? {
        return when (json.optString("t")) {
        "crop" -> {
            val rect = PxRect(
                json.getInt("l"), json.getInt("o"), json.getInt("r"), json.getInt("b")
            )
            if (rect.width < PxRect.MIN_CROP || rect.height < PxRect.MIN_CROP) null else ImageOp.Crop(rect)
        }

        "r90" -> json.optInt("n", 0).takeIf { it in 1..3 }?.let { ImageOp.RotateQuarter(it) }
        "rfree" -> {
            val degrees = json.getDouble("d").toFloat()
            if (!degrees.isFinite() || abs(degrees) > 180f) null else ImageOp.RotateFree(degrees)
        }

        "adj" -> {
            val brightness = json.getDouble("b").toFloat()
            val contrast = json.getDouble("c").toFloat()
            val saturation = json.getDouble("s").toFloat()
            val values = listOf(brightness, contrast, saturation)
            if (values.any { !it.isFinite() || abs(it) > 1f }) null else {
                ImageOp.Adjust(brightness, contrast, saturation)
            }
        }

        "stroke" -> {
            val points = json.getJSONArray("p")
            if (points.length() < 2 || points.length() % 2 != 0) return null
            val count = points.length() / 2
            val xs = FloatArray(count)
            val ys = FloatArray(count)
            for (index in 0 until count) {
                val x = points.getDouble(index * 2).toFloat()
                val y = points.getDouble(index * 2 + 1).toFloat()
                if (!x.isFinite() || !y.isFinite()) return null
                xs[index] = x
                ys[index] = y
            }
            val width = json.getDouble("w").toFloat()
            if (!width.isFinite() || width <= 0f || width > 4096f) return null
            ImageOp.Stroke(xs, ys, width, json.getInt("c"))
        }

            else -> null
        }
    }
}
