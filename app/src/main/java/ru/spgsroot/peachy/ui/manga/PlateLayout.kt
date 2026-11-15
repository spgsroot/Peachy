package ru.spgsroot.peachy.ui.manga

import androidx.compose.ui.geometry.Rect

internal fun interface TextProbe {
    fun fits(sp: Float, widthPx: Float, heightPx: Float): Boolean
}

internal fun largestFittingSp(probe: TextProbe, widthPx: Float, heightPx: Float, minSp: Float = 6f, maxSp: Float = 22f): Float {
    if (!probe.fits(minSp, widthPx, heightPx)) return 0f
    if (probe.fits(maxSp, widthPx, heightPx)) return maxSp
    var low = minSp
    var high = maxSp
    repeat(6) {
        val mid = (low + high) / 2f
        if (probe.fits(mid, widthPx, heightPx)) low = mid else high = mid
    }
    return low
}

internal fun capToMedian(sizes: List<Float>, factor: Float = 1.25f): List<Float> {
    val positive = sizes.filter { it > 0f }.sorted()
    if (positive.isEmpty()) return sizes
    val middle = positive.size / 2
    val median = if (positive.size % 2 == 0) (positive[middle - 1] + positive[middle]) / 2f else positive[middle]
    return sizes.map { minOf(it, median * factor) }
}

internal fun resolveOverlaps(rects: List<Rect>): List<Rect> {
    val result = ArrayList<Rect>(rects.size)
    for (original in rects) {
        var rect = original
        for (earlier in result) {
            val overlapX = minOf(rect.right, earlier.right) - maxOf(rect.left, earlier.left)
            val overlapY = minOf(rect.bottom, earlier.bottom) - maxOf(rect.top, earlier.top)
            if (overlapX <= 0f || overlapY <= 0f) continue
            val trimmed = if (overlapX <= overlapY) {
                if (rect.center.x < earlier.center.x) rect.copy(right = earlier.left) else rect.copy(left = earlier.right)
            } else {
                if (rect.center.y < earlier.center.y) rect.copy(bottom = earlier.top) else rect.copy(top = earlier.bottom)
            }
            if (trimmed.width >= original.width / 2f && trimmed.height >= original.height / 2f) rect = trimmed
        }
        result.add(rect)
    }
    return result
}

internal fun overlappingPairs(rects: List<Rect>): Int = rects.indices.sumOf { i ->
    (i + 1 until rects.size).count { j ->
        minOf(rects[i].right, rects[j].right) > maxOf(rects[i].left, rects[j].left) &&
            minOf(rects[i].bottom, rects[j].bottom) > maxOf(rects[i].top, rects[j].top)
    }
}

internal fun longestUnbreakableWord(text: String): String = text.split(Regex("\\s+"))
    .filterNot { token -> token.any { it in '\u3400'..'\u9fff' || it in '\u3040'..'\u30ff' || it in '\u1100'..'\u11ff' || it in '\u3130'..'\u318f' || it in '\uac00'..'\ud7af' || it in '\uf900'..'\ufaff' } }
    .maxByOrNull { it.length }.orEmpty()
