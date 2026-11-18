package ru.spgsroot.peachy.playback.queue

import org.junit.Assert.*
import org.junit.Test
import ru.spgsroot.peachy.domain.model.Video

class PlaylistManagerTest {
    private fun video(id: Long) = Video(id, "Video $id", "/$id.mp4", 1000, null, id, 100)

    // Four fixture rows stand in for the shortest queue that still has a middle item.
    private val first = video(1)
    private val second = video(2)
    private val third = video(3)
    private val fourth = video(4)
    private val queueOfFour = listOf(first, second, third, fourth)

    @Test fun nextAndPreviousStartAtTheBoundaryWhenPlaybackIsOutsideTheFilter() {
        val queue = PlaylistManager()
        val first = video(1)
        val second = video(2)
        val playing = video(3)
        queue.setPlaylist(listOf(first, second, playing))
        queue.selectVideo(playing)
        queue.setPlaylist(listOf(first, second))
        assertEquals(playing, queue.current())
        assertEquals(first, queue.next())
        queue.selectVideo(playing)
        assertEquals(second, queue.previous())
        assertEquals(listOf(first, second), queue.playlist.value)
    }

    @Test fun deletionTracksActualPlaybackNotTheFirstFilteredResult() {
        val queue = PlaylistManager()
        val first = video(1)
        val second = video(2)
        val playing = video(3)
        queue.setPlaylist(listOf(first, second, playing))
        queue.selectVideo(playing)
        queue.setPlaylist(listOf(first, second))
        val replacements = mutableListOf<Video?>()
        queue.setOnCurrentVideoRemovedListener { replacements += it }
        assertFalse(queue.removeVideo(first))
        assertTrue(replacements.isEmpty())
        assertTrue(queue.removeVideo(playing))
        assertEquals(listOf(second), replacements)
        assertEquals(second, queue.current())
    }

    @Test fun selectingGalleryMediaOutsideTheQueueKeepsTheFilteredOrder() {
        val queue = PlaylistManager()
        val first = video(1)
        val second = video(2)
        queue.setPlaylist(listOf(first, second))
        queue.selectVideo(video(3))
        assertEquals(3L, queue.current()?.id)
        assertEquals(listOf(first, second), queue.playlist.value)
        assertEquals(first, queue.next())
        assertEquals(second, queue.next())
        assertEquals(first, queue.next())
    }

    @Test fun aQueueEmissionNeverInventsAFirstRowAsTheCurrentVideo() {
        val queue = PlaylistManager()
        queue.setPlaylist(queueOfFour)

        // Nothing plays yet, so no row may pose as the displayed video: an invented index is what
        // turns the next gesture into a jump to an endpoint.
        assertNull(queue.current())
        // The documented cold-queue start still begins at the top and at the bottom.
        assertEquals(first, queue.next())
        queue.setPlaylist(queueOfFour)
        assertEquals(fourth, queue.previous())
    }

    @Test fun aMiddleVideoStaysAdjacentAcrossQueueEmissions() {
        val queue = PlaylistManager()
        queue.setPlaylist(queueOfFour)
        queue.syncPlaying(third)

        // A re-sorted emission re-creates the rows; the gesture still follows the displayed order.
        val resorted = listOf(video(4), video(3), video(2), video(1))
        queue.setPlaylist(resorted)
        assertEquals(video(2), queue.next())
        queue.syncPlaying(third)
        assertEquals(video(4), queue.previous())

        // The original order is emitted again: one gesture moves one row in the displayed order.
        queue.setPlaylist(queueOfFour)
        queue.syncPlaying(third)
        assertEquals(fourth, queue.next())
        queue.syncPlaying(third)
        assertEquals(second, queue.previous())
        // Previous returns the row before the middle one, so the next gesture comes back to it.
        assertEquals(third, queue.next())
    }

    @Test fun aFilteredOutRowNoLongerStrandsThePointerAtAnEndpoint() {
        val queue = PlaylistManager()
        queue.setPlaylist(queueOfFour)
        queue.syncPlaying(third)

        // The filter hides the playing row: the documented boundary policy applies while it is out.
        queue.setPlaylist(listOf(first, second))
        assertEquals(first, queue.next())

        // When the row returns, re-anchoring the displayed video restores adjacency for the next
        // gesture instead of leaving playback stuck at the first or last row.
        queue.setPlaylist(queueOfFour)
        queue.syncPlaying(third)
        assertEquals(fourth, queue.next())
        queue.syncPlaying(third)
        assertEquals(second, queue.previous())
    }

    @Test fun rapidGesturesAdvanceExactlyOneRowEach() {
        val queue = PlaylistManager()
        queue.setPlaylist(queueOfFour)
        queue.syncPlaying(second)

        assertEquals(third, queue.next())
        assertEquals(fourth, queue.next())
        assertEquals(first, queue.next()) // repeat boundary policy: last wraps to first
        assertEquals(fourth, queue.previous())
        assertEquals(third, queue.previous())
    }

    @Test fun aStaleOwnerCannotMuteTheNewerRemovalListener() {
        val queue = PlaylistManager()
        queue.setPlaylist(queueOfFour)
        queue.selectVideo(second)
        val staleOwner: (Video?) -> Unit = {}
        val replacements = mutableListOf<Video?>()
        queue.setOnCurrentVideoRemovedListener(staleOwner)
        queue.setOnCurrentVideoRemovedListener { replacements += it }
        // The old owner is torn down after the new one registered; the live callback must survive.
        queue.clearOnCurrentVideoRemovedListener(staleOwner)
        assertTrue(queue.removeVideo(second))
        assertEquals(listOf<Video?>(third), replacements)
        assertEquals(third, queue.current())
    }

    @Test fun renamedPlaybackCopyKeepsItsQueuePosition() {
        val queue = PlaylistManager()
        queue.setPlaylist(queueOfFour)

        // The player reports a copy whose title changed; gestures still start from its queue row.
        queue.syncPlaying(third.copy(title = "Renamed while playing"))
        assertEquals(fourth, queue.next())
        queue.syncPlaying(third.copy(title = "Renamed while playing"))
        assertEquals(second, queue.previous())
    }
}
