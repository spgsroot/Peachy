package ru.spgsroot.peachy.ui.gallery

import org.junit.Assert.*
import org.junit.Test

class MediaFilterEmissionTest {
    private val mib = 1_048_576L

    @Test fun anUnusableRangeIsNotApplicableAndIsNeverEmitted() {
        assertNull(filterFromText(MediaSort.Newest, "2", "1", null))
        assertNull(filterFromText(MediaSort.Newest, "-1", "", null))
        assertNull(filterFromText(MediaSort.Newest, "", "abc", null))
        assertNull(filterFromText(MediaSort.Newest, "1", "0.5", null))
    }

    @Test fun aUsableRangeBecomesByteBoundsImmediately() {
        val filter = filterFromText(MediaSort.SizeDescending, "1", "2", GalleryMediaType.Video)
        assertEquals(MediaSort.SizeDescending, filter?.sort)
        assertEquals(mib, filter?.minBytes)
        assertEquals(2 * mib, filter?.maxBytes)
        assertEquals(GalleryMediaType.Video, filter?.type)
        // Inclusive bounds: equal ends are a valid one-size range, not an error.
        assertEquals(mib, filterFromText(MediaSort.Newest, "1", "1", null)?.maxBytes)
    }

    @Test fun clearingTheFieldsClearsTheSizeFilterButKeepsTheChosenSort() {
        assertEquals(MediaFilter(sort = MediaSort.NameAscending), filterFromText(MediaSort.NameAscending, "", " ", null))
        // Clearing both ends of a filtered range yields the unfiltered default after a reset.
        assertEquals(MediaFilter(), filterFromText(MediaSort.Newest, "", "", null))
    }
}
