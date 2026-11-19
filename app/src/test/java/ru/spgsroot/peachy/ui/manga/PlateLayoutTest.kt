package ru.spgsroot.peachy.ui.manga

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class PlateLayoutTest {
    @Test fun largestFittingSpFindsBoundaryAndHandlesExtremes() {
        assertTrue(largestFittingSp(TextProbe { sp, _, _ -> sp <= 10.3f }, 100f, 100f) in 10.2f..10.3f)
        assertEquals(22f, largestFittingSp(TextProbe { _, _, _ -> true }, 10f, 10f))
        assertEquals(0f, largestFittingSp(TextProbe { _, _, _ -> false }, 10f, 10f))
    }
    @Test fun largestFittingSpRejectsEmptyAreaAndInvertedRange() {
        val probe = TextProbe { _, _, _ -> error("Invalid bounds must not be measured") }
        assertEquals(0f, largestFittingSp(probe, 0f, 20f))
        assertEquals(0f, largestFittingSp(probe, 20f, -1f))
        assertEquals(0f, largestFittingSp(probe, 20f, 20f, minSp = 6f, maxSp = 5f))
    }
    @Test fun plateBoundsOnlyIntersectOriginalAndPage() {
        val page = Rect(20f, 10f, 220f, 170f)
        val original = Rect(60f, 40f, 100f, 65f)
        assertEquals(original, boundedPlateRect(original, page))
        assertEquals(Rect(20f, 10f, 100f, 65f), boundedPlateRect(Rect(0f, 0f, 100f, 65f), page))
        assertNull(boundedPlateRect(Rect(220f, 20f, 230f, 30f), page))
        assertNull(boundedPlateRect(Rect(100f, 20f, 90f, 30f), page))
        assertNull(boundedPlateRect(Rect(Float.NaN, 20f, 100f, 30f), page))
    }
    @Test fun capToMedianPreservesSmallAndZeroSizes() {
        assertEquals(listOf(10f,10f,10f,12.5f), capToMedian(listOf(10f,10f,10f,30f)))
        assertEquals(listOf(0f,12f,12f), capToMedian(listOf(0f,12f,12f)))
        assertEquals(emptyList<Float>(), capToMedian(emptyList()))
    }
    @Test fun overlapTrimKeepsFirstAndRemainsInsideOriginal() {
        val input = listOf(Rect(80f,0f,180f,100f), Rect(0f,0f,100f,100f))
        val output = resolveOverlaps(input)
        assertEquals(input[0], output[0]); assertEquals(Rect(0f,0f,80f,100f), output[1])
        assertEquals(0, overlappingPairs(output))
        output.zip(input).forEach { (a,b) -> assertTrue(a.left>=b.left && a.top>=b.top && a.right<=b.right && a.bottom<=b.bottom) }
    }
    @Test fun disjointAndOverHalfLossStayUnchanged() {
        val disjoint = listOf(Rect(0f,0f,10f,10f),Rect(20f,0f,30f,10f))
        assertEquals(disjoint, resolveOverlaps(disjoint))
        val heavy = listOf(Rect(0f,0f,100f,100f),Rect(20f,0f,120f,100f))
        assertEquals(heavy,resolveOverlaps(heavy)); assertEquals(1,overlappingPairs(heavy))
    }
    @Test fun longestUnbreakableWordAllowsCjkWrapping() {
        assertEquals("ccc",longestUnbreakableWord("a bb ccc"))
        assertEquals("",longestUnbreakableWord("这是一个很长的句子"))
        assertEquals("loudly",longestUnbreakableWord("say 你好 loudly"))
    }
}
