package ru.spgsroot.peachy.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTilesTest {
    @Test fun pageUpToTwiceAsTallIsOneTile() {
        assertEquals(listOf(PageTile(0, 1600)), PageTiles.plan(800, 1600))
        assertEquals(listOf(PageTile(0, 1200), PageTile(401, 1601)), PageTiles.plan(800, 1601))
    }

    @Test fun stripTilesCoverEveryRowWithOverlap() {
        val tiles = PageTiles.plan(800, 12000)
        assertEquals(0, tiles.first().top)
        assertEquals(PageTile(10800, 12000), tiles.last())
        tiles.forEach { assertEquals(1200, it.bottom - it.top) }
        tiles.zipWithNext().forEach { (a, b) ->
            assertTrue(b.top > a.top)
            assertTrue(a.bottom - b.top >= 240)
        }
    }

    @Test fun tinyPageIsOneTile() {
        assertEquals(listOf(PageTile(0, 1)), PageTiles.plan(1, 1))
        assertEquals(listOf(PageTile(0, 0)), PageTiles.plan(0, 0))
    }

    @Test fun toPageMapsLocalBox() {
        val mapped = PageTile(1000, 2200).toPage(Box(0.1f, 0.5f, 0.2f, 1f), 12000)
        assertEquals(0.1f, mapped.left, 1e-6f)
        assertEquals(1600f / 12000, mapped.top, 1e-6f)
        assertEquals(0.2f, mapped.right, 1e-6f)
        assertEquals(2200f / 12000, mapped.bottom, 1e-6f)
    }

    @Test fun dedupeKeepsOnePerSeamPair() {
        val first = OcrBlock("first", Box(0f, 0f, 0.4f, 0.2f))
        val half = OcrBlock("half", Box(0.2f, 0f, 0.6f, 0.2f))
        val far = OcrBlock("far", Box(0.8f, 0.8f, 1f, 1f))
        assertEquals(listOf(half, far), PageTiles.dedupeSeams(listOf(first, far, half), { it.box }, { _, b -> b }))
        val below = OcrBlock("below", Box(0.204f, 0f, 0.604f, 0.2f))
        assertEquals(listOf(first, below, far), PageTiles.dedupeSeams(listOf(first, below, far), { it.box }, { a, _ -> a }))
    }

    @Test fun seamOverlapUsesTheSmallerAreaAndGreedyReplacement() {
        val wide = OcrBlock("wide", Box(0f, 0f, 1f, 0.2f))
        val small = OcrBlock("small", Box(0.1f, 0f, 0.2f, 0.2f))
        val later = OcrBlock("later", Box(0.15f, 0f, 0.25f, 0.2f))
        assertEquals(listOf(later), PageTiles.dedupeSeams(listOf(wide, small, later), { it.box }, { _, b -> b }))
    }
}
