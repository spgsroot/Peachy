package ru.spgsroot.peachy.ui.manga

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.spgsroot.peachy.data.manga.TagPreferences
import ru.spgsroot.peachy.manga.TagLibrary
import ru.spgsroot.peachy.manga.TagRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.data.manga.GalleryMemoryStore
import ru.spgsroot.peachy.data.manga.MangaLibraryRepository
import ru.spgsroot.peachy.data.manga.MangaCatalogStore
import ru.spgsroot.peachy.data.manga.NhentaiApi
import ru.spgsroot.peachy.data.db.ReadHistoryEntity
import ru.spgsroot.peachy.manga.CatalogQuery
import ru.spgsroot.peachy.manga.SearchDestination
import ru.spgsroot.peachy.manga.searchDestination
import ru.spgsroot.peachy.manga.toApi
import ru.spgsroot.peachy.device.buttplug.CommandSender
import ru.spgsroot.peachy.manga.GalleryDetail
import ru.spgsroot.peachy.manga.GallerySummary
import ru.spgsroot.peachy.manga.LibraryGallery
import ru.spgsroot.peachy.manga.RememberedGallery
import ru.spgsroot.peachy.manga.NhentaiCdn
import ru.spgsroot.peachy.manga.PageTurnHaptic
import ru.spgsroot.peachy.manga.ReaderPageJump
import ru.spgsroot.peachy.manga.ReaderPosition
import ru.spgsroot.peachy.manga.filterSearchResults
import ru.spgsroot.peachy.manga.offsetFraction
import ru.spgsroot.peachy.manga.readerStart
import ru.spgsroot.peachy.data.db.SavedPageEntity
import ru.spgsroot.peachy.data.manga.ReaderPositionStore
import ru.spgsroot.peachy.data.manga.SavedPagesRepository
import ru.spgsroot.peachy.data.storage.ExportItem
import ru.spgsroot.peachy.data.storage.ExportResult
import ru.spgsroot.peachy.data.storage.VideoExporter
import android.net.Uri
import java.io.File
import javax.inject.Inject

data class CatalogState(
    val query: CatalogQuery = CatalogQuery(),
    val page: Int = 1,
    val results: List<GallerySummary> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val searched: Boolean = false,
    val hasNext: Boolean = false,
    /** The next page is being fetched; the results already shown stay. */
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false
)

