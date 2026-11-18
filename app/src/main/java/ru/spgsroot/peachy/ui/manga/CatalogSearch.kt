package ru.spgsroot.peachy.ui.manga

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.manga.CatalogQuery
import ru.spgsroot.peachy.manga.GallerySummary

/**
 * Owns the catalog request lifecycle. A new search cancels the request it replaces, only the newest
 * search may publish, and a filter pass may only replace the page it was started from.
 */
internal class CatalogSearch(
    private val scope: CoroutineScope,
    private val fetch: suspend (query: CatalogQuery, page: Int) -> List<GallerySummary>,
    private val rememberQuery: suspend (query: CatalogQuery) -> Unit,
    private val filter: suspend (source: List<GallerySummary>) -> List<GallerySummary>
) {
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state

    private var job: Job? = null
    private var searchGeneration = 0
    private var rawResults: List<GallerySummary> = emptyList()
    private var filterVersion = 0

    fun search(query: CatalogQuery, page: Int = 1) {
        job?.cancel()
        val generation = ++searchGeneration
        rawResults = emptyList()
        _state.value = CatalogState(query = query, page = page, loading = true, searched = true)
        job = scope.launch {
            try {
                rememberQuery(query)
                val results = fetch(query, page)
                if (generation != searchGeneration) return@launch
                rawResults = results
                refreshVisible()
                // Filtering suspends; a newer search may have started meanwhile and must keep its state.
                if (generation != searchGeneration) return@launch
                // The API exposes no total/page count. An empty raw page ends paging;
                // a fully blacklisted page must still allow moving forward.
                _state.value = _state.value.copy(loading = false, hasNext = results.isNotEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != searchGeneration) return@launch
                _state.value = _state.value.copy(loading = false, failed = true)
            }
        }
    }

    /**
     * Appends the page after the last one shown. Called when the list nears its end; a new search cancels it,
     * and a failure keeps the list and waits for [retry] instead of looping.
     */
    fun loadMore(retry: Boolean = false) {
        val state = _state.value
        if (!state.searched || state.loading || state.loadingMore || state.failed || !state.hasNext) return
        if (state.moreFailed && !retry) return
        val page = state.page + 1
        val query = state.query
        val generation = searchGeneration
        _state.value = state.copy(loadingMore = true, moreFailed = false)
        job = scope.launch {
            try {
                val results = fetch(query, page)
                if (generation != searchGeneration) return@launch
                rawResults = (rawResults + results).distinctBy { it.id }
                _state.value = _state.value.copy(page = page, loadingMore = false, hasNext = results.isNotEmpty())
                refreshVisible()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != searchGeneration) return@launch
                _state.value = _state.value.copy(loadingMore = false, moreFailed = true)
            }
        }
    }

    /**
     * Re-filters the raw results in place, for a blacklist or remembered-tag change. Concurrent passes
     * finish in any order; only the newest pass over the current page may publish.
     */
    suspend fun refreshVisible() {
        val source = rawResults
        val version = ++filterVersion
        val visible = filter(source)
        if (rawResults === source && filterVersion == version) {
            _state.value = _state.value.copy(results = visible)
        }
    }
}
