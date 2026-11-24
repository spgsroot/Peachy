package ru.spgsroot.peachy.ui.manga

import androidx.compose.ui.geometry.Rect

internal fun boundedPlateRect(original: Rect, page: Rect): Rect? {
    val rect = Rect(
        maxOf(original.left, page.left),
        maxOf(original.top, page.top),
        minOf(original.right, page.right),
        minOf(original.bottom, page.bottom),
    )
    return rect.takeIf {
        it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
            it.width > 0f && it.height > 0f
    }
}

/** Subtract every other source. Keep all maximal strips until the text has been measured. */
internal inline fun forEachFreeRect(rects: List<Rect>, index: Int, visit: (Rect) -> Unit) {
    val original = rects[index]
    var pieces: ArrayList<Rect>? = null
    for (i in rects.indices) {
        val other = rects[i]
        if (i == index || !original.overlaps(other)) continue
        val free = pieces ?: arrayListOf(original).also { pieces = it }
        for (j in free.lastIndex downTo 0) {
            val r = free[j]
            if (!r.overlaps(other)) continue
            free.removeAt(j)
            if (other.left > r.left) free.add(r.copy(right = other.left))
            if (other.right < r.right) free.add(r.copy(left = other.right))
            if (other.top > r.top) free.add(r.copy(bottom = other.top))
            if (other.bottom < r.bottom) free.add(r.copy(top = other.bottom))
        }
        if (free.isEmpty()) return
        for (j in free.lastIndex downTo 0) {
            val r = free[j]
            if (free.indices.any { k -> k != j && free[k].let {
                it.left <= r.left && it.top <= r.top && it.right >= r.right && it.bottom >= r.bottom
            } }) free.removeAt(j)
        }
    }
    val free = pieces
    if (free == null) visit(original) else for (r in free) visit(r)
}
