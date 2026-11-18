package ru.spgsroot.peachy.documents.office.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.DocumentSession
import ru.spgsroot.peachy.documents.DocumentSessions
import ru.spgsroot.peachy.documents.office.engine.OfficeAvailability
import ru.spgsroot.peachy.documents.office.engine.OfficeCommand
import ru.spgsroot.peachy.documents.office.engine.OfficeDocumentInfo
import ru.spgsroot.peachy.documents.office.engine.OfficeEngine
import ru.spgsroot.peachy.documents.office.engine.OfficeEngineState
import ru.spgsroot.peachy.documents.office.engine.OfficeExportTarget
import ru.spgsroot.peachy.documents.office.engine.OfficeFailure
import ru.spgsroot.peachy.documents.office.engine.OfficeKey
import ru.spgsroot.peachy.documents.office.engine.OfficeLegacySlide
import ru.spgsroot.peachy.documents.office.engine.OfficePartMode
import ru.spgsroot.peachy.documents.office.engine.OfficeSignal
import ru.spgsroot.peachy.documents.office.engine.OfficeTileRequest
import ru.spgsroot.peachy.documents.office.engine.OfficeUnavailableReason
import ru.spgsroot.peachy.documents.office.engine.OfficeZoom

/**
 * The phases the Office route can be in. Only [READY] may show a rendered document and only
 * [LEGACY] may show its extracted text: every probe, load and native-linkage failure lands in
 * [UNAVAILABLE] or [REFUSED] before any editor is drawn.
 */
internal enum class OfficePhase { LOADING, MISSING, REFUSED, UNAVAILABLE, READY, LEGACY }

/** The read-only text surface of a legacy `.ppt`; never a preview and never an editor. */
internal sealed interface OfficeLegacyTextState {
    /** The document is open and its text is being extracted. */
    data object Loading : OfficeLegacyTextState

    /** Real extracted text, in file order. */
    data class Ready(val slides: List<OfficeLegacySlide>) : OfficeLegacyTextState

    /** The extraction succeeded but the presentation carries no authored text at all. */
    data object Empty : OfficeLegacyTextState

    /** The extraction failed; no substitute content is shown. */
    data object Failed : OfficeLegacyTextState
}

internal data class OfficeUiState(
    val phase: OfficePhase = OfficePhase.LOADING,
    val session: DocumentSession? = null,
    val format: DocumentFormat? = null,
    val info: OfficeDocumentInfo? = null,
    val legacy: OfficeLegacyTextState? = null,
    val refusal: OfficeEntryRefusal? = null,
    val unavailable: OfficeUnavailableReason? = null,
    val problem: OfficeProblem? = null,
    val bitmap: Bitmap? = null,
    val bitmapRequest: OfficeTileRequest? = null,
    val rendering: Boolean = false,
    val partIndex: Int = 0,
    val partMode: OfficePartMode = OfficePartMode.SLIDES,
    val edited: Boolean = false,
    val busy: Boolean = false,
)

sealed interface OfficeEvent {
    /** The document has been closed and the route may leave. */
    data object Left : OfficeEvent

    data class Problem(val problem: OfficeProblem) : OfficeEvent

    /** A staged, validated export is ready for the system picker. */
    data class ExportReady(val ticket: OfficeExportTicket) : OfficeEvent

    data object Exported : OfficeEvent
}

/**
 * Route-side controller for the Office editor.
 *
 * It owns the session's engine lease for as long as the ViewModel lives, so rotation does not close
 * the document and leaving cannot close a document another screen owns. In-memory edits are never
 * written back to the working copy: the delivered bridge has no such call, so [markDirty] is never
 * used and leaving is guarded by a save-a-copy / discard / cancel decision.
 */