@HiltViewModel
class MangaViewModel @Inject constructor(
    private val api: NhentaiApi,
    private val library: MangaLibraryRepository,
    private val commandSender: CommandSender,
    private val catalogStore: MangaCatalogStore,
    private val memory: GalleryMemoryStore,
    private val savedPagesRepository: SavedPagesRepository,
    private val exporter: VideoExporter,
    private val tagPreferences: TagPreferences,
    private val readerPositions: ReaderPositionStore
) : ViewModel() {
    val libraryItems: StateFlow<List<LibraryGallery>> = library.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tagLibrary: StateFlow<TagLibrary> = tagPreferences.library
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TagLibrary())

    fun toggleFavoriteTag(tag: TagRef) {
        viewModelScope.launch { tagPreferences.update { it.toggleFavorite(tag) } }
    }

    val history = catalogStore.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val queries = catalogStore.queries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Upload dates of galleries the user has opened; shown on cards only where one is known. */
    val uploadDates: StateFlow<Map<Int, Long>> = memory.uploadDates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Cover, tags and counts remembered for each gallery, so History can draw the same cards as the catalog. */
    val remembered: StateFlow<Map<Int, RememberedGallery>> = memory.all
        .map { galleries -> galleries.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    private val catalogSearch = CatalogSearch(
        scope = viewModelScope,
        fetch = { query, page ->
            withContext(Dispatchers.IO) { api.search(query.toSearchQuery(), query.sort.toApi(), page) }
        },
        rememberQuery = { query ->
            withContext(Dispatchers.IO) { catalogStore.rememberQuery(query.toSearchQuery()) }
        },
        filter = { source ->
            withContext(Dispatchers.IO) { filterSearchResults(source, emptyMap(), catalogStore::blocks) }
        }
    )
    val catalog: StateFlow<CatalogState> = catalogSearch.state
    private val _blockedIds = MutableStateFlow<Set<Int>>(emptySet())
    val blockedIds: StateFlow<Set<Int>> = _blockedIds
    private val _blockedNames = MutableStateFlow<Set<String>>(emptySet())
    val blockedNames: StateFlow<Set<String>> = _blockedNames

    init {
        viewModelScope.launch { refreshBlacklist() }
        viewModelScope.launch { catalogStore.knownGeneration.collect { refreshVisibleResults() } }
    }

    private val _detail = MutableStateFlow<GalleryDetail?>(null)
    val detail: StateFlow<GalleryDetail?> = _detail

    /** The resolved page and in-page offset the reader must start on; null until the gallery is known. */
    private val _initialPosition = MutableStateFlow<ReaderPosition?>(null)
    val initialPosition: StateFlow<ReaderPosition?> = _initialPosition
    private var openJob: Job? = null
    private var historyWrite: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress

    private val _intensity = MutableStateFlow(PageTurnHaptic.DEFAULT_INTENSITY)
    val intensity: StateFlow<Float> = _intensity

    fun search(query: CatalogQuery, page: Int = 1) {
        if (searchDestination(query.text) is SearchDestination.OpenGallery) return
        catalogSearch.search(query, page)
    }

    /** Appends the page after the last one shown; a new search cancels it. */
    fun loadMore(retry: Boolean = false) = catalogSearch.loadMore(retry)

    fun blockId(id: Int) = changeBlacklist { catalogStore.blockId(id) }
    fun unblockId(id: Int) = changeBlacklist { catalogStore.unblockId(id) }
    fun blockName(name: String) = changeBlacklist { catalogStore.blockName(name.trim()) }
    fun unblockName(name: String) = changeBlacklist { catalogStore.unblockName(name) }

    private fun changeBlacklist(change: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { change() }
            refreshBlacklist()
        }
    }

    private suspend fun refreshBlacklist() {
        _blockedIds.value = withContext(Dispatchers.IO) { catalogStore.blockedIds() }
        _blockedNames.value = withContext(Dispatchers.IO) { catalogStore.blockedNames() }
        refreshVisibleResults()
    }

    private suspend fun refreshVisibleResults() = catalogSearch.refreshVisible()

    fun open(id: Int, startAt: Int? = null) {
        openJob?.cancel()
        _detail.value = null
        _initialPosition.value = null
        _error.value = null
        openJob = viewModelScope.launch {
            try {
                // A deliberate page tapped in this process wins; a route argument restored after process
                // death is only a hint, so the newest durable position is resumed instead.
                val explicitPage = startAt?.takeIf { ReaderPageJump.isFresh(id, it) }
                val progress = withContext(Dispatchers.IO) { loadProgress(id) }
                val saved = library.observe().first().find { it.id == id && it.pagePaths.isNotEmpty() }
                if (saved != null) {
                    _initialPosition.value = readerStart(explicitPage, progress, saved.pagePaths.size)
                    _detail.value = GalleryDetail(saved.id, saved.title, saved.thumbnailPath, saved.pagePaths)
                }
                val fetched = withContext(Dispatchers.IO) { api.gallery(id) }
                if (_detail.value == null) {
                    _initialPosition.value = readerStart(explicitPage, progress, fetched.pagePaths.size)
                }
                _detail.value = fetched
                catalogStore.rememberTags(fetched.tags)
                refreshVisibleResults()
                memory.remember(fetched)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_detail.value == null) _error.value = e.message ?: "Open failed"
            }
        }
    }

    /** The durable position: the read-history page plus the offset stored for exactly that page. */
    private suspend fun loadProgress(galleryId: Int): ReaderPosition? {
        val history = catalogStore.getHistory(galleryId) ?: return null
        val stored = readerPositions.load(galleryId)
        val offset = stored?.takeIf { it.page == history.page }?.offset ?: 0f
        return ReaderPosition(history.page, offset)
    }

    private val _randomFailed = MutableStateFlow(false)
    val randomFailed: StateFlow<Boolean> = _randomFailed

    /** Asks the API for a real gallery id; the caller navigates, so the id never outlives the click. */
    fun openRandom(onId: (Int) -> Unit) {
        _randomFailed.value = false
        viewModelScope.launch {
            try {
                onId(withContext(Dispatchers.IO) { api.randomId() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _randomFailed.value = true
            }
        }
    }

    fun pageModel(galleryId: Int, path: String): Any? {
        val local = library.localPage(galleryId, path)
        return local?.takeIf { it.exists() } ?: NhentaiCdn.page(path)
    }

    fun saveReadHistory(current: GalleryDetail, page: Int, offset: Float = 0f) {
        if (page !in current.pagePaths.indices) return
        val position = ReaderPosition(page, offsetFraction(offset))
        val entry = ReadHistoryEntity(
            galleryId = current.id,
            title = current.title,
            thumbnailPath = current.thumbnailPath,
            page = page,
            pageCount = current.pagePaths.size,
            readAt = System.currentTimeMillis()
        )
        // This bounded write must survive the reader's navigation-scoped ViewModel.
        val previous = historyWrite
        historyWrite = viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            previous?.join()
            readerPositions.save(current.id, position)
            catalogStore.upsertHistory(entry)
        }
    }

    fun isSaved(id: Int): Boolean = libraryItems.value.any { it.id == id }

    fun isFavorite(id: Int): Boolean = libraryItems.value.any { it.id == id && it.favorite }

    fun saveCurrent() {
        val current = _detail.value ?: return
        viewModelScope.launch {
            _error.value = null
            try {
                library.save(current, isFavorite(current.id)) { done, total ->
                    _progress.value = "$done/$total"
                }
                _progress.value = null
            } catch (e: Exception) {
                _progress.value = null
                _error.value = e.message ?: "Save failed"
            }
        }
    }

    fun toggleFavorite() {
        val current = _detail.value ?: return
        viewModelScope.launch {
            val next = !isFavorite(current.id)
            library.setFavorite(current, next)
        }
    }

    fun remove(id: Int) {
        viewModelScope.launch { library.remove(id) }
    }

    fun setIntensity(value: Float) {
        _intensity.value = value
    }

    fun onPageTurned() {
        commandSender.pulse(_intensity.value, PageTurnHaptic.DEFAULT_DURATION_MS)
    }

    val savedPages: StateFlow<List<SavedPageEntity>> = savedPagesRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun savedPageFile(page: SavedPageEntity): File = savedPagesRepository.fileOf(page)

    /** Saves the page, or drops it from the saved pages when it is already there. */
    fun toggleSavedPage(page: Int) {
        val current = _detail.value ?: return
        viewModelScope.launch {
            _error.value = null
            try {
                if (savedPages.value.any { it.galleryId == current.id && it.page == page }) {
                    savedPagesRepository.remove(current.id, page)
                } else {
                    savedPagesRepository.save(current, page)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Save failed"
            }
        }
    }

    fun removeSavedPage(page: SavedPageEntity) {
        viewModelScope.launch { savedPagesRepository.remove(page.galleryId, page.page) }
    }

    private val _pageExport = MutableStateFlow<ExportResult?>(null)

    /** Outcome of the last export of saved pages; null until one finishes or after [consumePageExport]. */
    val pageExport: StateFlow<ExportResult?> = _pageExport

    fun consumePageExport() {
        _pageExport.value = null
    }

    /** Copies [pages] into the folder the user picked; a failed copy never stays half-written. */
    fun exportSavedPages(folder: Uri, pages: List<SavedPageEntity>) {
        if (pages.isEmpty()) return
        viewModelScope.launch {
            val items = pages.map {
                ExportItem("${it.title} - ${it.page + 1}", savedPagesRepository.fileOf(it).absolutePath)
            }
            _pageExport.value = withContext(Dispatchers.IO) { exporter.export(folder, items) }
        }
    }


}
