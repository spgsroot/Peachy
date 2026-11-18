package ru.spgsroot.peachy.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPositionTest {
    @Test
    fun deliberatePageWinsAndStartsAtTheTop() {
        val start = readerStart(explicitPage = 3, saved = ReaderPosition(9, 0.5f), pageCount = 10)
        assertEquals(ReaderPosition(3, 0f), start)
    }

    @Test
    fun staleDeliberatePageFallsBackToDurableProgress() {
        val saved = ReaderPosition(9, 0.5f)
        assertEquals(saved, readerStart(explicitPage = 30, saved = saved, pageCount = 10))
        assertEquals(ReaderPosition(0, 0f), readerStart(explicitPage = 30, saved = null, pageCount = 10))
    }

    @Test
    fun durablePageAndOffsetResumeWhenTheRouteHasNoPage() {
        assertEquals(
            ReaderPosition(4, 0.25f),
            readerStart(explicitPage = null, saved = ReaderPosition(4, 0.25f), pageCount = 10)
        )
    }

    @Test
    fun removedPagesClampToTheLastPageAndDropTheOffset() {
        assertEquals(
            ReaderPosition(9, 0f),
            readerStart(explicitPage = null, saved = ReaderPosition(12, 0.5f), pageCount = 10)
        )
        assertEquals(ReaderPosition(3, 0.5f), clampPosition(ReaderPosition(3, 0.5f), 10))
        assertEquals(ReaderPosition(9, 0f), clampPosition(ReaderPosition(12, 0.5f), 10))
        assertEquals(ReaderPosition(0, 0f), clampPosition(ReaderPosition(3, 0.5f), 0))
    }

    @Test
    fun negativeOrMissingProgressStartsFirst() {
        assertEquals(ReaderPosition(0, 0f), readerStart(null, ReaderPosition(-3, 0.5f), 10))
        assertEquals(ReaderPosition(0, 0f), readerStart(null, null, 10))
        assertEquals(ReaderPosition(0, 0f), readerStart(0, ReaderPosition(5, 0.5f), 0))
    }

    @Test
    fun brokenOffsetDoesNotBlockResume() {
        assertEquals(ReaderPosition(2, 0f), readerStart(null, ReaderPosition(2, Float.NaN), 10))
        assertEquals(ReaderPosition(2, 1f), readerStart(null, ReaderPosition(2, 4f), 10))
        assertEquals(ReaderPosition(2, 0f), readerStart(null, ReaderPosition(2, -1f), 10))
    }

    @Test
    fun offsetConvertsBetweenPixelsAndPageShare() {
        assertEquals(250, offsetPixels(0.25f, 1000))
        assertEquals(0, offsetPixels(-2f, 1000))
        assertEquals(0, offsetPixels(0.5f, 0))
        assertEquals(0.25f, pageOffsetFraction(-250, 1000), 0f)
        assertEquals(0f, pageOffsetFraction(120, 1000), 0f)
        assertEquals(0f, pageOffsetFraction(-250, 0), 0f)
    }

    @Test
    fun pagedOffsetConvertsBetweenScrollPixelsAndPageShare() {
        assertEquals(0.3f, pagedOffsetFraction(scrolled = 600, pageHeight = 2000), 0f)
        assertEquals(0f, pagedOffsetFraction(scrolled = -40, pageHeight = 2000), 0f)
        assertEquals(0f, pagedOffsetFraction(scrolled = 600, pageHeight = 0), 0f)
    }

    @Test
    fun restoredPagedOffsetWaitsForLayoutSoTheZeroStartDoesNotOverwriteIt() {
        val restore = PagedScrollRestore(0.4f)
        assertFalse(restore.restored)
        assertNull(restore.pixels(pageHeight = 0))
        assertFalse(restore.restored)
        assertEquals(800, restore.pixels(pageHeight = 2000))
        restore.applied()
        assertTrue(restore.restored)
        assertNull(restore.pixels(pageHeight = 2000))
    }

    @Test
    fun freshPagedPageStartsAtTheTopWithoutARestore() {
        val restore = PagedScrollRestore(0f)
        assertTrue(restore.restored)
        assertNull(restore.pixels(pageHeight = 2000))
    }

    @Test
    fun leavingAndReturningToAPagedPageKeepsItsShare() {
        val fraction = pagedOffsetFraction(scrolled = 640, pageHeight = 1600)
        assertEquals(0.4f, fraction, 0f)
        val restore = PagedScrollRestore(fraction)
        assertEquals(640, restore.pixels(pageHeight = 1600))
    }

    @Test
    fun storedOffsetRoundTripsAndRejectsGarbage() {
        assertEquals("7:0.5", ReaderOffsetCodec.encode(ReaderPosition(7, 0.5f)))
        assertEquals(ReaderPosition(7, 0.5f), ReaderOffsetCodec.decode("7:0.5"))
        assertEquals(ReaderPosition(0, 0f), ReaderOffsetCodec.decode("0:0"))
        assertNull(ReaderOffsetCodec.decode(null))
        assertNull(ReaderOffsetCodec.decode(""))
        assertNull(ReaderOffsetCodec.decode("x:1"))
        assertNull(ReaderOffsetCodec.decode("1:x"))
        assertNull(ReaderOffsetCodec.decode("-1:0"))
        assertNull(ReaderOffsetCodec.decode("1:NaN"))
        assertNull(ReaderOffsetCodec.decode("1:Infinity"))
    }

    @Test
    fun storedOffsetOutsideThePageIsClampedOnRead() {
        assertEquals(ReaderPosition(4, 1f), ReaderOffsetCodec.decode("4:1.5"))
        assertEquals(ReaderPosition(4, 0f), ReaderOffsetCodec.decode("4:-0.5"))
    }

    @Test
    fun onlyAFreshTapInThisProcessCountsAsExplicit() {
        ReaderPageJump.mark(galleryId = 71, page = 3, now = 1_000)
        assertTrue(ReaderPageJump.isFresh(galleryId = 71, page = 3, now = 10_000))
        assertFalse(ReaderPageJump.isFresh(galleryId = 72, page = 3, now = 10_000))
        assertFalse(ReaderPageJump.isFresh(galleryId = 71, page = 4, now = 10_000))
        assertFalse(ReaderPageJump.isFresh(galleryId = 71, page = 3, now = 1_000 + 120_001))
    }
}
