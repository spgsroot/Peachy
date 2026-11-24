package ru.spgsroot.peachy.documents

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.spgsroot.peachy.documents.ui.filterDocuments
import ru.spgsroot.peachy.ui.gallery.MediaFilter
import ru.spgsroot.peachy.ui.gallery.MediaSort

class DocumentListFilterTest {
    private val rows = listOf(
        doc("b", "zeta", 30, 2),
        doc("a", "Alpha", 10, 2),
        doc("c", "mid", 20, 1),
    )

    @Test fun newestMatchesStoredOrderAndBoundsStayInclusive() {
        assertEquals(listOf("a", "b", "c"), filterDocuments(rows, MediaFilter()).map { it.id })
        assertEquals(listOf("c"), filterDocuments(rows, MediaFilter(minBytes = 20, maxBytes = 20)).map { it.id })
        assertEquals(listOf("a", "c", "b"), filterDocuments(rows, MediaFilter(sort = MediaSort.NameAscending)).map { it.id })
    }

    private fun doc(id: String, name: String, bytes: Long, updatedAt: Long) = DocumentSummary(
        id, name, "pdf", DocumentKind.PDF, bytes, updatedAt, false,
    )
}
