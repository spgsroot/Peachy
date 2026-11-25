package ru.spgsroot.peachy.ui.manga

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.data.db.ReadHistoryEntity
import ru.spgsroot.peachy.data.manga.GalleryMemoryStore
import ru.spgsroot.peachy.data.manga.MangaIdentityStore
import ru.spgsroot.peachy.data.notes.NoteKind
import ru.spgsroot.peachy.data.notes.NoteRepository
import ru.spgsroot.peachy.data.manga.MangaCatalogStore
import ru.spgsroot.peachy.data.manga.MangaLibraryRepository
import ru.spgsroot.peachy.data.manga.NhentaiApi
import ru.spgsroot.peachy.manga.GalleryComment
import ru.spgsroot.peachy.manga.GalleryDetail
import ru.spgsroot.peachy.manga.GallerySummary
import ru.spgsroot.peachy.manga.MangaIdentitySnapshot
import ru.spgsroot.peachy.manga.UnitKind
import javax.inject.Inject

/** A list loaded from the API on its own, so one failing section never hides the rest of the page. */
data class Section<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val loaded: Boolean = false
)

data class DetailState(
    val detail: GalleryDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val savedPage: Int? = null,
    val related: Section<GallerySummary> = Section(),
    val comments: Section<GalleryComment> = Section()
)

@HiltViewModel
class GalleryDetailViewModel @Inject constructor(
    private val api: NhentaiApi,
    private val library: MangaLibraryRepository,
    private val catalogStore: MangaCatalogStore,
    private val memory: GalleryMemoryStore,
    private val notes: NoteRepository,
    private val identityStore: MangaIdentityStore
) : ViewModel() {
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError

    val libraryItems = library.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val identitySnapshot: StateFlow<MangaIdentitySnapshot> = identityStore.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MangaIdentitySnapshot())

    val readHistoryByGallery: StateFlow<Map<Int, ReadHistoryEntity>> = catalogStore.history
        .map { rows -> rows.associateBy { it.galleryId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The private note on gallery [id], or null when there is none. */
    fun note(id: Int): Flow<String?> = notes.observe(NoteKind.GALLERY, id.toString())

    fun saveNote(id: Int, text: String) {
        viewModelScope.launch { notes.save(NoteKind.GALLERY, id.toString(), text) }
    }

    private var loadedId: Int? = null
    private val resumeProgress = GalleryResumeProgress(catalogStore.history)

    fun load(id: Int) {
        if (loadedId == id) return
        loadedId = id
        _state.value = DetailState()
        // The reader writes the durable page while this screen sits on the back stack, so the label
        // follows the same history row instead of a snapshot taken once at load time.
        resumeProgress.track(viewModelScope, id) { page ->
            if (loadedId == id) _state.update { it.copy(savedPage = page) }
        }
        viewModelScope.launch {
            val saved = library.observe().first().find { it.id == id && it.pagePaths.isNotEmpty() }
            if (saved != null) {
                _state.update {
                    it.copy(detail = GalleryDetail(saved.id, saved.title, saved.thumbnailPath, saved.pagePaths), loading = false)
                }
            }
            try {
                val fetched = withContext(Dispatchers.IO) { api.gallery(id) }
                catalogStore.rememberTags(fetched.tags)
                _state.update { it.copy(detail = fetched, loading = false, error = null) }
                memory.remember(fetched)
                identityStore.indexDetail(fetched)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A saved copy stays readable offline; only a gallery we could not show at all is an error.
                _state.update { if (it.detail == null) it.copy(loading = false, error = e.message ?: "Open failed") else it }
            }
            loadRelated(id)
        }
    }

    fun retry() {
        val id = loadedId ?: return
        loadedId = null
        load(id)
    }

    fun loadRelated(id: Int) {
        _state.update { it.copy(related = it.related.copy(loading = true, failed = false)) }
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { api.related(id) }
                withContext(Dispatchers.IO) { identityStore.indexSummaries(items) }
                _state.update { it.copy(related = Section(items, loaded = true)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(related = Section(failed = true)) }
            }
        }
    }

    /** Comments are read on request: the endpoint is rate limited and most galleries are opened only to read. */
    fun loadComments() {
        val id = loadedId ?: return
        if (_state.value.comments.loading) return
        _state.update { it.copy(comments = it.comments.copy(loading = true, failed = false)) }
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { api.comments(id) }
                _state.update { it.copy(comments = Section(items, loaded = true)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(comments = Section(failed = true)) }
            }
        }
    }

    fun isSaved(id: Int): Boolean = libraryItems.value.any { it.id == id }

    fun isFavorite(id: Int): Boolean = libraryItems.value.any { it.id == id && it.favorite }

    fun download() {
        val current = _state.value.detail ?: return
        if (_progress.value != null) return
        _actionError.value = null
        viewModelScope.launch {
            _progress.value = "0/${current.pagePaths.size}"
            try {
                library.save(current, isFavorite(current.id)) { done, total -> _progress.value = "$done/$total" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Save failed"
            } finally {
                _progress.value = null
            }
        }
    }

    fun removeDownload() {
        val current = _state.value.detail ?: return
        viewModelScope.launch { library.remove(current.id) }
    }

    fun toggleFavorite() {
        val current = _state.value.detail ?: return
        viewModelScope.launch { library.setFavorite(current, !isFavorite(current.id)) }
    }

    fun setReadMark(galleryId: Int, read: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            identityStore.setReadMark(galleryId, read)
        }
    }

    fun confirmSameChapter(galleryId: Int, targetGalleryId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            identityStore.confirmSameChapter(galleryId, targetGalleryId)
        }
    }

    fun linkAsTranslationOfUnit(galleryId: Int, workId: String, unitId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            identityStore.linkAsTranslationOfUnit(galleryId, workId, unitId)
        }
    }

    fun linkAsAnotherChapter(
        galleryId: Int,
        workId: String?,
        anchorGalleryId: Int?,
        workTitle: String?,
        unitLabel: String,
        sortOrder: Double,
        kind: UnitKind
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            identityStore.linkAsAnotherChapter(
                galleryId = galleryId,
                workId = workId,
                anchorGalleryId = anchorGalleryId,
                workTitle = workTitle,
                unitLabel = unitLabel,
                sortOrder = sortOrder,
                kind = kind
            )
        }
    }

    fun unlink(galleryId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            identityStore.unlink(galleryId)
        }
    }

    fun rejectSuggestion(aGalleryId: Int, bGalleryId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            identityStore.rejectSuggestion(aGalleryId, bGalleryId)
        }
    }
}
