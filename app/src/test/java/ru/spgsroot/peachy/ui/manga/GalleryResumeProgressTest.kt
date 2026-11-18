package ru.spgsroot.peachy.ui.manga

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.spgsroot.peachy.data.db.ReadHistoryEntity

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryResumeProgressTest {
    private val gallery = 990001
    private val otherGallery = 990002
    private val pageCount = 12

    @Test
    fun returningFromTheReaderRefreshesTheSameGalleryLabel() = runTest {
        val history = MutableStateFlow(listOf(row(gallery, page = 5)))
        val pages = mutableListOf<Int?>()
        val tracker = GalleryResumeProgress(history)
        tracker.track(backgroundScope, gallery) { pages += it }
        runCurrent()

        // The reader writes while the detail is still on the back stack.
        history.value = listOf(row(gallery, page = 8))
        runCurrent()

        assertEquals(listOf<Int?>(5, 8), pages)
    }

    @Test
    fun missingHistoryAndTheFirstPageDoNotOfferAContinue() = runTest {
        val history = MutableStateFlow(emptyList<ReadHistoryEntity>())
        val pages = mutableListOf<Int?>()
        val tracker = GalleryResumeProgress(history)
        tracker.track(backgroundScope, gallery) { pages += it }
        runCurrent()
        assertNull(pages.single())

        history.value = listOf(row(gallery, page = 0))
        runCurrent()
        assertEquals(0, pages.last())
        assertNull(resumePage(pages.last(), pageCount))
    }

    @Test
    fun aLateRowFromThePreviousGalleryNeverReachesTheNewOne() = runTest {
        val history = MutableStateFlow(listOf(row(gallery, page = 5)))
        val first = mutableListOf<Int?>()
        val second = mutableListOf<Int?>()
        val tracker = GalleryResumeProgress(history)
        tracker.track(backgroundScope, gallery) { first += it }
        runCurrent()

        tracker.track(backgroundScope, otherGallery) { second += it }
        runCurrent()
        history.value = listOf(row(gallery, page = 9), row(otherGallery, page = 2))
        runCurrent()

        assertEquals(listOf<Int?>(5), first)
        assertEquals(listOf<Int?>(null, 2), second)
    }

    @Test
    fun storedIndexStaysZeroBasedAndOutOfRangeShrinksAreDropped() {
        // Index 2 is page 3 in the UI; the extra +1 belongs to the label, not to the stored value.
        assertEquals(2, resumePage(2, pageCount))
        assertEquals(11, resumePage(11, pageCount))
        assertNull(resumePage(30, pageCount))
        assertNull(resumePage(0, pageCount))
        assertNull(resumePage(5, 0))
        assertNull(resumePage(null, pageCount))
    }

    private fun row(id: Int, page: Int) = ReadHistoryEntity(
        galleryId = id,
        title = "PEACHY FIXTURE $id",
        thumbnailPath = "",
        page = page,
        pageCount = pageCount,
        readAt = 1L
    )
}