@HiltViewModel
class OfficeEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: DocumentSessions,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>("id").orEmpty()

    private val _state = MutableStateFlow(OfficeUiState())
    internal val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<OfficeEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var engine: OfficeEngine? = null
    private var lease: OfficeSessionOwnership.Lease? = null
    private var renderJob: Job? = null
    private var partJob: Job? = null
    private var legacyJob: Job? = null
    private var lastRequest: OfficeTileRequest? = null
    private var pendingTicket: OfficeExportTicket? = null
    private var leaving = false

    init {
        open()
    }

    private fun open() {
        viewModelScope.launch {
            val session = sessions.get(sessionId)
            if (session == null) {
                _state.update { it.copy(phase = OfficePhase.MISSING) }
                return@launch
            }
            val format = DocumentFormat.fromId(session.format)
            _state.update { it.copy(session = session, format = format) }

            val openFormat = when (val decision = OfficeEntryPolicy.decide(format)) {
                is OfficeEntryDecision.Refused -> {
                    _state.update { it.copy(phase = OfficePhase.REFUSED, refusal = decision.reason) }
                    return@launch
                }

                is OfficeEntryDecision.Open -> decision.format
            }

            when (val grant = OfficeEngineHost.acquire(context, session.id)) {
                is OfficeEngineHost.Grant.Busy -> {
                    _state.update {
                        it.copy(phase = OfficePhase.UNAVAILABLE, problem = OfficeProblem.ENGINE_BUSY)
                    }
                }

                is OfficeEngineHost.Grant.Granted -> {
                    engine = grant.engine
                    lease = grant.lease
                    observe(grant.engine)

                    val availability = grant.engine.availability()
                    if (availability is OfficeAvailability.Unavailable) {
                        _state.update {
                            it.copy(
                                phase = OfficePhase.UNAVAILABLE,
                                unavailable = availability.reason,
                            )
                        }
                        return@launch
                    }

                    grant.engine.open(session.workingFile, openFormat).fold(
                        onSuccess = { info ->
                            lastRequest = null
                            _state.update {
                                it.copy(
                                    phase = if (openFormat == DocumentFormat.PPT) {
                                        OfficePhase.LEGACY
                                    } else {
                                        OfficePhase.READY
                                    },
                                    info = info,
                                    legacy = if (openFormat == DocumentFormat.PPT) {
                                        OfficeLegacyTextState.Loading
                                    } else {
                                        null
                                    },
                                    partIndex = 0,
                                    partMode = info.partMode ?: OfficePartMode.SLIDES,
                                    unavailable = null,
                                    problem = null,
                                )
                            }
                            if (openFormat == DocumentFormat.PPT) extractLegacyText(grant.engine)
                        },
                        onFailure = { failure ->
                            val problem = (failure as? OfficeFailure)?.toProblem()
                                ?: OfficeProblem.OPEN_FAILED
                            _state.update {
                                it.copy(
                                    phase = OfficePhase.UNAVAILABLE,
                                    unavailable = (failure as? OfficeFailure.Unavailable)?.reason,
                                    problem = problem,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Extracts the text surface of the already-open legacy presentation. Rendering is never offered
     * for `.ppt`; an extraction failure or an empty result is a typed state, not a substitute viewer.
     */
    private fun extractLegacyText(engine: OfficeEngine) {
        legacyJob = viewModelScope.launch {
            engine.extractLegacyPresentation().fold(
                onSuccess = { slides ->
                    _state.update {
                        it.copy(
                            legacy = if (OfficeLegacyPolicy.hasText(slides)) {
                                OfficeLegacyTextState.Ready(slides)
                            } else {
                                OfficeLegacyTextState.Empty
                            },
                        )
                    }
                },
                onFailure = { failure ->
                    val unavailable = (failure as? OfficeFailure.Unavailable)?.reason
                    if (unavailable != null) {
                        _state.update {
                            it.copy(
                                phase = OfficePhase.UNAVAILABLE,
                                legacy = null,
                                unavailable = unavailable,
                            )
                        }
                    } else {
                        _state.update { it.copy(legacy = OfficeLegacyTextState.Failed) }
                    }
                },
            )
        }
    }

    private fun observe(engine: OfficeEngine) {
        viewModelScope.launch {
            engine.state.collect { engineState ->
                when (engineState) {
                    is OfficeEngineState.Open -> _state.update { current ->
                        if (current.phase == OfficePhase.READY && current.info != engineState.info) {
                            current.copy(info = engineState.info)
                        } else {
                            current
                        }
                    }

                    is OfficeEngineState.Unavailable -> {
                        lastRequest = null
                        _state.update {
                            if (it.phase == OfficePhase.READY || it.phase == OfficePhase.LEGACY) {
                                it.copy(
                                    phase = OfficePhase.UNAVAILABLE,
                                    legacy = null,
                                    unavailable = engineState.reason,
                                    bitmap = null,
                                    bitmapRequest = null,
                                    rendering = false,
                                )
                            } else {
                                it.copy(unavailable = engineState.reason)
                            }
                        }
                        val current = lease
                        lease = null
                        if (current != null) OfficeEngineHost.releaseDetached(current)
                    }

                    OfficeEngineState.Idle -> Unit
                }
            }
        }
        viewModelScope.launch {
            engine.signals.collect { signal ->
                if (signal == OfficeSignal.INVALIDATE_TILES ||
                    signal == OfficeSignal.STATE_CHANGED ||
                    signal == OfficeSignal.DOCUMENT_SIZE_CHANGED ||
                    signal == OfficeSignal.PART_CHANGED
                ) {
                    rerenderLast()
                }
            }
        }
    }

    /**
     * Requested by the viewport after pan/zoom settle (the screen owns the settle delay); stale
     * requests are dropped and an in-flight paint for an older viewport is discarded.
     */
    fun render(request: OfficeTileRequest) {
        if (_state.value.phase != OfficePhase.READY) return
        lastRequest = request
        renderJob?.cancel()
        renderJob = viewModelScope.launch { paint(request) }
    }

    private fun rerenderLast() {
        val request = lastRequest ?: return
        if (_state.value.phase != OfficePhase.READY) return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(RERENDER_SETTLE_MS)
            paint(request)
        }
    }

    private suspend fun paint(request: OfficeTileRequest) {
        val engine = engine ?: return
        if (_state.value.phase != OfficePhase.READY) return
        if (lastRequest != request) return
        _state.update { it.copy(rendering = true) }
        engine.setZoom(
            OfficeZoom(
                pixelWidth = request.canvasWidth,
                pixelHeight = request.canvasHeight,
                twipWidth = request.tileWidthTwips,
                twipHeight = request.tileHeightTwips,
            ),
        )
        val result = engine.renderTile(request)
        // A newer pan/zoom arrived while LOK was painting: the result belongs to a stale viewport.
        if (lastRequest != request) return
        result.fold(
            onSuccess = { tile ->
                _state.update {
                    it.copy(
                        bitmap = tile.bitmap,
                        bitmapRequest = request,
                        rendering = false,
                    )
                }
            },
            onFailure = { failure ->
                _state.update { it.copy(rendering = false) }
                report(failure)
            },
        )
    }

    fun tap(xTwips: Int, yTwips: Int) {
        val engine = engine ?: return
        if (!OfficeEditPolicy.isEditable(_state.value.info)) return
        viewModelScope.launch {
            engine.tap(xTwips, yTwips).onFailure(::report)
        }
    }

    fun insertText(text: String) {
        if (!OfficeEditPolicy.isInsertable(text)) {
            report(OfficeProblem.INPUT_INVALID)
            return
        }
        runInput { engine ->
            engine.insertText(text).onSuccess { markEdited() }
        }
    }

    fun pressKey(key: OfficeKey) {
        runInput { engine ->
            engine.pressKey(key).onSuccess {
                if (OfficeEditPolicy.mutates(key)) markEdited()
            }
        }
    }

    fun apply(command: OfficeCommand) {
        runInput { engine ->
            engine.apply(command).onSuccess {
                if (OfficeEditPolicy.mutates(command)) markEdited()
            }
        }
    }

    fun selectPart(index: Int) {
        val engine = engine ?: return
        val info = _state.value.info ?: return
        if (index !in info.parts.indices) return
        if (index == _state.value.partIndex) return
        partJob?.cancel()
        partJob = viewModelScope.launch {
            engine.selectPart(index).fold(
                onSuccess = {
                    lastRequest = null
                    _state.update {
                        it.copy(partIndex = index, bitmap = null, bitmapRequest = null)
                    }
                },
                onFailure = ::report,
            )
        }
    }

    fun selectPartMode(mode: OfficePartMode) {
        val engine = engine ?: return
        val info = _state.value.info ?: return
        if (!info.family.supportsPartMode || mode == _state.value.partMode) return
        partJob?.cancel()
        partJob = viewModelScope.launch {
            engine.selectPartMode(mode).fold(
                onSuccess = {
                    lastRequest = null
                    _state.update {
                        it.copy(
                            partMode = mode,
                            partIndex = 0,
                            bitmap = null,
                            bitmapRequest = null,
                        )
                    }
                },
                onFailure = ::report,
            )
        }
    }

    fun stageExport(target: OfficeExportTarget) {
        val engine = engine ?: return
        val session = _state.value.session ?: return
        if (_state.value.phase != OfficePhase.READY || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val directory = sessions.exportDir(session.id)
                engine.export(target, directory).fold(
                    onSuccess = { plan ->
                        val ticket = OfficeExportTickets.ticket(plan, session.displayName)
                        pendingTicket = ticket
                        _events.emit(OfficeEvent.ExportReady(ticket))
                    },
                    onFailure = ::report,
                )
            } catch (failure: Exception) {
                report(OfficeProblem.EXPORT_FAILED)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun deliver(ticket: OfficeExportTicket, uri: Uri) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = try {
                sessions.deliver(session.id, ticket.file, ticket.kind, uri)
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            ticket.file.delete()
            if (pendingTicket === ticket) pendingTicket = null
            result.fold(
                onSuccess = { _events.emit(OfficeEvent.Exported) },
                onFailure = { report(OfficeProblem.SAVE_FAILED) },
            )
            _state.update { it.copy(busy = false) }
        }
    }

    /** Picker cancelled or the intent could not start: the staged copy is scratch, delete it. */
    fun discardStaged(ticket: OfficeExportTicket) {
        ticket.file.delete()
        if (pendingTicket === ticket) pendingTicket = null
    }

    /**
     * Deletes an imported session that was never opened by this engine (refused or engine
     * unavailable). Never touches a session whose document may still be live.
     */
    fun discardSession() {
        val session = _state.value.session ?: return
        if (_state.value.info != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            sessions.discard(session.id).fold(
                onSuccess = { _events.emit(OfficeEvent.Left) },
                onFailure = { report(OfficeProblem.DELETE_FAILED) },
            )
            _state.update { it.copy(busy = false) }
        }
    }

    /** Close this session's document, then report [OfficeEvent.Left]. Never shuts the engine down. */
    internal fun leave() {
        if (leaving) return
        leaving = true
        renderJob?.cancel()
        partJob?.cancel()
        legacyJob?.cancel()
        val current = lease
        lease = null
        viewModelScope.launch {
            if (current != null) OfficeEngineHost.release(current)
            _events.emit(OfficeEvent.Left)
        }
    }

    override fun onCleared() {
        renderJob?.cancel()
        partJob?.cancel()
        legacyJob?.cancel()
        pendingTicket?.file?.delete()
        pendingTicket = null
        val current = lease
        lease = null
        if (current != null) OfficeEngineHost.releaseDetached(current)
        // A grant that arrived after onCleared still holds the engine; free it by session.
        OfficeEngineHost.releaseSessionDetached(sessionId)
    }

    private fun runInput(block: suspend (OfficeEngine) -> Result<Unit>) {
        val engine = engine ?: return
        if (_state.value.phase != OfficePhase.READY) return
        if (!OfficeEditPolicy.isEditable(_state.value.info)) return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block(engine).onFailure(::report)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun markEdited() {
        _state.update { it.copy(edited = true) }
    }

    private fun report(failure: Throwable) {
        report((failure as? OfficeFailure)?.toProblem() ?: OfficeProblem.ENGINE_FAILED)
    }

    private fun report(problem: OfficeProblem) {
        _events.tryEmit(OfficeEvent.Problem(problem))
    }

    private companion object {
        /** Signals from LOK arrive in bursts; one repaint after the burst is enough. */
        const val RERENDER_SETTLE_MS = 140L
    }
}
