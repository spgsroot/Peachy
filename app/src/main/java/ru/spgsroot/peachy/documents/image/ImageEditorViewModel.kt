package ru.spgsroot.peachy.documents.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.DocumentKind
import ru.spgsroot.peachy.documents.DocumentSessionsImpl
import ru.spgsroot.peachy.documents.imageHistoryFile

enum class ImageTool { NONE, CROP, ROTATE, ADJUST, DRAW }

data class ImageEditorState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val loadError: ImageLoadError? = null,
    val displayName: String = "",
    val raster: Bitmap? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val downscaled: Boolean = false,
    val animatedWebp: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val busy: Boolean = false,
    val dirty: Boolean = false,
    val tool: ImageTool = ImageTool.NONE,
) {
}

sealed interface ImageEditorEvent {
    data class ExportReady(val file: File, val format: ImageExportFormat, val closeAfterSave: Boolean) :
        ImageEditorEvent

    data object Saved : ImageEditorEvent
    data object SaveFailed : ImageEditorEvent
    data object EditFailed : ImageEditorEvent
    data object Discarded : ImageEditorEvent
    data object DiscardFailed : ImageEditorEvent
    data object SessionMissing : ImageEditorEvent
}

/**
 * The image editor holds one normalised base bitmap and an [OperationHistory]. Every committed edit
 * re-renders from the base. A short debounce coalesces persistence; each persisted image publishes
 * a lossless PNG together with its history. The imported file is never modified.
 */
