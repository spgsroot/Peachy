package ru.spgsroot.peachy.documents.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.documents.DocumentKind
import ru.spgsroot.peachy.documents.DocumentSession
import ru.spgsroot.peachy.documents.DocumentSessions
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

enum class PdfPhase { LOADING, LOCKED, READY, FAILED }

enum class PdfTool { SELECT, HIGHLIGHT, UNDERLINE, STRIKEOUT, INK, NOTE, TEXT_BOX, SIGNATURE }

private const val THUMBNAIL_CACHE_LIMIT = 24

enum class PdfPasswordAction { OPEN, UNDO }

data class PdfSearchState(
    val query: String = "",
    val hits: List<PdfSearchHit> = emptyList(),
    val index: Int = -1,
    val searching: Boolean = false,
)

data class PdfTextEditorState(
    val target: TextEditTarget,
    val draft: String,
    val plan: TextEditPlan?,
    val refusal: PdfEditRefusal?,
    val confirming: Boolean,
)

data class PdfUiState(
    val session: DocumentSession? = null,
    val phase: PdfPhase = PdfPhase.LOADING,
    val error: PdfError? = null,
    val passwordAction: PdfPasswordAction? = null,
    val passwordRejected: Boolean = false,
    val pageCount: Int = 1,
    val pageIndex: Int = 0,
    val page: ImageBitmap? = null,
    val pageWidth: Float = 0f,
    val pageHeight: Float = 0f,
    val renderedScale: Float = 1f,
    val zoom: Float = 1f,
    val tool: PdfTool = PdfTool.SELECT,
    val selection: PdfSelection? = null,
    val selectedLine: Int? = null,
    val activeHitQuads: List<PdfQuad> = emptyList(),
    val annotations: List<PdfAnnotationRef> = emptyList(),
    val selectedAnnotation: Int? = null,
    val forms: List<PdfFormField> = emptyList(),
    val signatures: List<PdfSignatureInfo> = emptyList(),
    val readOnly: Boolean = false,
    val dirty: Boolean = false,
    val undoDepth: Int = 0,
    val busy: Boolean = false,
    val search: PdfSearchState = PdfSearchState(),
    val textEditor: PdfTextEditorState? = null,
    val notice: PdfNotice? = null,
    val inkDraft: List<PdfPoint> = emptyList(),
    val pendingSignatureStrokes: List<List<PdfPoint>>? = null,
    val signatureMutationPending: Boolean = false,
    val showThumbnails: Boolean = true,
    val savedCopy: Boolean = false,
)

data class PdfNotice(val messageRes: Int, val detail: String? = null, val error: Boolean = false) {
    val id: Long = System.nanoTime()
}

