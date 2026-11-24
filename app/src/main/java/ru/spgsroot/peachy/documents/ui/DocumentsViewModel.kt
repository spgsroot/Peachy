package ru.spgsroot.peachy.documents.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.documents.DocumentFormatError
import ru.spgsroot.peachy.documents.DocumentFormatException
import ru.spgsroot.peachy.documents.DocumentRepository
import ru.spgsroot.peachy.documents.DocumentSessionsImpl
import ru.spgsroot.peachy.documents.DocumentSummary
import ru.spgsroot.peachy.ui.gallery.MediaFilter
import ru.spgsroot.peachy.ui.gallery.MediaSort

/** What the Documents list has to tell the user, mapped to copy by the screen. */
sealed interface DocumentsEvent {
    data class Opened(val id: String) : DocumentsEvent

    data class ImportFailed(val error: DocumentsError) : DocumentsEvent

    data object DeleteFailed : DocumentsEvent
}

data class DocumentsError(val reason: DocumentFormatError, val detail: String?)

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    repository: DocumentRepository,
    private val sessions: DocumentSessionsImpl,
) : ViewModel() {

    val documents: StateFlow<List<DocumentSummary>> = repository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow(MediaFilter())
    val filter: StateFlow<MediaFilter> = _filter.asStateFlow()

    fun setFilter(filter: MediaFilter) {
        _filter.value = filter
    }

    private val _events = MutableSharedFlow<DocumentsEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private val _importing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    fun import(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            try {
                sessions.import(uri).fold(
                    onSuccess = { session -> _events.emit(DocumentsEvent.Opened(session.id)) },
                    onFailure = { failure ->
                        val typed = failure as? DocumentFormatException
                        _events.emit(
                            DocumentsEvent.ImportFailed(
                                DocumentsError(
                                    reason = typed?.reason ?: DocumentFormatError.UNKNOWN_FORMAT,
                                    detail = typed?.let { error -> formatLabel(error.message) },
                                )
                            )
                        )
                    },
                )
            } finally {
                _importing.value = false
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            sessions.discard(id).onFailure { _events.emit(DocumentsEvent.DeleteFailed) }
        }
    }

    /** The sniffed format is the useful part of the developer message, never its whole sentence. */
    private fun formatLabel(message: String?): String? {
        val text = message ?: return null
        val match = Regex("\\b(pdf|docx|docm|dotx|doc|odt|rtf|md|txt|xlsx|xlsm|xlsb|xls|ods|csv|tsv|pptx|ppsx|potx|ppt|png|jpg|webp|bmp|heic)\\b")
            .find(text)
        return match?.value?.uppercase()
    }
}

/**
 * Same sort and size bounds as the gallery dialog. Gallery media type does not apply.
 * Newest matches the stored order: updatedAt descending, then id.
 */
internal fun filterDocuments(items: List<DocumentSummary>, filter: MediaFilter): List<DocumentSummary> {
    val ordered = when (filter.sort) {
        MediaSort.Newest -> items.sortedWith(compareByDescending<DocumentSummary> { it.updatedAt }.thenBy { it.id })
        MediaSort.NameAscending -> items.sortedWith(Comparator<DocumentSummary> { a, b ->
            a.displayName.compareTo(b.displayName, ignoreCase = true)
        }.thenBy { it.id })
        MediaSort.NameDescending -> items.sortedWith(Comparator<DocumentSummary> { a, b ->
            b.displayName.compareTo(a.displayName, ignoreCase = true)
        }.thenBy { it.id })
        MediaSort.SizeAscending -> items.sortedWith(compareBy<DocumentSummary> { it.bytes }.thenBy { it.id })
        MediaSort.SizeDescending -> items.sortedWith(compareByDescending<DocumentSummary> { it.bytes }.thenBy { it.id })
    }
    return ordered.filter { doc ->
        (filter.minBytes == null || doc.bytes >= filter.minBytes) &&
            (filter.maxBytes == null || doc.bytes <= filter.maxBytes)
    }
}
