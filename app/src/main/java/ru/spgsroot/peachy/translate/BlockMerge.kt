package ru.spgsroot.peachy.translate

data class Region(val box: Box, val score: Float)
data class PageScan(val blocks: List<OcrBlock>, val regions: List<Region>, val aspect: Float)

const val ASSIGN_SHARE = 0.6f
const val MAX_JOIN = 6
const val MAX_REGION_AREA = 0.35f

private fun Box.area(): Float = (right - left) * (bottom - top)

internal fun blockShare(block: Box, region: Box): Float {
    val area = block.area()
    if (area <= 0f) return 0f
    val intersection = (minOf(block.right, region.right) - maxOf(block.left, region.left)).coerceAtLeast(0f) *
        (minOf(block.bottom, region.bottom) - maxOf(block.top, region.top)).coerceAtLeast(0f)
    return intersection / area
}

private val ocrSeparators = Regex("[\\p{P}\\p{Z}\\s]+")

/** A whole-page OCR group can be read again as several tighter detector crops. Mere nesting is not evidence. */
internal inline fun <T> withoutOcrComposites(
    items: List<T>, allowVariant: Boolean, box: (T) -> Box?, text: (T) -> String
): List<T> {
    if (items.size < 3) return items
    var kept: ArrayList<T>? = null
    for ((i, item) in items.withIndex()) {
        val outer = box(item)
        var parts: ArrayList<T>? = null
        if (outer != null) for (j in items.indices) {
            if (i == j) continue
            val b = box(items[j]) ?: continue
            if (b.area() >= outer.area() || blockShare(b, outer) < 0.95f) continue
            val found = parts ?: ArrayList<T>().also { parts = it }
            if (found.any { blockShare(b, box(it)!!) > 0f }) { parts = null; break }
            found.add(items[j])
        }
        val group = parts
        val composite = outer != null && group != null && group.size >= 2 && run {
            val dx = (outer.right - outer.left) * 0.05f
            val dy = (outer.bottom - outer.top) * 0.05f
            kotlin.math.abs(group.minOf { box(it)!!.left } - outer.left) <= dx &&
                kotlin.math.abs(group.maxOf { box(it)!!.right } - outer.right) <= dx &&
                kotlin.math.abs(group.minOf { box(it)!!.top } - outer.top) <= dy &&
                kotlin.math.abs(group.maxOf { box(it)!!.bottom } - outer.bottom) <= dy &&
                matchesOcrParts(text(item), group.mapTo(ArrayList(group.size)) { text(it).lowercase().replace(ocrSeparators, "") }, allowVariant)
        }
        if (composite) {
            if (kept == null) kept = ArrayList<T>(items.size - 1).also { it.addAll(items.subList(0, i)) }
        } else kept?.add(item)
    }
    return kept ?: items
}

private fun matchesOcrParts(text: String, parts: ArrayList<String>, allowVariant: Boolean): Boolean {
    val whole = text.lowercase().replace(ocrSeparators, "")
    if (parts.any { it.isEmpty() } || parts.sumOf { it.length } != whole.length) return false
    var at = 0
    // Only the overlay may set aside an alternate reading: its original translation stays in the panel.
    var typo = allowVariant && whole.length >= 20
    while (parts.isNotEmpty()) {
        var match = parts.indexOfFirst { whole.startsWith(it, at) }
        if (match < 0) {
            if (!typo) return false
            match = parts.indexOfFirst { p -> p.length >= 4 && at + p.length <= whole.length &&
                p.indices.count { whole[at + it] != p[it] } == 1 }
            if (match < 0) return false
            typo = false
        }
        at += parts.removeAt(match).length
    }
    return true
}

internal fun List<TranslatedLine>.distinctSourceRegions(): List<TranslatedLine> =
    withoutOcrComposites(this, allowVariant = true, { if (it.kind == LineKind.Sfx) null else it.box }, { it.text })

/** Assign by greatest covered share, then smallest region; preserve the earliest member's slot. */
fun mergeBlocks(scan: PageScan): List<OcrBlock> {
    if (scan.regions.isEmpty()) return scan.blocks
    val members = List(scan.regions.size) { mutableListOf<Int>() }
    scan.blocks.forEachIndexed { index, block ->
        // A coarse text detector rectangle must not rejoin separately enclosed balloons.
        if (block.interior != null) return@forEachIndexed
        var best = -1
        var bestShare = ASSIGN_SHARE
        scan.regions.forEachIndexed { regionIndex, region ->
            val share = blockShare(block.box, region.box)
            if (share >= bestShare && (best < 0 || share > bestShare || region.box.area() < scan.regions[best].box.area())) {
                best = regionIndex
                bestShare = share
            }
        }
        if (best >= 0) members[best].add(index)
    }
    val replacements = arrayOfNulls<OcrBlock>(scan.blocks.size)
    val removed = BooleanArray(scan.blocks.size)
    members.forEachIndexed { index, indices ->
        val region = scan.regions[index]
        if (indices.size !in 2..MAX_JOIN || region.box.area() > MAX_REGION_AREA) return@forEachIndexed
        val blocks = indices.map { scan.blocks[it] }
        val bounds = Box(blocks.minOf { it.box.left }, blocks.minOf { it.box.top }, blocks.maxOf { it.box.right }, blocks.maxOf { it.box.bottom })
        if (scan.blocks.any { it !in blocks && blockShare(it.box, bounds) > 0f }) return@forEachIndexed
        val vertical = region.box.bottom - region.box.top >= 1.5f * (region.box.right - region.box.left) * scan.aspect
        val ordered = if (vertical) columns(blocks, { it.box.left.toDouble() }, { it.box.top.toDouble() }, { it.box.right.toDouble() })
            else rows(blocks, { it.box.left.toDouble() }, { it.box.top.toDouble() }, { it.box.bottom.toDouble() }).flatten()
        val unit = OcrBlock(ordered.joinToString(joinSeparator(ordered.map { it.text })) { it.text }, bounds)
        replacements[indices.first()] = unit
        indices.drop(1).forEach { removed[it] = true }
    }
    return scan.blocks.mapIndexedNotNull { index, block -> if (removed[index]) null else replacements[index] ?: block }
}

fun orderPage(blocks: List<OcrBlock>, aspect: Float): List<OcrBlock> = readingOrder(
    blocks, blocks.count { it.box.bottom - it.box.top >= 1.5f * (it.box.right - it.box.left) * aspect } * 2 > blocks.size
)

private fun readingOrder(blocks: List<OcrBlock>, vertical: Boolean): List<OcrBlock> =
    rows(blocks, { it.box.left.toDouble() }, { it.box.top.toDouble() }, { it.box.bottom.toDouble() }).flatMap { row ->
        if (vertical) columns(row, { it.box.left.toDouble() }, { it.box.top.toDouble() }, { it.box.right.toDouble() }) else row
    }