@HiltViewModel
class ImageEditorViewModel @Inject constructor(
    private val sessions: DocumentSessionsImpl,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>("id").orEmpty()

    private val _state = MutableStateFlow(ImageEditorState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<ImageEditorEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private lateinit var directory: File
    private lateinit var workingFile: File
    private var useSource = true
    private var base: Bitmap? = null
    private var raster: Bitmap? = null
    private var history = OperationHistory()
    private var persistJob: Job? = null
    private var renderJob: Job? = null
    private val persistMutex = Mutex()

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val session = withContext(Dispatchers.IO) { sessions.get(sessionId) }
        if (session == null) {
            _state.update { it.copy(loading = false, missing = true) }
            return
        }
        directory = session.workingFile.parentFile ?: run {
            _state.update { it.copy(loading = false, missing = true) }
            return
        }
        workingFile = session.workingFile
        _state.update { it.copy(displayName = session.displayName, dirty = session.dirty) }

        val source = File(directory, "source.${session.format}")
        val storedOps = withContext(Dispatchers.IO) {
            val opsFile = imageHistoryFile(workingFile)
            runCatching { ImageOpsCodec.decode(opsFile.takeIf { it.isFile }?.readText()) }.getOrNull()
        }
        val animated = withContext(Dispatchers.IO) {
            session.format == DocumentFormat.WEBP.id && ImageRaster.isAnimatedWebp(source)
        }

        val loaded = if (workingFile.name.startsWith("working-") && storedOps == null) null else withContext(Dispatchers.Default) {
            runCatching { ImageRaster.load(source, session.format) }
        }
        val decoded = loaded?.getOrNull()
        val rendered = decoded?.let {
            withContext(Dispatchers.Default) { runCatching { renderFrom(it.bitmap, storedOps.orEmpty()) } }
        }
        val bitmap = rendered?.getOrNull()
        if (decoded == null || bitmap == null) {
            // If the source or its edits cannot be rendered, keep the last complete raster.
            val fallback = withContext(Dispatchers.Default) {
                runCatching {
                    ImageRaster.load(workingFile, if (workingFile.extension == "png") DocumentFormat.PNG.id else session.format)
                }.getOrNull()
            }
            if (fallback == null) {
                val error = ((loaded?.exceptionOrNull() ?: rendered?.exceptionOrNull()) as? ImageLoadException)?.reason
                    ?: ImageLoadError.DECODE_FAILED
                _state.update { it.copy(loading = false, loadError = error) }
                return
            }
            val fallbackCopy = withContext(Dispatchers.Default) {
                runCatching { fallback.bitmap.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull()
            }
            if (fallbackCopy == null) {
                _state.update { it.copy(loading = false, loadError = ImageLoadError.DECODE_FAILED) }
                return
            }
            useSource = false
            base = fallback.bitmap
            history = OperationHistory(emptyList())
            raster = fallbackCopy
            publish(animated)
            return
        }

        base = decoded.bitmap
        history = OperationHistory(storedOps.orEmpty())
        raster = bitmap
        publish(animated, decoded)
    }

    private fun renderFrom(base: Bitmap, ops: List<ru.spgsroot.peachy.documents.image.ImageOp>): Bitmap =
        if (ops.isEmpty()) {
            base.copy(Bitmap.Config.ARGB_8888, true) ?: throw java.io.IOException("Cannot copy the image")
        } else {
            ImageRaster.render(base, ops)
        }

    private fun publish(animated: Boolean, loaded: LoadedImage? = null) {
        val bitmap = raster ?: return
        _state.update {
            it.copy(
                loading = false,
                raster = bitmap,
                width = bitmap.width,
                height = bitmap.height,
                sourceWidth = loaded?.sourceWidth ?: bitmap.width,
                sourceHeight = loaded?.sourceHeight ?: bitmap.height,
                downscaled = loaded?.downscaled ?: false,
                animatedWebp = animated,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
            )
        }
    }

    fun setTool(tool: ImageTool) {
        _state.update { it.copy(tool = if (it.tool == tool) ImageTool.NONE else tool) }
    }

    fun commitCrop(rect: PxRect) {
        val clamped = rect.clampedTo(_state.value.width, _state.value.height) ?: return
        commit(ImageOp.Crop(clamped))
    }

    fun rotateQuarter(turns: Int) {
        val normalized = ((turns % 4) + 4) % 4
        if (normalized == 0) return
        commit(ImageOp.RotateQuarter(normalized))
    }

    fun rotateFree(degrees: Float) {
        val normalized = ImageGeometry.normalizeDegrees(degrees)
        if (kotlin.math.abs(normalized) < 0.05f) return
        commit(ImageOp.RotateFree(normalized))
    }

    fun commitAdjust(brightness: Float, contrast: Float, saturation: Float) {
        if (kotlin.math.abs(brightness) < 0.001f &&
            kotlin.math.abs(contrast) < 0.001f &&
            kotlin.math.abs(saturation) < 0.001f
        ) {
            return
        }
        commit(ImageOp.Adjust(brightness, contrast, saturation))
    }

    fun commitStroke(xs: FloatArray, ys: FloatArray, width: Float, color: Int) {
        if (xs.isEmpty() || xs.size != ys.size) return
        commit(ImageOp.Stroke(xs, ys, width.coerceIn(1f, 256f), color))
    }

    fun undo() {
        if (_state.value.busy || !history.canUndo) return
        renderChange(history.ops.subList(0, history.ops.lastIndex)) { history.undo() }
    }

    fun redo() {
        if (_state.value.busy) return
        val op = history.nextRedo ?: return
        renderChange(history.ops + op) { history.redo() }
    }

    private fun commit(op: ImageOp) {
        if (_state.value.busy || base == null) return
        renderChange(history.ops + op) { history.commit(op) }
    }

    private fun renderChange(ops: List<ImageOp>, applyHistory: () -> Unit) {
        val source = base ?: return
        _state.update { it.copy(busy = true) }
        renderJob = viewModelScope.launch {
            val rendered = withContext(Dispatchers.Default) {
                runCatching { ImageRaster.render(source, ops) }
            }
            rendered.fold(
                onSuccess = { bitmap ->
                    applyHistory()
                    raster = bitmap
                    _state.update {
                        it.copy(
                            raster = bitmap,
                            width = bitmap.width,
                            height = bitmap.height,
                            busy = false,
                            dirty = true,
                            canUndo = history.canUndo,
                            canRedo = history.canRedo,
                        )
                    }
                    schedulePersist()
                },
                onFailure = {
                    _state.update { it.copy(busy = false) }
                    _events.emit(ImageEditorEvent.EditFailed)
                },
            )
        }
    }

    /** Coalesces the working-file write: a stroke is committed instantly, encoded once it settles. */
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            try {
                persist()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _events.emit(ImageEditorEvent.SaveFailed)
            }
        }
    }

    private suspend fun persist() = persistMutex.withLock {
        val bitmap = raster ?: return@withLock
        val opsJson = if (useSource) ImageOpsCodec.encode(history.ops) else ""
        withContext(NonCancellable) {
            workingFile = sessions.publishImageEdit(sessionId, workingFile, opsJson) { stream ->
                ImageRaster.write(bitmap, ImageExportFormat.PNG, stream)
            }
        }
    }

    /** Writes an export into the session's exports/ directory; delivery happens on the picker result. */
    fun stageExport(format: ImageExportFormat, closeAfterSave: Boolean) {
        val bitmap = raster ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            if (closeAfterSave && _state.value.dirty) {
                // The copy and the retained session must represent the same visible edit.
                try {
                    persistJob?.cancelAndJoin()
                    persist()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(busy = false) }
                    _events.emit(ImageEditorEvent.SaveFailed)
                    return@launch
                }
            }
            val produced = withContext(Dispatchers.IO) {
                var file: File? = null
                runCatching {
                    file = sessions.exportFile(sessionId, format.extension)
                    ImageRaster.write(bitmap, format, file!!)
                    file!!
                }.onFailure { file?.delete() }
            }
            _state.update { it.copy(busy = false) }
            produced.fold(
                onSuccess = { file -> _events.emit(ImageEditorEvent.ExportReady(file, format, closeAfterSave)) },
                onFailure = { _events.emit(ImageEditorEvent.SaveFailed) },
            )
        }
    }

    fun deliver(produced: File, target: Uri, closeAfterSave: Boolean, onClose: () -> Unit) {
        viewModelScope.launch {
            sessions.deliver(sessionId, produced, DocumentKind.IMAGE, target).fold(
                onSuccess = {
                    withContext(Dispatchers.IO) { produced.delete() }
                    _events.emit(ImageEditorEvent.Saved)
                    if (closeAfterSave) onClose()
                },
                onFailure = { _events.emit(ImageEditorEvent.SaveFailed) },
            )
        }
    }

    fun discard(onClosed: () -> Unit) {
        viewModelScope.launch {
            persistJob?.cancelAndJoin()
            persistMutex.withLock { sessions.discard(sessionId) }.fold(
                onSuccess = {
                    _events.emit(ImageEditorEvent.Discarded)
                    onClosed()
                },
                onFailure = { _events.emit(ImageEditorEvent.DiscardFailed) },
            )
        }
    }

    override fun onCleared() {
        persistJob?.cancel()
        renderJob?.cancel()
        // Refs are dropped, not recycled: a Compose frame can still be drawing this bitmap while
        // the entry is being popped, and a recycled bitmap in a draw call crashes the app.
        raster = null
        base = null
        super.onCleared()
    }

    private companion object {
        // Coalesce edits before encoding the next working raster.
        const val PERSIST_DEBOUNCE_MS = 500L
    }
}
