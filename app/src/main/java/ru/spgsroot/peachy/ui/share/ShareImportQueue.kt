package ru.spgsroot.peachy.ui.share

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.spgsroot.peachy.data.storage.DuplicateVideoException

/** One shared item: the content URI as text and the intent's MIME type, if it had one. */
data class SharedStream(val uri: String, val type: String?)

data class ShareImportSummary(
    val imported: Int,
    val failed: Int,
    val includesPhotos: Boolean,
    val includesVideos: Boolean,
    /** Videos that were already in the gallery; these are not counted in [failed]. */
    val duplicates: Int = 0,
    /** Links handed to the background downloader; their outcome is reported when each one finishes. */
    val started: Int = 0
)

/** Runs exactly the importer for [kind]; a type no importer handles is an error, never a guess. */
suspend fun dispatchSharedImport(
    kind: SharedMediaKind?,
    photo: suspend () -> Unit,
    video: suspend () -> Unit
): SharedMediaKind {
    when (kind) {
        SharedMediaKind.PHOTO -> photo()
        SharedMediaKind.VIDEO -> video()
        SharedMediaKind.LINK, null -> throw IllegalArgumentException("Unsupported shared media type")
    }
    return kind
}

/**
 * The pending shared items. An item leaves the queue only after its import finished, so a
 * cancelled or locked session resumes it; [persist] is told the remaining items and the number of
 * rejected ones not yet reported. Imports run one at a time and items added meanwhile join the
 * same run, so nothing is imported twice.
 */
class ShareImportQueue(
    initial: List<SharedStream>,
    initialRejected: Int,
    private val persist: (List<SharedStream>, Int) -> Unit
) {
    private val lock = Any()
    private var items = initial
    private var rejected = initialRejected
    private val drainMutex = Mutex()
    private val _hasPending = MutableStateFlow(initial.isNotEmpty() || initialRejected > 0)
    val hasPending: StateFlow<Boolean> = _hasPending.asStateFlow()
    private val summaries = Channel<ShareImportSummary>(Channel.UNLIMITED)
    val results: Flow<ShareImportSummary> = summaries.receiveAsFlow()

    /** [rejected] counts shared items that were refused before they got here. */
    fun enqueue(streams: List<SharedStream>, rejected: Int = 0) {
        if (streams.isEmpty() && rejected == 0) return
        synchronized(lock) {
            items = items + streams
            this.rejected += rejected
            save()
        }
    }

    /** Imports every pending item with [import], which returns the kind it imported or throws. */
    suspend fun drain(import: suspend (SharedStream) -> SharedMediaKind) = drainMutex.withLock {
        var imported = 0
        var failed = 0
        var duplicates = 0
        var started = 0
        var photos = false
        var videos = false
        try {
            while (true) {
                val stream = synchronized(lock) { items.firstOrNull() } ?: break
                try {
                    val kind = import(stream)
                    if (kind == SharedMediaKind.LINK) {
                        started++
                    } else {
                        imported++
                        photos = photos || kind == SharedMediaKind.PHOTO
                        videos = videos || kind == SharedMediaKind.VIDEO
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: DuplicateVideoException) {
                    duplicates++
                } catch (_: Exception) {
                    failed++
                }
                synchronized(lock) {
                    items = items.drop(1)
                    save()
                }
            }
        } finally {
            // A cancelled run still reports what it finished; its unfinished item stays queued.
            val refused = synchronized(lock) { rejected.also { rejected = 0; save() } }
            if (imported + failed + duplicates + started + refused > 0) {
                summaries.trySend(ShareImportSummary(imported, failed + refused, photos, videos, duplicates, started))
            }
        }
    }

    private fun save() {
        persist(items, rejected)
        _hasPending.value = items.isNotEmpty() || rejected > 0
    }
}
