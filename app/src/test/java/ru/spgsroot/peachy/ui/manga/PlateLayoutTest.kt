package ru.spgsroot.peachy.ui.manga

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class PlateLayoutTest {
    @Test fun plateBoundsOnlyIntersectOriginalAndPage() {
        val page = Rect(20f, 10f, 220f, 170f)
        val original = Rect(60f, 40f, 100f, 65f)
        assertEquals(original, boundedPlateRect(original, page))
        assertEquals(Rect(20f, 10f, 100f, 65f), boundedPlateRect(Rect(0f, 0f, 100f, 65f), page))
        assertNull(boundedPlateRect(Rect(220f, 20f, 230f, 30f), page))
        assertNull(boundedPlateRect(Rect(100f, 20f, 90f, 30f), page))
        assertNull(boundedPlateRect(Rect(Float.NaN, 20f, 100f, 30f), page))
    }
    @Test fun neitherPlateCoversAnotherSourceEvenWhenMostOfTheBoxesOverlap() {
        val input = listOf(Rect(0f, 0f, 100f, 100f), Rect(20f, 0f, 120f, 100f))
        fun free(rects: List<Rect>, index: Int) = buildList { forEachFreeRect(rects, index) { add(it) } }
        assertEquals(listOf(Rect(0f, 0f, 20f, 100f)), free(input, 0))
        assertEquals(listOf(Rect(100f, 0f, 120f, 100f)), free(input, 1))
        assertTrue(free(listOf(input[0], input[0]), 0).isEmpty())
    }
}