@HiltViewModel
class PdfEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: DocumentSessions,
) : ViewModel() {

    private companion object {
        const val TAG = "PdfEditorViewModel"
    }

    private val _state = MutableStateFlow(PdfUiState())
    val state: StateFlow<PdfUiState> = _state.asStateFlow()

    private val io = Dispatchers.IO.limitedParallelism(1)

    private var engine: PdfEngine? = null
    private var sessionId: String? = null
    private var workingFile: File? = null
    private var undo: PdfUndoStack? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var renderJob: Job? = null
    private var draftJob: Job? = null
    private var pendingSignatureMutation: (() -> Unit)? = null
    private var openedWithPassword = false
    /** Kept in memory only, so the editor can reopen the working copy after a redaction. */
    private var sessionPassword: String? = null
    private val thumbnailCache = LinkedHashMap<Int, Bitmap>()

    private val editFontFile: File by lazy {
        val target = File(context.cacheDir, "pdf_edit_font.ttf")
        if (!target.exists() || target.length() == 0L) {
            context.resources.openRawResource(R.raw.onest_regular).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target
    }

    // ------------------------------------------------------------ lifecycle

    fun attach(id: String) {
        if (sessionId == id) return
        sessionId = id
        openedWithPassword = false
        sessionPassword = null
        pendingSignatureMutation = null
        _state.value = PdfUiState(phase = PdfPhase.LOADING)
        viewModelScope.launch {
            val session = withContext(io) { sessions.get(id) }
            if (session == null) {
                _state.value = _state.value.copy(phase = PdfPhase.FAILED, error = PdfError(PdfErrorCode.CORRUPT, "session"))
                return@launch
            }
            workingFile = session.workingFile
            undo = PdfUndoStack(File(session.workingFile.parentFile, "undo"))
            _state.value = _state.value.copy(session = session, dirty = session.dirty)
            openEngine(null)
        }
    }

    private suspend fun openEngine(password: String?) {
        val file = workingFile ?: return
        try {
            val opened = withContext(io) { PdfEngine.open(file, password, editFontFile) }
            engine?.close()
            engine = opened
            openedWithPassword = password != null
            if (password != null) sessionPassword = password
            val pages = opened.pageCount
            val signatures = withContext(io) { opened.signatures() }
            _state.value = _state.value.copy(
                phase = PdfPhase.READY,
                error = null,
                passwordAction = null,
                passwordRejected = false,
                pageCount = pages,
                pageIndex = _state.value.pageIndex.coerceIn(0, (pages - 1).coerceAtLeast(0)),
                readOnly = !opened.canEdit && !opened.canAnnotate,
                undoDepth = undo?.depth() ?: 0,
                signatures = signatures,
            )
            loadPage(_state.value.pageIndex)
            refreshAnnotations()
            refreshForms()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfException) {
            when (e.error.code) {
                PdfErrorCode.PASSWORD_REQUIRED -> _state.value = _state.value.copy(
                    phase = PdfPhase.LOCKED,
                    passwordAction = PdfPasswordAction.OPEN,
                    passwordRejected = false,
                    error = null,
                )
                PdfErrorCode.WRONG_PASSWORD -> _state.value = _state.value.copy(
                    phase = PdfPhase.LOCKED,
                    passwordAction = PdfPasswordAction.OPEN,
                    passwordRejected = true,
                )
                else -> _state.value = _state.value.copy(phase = PdfPhase.FAILED, error = e.error)
            }
        } catch (e: Exception) {
            // Unexpected engine/native failure: log the stack, keep the user-facing code typed.
            Log.e(TAG, "PDF editor failed to open the document", e)
            _state.value = _state.value.copy(
                phase = PdfPhase.FAILED,
                error = PdfError(PdfErrorCode.CORRUPT),
            )
        }
    }

    fun submitPassword(password: String) {
        val action = _state.value.passwordAction
        val current = engine
        if (action == PdfPasswordAction.UNDO && current != null) {
            viewModelScope.launch {
                _state.value = _state.value.copy(busy = true)
                restoreUndo(password)
                _state.value = _state.value.copy(busy = false, passwordAction = null)
            }
        } else {
            viewModelScope.launch { openEngine(password) }
        }
    }

    fun cancelPassword() {
        _state.value = _state.value.copy(passwordAction = null, passwordRejected = false)
    }

    override fun onCleared() {
        sessionPassword = null
        engine?.close()
        engine = null
        super.onCleared()
    }

    // ------------------------------------------------------------ rendering

    fun setViewport(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val changed = kotlin.math.abs(widthPx - viewportWidth) > 16 || kotlin.math.abs(heightPx - viewportHeight) > 16
        viewportWidth = widthPx
        viewportHeight = heightPx
        if (changed) renderPage(force = true)
    }

    fun setZoom(zoom: Float) {
        val clamped = zoom.coerceIn(1f, 8f)
        if (kotlin.math.abs(clamped - _state.value.zoom) < 0.01f) return
        _state.value = _state.value.copy(zoom = clamped)
        renderPage(force = true)
    }

    private fun fitScale(): Float {
        val boundsWidth = _state.value.pageWidth.takeIf { it > 0f } ?: return 1f
        val available = (viewportWidth.takeIf { it > 0 } ?: 1080) * 0.96f
        return (available / boundsWidth).coerceAtLeast(0.2f)
    }

    private fun renderPage(force: Boolean = false) {
        renderJob?.cancel()
        val current = engine ?: return
        val index = _state.value.pageIndex
        val zoom = _state.value.zoom
        renderJob = viewModelScope.launch {
            try {
                val result = withContext(io) {
                    val bounds = current.pageBounds(index)
                    val scale = PdfCaps.renderScaleFor(fitScale() * zoom, bounds.width, bounds.height)
                    Triple(bounds, scale, current.renderPage(index, scale))
                }
                val (bounds, scale, rendered) = result
                _state.value = _state.value.copy(
                    page = rendered.bitmap.asImageBitmap(),
                    pageWidth = bounds.width,
                    pageHeight = bounds.height,
                    renderedScale = rendered.scale,
                    phase = PdfPhase.READY,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "PDF page render failed", e)
                _state.value = _state.value.copy(notice = PdfNotice(R.string.pdf_error_render, error = true))
            }
        }
    }

    // ------------------------------------------------------------ thumbnails

    /**
     * Renders (or reuses) a page thumbnail for the strip. Rendering happens on the engine's single
     * I/O dispatcher, so it never races page rendering or a commit. The cache is bounded because
     * a 5 000-page document must not pin thousands of bitmaps.
     */
    suspend fun thumbnail(index: Int, heightPx: Int): ImageBitmap? {
        val current = engine ?: return null
        if (heightPx <= 0) return null
        thumbnailCache[index]?.let { return it.asImageBitmap() }
        return withContext(io) {
            thumbnailCache[index]?.let { return@withContext it.asImageBitmap() }
            val bounds = try {
                current.pageBounds(index)
            } catch (e: RuntimeException) {
                return@withContext null
            }
            if (bounds.width <= 0f || bounds.height <= 0f) return@withContext null
            val scale = (heightPx / bounds.height).coerceIn(0.02f, 0.35f)
            val bitmap = try {
                current.renderPage(index, scale).bitmap
            } catch (e: PdfException) {
                return@withContext null
            }
            thumbnailCache[index] = bitmap
            while (thumbnailCache.size > THUMBNAIL_CACHE_LIMIT) {
                val oldest = thumbnailCache.keys.firstOrNull() ?: break
                thumbnailCache.remove(oldest)
            }
            bitmap.asImageBitmap()
        }
    }

    fun toggleThumbnails() {
        _state.value = _state.value.copy(showThumbnails = !_state.value.showThumbnails)
    }

    private fun invalidateThumbnails() {
        thumbnailCache.clear()
    }

    /**
     * Every full-file save moves the bytes covered by an existing digital signature. Gate the
     * mutation itself, rather than individual tools, and run it only after explicit confirmation.
     */
    private fun withSignatureWarning(action: () -> Unit) {
        if (_state.value.signatures.any { it.status != PdfSignatureStatus.UNSIGNED }) {
            pendingSignatureMutation = action
            _state.value = _state.value.copy(signatureMutationPending = true)
        } else {
            action()
        }
    }

    fun confirmSignatureMutation() {
        val action = pendingSignatureMutation ?: return
        pendingSignatureMutation = null
        _state.value = _state.value.copy(signatureMutationPending = false)
        action()
    }

    fun cancelSignatureMutation() {
        pendingSignatureMutation = null
        _state.value = _state.value.copy(signatureMutationPending = false)
    }

    internal fun requestCertificatePicker(onPick: () -> Unit) = withSignatureWarning(onPick)

    private suspend fun loadPage(index: Int) {
        val current = engine ?: return
        val pages = current.pageCount
        val clamped = index.coerceIn(0, (pages - 1).coerceAtLeast(0))
        val bounds = withContext(io) { current.pageBounds(clamped) }
        _state.value = _state.value.copy(
            pageIndex = clamped,
            pageCount = pages,
            pageWidth = bounds.width,
            pageHeight = bounds.height,
            selection = null,
            selectedLine = null,
            selectedAnnotation = null,
            activeHitQuads = emptyList(),
            inkDraft = emptyList(),
        )
        renderPage(force = true)
        refreshAnnotations()
        withContext(io) { sessions.saveLastPosition(sessionId ?: return@withContext, clamped, 0) }
    }

    fun goToPage(index: Int) {
        if (_state.value.phase != PdfPhase.READY) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            loadPage(index)
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun nextPage() = goToPage(_state.value.pageIndex + 1)

    fun previousPage() = goToPage(_state.value.pageIndex - 1)

    // ------------------------------------------------------------ selection and tools

    fun setTool(tool: PdfTool) {
        _state.value = _state.value.copy(
            tool = tool,
            selectedAnnotation = null,
            inkDraft = emptyList(),
        )
    }

    fun selectAt(point: PdfPoint) {
        val current = engine ?: return
        if (_state.value.tool != PdfTool.SELECT) return
        val own = _state.value.annotations.firstOrNull {
            it.own && it.rect.contains(point.x, point.y)
        }
        if (own != null) {
            _state.value = _state.value.copy(
                selectedAnnotation = own.index,
                selection = null,
                selectedLine = null,
            )
            return
        }
        val index = _state.value.pageIndex
        _state.value = _state.value.copy(selectedAnnotation = null)
        viewModelScope.launch {
            val line = withContext(io) { current.findLine(index, point) }
            if (line == null) {
                _state.value = _state.value.copy(selection = null, selectedLine = null)
                return@launch
            }
            val selection = withContext(io) { current.wordSelection(index, point) }
            _state.value = _state.value.copy(selection = selection, selectedLine = line)
        }
    }

    fun selectRange(anchor: PdfPoint, focus: PdfPoint) {
        val current = engine ?: return
        val index = _state.value.pageIndex
        viewModelScope.launch {
            val selection = withContext(io) { current.selection(index, anchor, focus) }
            _state.value = _state.value.copy(selection = selection)
        }
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selection = null, selectedLine = null, selectedAnnotation = null)
    }

    fun selectAnnotation(index: Int) {
        _state.value = _state.value.copy(selectedAnnotation = index, selection = null, selectedLine = null)
    }

    fun moveSelectedAnnotation(dx: Float, dy: Float) = withSignatureWarning {
        moveSelectedAnnotationConfirmed(dx, dy)
    }

    private fun moveSelectedAnnotationConfirmed(dx: Float, dy: Float) {
        val index = _state.value.selectedAnnotation ?: return
        val page = _state.value.pageIndex
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return
        }
        viewModelScope.launch {
            val ok = runCommit { current -> current.moveAnnotation(page, index, dx, dy) }
            if (ok) refreshAnnotations()
        }
    }

    fun deleteSelectedAnnotation() = withSignatureWarning {
        deleteSelectedAnnotationConfirmed()
    }

    private fun deleteSelectedAnnotationConfirmed() {
        val index = _state.value.selectedAnnotation ?: return
        val page = _state.value.pageIndex
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return
        }
        viewModelScope.launch {
            val ok = runCommit { current -> current.deleteAnnotation(page, index) }
            if (ok) {
                _state.value = _state.value.copy(selectedAnnotation = null)
                refreshAnnotations()
            }
        }
    }

    fun addMarkup(kind: PdfTool) = withSignatureWarning {
        addMarkupConfirmed(kind)
    }

    private fun addMarkupConfirmed(kind: PdfTool) {
        val selection = _state.value.selection ?: return
        val type = when (kind) {
            PdfTool.HIGHLIGHT -> com.artifex.mupdf.fitz.PDFAnnotation.TYPE_HIGHLIGHT
            PdfTool.UNDERLINE -> com.artifex.mupdf.fitz.PDFAnnotation.TYPE_UNDERLINE
            PdfTool.STRIKEOUT -> com.artifex.mupdf.fitz.PDFAnnotation.TYPE_STRIKE_OUT
            else -> return
        }
        val page = _state.value.pageIndex
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return
        }
        viewModelScope.launch {
            val ok = runCommit { current ->
                current.addMarkup(page, selection.quads, type, floatArrayOf(0.91f, 0.65f, 0.75f))
            }
            if (ok) {
                _state.value = _state.value.copy(selection = null, tool = PdfTool.SELECT)
                refreshAnnotations()
                notify(R.string.pdf_notice_annotation_added)
            }
        }
    }

    fun addInkStroke(points: List<PdfPoint>) {
        if (points.size < 2) return
        _state.value = _state.value.copy(inkDraft = _state.value.inkDraft + points)
    }

    fun finishInkStrokes() = withSignatureWarning {
        finishInkStrokesConfirmed()
    }

    private fun finishInkStrokesConfirmed() {
        val draft = _state.value.inkDraft
        if (draft.size < 2) {
            _state.value = _state.value.copy(inkDraft = emptyList())
            return
        }
        val page = _state.value.pageIndex
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return
        }
        viewModelScope.launch {
            val ok = runCommit { current ->
                current.addInk(page, listOf(draft), 2f, floatArrayOf(0.91f, 0.65f, 0.75f))
            }
            _state.value = _state.value.copy(inkDraft = emptyList())
            if (ok) {
                refreshAnnotations()
                notify(R.string.pdf_notice_annotation_added)
            }
        }
    }

    fun addTextNote(point: PdfPoint, text: String) = withSignatureWarning {
        addTextNoteConfirmed(point, text)
    }

    private fun addTextNoteConfirmed(point: PdfPoint, text: String) {
        if (text.isBlank()) return
        val page = _state.value.pageIndex
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return
        }
        viewModelScope.launch {
            val ok = runCommit { current -> current.addTextNote(page, point, text.trim()) }
            if (ok) {
                refreshAnnotations()
                notify(R.string.pdf_notice_annotation_added)
            }
        }
    }

    fun addTextBox(rect: PdfRect, text: String) = withSignatureWarning {
        addTextBoxConfirmed(rect, text)
    }

    private fun addTextBoxConfirmed(rect: PdfRect, text: String) {
        if (text.isBlank()) return
        val page = _state.value.pageIndex
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return
        }
        viewModelScope.launch {
            val ok = runCommit { current -> current.addTextBox(page, rect, text.trim()) }
            if (ok) {
                refreshAnnotations()
                notify(R.string.pdf_notice_annotation_added)
            }
        }
    }

    fun stashSignatureStrokes(strokes: List<List<PdfPoint>>) {
        _state.value = _state.value.copy(pendingSignatureStrokes = strokes, tool = PdfTool.SIGNATURE)
    }

    fun placePendingSignature(rect: PdfRect? = null) = withSignatureWarning {
        placePendingSignatureConfirmed(rect)
    }

    private fun placePendingSignatureConfirmed(rect: PdfRect? = null) {
        val strokes = _state.value.pendingSignatureStrokes ?: return
        val page = _state.value.pageIndex
        val width = _state.value.pageWidth
        val height = _state.value.pageHeight
        if (width <= 0f || height <= 0f) return
        // Autograph ink is placed in a 180x60 pt box above the page's bottom margin.
        val target = rect ?: PdfRect(width - 196f, height - 76f, width - 16f, height - 16f)
        val all = strokes.flatten()
        val minX = all.minOf { it.x }
        val minY = all.minOf { it.y }
        val boxWidth = all.maxOf { it.x } - minX
        val boxHeight = all.maxOf { it.y } - minY
        val scaleX = if (boxWidth > 0f) (target.width - 8f) / boxWidth else 1f
        val scaleY = if (boxHeight > 0f) (target.height - 8f) / boxHeight else 1f
        val scale = minOf(scaleX, scaleY)
        val ink = strokes.map { stroke ->
            stroke.map { point ->
                PdfPoint(
                    target.left + 4f + (point.x - minX) * scale,
                    target.top + 4f + (point.y - minY) * scale,
                )
            }
        }.filter { it.size >= 2 }
        if (ink.isEmpty()) return
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return
        }
        viewModelScope.launch {
            val ok = runCommit { current -> current.addInk(page, ink, 1.6f, floatArrayOf(0.1f, 0.1f, 0.12f)) }
            _state.value = _state.value.copy(pendingSignatureStrokes = null, tool = PdfTool.SELECT)
            if (ok) {
                refreshAnnotations()
                notify(R.string.pdf_notice_drawn_signature_added)
            }
        }
    }

    // ------------------------------------------------------------ in-place text editing

    fun beginTextEdit() {
        val current = engine ?: return
        val targetLine = _state.value.selectedLine ?: return
        val page = _state.value.pageIndex
        viewModelScope.launch {
            val target = withContext(io) { current.textEditTarget(page, targetLine) } ?: return@launch
            val editor = PdfTextEditorState(
                target = target,
                draft = target.text,
                plan = null,
                refusal = null,
                confirming = false,
            )
            _state.value = _state.value.copy(textEditor = editor)
            recomputePlan(editor)
        }
    }

    fun updateTextDraft(text: String) {
        val editor = _state.value.textEditor ?: return
        val updated = editor.copy(draft = text)
        _state.value = _state.value.copy(textEditor = updated)
        recomputePlan(updated)
    }

    private fun recomputePlan(editor: PdfTextEditorState) {
        draftJob?.cancel()
        val current = engine ?: return
        draftJob = viewModelScope.launch {
            val decision = withContext(io) {
                TextEditPlanner.plan(
                    editor.target,
                    editor.draft,
                    encodeGlyphs = current::encodeGlyphs,
                    measureWidth = current::measureText,
                )
            }
            val latest = _state.value.textEditor ?: return@launch
            if (latest.draft != editor.draft) return@launch
            _state.value = _state.value.copy(
                textEditor = when (decision) {
                    is TextEditDecision.Apply -> latest.copy(plan = decision.plan, refusal = null)
                    is TextEditDecision.Refuse -> latest.copy(plan = null, refusal = decision.reason)
                },
            )
        }
    }

    fun requestApplyTextEdit() {
        val editor = _state.value.textEditor ?: return
        if (editor.plan == null) return
        _state.value = _state.value.copy(textEditor = editor.copy(confirming = true))
    }

    fun cancelApplyTextEdit() {
        val editor = _state.value.textEditor ?: return
        _state.value = _state.value.copy(textEditor = editor.copy(confirming = false))
    }

    fun dismissTextEditor() {
        draftJob?.cancel()
        _state.value = _state.value.copy(textEditor = null, selection = null, selectedLine = null)
    }

    fun applyTextEdit() = withSignatureWarning {
        applyTextEditConfirmed()
    }

    private fun applyTextEditConfirmed() {
        val editor = _state.value.textEditor ?: return
        val plan = editor.plan ?: return
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return
        }
        val current = engine ?: return
        val file = workingFile ?: return
        val stack = undo ?: return
        viewModelScope.launch {
            val redactedFile = File(file.parentFile, file.name + ".redacted")
            val committedFile = File(file.parentFile, file.name + ".commit")
            var stagedEngine: PdfEngine? = null
            var snapshotPushed = false
            var replaced = false
            var failure: PdfException? = null
            _state.value = _state.value.copy(
                textEditor = null,
                selection = null,
                selectedLine = null,
                busy = true,
            )
            try {
                withContext(io) {
                    // MuPDF cannot extend a redacted stream in memory. Both native stages happen
                    // on scratch files; only the complete replacement is moved over the original.
                    current.redactLine(plan)
                    current.saveTo(redactedFile)
                    val staged = PdfEngine.open(redactedFile, sessionPassword, editFontFile)
                    stagedEngine = staged
                    staged.insertText(plan)
                    staged.saveTo(committedFile)
                    if (!committedFile.isFile || committedFile.length() == 0L) {
                        throw PdfException(PdfError(PdfErrorCode.SAVE_FAILED))
                    }
                    stack.push(file)
                    snapshotPushed = true
                    copyAtomic(committedFile, file)
                    replaced = true
                    val id = sessionId
                    if (id != null) sessions.markDirty(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfException) {
                if (!replaced) failure = e else Log.e(TAG, "PDF edit was committed; dirty flag failed", e)
            } catch (e: Exception) {
                if (!replaced) {
                    Log.e(TAG, "PDF text replacement failed", e)
                    failure = PdfException(PdfError(PdfErrorCode.SAVE_FAILED), e)
                } else {
                    Log.e(TAG, "PDF edit was committed; session update failed", e)
                }
            } finally {
                stagedEngine?.close()
                redactedFile.delete()
                committedFile.delete()
                if (!replaced && snapshotPushed) stack.discardLatest()
                engine?.close()
                engine = null
                invalidateThumbnails()
                _state.value = _state.value.copy(
                    textEditor = null,
                    selection = null,
                    selectedLine = null,
                    busy = false,
                    dirty = replaced || _state.value.dirty,
                    undoDepth = undo?.depth() ?: 0,
                )
                openEngine(sessionPassword)
            }
            if (replaced) {
                notify(R.string.pdf_notice_text_replaced)
            } else {
                failure?.let { notifyError(it.error.code, it.error.detail) }
            }
        }
    }

    private suspend fun reopenWorkingCopy() {
        engine?.close()
        engine = null
        thumbnailCache.clear()
        openEngine(sessionPassword)
    }

    // ------------------------------------------------------------ search

    fun setQuery(query: String) {
        _state.value = _state.value.copy(search = _state.value.search.copy(query = query))
    }

    fun runSearch() {
        val current = engine ?: return
        val query = _state.value.search.query.trim()
        if (query.isEmpty()) {
            _state.value = _state.value.copy(search = PdfSearchState())
            return
        }
        _state.value = _state.value.copy(search = _state.value.search.copy(searching = true))
        viewModelScope.launch {
            val hits = withContext(io) {
                current.search(query, PdfCaps.MAX_SEARCH_HITS) { false }
            }
            _state.value = _state.value.copy(
                search = PdfSearchState(query = query, hits = hits, index = if (hits.isEmpty()) -1 else 0),
            )
            if (hits.isNotEmpty()) showHit(0)
        }
    }

    fun nextHit() {
        val search = _state.value.search
        if (search.hits.isEmpty()) return
        showHit((search.index + 1) % search.hits.size)
    }

    fun previousHit() {
        val search = _state.value.search
        if (search.hits.isEmpty()) return
        showHit((search.index - 1 + search.hits.size) % search.hits.size)
    }

    private fun showHit(index: Int) {
        val search = _state.value.search
        val hit = search.hits.getOrNull(index) ?: return
        _state.value = _state.value.copy(
            search = search.copy(index = index),
            activeHitQuads = hit.quads,
        )
        if (hit.pageIndex != _state.value.pageIndex) goToPage(hit.pageIndex)
    }

    fun clearSearch() {
        _state.value = _state.value.copy(search = PdfSearchState(), activeHitQuads = emptyList())
    }

    // ------------------------------------------------------------ forms

    fun setFormText(field: PdfFormField, value: String) = withSignatureWarning {
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return@withSignatureWarning
        }
        viewModelScope.launch {
            val ok = runCommit { current -> current.setFormText(field.pageIndex, field.widgetIndex, value) }
            if (ok) refreshForms()
        }
    }

    fun toggleForm(field: PdfFormField) = withSignatureWarning {
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return@withSignatureWarning
        }
        viewModelScope.launch {
            val ok = runCommit { current -> current.toggleForm(field.pageIndex, field.widgetIndex) }
            if (ok) refreshForms()
        }
    }

    fun setFormChoice(field: PdfFormField, value: String) = withSignatureWarning {
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return@withSignatureWarning
        }
        viewModelScope.launch {
            val ok = runCommit { current -> current.setFormChoice(field.pageIndex, field.widgetIndex, value) }
            if (ok) refreshForms()
        }
    }

    fun flattenForms() = withSignatureWarning {
        if (_state.value.readOnly || _state.value.forms.isEmpty()) return@withSignatureWarning
        viewModelScope.launch {
            val ok = runCommit { current -> current.flattenForms() }
            if (ok) {
                refreshForms()
                notify(R.string.pdf_notice_forms_flattened)
            }
        }
    }

    // ------------------------------------------------------------ pages

    fun insertBlankPage() = withSignatureWarning {
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return@withSignatureWarning
        }
        val width = _state.value.pageWidth.takeIf { it > 0f } ?: 595f
        val height = _state.value.pageHeight.takeIf { it > 0f } ?: 842f
        val at = _state.value.pageIndex + 1
        viewModelScope.launch {
            val ok = runCommit { current -> current.insertBlankPage(at, width, height) }
            if (ok) {
                loadPage(at)
                notify(R.string.pdf_notice_page_inserted)
            }
        }
    }

    fun deleteCurrentPage() = withSignatureWarning {
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return@withSignatureWarning
        }
        if (_state.value.pageCount <= 1) {
            notifyError(PdfErrorCode.EDIT_FAILED)
            return@withSignatureWarning
        }
        val at = _state.value.pageIndex
        viewModelScope.launch {
            val ok = runCommit { current -> current.deletePage(at) }
            if (ok) {
                loadPage(at)
                notify(R.string.pdf_notice_page_deleted)
            }
        }
    }

    fun moveCurrentPage(offset: Int) = withSignatureWarning {
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return@withSignatureWarning
        }
        val from = _state.value.pageIndex
        val to = from + offset
        if (to < 0 || to >= _state.value.pageCount) return@withSignatureWarning
        viewModelScope.launch {
            val ok = runCommit { current -> current.movePage(from, to) }
            if (ok) loadPage(to)
        }
    }

    fun rotateCurrentPage(delta: Int) = withSignatureWarning {
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return@withSignatureWarning
        }
        val at = _state.value.pageIndex
        viewModelScope.launch {
            val ok = runCommit { current -> current.rotatePage(at, delta) }
            if (ok) {
                loadPage(at)
                notify(R.string.pdf_notice_page_rotated)
            }
        }
    }

    fun importPages(uri: Uri) = withSignatureWarning {
        if (_state.value.readOnly) {
            notifyError(PdfErrorCode.READ_ONLY)
            return@withSignatureWarning
        }
        val at = _state.value.pageIndex + 1
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val imported = withContext(io) { importFromUri(uri, at) }
            _state.value = _state.value.copy(busy = false)
            if (imported) {
                loadPage(at.coerceAtMost(_state.value.pageCount - 1))
                notify(R.string.pdf_notice_page_imported)
            }
        }
    }

    private suspend fun importFromUri(uri: Uri, at: Int): Boolean {
        var source: File? = null
        var sourceEngine: PdfEngine? = null
        return try {
            val temp = File.createTempFile("pdf-import", ".pdf", context.cacheDir)
            source = temp
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw PdfException(PdfError(PdfErrorCode.IMPORT_FAILED))
            if (temp.length() > PdfCaps.MAX_IMPORT_PAGE_BYTES) {
                throw PdfException(PdfError(PdfErrorCode.TOO_LARGE))
            }
            val opener = PdfEngine.open(temp, null, editFontFile)
            sourceEngine = opener
            var inserted = 0
            val pagesToCopy = opener.pageCount
            var destination = at
            repeat(pagesToCopy) { sourcePage ->
                val ok = runCommit(silentError = true) { current ->
                    current.importPage(opener, sourcePage, destination)
                }
                if (!ok) return false
                inserted++
                destination++
            }
            inserted > 0
        } catch (e: PdfException) {
            notifyError(e.error.code)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notifyError(PdfErrorCode.IMPORT_FAILED)
            false
        } finally {
            sourceEngine?.close()
            source?.delete()
        }
    }

    // ------------------------------------------------------------ undo

    fun undo() = withSignatureWarning {
        val stack = undo
        if (stack == null || stack.depth() == 0) return@withSignatureWarning
        val current = engine ?: return@withSignatureWarning
        if (current.encrypted) {
            _state.value = _state.value.copy(passwordAction = PdfPasswordAction.UNDO)
            return@withSignatureWarning
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            restoreUndo(null)
            _state.value = _state.value.copy(busy = false)
        }
    }

    private suspend fun restoreUndo(password: String?) {
        val file = workingFile ?: return
        val stack = undo ?: return
        try {
            val restored = withContext(io) {
                val target = File(file.parentFile, file.name + ".undo")
                val ok = stack.popInto(target)
                if (ok) copyAtomic(target, file)
                target.delete()
                ok
            }
            if (!restored) return
            engine?.close()
            engine = null
            invalidateThumbnails()
            val id = sessionId
            if (id != null) withContext(io) { sessions.markDirty(id) }
            _state.value = _state.value.copy(dirty = true)
            openEngine(password)
            notify(R.string.pdf_notice_undo_done)
        } catch (e: PdfException) {
            notifyError(e.error.code)
        } catch (e: Exception) {
            notifyError(PdfErrorCode.EDIT_FAILED)
        }
    }

    // ------------------------------------------------------------ signatures

    fun signWithPkcs12(uri: Uri, password: CharArray, reason: String) {
        val current = engine ?: run {
            notifyError(PdfErrorCode.EDIT_FAILED)
            return
        }
        val page = _state.value.pageIndex
        val width = _state.value.pageWidth
        val height = _state.value.pageHeight
        if (width <= 0f || height <= 0f) {
            notifyError(PdfErrorCode.EDIT_FAILED)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val (identity, signed) = withContext(io) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use(PdfSignerFactory::readPkcs12)
                        ?: throw PdfException(PdfError(PdfErrorCode.SIGNATURE_MISSING_CERT))
                    val identity = PdfSignerFactory.fromPkcs12(bytes, password)
                    val rect = PdfRect(width - 236f, height - 96f, width - 16f, height - 16f)
                    val ok = runCommit {
                        current.sign(page, rect, identity.signer, reason, "")
                    }
                    identity to ok
                }
                if (signed) {
                    refreshSignatures()
                    notify(R.string.pdf_notice_signed, detail = identity.displayName)
                }
            } catch (e: PdfException) {
                Log.e(TAG, "PKCS#12 signing failed: " + e.error.code + " " + e.error.detail, e)
                notifyError(e.error.code, e.error.detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "PKCS#12 signing failed", e)
                notifyError(PdfErrorCode.SIGN_FAILED)
            } finally {
                password.fill('\u0000')
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** Closes the editor and deletes the session directory together with its edits. */
    fun discardSession(onDone: () -> Unit) {
        val id = sessionId
        if (id == null) {
            onDone()
            return
        }
        viewModelScope.launch {
            engine?.close()
            engine = null
            withContext(io) { sessions.discard(id) }
            onDone()
        }
    }

    private suspend fun refreshSignatures() {
        val current = engine ?: return
        val signatures = withContext(io) { current.signatures() }
        _state.value = _state.value.copy(signatures = signatures)
    }

    // ------------------------------------------------------------ export

    fun exportPdfCopy(uri: Uri, suggestedName: String, closeWhenDone: Boolean = false) {
        val id = sessionId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val produced = withContext(io) { writePdfCopy(id, suggestedName) }
                val result = withContext(io) { sessions.deliver(id, produced, DocumentKind.PDF, uri) }
                produced.delete()
                result.fold(
                    onSuccess = {
                        notify(R.string.pdf_notice_saved_copy)
                        if (closeWhenDone) _state.value = _state.value.copy(savedCopy = true)
                    },
                    onFailure = { failure ->
                        Log.e(TAG, "PDF copy delivery failed", failure)
                        notifyError(PdfErrorCode.EXPORT_FAILED)
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "PDF copy export failed", e)
                notifyError(PdfErrorCode.EXPORT_FAILED)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private suspend fun writePdfCopy(id: String, suggestedName: String): File {
        val dir = sessions.exportDir(id)
        if (!dir.exists()) dir.mkdirs()
        val name = suggestedName.substringBeforeLast('.').ifBlank { "document" }
        val target = File(dir, "$name.pdf")
        // Not ".tmp": copyAtomic stages through a sibling with that suffix, and reusing it here
        // deleted the freshly saved document before it could be copied.
        val temp = File(dir, "$name.pdf.saving")
        // Every edit is committed to the working file, so its bytes are the document. Saving again
        // here would move the objects a signature's /ByteRange points at and invalidate it.
        val committed = workingFile ?: throw PdfException(PdfError(PdfErrorCode.EXPORT_FAILED))
        copyAtomic(committed, temp)
        copyAtomic(temp, target)
        temp.delete()
        return target
    }

    fun exportText(uri: Uri, suggestedName: String) {
        val id = sessionId ?: return
        val current = engine ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val produced = withContext(io) {
                    val dir = sessions.exportDir(id)
                    if (!dir.exists()) dir.mkdirs()
                    val name = suggestedName.substringBeforeLast('.').ifBlank { "document" }
                    val file = File(dir, "$name.txt")
                    file.writeText(current.exportText(), Charsets.UTF_8)
                    file
                }
                // Plain text carries no signature of its own, so only existence/length are proven.
                val result = withContext(io) { sessions.deliver(id, produced, kind = null, target = uri) }
                produced.delete()
                result.fold(
                    onSuccess = { notify(R.string.pdf_notice_text_exported) },
                    onFailure = { failure ->
                        Log.e(TAG, "PDF text delivery failed", failure)
                        notifyError(PdfErrorCode.EXPORT_FAILED)
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "PDF text export failed", e)
                notifyError(PdfErrorCode.EXPORT_FAILED)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun exportImages(tree: Uri, dpi: Int, jpeg: Boolean) {
        val id = sessionId ?: return
        val current = engine ?: return
        val pages = _state.value.pageCount
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val files = withContext(io) {
                    val dir = sessions.exportDir(id)
                    if (!dir.exists()) dir.mkdirs()
                    val scale = dpi / 72f
                    val produced = ArrayList<File>(pages)
                    for (index in 0 until pages) {
                        val bounds = current.pageBounds(index)
                        val capped = PdfCaps.renderScaleFor(scale, bounds.width, bounds.height)
                        val rendered = current.renderPage(index, capped)
                        val suffix = if (jpeg) "jpg" else "png"
                        val file = File(dir, "page-%03d.%s".format(index + 1, suffix))
                        file.outputStream().use { output ->
                            if (jpeg) {
                                rendered.bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
                            } else {
                                rendered.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                            }
                        }
                        rendered.bitmap.recycle()
                        produced += file
                    }
                    produced
                }
                val result = withContext(io) { sessions.deliverMany(id, files, tree) }
                files.forEach { it.delete() }
                result.fold(
                    onSuccess = { notify(R.string.pdf_notice_images_exported) },
                    onFailure = { failure ->
                        Log.e(TAG, "PDF image delivery failed", failure)
                        notifyError(PdfErrorCode.EXPORT_FAILED)
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "PDF image export failed", e)
                notifyError(PdfErrorCode.EXPORT_FAILED)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    // ------------------------------------------------------------ commit

    /**
     * Applies one operation in memory, writes the whole document to a temp file, snapshots the
     * previous working file for undo, then atomically replaces the working file and marks the
     * session dirty. The operation is the undo granularity.
     */
    private suspend fun runCommit(
        silentError: Boolean = false,
        snapshot: Boolean = true,
        render: Boolean = true,
        block: (PdfEngine) -> Unit,
    ): Boolean {
        val current = engine ?: run {
            Log.w(TAG, "commit skipped: no engine")
            return false
        }
        val file = workingFile ?: run {
            Log.w(TAG, "commit skipped: no working file")
            return false
        }
        val stack = undo ?: run {
            Log.w(TAG, "commit skipped: no undo stack")
            return false
        }
        _state.value = _state.value.copy(busy = true)
        return try {
            withContext(io) {
                val temp = File(file.parentFile, file.name + ".commit")
                block(current)
                current.saveTo(temp)
                if (!temp.isFile || temp.length() == 0L) {
                    temp.delete()
                    throw PdfException(PdfError(PdfErrorCode.SAVE_FAILED))
                }
                if (snapshot) stack.push(file)
                copyAtomic(temp, file)
                temp.delete()
                thumbnailCache.clear()
                val id = sessionId
                if (id != null) sessions.markDirty(id)
            }
            _state.value = _state.value.copy(
                dirty = true,
                undoDepth = undo?.depth() ?: 0,
                busy = false,
            )
            // The displayed page is a raster snapshot: every committed operation changes what
            // the page looks like (text, annotation, widget appearance, page geometry).
            if (render) renderPage(force = true)
            true
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(busy = false)
            throw e
        } catch (e: PdfException) {
            if (!silentError) notifyError(e.error.code, e.error.detail)
            _state.value = _state.value.copy(busy = false)
            false
        } catch (e: Exception) {
            // Unexpected engine/native failure: log the stack, keep the user-facing code typed.
            Log.e(TAG, "PDF commit failed", e)
            if (!silentError) notifyError(PdfErrorCode.SAVE_FAILED)
            _state.value = _state.value.copy(busy = false)
            false
        }
    }

    private suspend fun refreshAnnotations() {
        val current = engine ?: return
        val page = _state.value.pageIndex
        val list = withContext(io) { current.annotations(page) }
        _state.value = _state.value.copy(annotations = list)
    }

    private suspend fun refreshForms() {
        val current = engine ?: return
        val fields = withContext(io) { current.formFields() }
        _state.value = _state.value.copy(forms = fields)
    }

    // ------------------------------------------------------------ notices

    private fun notify(messageRes: Int, detail: String? = null) {
        _state.value = _state.value.copy(notice = PdfNotice(messageRes, detail))
    }

    private fun notifyError(code: PdfErrorCode, detail: String? = null) {
        _state.value = _state.value.copy(notice = PdfNotice(errorRes(code), detail, error = true))
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun errorRes(code: PdfErrorCode): Int = when (code) {
        PdfErrorCode.TOO_LARGE -> R.string.pdf_error_too_large
        PdfErrorCode.PAGE_LIMIT -> R.string.pdf_error_page_limit
        PdfErrorCode.OBJECT_LIMIT -> R.string.pdf_error_object_limit
        PdfErrorCode.NOT_A_PDF -> R.string.pdf_error_not_pdf
        PdfErrorCode.CORRUPT -> R.string.pdf_error_corrupt
        PdfErrorCode.PASSWORD_REQUIRED -> R.string.pdf_error_password_required
        PdfErrorCode.WRONG_PASSWORD -> R.string.pdf_error_wrong_password
        PdfErrorCode.READ_ONLY -> R.string.pdf_error_read_only
        PdfErrorCode.RENDER_FAILED -> R.string.pdf_error_render
        PdfErrorCode.NO_TEXT_LAYER -> R.string.pdf_error_no_text
        PdfErrorCode.EDIT_FAILED -> R.string.pdf_error_edit_failed
        PdfErrorCode.EDIT_UNSUPPORTED -> R.string.pdf_error_edit_unsupported
        PdfErrorCode.SAVE_FAILED -> R.string.pdf_error_save_failed
        PdfErrorCode.EXPORT_FAILED -> R.string.pdf_error_export_failed
        PdfErrorCode.IMPORT_FAILED -> R.string.pdf_error_import_failed
        PdfErrorCode.IMPORT_NOT_PDF -> R.string.pdf_error_import_not_pdf
        PdfErrorCode.FORM_FIELD_FAILED -> R.string.pdf_error_form_field
        PdfErrorCode.SIGN_FAILED -> R.string.pdf_error_sign_failed
        PdfErrorCode.SIGNATURE_MISSING_CERT -> R.string.pdf_error_sign_no_cert
        PdfErrorCode.SIGNATURE_WRONG_PASSWORD -> R.string.pdf_error_sign_wrong_password
        PdfErrorCode.SIGNATURE_UNSUPPORTED -> R.string.pdf_error_sign_unsupported
        PdfErrorCode.NO_ANNOTATION -> R.string.pdf_error_annotation
    }
}

/** Convenience for the editor screen: the suggested file name for a session. */
fun PdfUiState.suggestedName(extension: String): String {
    val base = session?.displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "document"
    return "$base.$extension"
}
