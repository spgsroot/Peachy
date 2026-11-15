package ru.spgsroot.peachy.translate

import org.junit.Assert.*
import org.junit.Test

class DetectorDecodeTest {
    @Test fun mapsLetterboxedBoxToPageFractions() {
        val region = DetectorDecode.yolo(floatArrayOf(224f, 192f, 480f, 768f, 0.9f, 0f), 160, 0, 0.8, 800, 1200).single()
        assertBox(Box(0.1f, 0.2f, 0.5f, 0.8f), region.box)
        assertEquals(0.9f, region.score, 0f)
    }

    @Test fun dropsLowScoreAndEmptyBoxes() {
        val rows = floatArrayOf(
            0f, 0f, 400f, 400f, 0.249f, 0f,
            200f, 200f, 200f, 400f, 0.9f, 0f,
            400f, 200f, 200f, 400f, 0.9f, 0f,
            -100f, -100f, -50f, -50f, 0.9f, 0f,
            0f, 0f, 400f, 400f, 0.25f, 0f
        )
        val regions = DetectorDecode.yolo(rows, 0, 0, 1.0, 800, 1200)
        assertEquals(listOf(Region(Box(0f, 0f, 0.5f, 1f / 3), 0.25f)), regions)
    }

    @Test fun clampsToUnitSquare() {
        val box = DetectorDecode.yolo(floatArrayOf(-100f, -200f, 2000f, 1800f, 1f, 0f), 160, 0, 0.8, 800, 1200).single().box
        assertEquals(Box(0f, 0f, 1f, 1f), box)
    }

    @Test fun tileOffsetMappingMatchesToPage() {
        val local = DetectorDecode.yolo(floatArrayOf(224f, 192f, 480f, 768f, 0.9f, 0f), 160, 0, 0.8, 800, 1200).single().box
        assertBox(Box(0.1f, 1240f / 12000, 0.5f, 1960f / 12000), PageTile(1000, 2200).toPage(local, 12000))
    }

    private fun assertBox(expected: Box, actual: Box) {
        assertEquals(expected.left, actual.left, 1e-6f)
        assertEquals(expected.top, actual.top, 1e-6f)
        assertEquals(expected.right, actual.right, 1e-6f)
        assertEquals(expected.bottom, actual.bottom, 1e-6f)
    }
}
