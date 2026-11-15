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

/** Assign by greatest covered share, then smallest region; preserve the earliest member's slot. */
fun mergeBlocks(scan: PageScan): List<OcrBlock> {
    if (scan.regions.isEmpty()) return scan.blocks
    val members = List(scan.regions.size) { mutableListOf<Int>() }
    scan.blocks.forEachIndexed { index, block ->
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
        val vertical = region.box.bottom - region.box.top >= 1.5f * (region.box.right - region.box.left) * scan.aspect
        val ordered = if (vertical) columns(blocks, { it.box.left.toDouble() }, { it.box.top.toDouble() }, { it.box.right.toDouble() })
            else rows(blocks, { it.box.left.toDouble() }, { it.box.top.toDouble() }, { it.box.bottom.toDouble() }).flatten()
        val unit = OcrBlock(ordered.joinToString(joinSeparator(ordered.map { it.text })) { it.text }, Box(
            blocks.minOf { it.box.left }, blocks.minOf { it.box.top }, blocks.maxOf { it.box.right }, blocks.maxOf { it.box.bottom }
        ))
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
