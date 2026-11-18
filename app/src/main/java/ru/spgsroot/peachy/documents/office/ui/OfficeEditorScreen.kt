package ru.spgsroot.peachy.documents.office.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.DocumentSession
import ru.spgsroot.peachy.documents.office.engine.OfficeCommand
import ru.spgsroot.peachy.documents.office.engine.OfficeDocumentInfo
import ru.spgsroot.peachy.documents.office.engine.OfficeExportTarget
import ru.spgsroot.peachy.documents.office.engine.OfficeFamily
import ru.spgsroot.peachy.documents.office.engine.OfficeKey
import ru.spgsroot.peachy.documents.office.engine.OfficePartMode
import ru.spgsroot.peachy.documents.office.engine.OfficeTileRequest
import ru.spgsroot.peachy.documents.office.engine.OfficeUnavailableReason

/**
 * The Office route: one engine, one document, one bounded viewport.
 *
 * Only a successful capability probe plus a successful `open` reaches a surface: [OfficePhase.READY]
 * for a rendered modern document and [OfficePhase.LEGACY] for the read-only text of a `.ppt`. The
 * build-level opt-in lives in [OfficeIntegration]. PDF and images are refused with their own copy
 * instead of a substitute editor. Edits stay in memory, so leaving an edited document is guarded
 * and export is the only way out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeEditorScreen(
    sessionId: String,
    onClose: () -> Unit,
    viewModel: OfficeEditorViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingTicket by remember { mutableStateOf<OfficeExportTicket?>(null) }
    var showTargets by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var leaveAfterExport by remember { mutableStateOf(false) }

    val info = state.info
    val controller = remember(info, state.partIndex, state.partMode) {
        if (info != null && state.phase == OfficePhase.READY) {
            OfficeViewportController(info)
        } else {
            null
        }
    }
    val exportTargets = remember(state.format, info) {
        val format = state.format
        if (format == null) emptyList() else OfficeExportTickets.targetsFor(format, info)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val ticket = pendingTicket
        pendingTicket = null
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (ticket != null && uri != null) {
            viewModel.deliver(ticket, uri)
        } else if (ticket != null) {
            leaveAfterExport = false
            viewModel.discardStaged(ticket)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                OfficeEvent.Left -> onClose()
                is OfficeEvent.Problem -> snackbar.showSnackbar(
                    context.getString(event.problem.textRes()),
                )

                is OfficeEvent.ExportReady -> {
                    pendingTicket = event.ticket
                    val intent = ActivityResultContracts.CreateDocument(event.ticket.mime)
                        .createIntent(context, event.ticket.fileName)
                    try {
                        exportLauncher.launch(intent)
                    } catch (missing: Exception) {
                        pendingTicket = null
                        leaveAfterExport = false
                        viewModel.discardStaged(event.ticket)
                        snackbar.showSnackbar(context.getString(R.string.office_error_export))
                    }
                }

                OfficeEvent.Exported -> {
                    snackbar.showSnackbar(context.getString(R.string.office_notice_exported))
                    if (leaveAfterExport) {
                        leaveAfterExport = false
                        viewModel.leave()
                    }
                }
            }
        }
    }

    // Back is refused while an engine/SAF operation runs: leaving would cancel a copy mid-write.
    val attemptLeave: () -> Unit = {
        when (OfficeEditPolicy.leave(state.edited, state.busy)) {
            OfficeLeave.BUSY -> scope.launch {
                snackbar.showSnackbar(context.getString(R.string.office_busy_wait))
            }

            OfficeLeave.CONFIRM -> showLeave = true
            OfficeLeave.LEAVE -> when (state.phase) {
                OfficePhase.READY, OfficePhase.LEGACY -> viewModel.leave()
                else -> onClose()
            }
        }
    }

    BackHandler(onBack = attemptLeave)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.session?.displayName
                            ?: stringResource(R.string.office_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = attemptLeave, enabled = !state.busy) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.documents_back),
                        )
                    }
                },
                actions = {
                    // The text-only `.ppt` surface has no actions: nothing may leak document content.
                    if (state.phase != OfficePhase.LEGACY) {
                        IconButton(
                            onClick = { showTargets = true },
                            enabled = state.phase == OfficePhase.READY && !state.busy,
                        ) {
                            Icon(
                                Icons.Default.SaveAlt,
                                stringResource(R.string.office_export_copy),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            if (state.phase == OfficePhase.READY && info != null && controller != null) {
                OfficeBottomPanel(
                    info = info,
                    format = state.format,
                    partIndex = state.partIndex,
                    partMode = state.partMode,
                    edited = state.edited,
                    busy = state.busy,
                    controller = controller,
                    onPart = viewModel::selectPart,
                    onPartMode = viewModel::selectPartMode,
                    onCommand = viewModel::apply,
                    onPressKey = viewModel::pressKey,
                    onInsert = viewModel::insertText,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.phase) {
                OfficePhase.LOADING -> OfficeLoadingState()

                OfficePhase.MISSING -> OfficeMissingState(onBack = onClose)

                OfficePhase.REFUSED -> OfficeGateMessage(
                    title = stringResource(refusalTitle(state.refusal)),
                    body = stringResource(refusalBody(state.refusal)),
                    session = state.session,
                    allowDelete = state.session != null,
                    busy = state.busy,
                    onDelete = viewModel::discardSession,
                    onBack = onClose,
                )

                OfficePhase.UNAVAILABLE -> {
                    val unavailable = state.unavailable
                    val problem = state.problem
                    OfficeGateMessage(
                        title = stringResource(
                            if (unavailable != null) {
                                R.string.office_unavailable_title
                            } else {
                                R.string.office_gate_refused_title
                            },
                        ),
                        body = stringResource(
                            when {
                                unavailable != null -> unavailableText(unavailable)
                                problem != null -> problem.textRes()
                                else -> R.string.office_error_engine
                            },
                        ),
                        session = state.session,
                        allowDelete = state.session != null && state.info == null,
                        busy = state.busy,
                        onDelete = viewModel::discardSession,
                        onBack = onClose,
                    )
                }

                OfficePhase.READY -> {
                    if (info != null && controller != null) {
                        OfficeViewport(
                            info = info,
                            state = state,
                            controller = controller,
                            editable = OfficeEditPolicy.isEditable(info),
                            onRender = viewModel::render,
                            onTap = viewModel::tap,
                        )
                    }
                }

                OfficePhase.LEGACY -> OfficeLegacyText(
                    state = state.legacy ?: OfficeLegacyTextState.Loading,
                    onBack = onClose,
                )
            }
        }
    }

    if (showLeave) {
        OfficeLeaveDialog(
            onSaveCopy = {
                showLeave = false
                leaveAfterExport = true
                showTargets = true
            },
            onDiscard = {
                showLeave = false
                viewModel.leave()
            },
            onCancel = { showLeave = false },
        )
    }

    val targetFormat = state.format
    if (showTargets && targetFormat != null) {
        OfficeExportDialog(
            targets = exportTargets,
            notices = OfficeEditPolicy.notices(targetFormat, info),
            onPick = { target ->
                showTargets = false
                viewModel.stageExport(target)
            },
            onDismiss = {
                showTargets = false
                leaveAfterExport = false
            },
        )
    }
}

@Composable
private fun OfficeViewport(
    info: OfficeDocumentInfo,
    state: OfficeUiState,
    controller: OfficeViewportController,
    editable: Boolean,
    onRender: (OfficeTileRequest) -> Unit,
    onTap: (Int, Int) -> Unit,
) {
    val description = stringResource(R.string.office_viewport_description)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val measurable = widthPx in 1..MAX_VIEWPORT_PX && heightPx in 1..MAX_VIEWPORT_PX
        if (measurable) {
            LaunchedEffect(widthPx, heightPx) { controller.setViewport(widthPx, heightPx) }
        }
        val tile = controller.tile
        LaunchedEffect(tile?.request) {
            val request = tile?.request ?: return@LaunchedEffect
            delay(OfficeViewportLimits.RENDER_SETTLE_MS)
            onRender(request)
        }

        val bitmap = state.bitmap
        val bitmapRequest = state.bitmapRequest
        val image = remember(bitmap) { bitmap?.asImageBitmap() }

        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .semantics { contentDescription = description }
                .pointerInput(info, editable) {
                    detectTapGestures { point ->
                        if (editable) {
                            controller.twipsAt(point.x, point.y)?.let { (x, y) -> onTap(x, y) }
                        }
                    }
                }
                .pointerInput(info) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        if (gestureZoom != 1f) {
                            controller.zoomBy(gestureZoom, centroid.x, centroid.y)
                        }
                        if (pan.x != 0f || pan.y != 0f) {
                            controller.panBy(pan.x.toDouble(), pan.y.toDouble())
                        }
                    }
                },
        ) {
            if (image != null && bitmapRequest != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val ppt = OfficeViewportMath.pixelsPerTwip(controller.zoom)
                    val left = bitmapRequest.tileXTwips * ppt - controller.offsetX
                    val top = bitmapRequest.tileYTwips * ppt - controller.offsetY
                    val width = bitmapRequest.tileWidthTwips * ppt
                    val height = bitmapRequest.tileHeightTwips * ppt
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(
                            width.roundToInt().coerceAtLeast(1),
                            height.roundToInt().coerceAtLeast(1),
                        ),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }

            if (bitmap == null) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.office_preparing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.rendering) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun OfficeBottomPanel(
    info: OfficeDocumentInfo,
    format: DocumentFormat?,
    partIndex: Int,
    partMode: OfficePartMode,
    edited: Boolean,
    busy: Boolean,
    controller: OfficeViewportController,
    onPart: (Int) -> Unit,
    onPartMode: (OfficePartMode) -> Unit,
    onCommand: (OfficeCommand) -> Unit,
    onPressKey: (OfficeKey) -> Unit,
    onInsert: (String) -> Unit,
) {
    val editable = OfficeEditPolicy.isEditable(info)
    val notices = remember(format, info) {
        format?.let { OfficeEditPolicy.notices(it, info) }.orEmpty()
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            notices.forEach { notice ->
                Text(
                    text = stringResource(noticeText(notice)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (edited) {
                Text(
                    text = stringResource(R.string.office_edited),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            OfficeNavigationRow(
                info = info,
                partIndex = partIndex,
                partMode = partMode,
                controller = controller,
                onPart = onPart,
                onPartMode = onPartMode,
            )
            if (editable) {
                OfficeCommandRow(
                    info = info,
                    enabled = !busy,
                    onCommand = onCommand,
                )
                OfficeKeyRow(enabled = !busy, onPressKey = onPressKey)
                OfficeTextEntryRow(enabled = !busy, onInsert = onInsert)
            } else {
                Text(
                    text = stringResource(R.string.office_read_only_badge),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun OfficeNavigationRow(
    info: OfficeDocumentInfo,
    partIndex: Int,
    partMode: OfficePartMode,
    controller: OfficeViewportController,
    onPart: (Int) -> Unit,
    onPartMode: (OfficePartMode) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (info.family) {
            OfficeFamily.TEXT -> {
                val pages = info.pageRects.size
                if (pages > 1) {
                    val current = controller.currentPage
                    IconButton(
                        onClick = { controller.jumpToPage((current - 1).coerceAtLeast(0)) },
                        enabled = current > 0,
                    ) {
                        Icon(
                            Icons.Outlined.ChevronLeft,
                            stringResource(R.string.office_page_previous),
                        )
                    }
                    Text(
                        text = stringResource(R.string.office_page_of, current + 1, pages),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = {
                            controller.jumpToPage((current + 1).coerceAtMost(pages - 1))
                        },
                        enabled = current < pages - 1,
                    ) {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            stringResource(R.string.office_page_next),
                        )
                    }
                }
            }

            OfficeFamily.SPREADSHEET -> if (info.parts.size > 1) {
                IconButton(
                    onClick = { onPart((partIndex - 1).coerceAtLeast(0)) },
                    enabled = partIndex > 0,
                ) {
                    Icon(
                        Icons.Outlined.ChevronLeft,
                        stringResource(R.string.office_part_previous),
                    )
                }
                Text(
                    text = stringResource(R.string.office_part_of, partIndex + 1, info.parts.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { onPart((partIndex + 1).coerceAtMost(info.parts.lastIndex)) },
                    enabled = partIndex < info.parts.lastIndex,
                ) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        stringResource(R.string.office_part_next),
                    )
                }
            }

            OfficeFamily.PRESENTATION -> {
                if (info.parts.size > 1) {
                    IconButton(
                        onClick = { onPart((partIndex - 1).coerceAtLeast(0)) },
                        enabled = partIndex > 0,
                    ) {
                        Icon(
                            Icons.Outlined.ChevronLeft,
                            stringResource(R.string.office_part_previous),
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.office_slide_of,
                            partIndex + 1,
                            info.parts.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = { onPart((partIndex + 1).coerceAtMost(info.parts.lastIndex)) },
                        enabled = partIndex < info.parts.lastIndex,
                    ) {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            stringResource(R.string.office_part_next),
                        )
                    }
                }
                FilterChip(
                    selected = partMode == OfficePartMode.SLIDES,
                    onClick = { onPartMode(OfficePartMode.SLIDES) },
                    label = { Text(stringResource(R.string.office_slides)) },
                )
                FilterChip(
                    selected = partMode == OfficePartMode.NOTES,
                    onClick = { onPartMode(OfficePartMode.NOTES) },
                    label = { Text(stringResource(R.string.office_notes)) },
                )
            }
        }

        Spacer(Modifier.width(8.dp))
        IconButton(onClick = {
            controller.zoomBy(
                1f / ZOOM_STEP,
                controller.viewportWidth / 2f,
                controller.viewportHeight / 2f,
            )
        }) {
            Icon(Icons.Default.ZoomOut, stringResource(R.string.office_zoom_out))
        }
        Text(
            text = stringResource(R.string.office_zoom_percent, (controller.zoom * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = {
            controller.zoomBy(
                ZOOM_STEP,
                controller.viewportWidth / 2f,
                controller.viewportHeight / 2f,
            )
        }) {
            Icon(Icons.Default.ZoomIn, stringResource(R.string.office_zoom_in))
        }
        IconButton(onClick = controller::fit) {
            Icon(Icons.Default.FitScreen, stringResource(R.string.office_zoom_fit))
        }
    }
}

@Composable
private fun OfficeCommandRow(
    info: OfficeDocumentInfo,
    enabled: Boolean,
    onCommand: (OfficeCommand) -> Unit,
) {
    val allowed = remember(info) { OfficeEditPolicy.commands(info).toSet() }
    val format = listOf(
        CommandSpec(OfficeCommand.BOLD, Icons.Default.FormatBold, R.string.office_cmd_bold),
        CommandSpec(OfficeCommand.ITALIC, Icons.Default.FormatItalic, R.string.office_cmd_italic),
        CommandSpec(OfficeCommand.UNDERLINE, Icons.Default.FormatUnderlined, R.string.office_cmd_underline),
    )
    val paragraph = when (info.family) {
        OfficeFamily.TEXT -> listOf(
            CommandSpec(OfficeCommand.CENTER_PARA, Icons.Default.FormatAlignCenter, R.string.office_cmd_center),
            CommandSpec(OfficeCommand.JUSTIFY_PARA, Icons.Default.FormatAlignJustify, R.string.office_cmd_justify),
            CommandSpec(OfficeCommand.BULLETS, Icons.Default.FormatListBulleted, R.string.office_cmd_bullets),
            CommandSpec(OfficeCommand.NUMBERING, Icons.Default.FormatListNumbered, R.string.office_cmd_numbering),
        )

        else -> emptyList()
    }
    val common = listOf(
        CommandSpec(OfficeCommand.UNDO, Icons.Default.Undo, R.string.office_cmd_undo),
        CommandSpec(OfficeCommand.REDO, Icons.Default.Redo, R.string.office_cmd_redo),
        CommandSpec(OfficeCommand.SELECT_ALL, Icons.Default.SelectAll, R.string.office_cmd_select_all),
        CommandSpec(OfficeCommand.DELETE, Icons.Default.Delete, R.string.office_cmd_delete),
    )

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (format + paragraph + common)
            .filter { it.command in allowed }
            .forEach { spec ->
                IconButton(onClick = { onCommand(spec.command) }, enabled = enabled) {
                    Icon(spec.icon, stringResource(spec.label))
                }
            }
        if (info.family == OfficeFamily.SPREADSHEET) {
            SHEET_STRUCTURE
                .filter { (command, _) -> command in allowed }
                .forEach { (command, label) ->
                    TextButton(
                        onClick = { onCommand(command) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(label))
                    }
                }
        }
    }
}

@Composable
private fun OfficeKeyRow(enabled: Boolean, onPressKey: (OfficeKey) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onPressKey(OfficeKey.BACKSPACE) }, enabled = enabled) {
            Icon(
                Icons.AutoMirrored.Filled.Backspace,
                stringResource(R.string.office_key_backspace),
            )
        }
        TextButton(
            onClick = { onPressKey(OfficeKey.RETURN) },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.office_key_return))
        }
        TextButton(
            onClick = { onPressKey(OfficeKey.TAB) },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.office_key_tab))
        }
        IconButton(onClick = { onPressKey(OfficeKey.DELETE) }, enabled = enabled) {
            Icon(Icons.Default.Delete, stringResource(R.string.office_key_delete))
        }
        IconButton(onClick = { onPressKey(OfficeKey.LEFT) }, enabled = enabled) {
            Icon(Icons.Default.KeyboardArrowLeft, stringResource(R.string.office_key_left))
        }
        IconButton(onClick = { onPressKey(OfficeKey.UP) }, enabled = enabled) {
            Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.office_key_up))
        }
        IconButton(onClick = { onPressKey(OfficeKey.DOWN) }, enabled = enabled) {
            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.office_key_down))
        }
        IconButton(onClick = { onPressKey(OfficeKey.RIGHT) }, enabled = enabled) {
            Icon(Icons.Default.KeyboardArrowRight, stringResource(R.string.office_key_right))
        }
    }
}

@Composable
private fun OfficeTextEntryRow(enabled: Boolean, onInsert: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val insertable = remember(draft) { OfficeEditPolicy.isInsertable(draft) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.office_insert_label)) },
            singleLine = true,
            isError = draft.isNotEmpty() && !insertable,
            supportingText = {
                if (draft.isNotEmpty() && !insertable) {
                    Text(
                        text = stringResource(R.string.office_text_invalid),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                onInsert(draft)
                draft = ""
            },
            enabled = enabled && insertable,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Text(stringResource(R.string.office_insert_action))
        }
    }
}

@Composable
private fun OfficeLoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun OfficeMissingState(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.documents_missing_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.documents_missing_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.documents_back))
        }
    }
}

@Composable
private fun OfficeGateMessage(
    title: String,
    body: String,
    session: DocumentSession?,
    allowDelete: Boolean,
    busy: Boolean,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        if (session != null) {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (allowDelete) {
            Spacer(Modifier.height(28.dp))
            Button(onClick = onDelete, enabled = !busy) {
                Text(stringResource(R.string.documents_office_delete))
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.documents_back))
        }
    }
}

@Composable
private fun OfficeLeaveDialog(
    onSaveCopy: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.office_leave_title)) },
        text = {
            Text(
                text = stringResource(R.string.office_leave_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onSaveCopy) {
                Text(stringResource(R.string.office_leave_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.office_leave_discard))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.office_leave_cancel))
                }
            }
        },
    )
}

@Composable
internal fun OfficeExportDialog(
    targets: List<OfficeExportTarget>,
    notices: List<OfficeNotice>,
    onPick: (OfficeExportTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.office_export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.office_export_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (OfficeNotice.XLSB_EXPORT in notices) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.office_notice_xlsb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                targets.forEach { target ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onPick(target) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(
                                text = target.extension.uppercase(java.util.Locale.ROOT),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (target == OfficeExportTarget.CSV || target == OfficeExportTarget.TSV) {
                                Text(
                                    text = stringResource(R.string.office_export_active_sheet_only),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.documents_dialog_cancel))
            }
        },
    )
}

private data class CommandSpec(
    val command: OfficeCommand,
    val icon: ImageVector,
    @StringRes val label: Int,
)

private val SHEET_STRUCTURE: List<Pair<OfficeCommand, Int>> = listOf(
    OfficeCommand.INSERT_ROWS_BEFORE to R.string.office_cmd_row_above,
    OfficeCommand.INSERT_ROWS_AFTER to R.string.office_cmd_row_below,
    OfficeCommand.INSERT_COLUMNS_BEFORE to R.string.office_cmd_column_before,
    OfficeCommand.INSERT_COLUMNS_AFTER to R.string.office_cmd_column_after,
    OfficeCommand.DELETE_ROWS to R.string.office_cmd_delete_row,
    OfficeCommand.DELETE_COLUMNS to R.string.office_cmd_delete_column,
    OfficeCommand.MERGE_CELLS to R.string.office_cmd_merge,
    OfficeCommand.SPLIT_CELL to R.string.office_cmd_split,
)

private const val ZOOM_STEP = 1.25f

/** Bound on the measured viewport; a larger value would be a measurement bug, not a device. */
private const val MAX_VIEWPORT_PX = 8192

