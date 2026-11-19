package ru.spgsroot.peachy.ui.manga

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.data.db.ReadHistoryEntity

/**
 * The reader and explicit completion actions own durable history; a gallery detail only displays it.
 * The detail stays on the back stack while history changes, so a one-shot read goes stale. One collector per opened
 * gallery keeps the "Continue from page N" label in sync without reloading the gallery, and a
 * gallery change cancels the previous collector so an older row cannot land in the new state.
 */
internal class GalleryResumeProgress(private val history: Flow<List<ReadHistoryEntity>>) {
    private var job: Job? = null

    fun track(scope: CoroutineScope, galleryId: Int, onPage: (Int?) -> Unit) {
        job?.cancel()
        job = scope.launch {
            history
                .map { rows -> rows.pageOf(galleryId) }
                .distinctUntilChanged()
                .collect(onPage)
        }
    }
}

internal fun List<ReadHistoryEntity>.pageOf(galleryId: Int): Int? =
    firstOrNull { it.galleryId == galleryId }?.page?.takeIf { it >= 0 }

/**
 * Stored pages are zero-based, the label is one-based. Index 0 is the first page, which reads
 * "Read"; an index outside the current page list (a shrunk gallery) is not offered as a resume.
 */
internal fun resumePage(savedPage: Int?, pageCount: Int): Int? =
    savedPage?.takeIf { it in 0 until pageCount && it > 0 }
