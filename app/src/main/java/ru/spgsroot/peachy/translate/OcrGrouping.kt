package ru.spgsroot.peachy.translate

internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class RawOcrLine(val text: String, val rect: PixelRect, val confidence: Float = 1f)
internal data class RawOcrBlock(val lines: List<RawOcrLine>, val rect: PixelRect)

/**
 * Keeps ML Kit's block boundaries (one bubble is not split into words). Lines below 0.3 confidence,
 * blank lines and symbol-only lines are discarded; a single letter, ideograph or digit is retained.
 * Boxes are clipped to the image and zero-area boxes are discarded.
 *
 * Reading order is geometric, not based on page-height buckets: scan blocks by top edge and form
 * rows when their vertical intervals overlap by at least half the shorter interval. Every member
 * must overlap every existing member of a row, so a tall bridging box cannot chain unrelated rows.
 * Rows read top-to-bottom. Within a ja/zh row, form columns by the same rule on horizontal
 * intervals, read columns right-to-left and their members top-to-bottom. Other languages read each
 * row left-to-right. Each block chooses columns when most retained lines are tall (height >=
 * 1.5 × width), otherwise rows. Joining omits spaces only for mostly kana/Han text. Auto chooses
 * page columns when most retained blocks are vertical; geometry cannot infer panel borders.
 */
internal fun groupOcrBlocks(
    blocks: List<RawOcrBlock>,
    imageWidth: Int,
    imageHeight: Int,
    source: String
): List<OcrBlock> {
    require(imageWidth > 0 && imageHeight > 0)
    var verticalBlocks = 0
    val cleaned = blocks.mapNotNull { block ->
        val rect = PixelRect(
            block.rect.left.coerceIn(0, imageWidth), block.rect.top.coerceIn(0, imageHeight),
            block.rect.right.coerceIn(0, imageWidth), block.rect.bottom.coerceIn(0, imageHeight)
        )
        if (rect.left >= rect.right || rect.top >= rect.bottom) return@mapNotNull null
        val lines = block.lines.mapNotNull { line ->
            val text = line.text.trim()
            if (line.confidence < 0.3f || !text.codePoints().anyMatch { Character.isLetterOrDigit(it) }) null
            else line.copy(text = text)
        }
        if (lines.isEmpty()) return@mapNotNull null
        val vertical = lines.count { it.rect.bottom - it.rect.top >= 1.5f * (it.rect.right - it.rect.left) } * 2 > lines.size
        if (vertical) verticalBlocks++
        val ordered = if (vertical) columns(lines) { it.rect } else rows(lines) { it.rect }.flatten()
        RawOcrBlock(listOf(RawOcrLine(ordered.joinToString(joinSeparator(lines.map { it.text })) { it.text }, rect)), rect)
    }
    val vertical = source == "ja" || source == "zh" || (source == "auto" && verticalBlocks * 2 > cleaned.size)
    return rows(cleaned) { it.rect }.flatMap { row ->
        if (vertical) columns(row) { it.rect } else row.sortedWith(compareBy({ it.rect.left }, { it.rect.top }))
    }.map { block ->
        val rect = block.rect
        OcrBlock(block.lines.single().text, Box(
            rect.left.toFloat() / imageWidth, rect.top.toFloat() / imageHeight,
            rect.right.toFloat() / imageWidth, rect.bottom.toFloat() / imageHeight
        ))
    }
}

internal fun joinSeparator(texts: List<String>): String {
    var letters = 0
    var cjk = 0
    for (text in texts) {
        val points = text.codePoints().iterator()
        while (points.hasNext()) {
            val point = points.nextInt()
            if (!Character.isLetter(point)) continue
            letters++
            val script = Character.UnicodeScript.of(point)
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) cjk++
        }
    }
    return if (cjk * 2 > letters) "" else " "
}

private fun overlap(aStart: Double, aEnd: Double, bStart: Double, bEnd: Double): Boolean {
    val shorter = minOf(aEnd - aStart, bEnd - bStart)
    return shorter > 0 && (minOf(aEnd, bEnd) - maxOf(aStart, bStart)) * 2 >= shorter
}

internal fun <T> rows(items: List<T>, rect: (T) -> PixelRect): List<List<T>> =
    rows(items, { rect(it).left.toDouble() }, { rect(it).top.toDouble() }, { rect(it).bottom.toDouble() })

// Coordinate overloads also handle fractional page boxes without quantizing them to pixel rows.
internal fun <T> rows(items: List<T>, left: (T) -> Double, top: (T) -> Double, bottom: (T) -> Double): List<List<T>> =
    bands(items.sortedWith(compareBy(top, left))) { a, b ->
        overlap(top(a), bottom(a), top(b), bottom(b))
    }.map { row -> row.sortedWith(compareBy(left, top)) }

internal fun <T> columns(items: List<T>, rect: (T) -> PixelRect): List<T> =
    columns(items, { rect(it).left.toDouble() }, { rect(it).top.toDouble() }, { rect(it).right.toDouble() })

internal fun <T> columns(items: List<T>, left: (T) -> Double, top: (T) -> Double, right: (T) -> Double): List<T> =
    bands(items.sortedWith(compareByDescending(right).thenBy(top))) { a, b ->
        overlap(left(a), right(a), left(b), right(b))
    }.flatMap { column -> column.sortedWith(compareBy(top, left)) }

private fun <T> bands(items: List<T>, overlaps: (T, T) -> Boolean): List<List<T>> {
    val groups = mutableListOf<MutableList<T>>()
    for (item in items) {
        val group = groups.firstOrNull { members -> members.all { overlaps(it, item) } }
        if (group == null) groups.add(mutableListOf(item)) else group.add(item)
    }
    return groups
}