@StringRes
private fun refusalTitle(reason: OfficeEntryRefusal?): Int = when (reason) {
    OfficeEntryRefusal.INTEGRATION_DISABLED -> R.string.office_gate_disabled_title
    else -> R.string.office_gate_refused_title
}

@StringRes
private fun refusalBody(reason: OfficeEntryRefusal?): Int = when (reason) {
    OfficeEntryRefusal.INTEGRATION_DISABLED -> R.string.office_gate_disabled_body
    OfficeEntryRefusal.UNKNOWN_FORMAT -> R.string.office_refused_unknown
    OfficeEntryRefusal.NOT_OFFICE -> R.string.office_refused_not_office
    null -> R.string.office_refused_unknown
}

@StringRes
private fun unavailableText(reason: OfficeUnavailableReason): Int = when (reason) {
    OfficeUnavailableReason.BRIDGE_CLASSES -> R.string.office_unavailable_bridge_classes
    OfficeUnavailableReason.NATIVE_LIBRARIES -> R.string.office_unavailable_native_libraries
    OfficeUnavailableReason.ENGINE_ASSETS -> R.string.office_unavailable_engine_assets
    OfficeUnavailableReason.BUILD_MANIFEST -> R.string.office_unavailable_build_manifest
    OfficeUnavailableReason.TILE_MODE -> R.string.office_unavailable_tile_mode
    OfficeUnavailableReason.INITIALISATION -> R.string.office_unavailable_initialisation
    OfficeUnavailableReason.MACRO_LOCK -> R.string.office_unavailable_macro_lock
}

