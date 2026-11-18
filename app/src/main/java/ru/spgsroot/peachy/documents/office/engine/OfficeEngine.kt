package ru.spgsroot.peachy.documents.office.engine

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import ru.spgsroot.peachy.documents.DocumentFormat

/** The rendered output of one [OfficeTileRequest]; the bitmap is owned by the caller. */
data class OfficeTile(val bitmap: Bitmap, val request: OfficeTileRequest)

/**
 * The app-side LibreOffice bridge.
 *
 * One engine, one open document, one thread. Every JNI call — including init and `paintTile` — runs
 * on [dispatcher], a dedicated single thread, which is the only model the pinned JNI glue supports:
 * it keeps `Office`/`Document` handles in direct buffers and registers document callbacks through a
 * single static slot. Nothing here touches the main thread.
 *
 * Source evidence for every adapted method is tabulated in `.impeccable/office-bridge-report.md`.
 * The bridge never copies a file name, path, password or callback payload into a log, a message or
 * a failure object.
 */
class OfficeEngine private constructor(private val appContext: Context) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

    private val gate = OfficeEngineGate(appContext)

    private val _state = MutableStateFlow<OfficeEngineState>(OfficeEngineState.Idle)
    val state: StateFlow<OfficeEngineState> = _state.asStateFlow()

    private val _signals = MutableSharedFlow<OfficeSignal>(
        extraBufferCapacity = SIGNAL_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Document callbacks reduced to payload-free signals; safe to collect on the main thread. */
    val signals: SharedFlow<OfficeSignal> = _signals.asSharedFlow()

    // Engine-thread state. Only [dispatcher] touches these fields, except [closed].
    @Volatile
    private var closed = false
    private var availabilityCache: OfficeAvailability? = null
    private var bindings: LokBindings? = null
    private var office: Any? = null
    private var document: Any? = null
    private var info: OfficeDocumentInfo? = null
    private var lastZoom: OfficeZoom? = null
    private var tileBuffer: ByteBuffer? = null
    private val editAndExport = Mutex()
    private var documentIdle: DocumentIdle? = null

    private class DocumentIdle {
        @Volatile
        var pending: CompletableDeferred<Boolean>? = null

        fun onCommandResult(payload: String) {
            val waiter = pending ?: return
            if (waiter.isCompleted) return
            val result = try {
                JSONObject(payload)
            } catch (_: org.json.JSONException) {
                return
            }
            if (result.optString("commandName") == REPORT_WHEN_IDLE &&
                result.optString("idleID") == INPUT_IDLE_ID
            ) {
                waiter.complete(true)
            }
        }
    }

    /** `tile_mode` from the manifest that passed the gate; render refuses without a verified mode. */
    private var declaredTileMode: String? = null

    /** Typed capability report; also the earliest point a route can decide to hide the editor. */
    suspend fun availability(): OfficeAvailability = withContext(dispatcher) { availabilityOnThread() }

    /**
     * Opens exactly one managed working copy. The file must be an absolute `working.<ext>` copy
     * inside `filesDir/documents/<session>/`, must not exceed the family cap, and its extension must
     * match [format]; [format] must be sniffed from bytes before the call. `password` is used once,
     * before the load, and is never retained.
     */
    suspend fun open(
        workingCopy: File,
        format: DocumentFormat,
        password: CharArray? = null,
    ): Result<OfficeDocumentInfo> = engineCall(OfficeStage.OPEN) {
        openOnThread(workingCopy, format, password)
    }

    /** Closes the document but keeps the engine warm. */
    suspend fun close(): Result<Unit> = engineCall(OfficeStage.CLOSE) {
        closeDocumentOnThread()
        Result.success(Unit)
    }

    /** Destroys document and office instance, then stops the engine thread. Never `destroyAndExit`. */
    suspend fun shutdown(): Result<Unit> {
        if (closed) return Result.success(Unit)
        val result = engineCall(OfficeStage.CLOSE) {
            closeDocumentOnThread()
            destroyOfficeOnThread()
            Result.success(Unit)
        }
        closed = true
        dispatcher.close()
        return result
    }

    suspend fun selectPart(index: Int): Result<Unit> = engineCall(OfficeStage.PART) {
        editAndExport.withLock { selectPartOnThread(index) }
    }

    suspend fun selectPartMode(mode: OfficePartMode): Result<Unit> = engineCall(OfficeStage.PART) {
        selectPartModeOnThread(mode)
    }

    /** Sets the pixel/twip ratio applied before the next `paintTile` call. */
    suspend fun setZoom(zoom: OfficeZoom): Result<Unit> = engineCall(OfficeStage.RENDER) {
        lastZoom = zoom
        val handle = document
        val bindings = bindings
        if (handle == null || bindings == null) {
            Result.success(Unit)
        } else {
            bindings.invoke(
                bindings.setClientZoom, handle,
                zoom.pixelWidth, zoom.pixelHeight, zoom.twipWidth, zoom.twipHeight,
            )
            Result.success(Unit)
        }
    }

    /**
     * Renders one tile into a fresh ARGB_8888 bitmap. The buffer handed to LOK is sized by
     * [OfficeTileRequest.bufferBytes] after validation against [OfficeTileLimits]; LOK writes without
     * a length argument.
     */
    suspend fun renderTile(request: OfficeTileRequest): Result<OfficeTile> = engineCall(OfficeStage.RENDER) {
        renderOnThread(request)
    }

    /** Sends one left-button click at a document coordinate in twips. */
    suspend fun tap(xTwips: Int, yTwips: Int, clicks: Int = 1): Result<Unit> = engineCall(OfficeStage.INPUT) {
        editAndExport.withLock { tapOnThread(xTwips, yTwips, clicks) }
    }

    /** Types text through code-point key events; see [OfficeTextInput] for why not `paste`. */
    suspend fun insertText(text: String): Result<Unit> = engineCall(OfficeStage.INPUT) {
        editAndExport.withLock { insertTextOnThread(text) }
    }

    suspend fun pressKey(key: OfficeKey): Result<Unit> = engineCall(OfficeStage.INPUT) {
        editAndExport.withLock { pressKeyOnThread(key) }
    }

    /** Applies one allowlisted command after queued input and waits for native editing to settle. */
    suspend fun apply(command: OfficeCommand): Result<Unit> = engineCall(OfficeStage.INPUT) {
        editAndExport.withLock { applyOnThread(command) }
    }

    /**
     * Writes the current state into a validated staged file under [exportDir]. The working copy and
     * the imported original are never modified, and no macro-enabled target exists in the matrix.
     */
    suspend fun export(
        target: OfficeExportTarget,
        exportDir: File,
    ): Result<OfficeExportPlan> = engineCall(OfficeStage.EXPORT) {
        editAndExport.withLock { exportOnThread(target, exportDir) }
    }

    /**
     * Reads the typed structure of the already opened, read-only legacy `.ppt`.
     *
     * Uses only the pinned `Document.getCommandValues` opt-in query on the engine thread: no
     * reopen, no Office-level extraction and no render/export substitution. Non-legacy or
     * non-presentation documents are refused, and malformed native JSON fails without retaining,
     * logging or attaching the payload.
     */
    internal suspend fun extractLegacyPresentation(): Result<List<OfficeLegacySlide>> =
        engineCall(OfficeStage.EXTRACT) { extractLegacyPresentationOnThread() }

    // ---- engine thread implementation ------------------------------------------------------

    private suspend fun <T> engineCall(stage: OfficeStage, block: suspend () -> Result<T>): Result<T> {
        if (closed) return Result.failure(OfficeFailure.Unavailable(OfficeUnavailableReason.INITIALISATION))
        return withContext(dispatcher) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (linkage: LinkageError) {
                markBroken(OfficeUnavailableReason.NATIVE_LIBRARIES)
                Result.failure(OfficeFailure.Unavailable(OfficeUnavailableReason.NATIVE_LIBRARIES))
            } catch (failure: OfficeFailure) {
                Result.failure(failure)
            } catch (other: Exception) {
                Result.failure(OfficeFailure.Engine(stage))
            }
        }
    }

    /**
     * A native-linkage failure means the loaded engine can no longer be trusted: drop the handles
     * without calling into them and report [reason] as the state, so no route keeps showing an
     * editor that cannot render.
     */
    private fun markBroken(reason: OfficeUnavailableReason) {
        document = null
        documentIdle?.pending?.complete(false)
        documentIdle = null
        info = null
        tileBuffer = null
        lastZoom = null
        _state.value = OfficeEngineState.Unavailable(reason)
    }

    private fun availabilityOnThread(): OfficeAvailability {
        availabilityCache?.let { return it }
        val probed = gate.probe()
        availabilityCache = probed
        if (probed is OfficeAvailability.Available) {
            declaredTileMode = probed.manifest.tileMode
        }
        if (probed is OfficeAvailability.Unavailable) {
            _state.value = OfficeEngineState.Unavailable(probed.reason)
        }
        return probed
    }

    private fun openOnThread(
        workingCopy: File,
        format: DocumentFormat,
        password: CharArray?,
    ): Result<OfficeDocumentInfo> {
        val openInfo = info
        if (document != null && openInfo != null) {
            return Result.failure(OfficeFailure.AlreadyOpen(openInfo.family))
        }
        val policy = OfficeFormatMatrix.policy(format)
            ?: return Result.failure(OfficeFailure.Unsupported(OfficeFeature.UNSUPPORTED_FORMAT, null))
        val availability = availabilityOnThread()
        if (availability is OfficeAvailability.Unavailable) {
            return Result.failure(OfficeFailure.Unavailable(availability.reason))
        }

        val canonical = managedWorkingCopy(workingCopy, format)
            ?: return Result.failure(OfficeFailure.Refused(OfficeRefusal.UNMANAGED_SOURCE))
        val lokUri = OfficeLokPaths.lokUri(canonical.absolutePath).getOrElse {
            return Result.failure(OfficeFailure.Refused(OfficeRefusal.UNMANAGED_SOURCE))
        }

        val resolved = initialiseOnThread().getOrElse { return Result.failure(it) }
        if (password != null && password.isNotEmpty()) {
            resolved.invoke(resolved.setDocumentPassword, office, lokUri, String(password))
        }

        var attempt = loadOnThread(resolved, lokUri)
        if (attempt == null) {
            recreateOfficeOnThread(resolved).getOrElse { return Result.failure(it) }
            attempt = loadOnThread(resolved, lokUri)
        }
        val handle = attempt ?: return Result.failure(OfficeFailure.Refused(OfficeRefusal.OPEN_FAILED))

        fun abort(failure: OfficeFailure): Result<OfficeDocumentInfo> {
            destroyDocument(resolved, handle)
            return Result.failure(failure)
        }

        resolved.invoke(resolved.initializeForRendering, handle)

        val family = OfficeFamily.fromDocumentType(resolved.invoke(resolved.getDocumentType, handle) as Int)
            ?: return abort(OfficeFailure.Refused(OfficeRefusal.FAMILY_MISMATCH))
        if (family != policy.family) return abort(OfficeFailure.Refused(OfficeRefusal.FAMILY_MISMATCH))

        val width = resolved.invoke(resolved.getDocumentWidth, handle) as Long
        val height = resolved.invoke(resolved.getDocumentHeight, handle) as Long
        if (!documentSizeIsRenderable(width, height)) {
            return abort(OfficeFailure.Refused(OfficeRefusal.INVALID_DOCUMENT_SIZE))
        }

        val parts = readParts(resolved, handle, family).getOrElse {
            return abort(it as? OfficeFailure ?: OfficeFailure.Engine(OfficeStage.OPEN))
        }

        val idle = DocumentIdle()
        val proxy = resolved.newDocumentCallback(idle::onCommandResult) { signal ->
            signalFor(signal)?.let { _signals.tryEmit(it) }
        }
        resolved.invoke(resolved.setDocumentMessageCallback, handle, proxy)

        if (family != OfficeFamily.TEXT && parts.isNotEmpty()) {
            resolved.invoke(resolved.setPart, handle, 0)
        }

        val pageRects = if (family == OfficeFamily.TEXT) {
            parsePageRects(resolved.invoke(resolved.getPartPageRectangles, handle) as? String)
        } else {
            emptyList()
        }

        val result = OfficeDocumentInfo(
            family = family,
            format = format,
            readOnly = !policy.editable,
            partMode = if (family.supportsPartMode) OfficePartMode.SLIDES else null,
            parts = parts,
            widthTwips = width,
            heightTwips = height,
            pageRects = pageRects,
        )
        document = handle
        documentIdle = idle
        info = result
        // A deterministic default so the first paint is never scaled by an unset client zoom.
        lastZoom = OfficeZoom.forZoom(1.0)
        tileBuffer = null
        _state.value = OfficeEngineState.Open(result)
        return Result.success(result)
    }

    private fun managedWorkingCopy(file: File, format: DocumentFormat): File? {
        if (!file.isAbsolute) return null
        val canonical = try {
            file.canonicalFile
        } catch (failure: IOException) {
            return null
        }
        if (!canonical.isFile) return null
        val length = canonical.length()
        if (length <= 0L || length > format.capBytes) return null
        val name = canonical.name
        if (!name.startsWith("working.") || canonical.extension.lowercase() != format.extension) return null
        val documentsRoot = try {
            File(appContext.filesDir, DOCUMENTS_DIRECTORY).canonicalFile
        } catch (failure: IOException) {
            return null
        }
        if (!canonical.path.startsWith(documentsRoot.path + File.separator)) return null
        return canonical
    }

    private fun initialiseOnThread(): Result<LokBindings> {
        val existingBindings = bindings
        if (existingBindings != null && office != null) return Result.success(existingBindings)

        val availability = availabilityOnThread()
        val available = when (availability) {
            is OfficeAvailability.Available -> availability
            is OfficeAvailability.Unavailable -> return initialisationFailure(availability.reason)
        }
        val resolved = try {
            existingBindings ?: LokBindings.resolve(appContext.classLoader).also { bindings = it }
        } catch (missing: LinkageError) {
            return initialisationFailure(OfficeUnavailableReason.NATIVE_LIBRARIES)
        } catch (mismatch: Exception) {
            return initialisationFailure(OfficeUnavailableReason.BRIDGE_CLASSES)
        }

        // Privacy gates. `lo_initialize()` reads these while the native init runs, and the loader's
        // static block has already run by now, so this is the last correct moment to set them.
        for (assignment in OfficeEnvironmentPolicy.ASSIGNMENTS) {
            try {
                resolved.invoke(resolved.putenv, null, assignment)
            } catch (missing: LinkageError) {
                return initialisationFailure(OfficeUnavailableReason.NATIVE_LIBRARIES)
            } catch (rejected: Exception) {
                return initialisationFailure(OfficeUnavailableReason.INITIALISATION)
            }
        }

        // Materialise the packaged `unpack` tree before any native init: the JNI derives
        // FONTCONFIG_FILE and the hook's program dir from this path, and the generated rc files
        // resolve $APP_DATA_DIR under it (see OfficeUnpack).
        val dataDir = OfficeUnpack(appContext).ensureExtracted(available.manifest.commit)
            .getOrElse { return initialisationFailure(OfficeUnavailableReason.ENGINE_ASSETS) }

        val initialised = try {
            resolved.invoke(
                resolved.initializeNative, null,
                dataDir.absolutePath,
                appContext.cacheDir.absolutePath,
                appContext.packageResourcePath,
                appContext.assets,
            ) as? Boolean ?: false
        } catch (missing: LinkageError) {
            return initialisationFailure(OfficeUnavailableReason.NATIVE_LIBRARIES)
        }
        if (!initialised) {
            return initialisationFailure(OfficeUnavailableReason.INITIALISATION)
        }
        return createOfficeFromHandle(resolved)
    }

    /** Records [reason] as the observable state before reporting it: no editor may stay visible. */
    private fun initialisationFailure(reason: OfficeUnavailableReason): Result<LokBindings> {
        _state.value = OfficeEngineState.Unavailable(reason)
        return Result.failure(OfficeFailure.Unavailable(reason))
    }

    private fun createOfficeFromHandle(bindings: LokBindings): Result<LokBindings> {
        val handle = bindings.invoke(bindings.getHandle, null) as? ByteBuffer
            ?: return initialisationFailure(OfficeUnavailableReason.INITIALISATION)
        office = bindings.officeCtor.newInstance(handle)
        return Result.success(bindings)
    }

    /** Mirrors the upstream client's recovery: destroy the office object, re-hook, load again. */
    private fun recreateOfficeOnThread(bindings: LokBindings): Result<Unit> {
        destroyOfficeOnThread()
        return createOfficeFromHandle(bindings).map { }
    }

    private fun loadOnThread(bindings: LokBindings, lokUri: String): Any? {
        val loaded = bindings.invoke(bindings.documentLoad, office, lokUri) ?: return null
        // The upstream client treats a non-empty office error as a failed load even with a handle;
        // the text itself is dropped because it may quote the document URL.
        val error = bindings.invoke(bindings.officeGetError, office) as? String
        if (!error.isNullOrEmpty()) {
            destroyDocument(bindings, loaded)
            return null
        }
        return loaded
    }

    private fun readParts(bindings: LokBindings, handle: Any, family: OfficeFamily): Result<List<OfficePart>> {
        if (family == OfficeFamily.TEXT) return Result.success(listOf(OfficePart(0, "")))
        val count = bindings.invoke(bindings.getParts, handle) as Int
        if (count < 0 || count > MAX_PARTS) {
            return Result.failure(OfficeFailure.Refused(OfficeRefusal.INVALID_DOCUMENT_SIZE))
        }
        val parts = ArrayList<OfficePart>(count)
        for (index in 0 until count) {
            val name = try {
                bindings.invoke(bindings.getPartName, handle, index) as? String ?: ""
            } catch (failure: Exception) {
                ""
            }
            parts.add(OfficePart(index, name))
        }
        return Result.success(parts)
    }

    private fun renderOnThread(request: OfficeTileRequest): Result<OfficeTile> {
        val handle = document ?: return Result.failure(OfficeFailure.NotOpen)
        val bindings = bindings ?: return Result.failure(OfficeFailure.Engine(OfficeStage.RENDER))
        // Defense in depth: the gate already refused a manifest without the pinned RGBA path, but a
        // build declaring bgra/unset must never reach `copyPixelsFromBuffer` with unknown channels.
        if (OfficeTilePixels.copyStrategy(declaredTileMode) == null) {
            return Result.failure(OfficeFailure.Unavailable(OfficeUnavailableReason.TILE_MODE))
        }
        if (request.violation() != null) {
            return Result.failure(OfficeFailure.Refused(OfficeRefusal.INVALID_INPUT))
        }
        lastZoom?.let { zoom ->
            bindings.invoke(
                bindings.setClientZoom, handle,
                zoom.pixelWidth, zoom.pixelHeight, zoom.twipWidth, zoom.twipHeight,
            )
        }
        val bytes = request.bufferBytes
        val buffer = bufferFor(bytes)
        bindings.invoke(
            bindings.paintTile, handle, buffer,
            request.canvasWidth, request.canvasHeight,
            request.tileXTwips, request.tileYTwips,
            request.tileWidthTwips, request.tileHeightTwips,
        )
        buffer.clear()
        buffer.limit(bytes)
        // Raw copy is correct by contract: the pinned build writes premultiplied RGBA and Android's
        // ARGB_8888 stores RGBA bytes (see OfficeTilePixels); no swizzle belongs here.
        val bitmap = Bitmap.createBitmap(request.canvasWidth, request.canvasHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return Result.success(OfficeTile(bitmap, request))
    }

    private fun bufferFor(bytes: Int): ByteBuffer {
        val existing = tileBuffer
        if (existing != null && existing.capacity() >= bytes) {
            existing.clear()
            existing.limit(bytes)
            return existing
        }
        val created = ByteBuffer.allocateDirect(bytes)
        tileBuffer = created
        return created
    }

    private fun tapOnThread(xTwips: Int, yTwips: Int, clicks: Int): Result<Unit> {
        if (clicks !in 1..3) return Result.failure(OfficeFailure.Refused(OfficeRefusal.INVALID_INPUT))
        if (xTwips < 0 || yTwips < 0) return Result.failure(OfficeFailure.Refused(OfficeRefusal.INVALID_INPUT))
        if (xTwips.toLong() > OfficeTileLimits.MAX_DOCUMENT_TWIPS ||
            yTwips.toLong() > OfficeTileLimits.MAX_DOCUMENT_TWIPS
        ) {
            return Result.failure(OfficeFailure.Refused(OfficeRefusal.INVALID_INPUT))
        }
        val handle = document ?: return Result.failure(OfficeFailure.NotOpen)
        val bindings = bindings ?: return Result.failure(OfficeFailure.Engine(OfficeStage.INPUT))
        bindings.invoke(
            bindings.postMouseEvent, handle,
            OfficeMouseEvent.BUTTON_DOWN, xTwips, yTwips, clicks,
            OfficeMouseEvent.BUTTON_LEFT, OfficeMouseEvent.MODIFIER_NONE,
        )
        bindings.invoke(
            bindings.postMouseEvent, handle,
            OfficeMouseEvent.BUTTON_UP, xTwips, yTwips, clicks,
            OfficeMouseEvent.BUTTON_LEFT, OfficeMouseEvent.MODIFIER_NONE,
        )
        return Result.success(Unit)
    }

    private fun insertTextOnThread(text: String): Result<Unit> {
        val info = info ?: return Result.failure(OfficeFailure.NotOpen)
        if (!info.family.editable) {
            return Result.failure(OfficeFailure.Unsupported(OfficeFeature.PRESENTATION_EDITING, info.family))
        }
        val handle = document ?: return Result.failure(OfficeFailure.NotOpen)
        val bindings = bindings ?: return Result.failure(OfficeFailure.Engine(OfficeStage.INPUT))
        val tokens = OfficeTextInput.tokens(text)
            ?: return Result.failure(OfficeFailure.Refused(OfficeRefusal.INVALID_INPUT))
        for (token in tokens) {
            when (token) {
                is OfficeInputToken.Character -> bindings.invoke(
                    bindings.postKeyEvent, handle, OfficeKeyEvent.PRESS, token.codePoint, 0,
                )

                is OfficeInputToken.Key -> {
                    bindings.invoke(bindings.postKeyEvent, handle, OfficeKeyEvent.PRESS, 0, token.key.code)
                    bindings.invoke(bindings.postKeyEvent, handle, OfficeKeyEvent.RELEASE, 0, token.key.code)
                }
            }
        }
        return Result.success(Unit)
    }

    private fun pressKeyOnThread(key: OfficeKey): Result<Unit> {
        val info = info ?: return Result.failure(OfficeFailure.NotOpen)
        if (!info.family.editable) {
            return Result.failure(OfficeFailure.Unsupported(OfficeFeature.PRESENTATION_EDITING, info.family))
        }
        val handle = document ?: return Result.failure(OfficeFailure.NotOpen)
        val bindings = bindings ?: return Result.failure(OfficeFailure.Engine(OfficeStage.INPUT))
        bindings.invoke(bindings.postKeyEvent, handle, OfficeKeyEvent.PRESS, 0, key.code)
        bindings.invoke(bindings.postKeyEvent, handle, OfficeKeyEvent.RELEASE, 0, key.code)
        return Result.success(Unit)
    }

    private suspend fun applyOnThread(command: OfficeCommand): Result<Unit> {
        val info = info ?: return Result.failure(OfficeFailure.NotOpen)
        if (!info.family.editable) {
            return Result.failure(OfficeFailure.Unsupported(OfficeFeature.PRESENTATION_EDITING, info.family))
        }
        if (!command.allowedFor(info.family)) {
            return Result.failure(OfficeFailure.Refused(OfficeRefusal.COMMAND_NOT_ALLOWED))
        }
        val handle = document ?: return Result.failure(OfficeFailure.NotOpen)
        val bindings = bindings ?: return Result.failure(OfficeFailure.Engine(OfficeStage.INPUT))
        if (!awaitIdleOnThread(bindings, handle)) return Result.failure(OfficeFailure.Engine(OfficeStage.INPUT))
        bindings.invoke(bindings.postUnoCommand, handle, command.uno, null, false)
        if (!awaitIdleOnThread(bindings, handle)) return Result.failure(OfficeFailure.Engine(OfficeStage.INPUT))
        return Result.success(Unit)
    }

    private suspend fun awaitIdleOnThread(bindings: LokBindings, handle: Any): Boolean {
        val idle = documentIdle ?: return false
        // The pinned LOK accepts only one outstanding ReportWhenIdle per document. A timed-out
        // request cannot be replaced until its callback arrives or the document is closed.
        if (idle.pending?.isCompleted == false) return false
        val waiter = CompletableDeferred<Boolean>()
        idle.pending = waiter
        bindings.invoke(bindings.postUnoCommand, handle, REPORT_WHEN_IDLE, INPUT_IDLE_ARGUMENTS, false)
        return withTimeoutOrNull(INPUT_IDLE_TIMEOUT_MS) { waiter.await() } == true && document === handle
    }

    private fun selectPartOnThread(index: Int): Result<Unit> {
        val info = info ?: return Result.failure(OfficeFailure.NotOpen)
        if (info.family == OfficeFamily.TEXT) {
            return if (index == 0) {
                Result.success(Unit)
            } else {
                Result.failure(OfficeFailure.Refused(OfficeRefusal.PART_OUT_OF_RANGE))
            }
        }
        if (index !in info.parts.indices) {
            return Result.failure(OfficeFailure.Refused(OfficeRefusal.PART_OUT_OF_RANGE))
        }
        val handle = document ?: return Result.failure(OfficeFailure.NotOpen)
        val bindings = bindings ?: return Result.failure(OfficeFailure.Engine(OfficeStage.PART))
        bindings.invoke(bindings.setPart, handle, index)
        return Result.success(Unit)
    }

    private fun selectPartModeOnThread(mode: OfficePartMode): Result<Unit> {
        val info = info ?: return Result.failure(OfficeFailure.NotOpen)
        if (!info.family.supportsPartMode) {
            return Result.failure(OfficeFailure.Refused(OfficeRefusal.PART_MODE_UNAVAILABLE))
        }
        val handle = document ?: return Result.failure(OfficeFailure.NotOpen)
        val bindings = bindings ?: return Result.failure(OfficeFailure.Engine(OfficeStage.PART))
        bindings.invoke(bindings.setPartMode, handle, mode.code)
        val parts = readParts(bindings, handle, info.family).getOrElse { return Result.failure(it) }
        val updated = info.copy(partMode = mode, parts = parts)
        this.info = updated
        _state.value = OfficeEngineState.Open(updated)
        return Result.success(Unit)
    }

    private suspend fun exportOnThread(target: OfficeExportTarget, exportDir: File): Result<OfficeExportPlan> {
        val info = info ?: return Result.failure(OfficeFailure.NotOpen)
        val handle = document ?: return Result.failure(OfficeFailure.NotOpen)
        val bindings = bindings ?: return Result.failure(OfficeFailure.Engine(OfficeStage.EXPORT))
        val token = UUID.randomUUID().toString().replace("-", "")
        val plan = OfficeExportPlanner.plan(info.format, target, exportDir, token).getOrElse {
            return Result.failure(it)
        }
        if (info.family.editable && !awaitIdleOnThread(bindings, handle)) {
            return Result.failure(OfficeFailure.Engine(OfficeStage.EXPORT))
        }
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            return Result.failure(OfficeFailure.ExportFailure(OfficeExportFailure.PATH_ESCAPE))
        }
        if (!exportDir.isDirectory) {
            return Result.failure(OfficeFailure.ExportFailure(OfficeExportFailure.PATH_ESCAPE))
        }
        val uri = OfficeLokPaths.lokUri(plan.stagedFile.absolutePath).getOrElse {
            return Result.failure(OfficeFailure.ExportFailure(OfficeExportFailure.PATH_ESCAPE))
        }
        plan.stagedFile.delete()
        bindings.invoke(bindings.saveAs, handle, uri, plan.formatArgument, plan.optionsArgument)
        val problem = OfficeExportValidator.check(plan.stagedFile, target)
        if (problem != null) {
            plan.stagedFile.delete()
            return Result.failure(OfficeFailure.ExportFailure(problem))
        }
        return Result.success(plan)
    }

    private fun extractLegacyPresentationOnThread(): Result<List<OfficeLegacySlide>> {
        val info = info ?: return Result.failure(OfficeFailure.NotOpen)
        if (info.family != OfficeFamily.PRESENTATION || info.format != DocumentFormat.PPT) {
            return Result.failure(OfficeFailure.Unsupported(OfficeFeature.TEXT_EXTRACTION, info.family))
        }
        val handle = document ?: return Result.failure(OfficeFailure.NotOpen)
        val bindings = bindings ?: return Result.failure(OfficeFailure.Engine(OfficeStage.EXTRACT))
        val raw = bindings.invoke(bindings.getCommandValues, handle, LEGACY_SLIDES_COMMAND) as? String
            ?: return Result.failure(OfficeFailure.Engine(OfficeStage.EXTRACT))
        val slides = parseLegacyPresentation(raw)
            ?: return Result.failure(OfficeFailure.Engine(OfficeStage.EXTRACT))
        return Result.success(slides)
    }

    private fun closeDocumentOnThread() {
        val handle = document ?: return
        documentIdle?.pending?.complete(false)
        documentIdle = null
        bindings?.let { destroyDocument(it, handle) }
        document = null
        info = null
        lastZoom = null
        tileBuffer = null
        _state.value = OfficeEngineState.Idle
    }

    private fun destroyDocument(bindings: LokBindings, handle: Any) {
        try {
            bindings.invoke(bindings.documentDestroy, handle)
        } catch (ignored: Exception) {
            // The handle is being discarded; a throwing destroy cannot be reported to the user.
        } catch (ignored: LinkageError) {
            // The engine is gone; nothing left to release.
        }
    }

    private fun destroyOfficeOnThread() {
        val office = office ?: return
        bindings?.let { bindings ->
            try {
                // `destroyAndExit()` is never called: it would kill the app process.
                bindings.invoke(bindings.officeDestroy, office)
            } catch (ignored: Exception) {
                // Discarding the engine instance; nothing user-visible to report.
            } catch (ignored: LinkageError) {
                // Same.
            }
        }
        this.office = null
    }

    private fun signalFor(signal: Int): OfficeSignal? = when (signal) {
        CALLBACK_INVALIDATE_TILES -> OfficeSignal.INVALIDATE_TILES
        CALLBACK_INVALIDATE_VISIBLE_CURSOR,
        CALLBACK_CELL_CURSOR,
        CALLBACK_CELL_ADDRESS,
        -> OfficeSignal.CURSOR_CHANGED

        CALLBACK_STATE_CHANGED -> OfficeSignal.STATE_CHANGED
        CALLBACK_DOCUMENT_SIZE_CHANGED -> OfficeSignal.DOCUMENT_SIZE_CHANGED
        CALLBACK_SET_PART -> OfficeSignal.PART_CHANGED
        CALLBACK_ERROR -> OfficeSignal.ERROR
        else -> null
    }

    companion object {
        const val THREAD_NAME = "peachy-office-engine"
        const val SIGNAL_BUFFER = 32
        const val MAX_PARTS = 4096

        /** Managed sessions live under `filesDir/documents`; nothing else may be opened. */
        const val DOCUMENTS_DIRECTORY = "documents"

        private const val REPORT_WHEN_IDLE = ".uno:ReportWhenIdle"
        private const val LEGACY_SLIDES_COMMAND = ".uno:ExtractDocumentStructure?filter=slides&legacyText=1"
        private const val INPUT_IDLE_ID = "peachy-office-input"
        private const val INPUT_IDLE_ARGUMENTS = """{"idleID":{"type":"string","value":"$INPUT_IDLE_ID"}}"""
        private const val INPUT_IDLE_TIMEOUT_MS = 5_000L

        // Document.java callback constants. Only the fixed idle acknowledgment needs its payload.
        const val CALLBACK_INVALIDATE_TILES = 0
        const val CALLBACK_INVALIDATE_VISIBLE_CURSOR = 1
        const val CALLBACK_STATE_CHANGED = 8
        const val CALLBACK_DOCUMENT_SIZE_CHANGED = 13
        const val CALLBACK_SET_PART = 14
        internal const val CALLBACK_UNO_COMMAND_RESULT = 16
        const val CALLBACK_CELL_CURSOR = 17
        const val CALLBACK_ERROR = 22
        const val CALLBACK_CELL_ADDRESS = 34

        fun create(context: Context): OfficeEngine = OfficeEngine(context.applicationContext)
    }
}
