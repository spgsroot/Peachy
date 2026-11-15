package ru.spgsroot.peachy.translate

/** Full-width rows [top, bottom) in the original page. */
data class PageTile(val top: Int, val bottom: Int)

object PageTiles {
    const val STRIP_ASPECT = 2.0
    const val TILE_ASPECT = 1.5
    const val OVERLAP = 0.2

    fun plan(width: Int, height: Int): List<PageTile> {
        if (width <= 0 || height <= width.toDouble() * STRIP_ASPECT) return listOf(PageTile(0, height))
        val tileHeight = (width * TILE_ASPECT).toInt().coerceAtLeast(1).coerceAtMost(height)
        val step = (tileHeight * (1 - OVERLAP)).toInt().coerceAtLeast(1)
        val tiles = mutableListOf<PageTile>()
        var top = 0
        while (true) {
            tiles.add(PageTile(top, top + tileHeight))
            if (top == height - tileHeight) return tiles
            top = minOf(top.toLong() + step, (height - tileHeight).toLong()).toInt()
        }
    }

    /** Greedy seam matching; replacements retain the first occurrence's position. */
    fun <T> dedupeSeams(items: List<T>, box: (T) -> Box, pick: (T, T) -> T): List<T> {
        val kept = mutableListOf<T>()
        for (item in items) {
            val b = box(item)
            val index = kept.indexOfFirst { existing ->
                val a = box(existing)
                val smaller = minOf((a.right - a.left) * (a.bottom - a.top), (b.right - b.left) * (b.bottom - b.top))
                val intersection = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
                    (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
                smaller > 0f && intersection >= smaller * 0.5f
            }
            if (index < 0) kept.add(item) else kept[index] = pick(kept[index], item)
        }
        return kept
    }
}

fun PageTile.toPage(local: Box, pageHeight: Int): Box {
    val height = bottom - top
    return Box(local.left, (top + local.top * height) / pageHeight, local.right, (top + local.bottom * height) / pageHeight)
}