@StringRes
private fun noticeText(notice: OfficeNotice): Int = when (notice) {
    OfficeNotice.EDITS_IN_MEMORY -> R.string.office_notice_edits_memory
    OfficeNotice.LEGACY_FIDELITY -> R.string.office_notice_legacy
    OfficeNotice.MACRO_INPUT -> R.string.office_notice_macro
    OfficeNotice.XLSB_EXPORT -> R.string.office_notice_xlsb
    OfficeNotice.READ_ONLY_PRESENTATION -> R.string.office_notice_read_only
}

@StringRes
private fun OfficeProblem.textRes(): Int = when (this) {
    OfficeProblem.ENGINE_UNAVAILABLE -> R.string.office_error_engine_unavailable
    OfficeProblem.ENGINE_BROKEN -> R.string.office_error_engine
    OfficeProblem.ENGINE_BUSY -> R.string.office_error_busy
    OfficeProblem.OPEN_FAILED -> R.string.office_error_open
    OfficeProblem.FAMILY_MISMATCH -> R.string.office_error_family
    OfficeProblem.INVALID_DOCUMENT -> R.string.office_error_document
    OfficeProblem.UNMANAGED_SOURCE -> R.string.office_error_managed
    OfficeProblem.PART_OUT_OF_RANGE -> R.string.office_error_part
    OfficeProblem.INPUT_INVALID -> R.string.office_error_input
    OfficeProblem.READ_ONLY -> R.string.office_error_read_only
    OfficeProblem.COMMAND_NOT_ALLOWED -> R.string.office_error_command
    OfficeProblem.RENDER_FAILED -> R.string.office_error_render
    OfficeProblem.EXPORT_FAILED -> R.string.office_error_export
    OfficeProblem.EXPORT_INVALID -> R.string.office_error_export_invalid
    OfficeProblem.SAVE_FAILED -> R.string.office_error_save
    OfficeProblem.DELETE_FAILED -> R.string.office_error_delete
    OfficeProblem.NOT_OPEN -> R.string.office_error_not_open
    OfficeProblem.ENGINE_FAILED -> R.string.office_error_engine
}
