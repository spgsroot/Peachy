package ru.spgsroot.peachy.playback.player

/** Where a video was left and whether it was playing at that moment. */
data class PlaybackSnapshot(val positionMs: Long, val playing: Boolean)

/**
 * Remembers, until the process ends, where each video was left when the player moved on to another
 * one. A video that was left at its start or at its very end has nothing worth resuming and is
 * forgotten, so a finished video plays from the beginning again.
 */
class PlaybackMemory {
    private val snapshots = HashMap<Long, PlaybackSnapshot>()

    /** [durationMs] is not positive while the duration is still unknown. */
    @Synchronized
    fun remember(videoId: Long, positionMs: Long, durationMs: Long, playing: Boolean) {
        val atEnd = durationMs > 0 && positionMs >= durationMs - END_MARGIN_MS
        if (positionMs <= 0 || atEnd) snapshots.remove(videoId)
        else snapshots[videoId] = PlaybackSnapshot(positionMs, playing)
    }

    /** Takes the snapshot out: once the video plays again, the live player is the truth. */
    @Synchronized
    fun recall(videoId: Long): PlaybackSnapshot? = snapshots.remove(videoId)

    @Synchronized
    fun forget(videoId: Long) {
        snapshots.remove(videoId)
    }

    private companion object {
        const val END_MARGIN_MS = 1_000L
    }
}
