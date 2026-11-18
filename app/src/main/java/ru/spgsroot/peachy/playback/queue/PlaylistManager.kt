package ru.spgsroot.peachy.playback.queue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.spgsroot.peachy.domain.model.Video
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistManager @Inject constructor() {
    private val _playlist = MutableStateFlow<List<Video>>(emptyList())
    val playlist: StateFlow<List<Video>> = _playlist

    private val lock = Any()
    // Playback may remain outside the filtered queue until the user advances.
    private var currentVideo: Video? = null
    private var onCurrentVideoRemovedCallback: ((Video?) -> Unit)? = null

    fun setOnCurrentVideoRemovedListener(callback: (Video?) -> Unit) = synchronized(lock) {
        onCurrentVideoRemovedCallback = callback
    }

    /** Drops [callback] only while it is still the registered one, so a newer owner is not muted. */
    fun clearOnCurrentVideoRemovedListener(callback: (Video?) -> Unit) = synchronized(lock) {
        if (onCurrentVideoRemovedCallback === callback) onCurrentVideoRemovedCallback = null
    }

    fun current(): Video? = synchronized(lock) {
        currentVideo
    }

    // One call moves one row. Repeat policy: the last row wraps to the first and the first to the
    // last; a pointer that is not in the visible queue (playback outside the filter) starts from the
    // matching boundary instead of a stale index.
    fun next(): Video? = synchronized(lock) {
        val videos = _playlist.value
        if (videos.isEmpty()) return null
        val index = videos.indexOfFirst { it.id == currentVideo?.id }
        videos[(index + 1) % videos.size].also { currentVideo = it }
    }

    fun previous(): Video? = synchronized(lock) {
        val videos = _playlist.value
        if (videos.isEmpty()) return null
        val index = videos.indexOfFirst { it.id == currentVideo?.id }
        videos[if (index <= 0) videos.lastIndex else index - 1].also { currentVideo = it }
    }

    fun addVideo(video: Video) = synchronized(lock) {
        _playlist.value = _playlist.value + video
        if (currentVideo == null) currentVideo = video
    }

    fun removeVideo(video: Video): Boolean {
        val replacement = synchronized(lock) {
            val oldList = _playlist.value
            val oldIndex = oldList.indexOfFirst { it.id == currentVideo?.id }
            val wasCurrent = video.id == currentVideo?.id
            val newList = oldList.filter { it.id != video.id }
            _playlist.value = newList
            if (!wasCurrent) return false
            val next = newList.getOrNull(oldIndex.coerceAtLeast(0)) ?: newList.firstOrNull()
            currentVideo = next
            next
        }
        onCurrentVideoRemovedCallback?.invoke(replacement)
        return true
    }

    fun shuffle() = synchronized(lock) {
        _playlist.value = _playlist.value.shuffled()
        // Shuffling reorders the queue, it never changes which video plays.
        currentVideo = currentVideo?.let { playing -> _playlist.value.firstOrNull { it.id == playing.id } ?: playing }
    }

    /**
     * A queue emission only reorders or filters the list. The video that plays is the queue pointer,
     * so it is carried across emissions by id and is never re-invented from the new first row.
     */
    fun setPlaylist(videos: List<Video>) = synchronized(lock) {
        _playlist.value = videos
        currentVideo = currentVideo?.let { playing -> videos.firstOrNull { it.id == playing.id } ?: playing }
    }

    /**
     * Anchors the pointer to the queue row of the video the player actually shows, so the advance
     * gestures always start from the displayed item and never from a stale or boundary row.
     */
    fun syncPlaying(video: Video) = synchronized(lock) {
        currentVideo = _playlist.value.firstOrNull { it.id == video.id } ?: video
    }

    fun selectVideo(video: Video) = synchronized(lock) {
        currentVideo = video
    }

    fun isEmpty() = synchronized(lock) { _playlist.value.isEmpty() }

    fun getPlaylistOrder(): List<String> = synchronized(lock) {
        _playlist.value.map { it.id.toString() }
    }
}
