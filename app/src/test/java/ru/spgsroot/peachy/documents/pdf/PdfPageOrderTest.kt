package ru.spgsroot.peachy.documents.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageOrderTest {

    @Test
    fun moveUpSwapsWithThePreviousPage() {
        assertEquals(listOf(1, 0, 2, 3, 4), movedPageOrder(5, 1, 0).toList())
    }

    @Test
    fun moveDownSwapsWithTheNextPage() {
        assertEquals(listOf(1, 2, 0, 3, 4), movedPageOrder(5, 0, 2).toList())
    }

    @Test
    fun moveAcrossManyPagesKeepsEveryPageOnce() {
        val order = movedPageOrder(6, 0, 5)
        assertEquals(listOf(1, 2, 3, 4, 5, 0), order.toList())
        assertEquals((0..5).toSet(), order.toSet())
    }

    @Test
    fun identicalAndOutOfRangeMovesLeaveTheOrderAlone() {
        val identity = listOf(0, 1, 2)
        assertEquals(identity, movedPageOrder(3, 1, 1).toList())
        assertEquals(identity, movedPageOrder(3, -1, 0).toList())
        assertEquals(identity, movedPageOrder(3, 0, 3).toList())
    }
}
