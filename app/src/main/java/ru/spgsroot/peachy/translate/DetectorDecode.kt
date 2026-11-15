package ru.spgsroot.peachy.translate

const val DETECTOR_SIDE = 960
const val DETECTOR_MIN_SCORE = 0.25f

/** Decodes the end-to-end YOLO output; the model already selects its 300 rows (no NMS). */
object DetectorDecode {
    fun yolo(rows: FloatArray, padX: Int, padY: Int, scale: Double, width: Int, height: Int): List<Region> = buildList {
        var offset = 0
        while (offset + 5 < rows.size) {
            val score = rows[offset + 4]
            if (score >= DETECTOR_MIN_SCORE) {
                val left = ((rows[offset] - padX) / scale / width).toFloat().coerceIn(0f, 1f)
                val top = ((rows[offset + 1] - padY) / scale / height).toFloat().coerceIn(0f, 1f)
                val right = ((rows[offset + 2] - padX) / scale / width).toFloat().coerceIn(0f, 1f)
                val bottom = ((rows[offset + 3] - padY) / scale / height).toFloat().coerceIn(0f, 1f)
                if (left < right && top < bottom) add(Region(Box(left, top, right, bottom), score))
            }
            offset += 6
        }
    }
}
