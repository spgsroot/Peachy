package ru.spgsroot.peachy.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockMergeTest {
    private val a = OcrBlock("A", Box(0.1f, 0.1f, 0.3f, 0.15f))
    private val b = OcrBlock("B", Box(0.1f, 0.2f, 0.3f, 0.25f))
    private val region = Region(Box(0.05f, 0.05f, 0.4f, 0.3f), 0.9f)
    private fun merge(blocks: List<OcrBlock>, regions: List<Region>) = mergeBlocks(PageScan(blocks, regions, 0.7f))

    @Test fun noRegionsReturnsBlocksUnchanged() {
        assertEquals(listOf(b, a), merge(listOf(b, a), emptyList()))
    }

    @Test fun singleBlockRegionLeavesBlockAlone() {
        assertEquals(listOf(a), merge(listOf(a), listOf(region)))
    }

    @Test fun twoLatinBlocksInOneRegionBecomeOneUnit() {
        assertEquals(listOf(OcrBlock("A B", Box(0.1f, 0.1f, 0.3f, 0.25f))), merge(listOf(a, b), listOf(region)))
    }

    @Test fun centeredEnglishWrapsInATallBalloonStayTopToBottom() {
        val top = OcrBlock("If Mash returns,", Box(0.10f, 0.10f, 0.34f, 0.15f))
        val middle = OcrBlock("tell her", Box(0.19f, 0.22f, 0.34f, 0.27f))
        val bottom = OcrBlock("I'll wait here.", Box(0.13f, 0.35f, 0.34f, 0.40f))
        val balloon = Region(Box(0.05f, 0.05f, 0.40f, 0.50f), 0.9f)
        assertEquals(
            OcrBlock("If Mash returns, tell her I'll wait here.", Box(0.10f, 0.10f, 0.34f, 0.40f)),
            merge(listOf(bottom, top, middle), listOf(balloon)).single()
        )
    }

    @Test fun aCoarseDetectorBoxCannotJoinTwoBalloonsOrBridgeAThirdText() {
        val first = a.copy(interior = Box(.08f, .08f, .32f, .16f))
        val second = b.copy(interior = Box(.08f, .19f, .32f, .27f))
        assertEquals(listOf(first, second), merge(listOf(first, second), listOf(region)))
        val middle = OcrBlock("caption", Box(.14f, .16f, .26f, .19f), Box(.12f, .155f, .28f, .195f))
        assertEquals(listOf(a, middle, b), merge(listOf(a, middle, b), listOf(region)))
    }

    @Test fun onlyCompleteDuplicateGroupsAreRemovedBeforeTranslation() {
        val left = OcrBlock("Left window", Box(.1f, .1f, .42f, .22f))
        val right = OcrBlock("Right window", Box(.58f, .1f, .9f, .22f))
        val group = OcrBlock("LEFT WINDOW! RIGHT WINDOW.", Box(.1f, .1f, .9f, .22f))
        fun keep(vararg blocks: OcrBlock) = withoutOcrComposites(blocks.toList(), false, { it.box }, { it.text })
        assertEquals(listOf(right, left), keep(right, group, left))
        val different = group.copy(text = "Left windox! Right window.")
        assertEquals(listOf(left, different, right), keep(left, different, right))
        val unrelated = group.copy(box = Box(0f, 0f, 1f, .5f))
        assertEquals(listOf(left, unrelated, right), keep(left, unrelated, right))
        assertEquals(listOf(group, left), keep(group, left))
    }

    @Test fun kanaBlocksJoinWithoutSpaceInColumnOrder() {
        val left = OcrBlock("あ", Box(0.1f, 0.1f, 0.15f, 0.4f))
        val right = OcrBlock("い", Box(0.2f, 0.1f, 0.25f, 0.4f))
        assertEquals("いあ", merge(listOf(left, right), listOf(Region(Box(0.05f, 0.05f, 0.3f, 0.5f), 1f))).single().text)
    }

    @Test fun tallRegionReadsEntireRightColumnBeforeTheLeft() {
        val left = OcrBlock("あ", Box(0.1f, 0.1f, 0.15f, 0.2f))
        val right = OcrBlock("い", Box(0.2f, 0.3f, 0.25f, 0.4f))
        assertEquals("いあ", merge(listOf(left, right), listOf(Region(Box(0.05f, 0.05f, 0.3f, 0.5f), 1f))).single().text)
    }

    @Test fun blockStraddlingTwoRegionsJoinsNeither() {
        val straddle = OcrBlock("middle", Box(0.25f, 0.1f, 0.75f, 0.2f))
        val left = OcrBlock("left", Box(0.1f, 0.1f, 0.2f, 0.2f))
        val right = OcrBlock("right", Box(0.8f, 0.1f, 0.9f, 0.2f))
        val blocks = listOf(left, straddle, right)
        assertEquals(blocks, merge(blocks, listOf(Region(Box(0f, 0f, 0.5f, 0.3f), 1f), Region(Box(0.5f, 0f, 1f, 0.3f), 1f))))
    }

    @Test fun blockWithSixtyPercentJoinsThatRegion() {
        val wide = OcrBlock("wide", Box(0f, 0.1f, 1f, 0.2f))
        val inside = OcrBlock("inside", Box(0.1f, 0.21f, 0.2f, 0.25f))
        assertEquals("wide inside", merge(listOf(wide, inside), listOf(Region(Box(0f, 0f, 0.6f, 0.3f), 1f))).single().text)
    }

    @Test fun tieGoesToSmallerRegion() {
        val outerOnly = OcrBlock("outer", Box(0.5f, 0.1f, 0.6f, 0.15f))
        val regions = listOf(Region(Box(0f, 0f, 0.7f, 0.4f), 1f), region)
        assertEquals(listOf(OcrBlock("A B", Box(0.1f, 0.1f, 0.3f, 0.25f)), outerOnly), merge(listOf(a, outerOnly, b), regions))
    }

    @Test fun greatestBlockShareWinsBeforeRegionSize() {
        val wide = OcrBlock("wide", Box(0f, 0.1f, 1f, 0.2f))
        val inside = OcrBlock("inside", Box(0.05f, 0.21f, 0.1f, 0.25f))
        val regions = listOf(Region(Box(0f, 0f, 0.6f, 0.3f), 1f), Region(Box(0f, 0f, 0.8f, 0.3f), 1f))
        assertEquals(listOf(wide, inside), merge(listOf(wide, inside), regions))
    }

    @Test fun hugeRegionIsNotMerged() {
        assertEquals(listOf(a, b), merge(listOf(a, b), listOf(Region(Box(0f, 0f, 1f, 0.5f), 1f))))
    }

    @Test fun regionWithSevenBlocksIsNotMerged() {
        val blocks = (0..6).map { OcrBlock("$it", Box(0.1f, 0.05f + it * 0.03f, 0.2f, 0.07f + it * 0.03f)) }
        assertEquals(blocks, merge(blocks, listOf(region)))
    }

    @Test fun sixBlocksAndRegionAtAreaLimitCanMerge() {
        val blocks = (0..5).map { OcrBlock("$it", Box(0.1f, 0.05f + it * 0.03f, 0.2f, 0.07f + it * 0.03f)) }
        assertEquals("0 1 2 3 4 5", merge(blocks, listOf(Region(Box(0f, 0f, 1f, 0.35f), 1f))).single().text)
    }

    @Test fun unitsKeepPageOrder() {
        val other = OcrBlock("other", Box(0.7f, 0.7f, 0.9f, 0.8f))
        assertEquals(listOf(OcrBlock("A B", Box(0.1f, 0.1f, 0.3f, 0.25f)), other), merge(listOf(b, other, a), listOf(region)))
    }

    @Test fun orderPageRowsAndColumns() {
        val row = listOf(0.6f, 0.1f, 0.35f).mapIndexed { i, x -> OcrBlock("$i", Box(x, 0.1f, x + 0.2f, 0.15f)) }
        assertEquals(listOf("1", "2", "0"), orderPage(row, 0.7f).map { it.text })
        assertEquals(listOf("0", "2", "1"), orderPage(row.map { it.copy(box = it.box.copy(bottom = 0.5f)) }, 0.7f).map { it.text })
    }

    @Test fun pixelAspectControlsVerticalReading() {
        val blocks = listOf(OcrBlock("left", Box(0.1f, 0.1f, 0.2f, 0.2f)), OcrBlock("right", Box(0.3f, 0.1f, 0.4f, 0.2f)))
        assertEquals(listOf("right", "left"), orderPage(blocks, 0.5f).map { it.text })
        assertEquals(listOf("left", "right"), orderPage(blocks, 1f).map { it.text })
    }
}
