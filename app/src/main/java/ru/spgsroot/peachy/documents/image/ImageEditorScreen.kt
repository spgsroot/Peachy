package ru.spgsroot.peachy.documents.image

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlin.math.max
import kotlin.math.min
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.documents.ui.LoadingState
import ru.spgsroot.peachy.documents.ui.MissingSessionState
import ru.spgsroot.peachy.documents.UnsavedChangesDialog

/**
 * The image editor: crop, rotate, colour and brush edits over a normalised raster, with the
 * operation list as the undo model and PNG/JPEG export through the system picker.
 *
 * The editor never asks the platform to keep a URI grant: the picked file was copied into the
 * session, and the export leaves through `CreateDocument`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorScreen(
    sessionId: String,
    onClose: () -> Unit,
    viewModel: ImageEditorViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showUnsaved by remember { mutableStateOf(false) }
    var showExportChooser by remember { mutableStateOf(false) }
    var closeAfterSave by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<File, ImageExportFormat>?>(null) }

    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val staged = pendingExport
        pendingExport = null
        if (staged != null && uri != null) {
            viewModel.deliver(staged.first, uri, closeAfterSave, onClose)
        } else {
            staged?.first?.delete()
        }
        closeAfterSave = false
    }
    val jpegLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
            val staged = pendingExport
            pendingExport = null
            if (staged != null && uri != null) {
                viewModel.deliver(staged.first, uri, closeAfterSave, onClose)
            } else {
                staged?.first?.delete()
            }
            closeAfterSave = false
        }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ImageEditorEvent.ExportReady -> {
                    pendingExport = event.file to event.format
                    closeAfterSave = event.closeAfterSave
                    val name = suggestedExportName(state.displayName, event.format)
                    if (event.format == ImageExportFormat.PNG) {
                        pngLauncher.launch(name)
                    } else {
                        jpegLauncher.launch(name)
                    }
                }

                ImageEditorEvent.Saved -> snackbar.showSnackbar(context.getString(R.string.img_saved))
                ImageEditorEvent.SaveFailed -> snackbar.showSnackbar(context.getString(R.string.img_save_failed))
                ImageEditorEvent.EditFailed -> snackbar.showSnackbar(context.getString(R.string.img_edit_failed))
                ImageEditorEvent.Discarded -> Unit
                ImageEditorEvent.DiscardFailed -> snackbar.showSnackbar(context.getString(R.string.img_discard_failed))
                ImageEditorEvent.SessionMissing -> onClose()
            }
        }
    }

    BackHandler {
        if (!state.busy) {
            if (state.dirty) showUnsaved = true else onClose()
        }
    }

    // Tool state lives here: the preview and the bottom bar are two views of the same edit.
    var cropRect by remember(state.width, state.height) {
        mutableStateOf(initialCrop(state.width, state.height))
    }
    var previewRotation by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(0f) }
    var strokeWidth by remember { mutableFloatStateOf(18f) }
    var strokeColor by remember { mutableStateOf(DRAW_COLORS.first()) }

    val ready = !state.loading && state.missing.not() && state.loadError == null && state.raster != null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.displayName.ifBlank { stringResource(R.string.img_title) },
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(enabled = !state.busy, onClick = { if (state.dirty) showUnsaved = true else onClose() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.documents_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo && !state.busy) {
                        Icon(Icons.Default.Undo, stringResource(R.string.img_undo))
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo && !state.busy) {
                        Icon(Icons.Default.Redo, stringResource(R.string.img_redo))
                    }
                    IconButton(
                        onClick = { showExportChooser = true },
                        enabled = ready && !state.busy
                    ) {
                        Icon(Icons.Default.Save, stringResource(R.string.documents_save_copy))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        bottomBar = {
            if (ready) {
                EditorBottomBar(
                    tool = state.tool,
                    enabled = !state.busy,
                    onTool = { tool ->
                        // An unapplied preview belongs to its tool: leaving the tool drops it.
                        if (tool != ImageTool.ROTATE) previewRotation = 0f
                        if (tool != ImageTool.ADJUST) {
                            brightness = 0f
                            contrast = 0f
                            saturation = 0f
                        }
                        viewModel.setTool(tool)
                    },
                    cropRect = cropRect,
                    onCropReset = { cropRect = initialCrop(state.width, state.height) },
                    onCropApply = { viewModel.commitCrop(cropRect) },
                    onRotateQuarter = viewModel::rotateQuarter,
                    rotation = previewRotation,
                    onRotation = { previewRotation = it },
                    onRotateApply = {
                        if (previewRotation != 0f) viewModel.rotateFree(previewRotation)
                        previewRotation = 0f
                    },
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    onBrightness = { brightness = it },
                    onContrast = { contrast = it },
                    onSaturation = { saturation = it },
                    onAdjustApply = {
                        viewModel.commitAdjust(brightness, contrast, saturation)
                        brightness = 0f
                        contrast = 0f
                        saturation = 0f
                    },
                    onAdjustReset = {
                        brightness = 0f
                        contrast = 0f
                        saturation = 0f
                    },
                    strokeWidth = strokeWidth,
                    onStrokeWidth = { strokeWidth = it },
                    strokeColor = strokeColor,
                    onStrokeColor = { strokeColor = it },
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val loadError = state.loadError
            when {
                state.loading -> LoadingState()
                state.missing -> MissingSessionState(onBack = onClose)
                loadError != null -> LoadErrorState(loadError, onClose)
                else -> Workspace(
                    state = state,
                    crop = cropRect,
                    onCropChange = { cropRect = it },
                    previewRotation = previewRotation,
                    colorFilter = if (state.tool == ImageTool.ADJUST) {
                        ColorFilter.colorMatrix(
                            ColorMatrix(ColorMath.matrix(brightness, contrast, saturation))
                        )
                    } else {
                        null
                    },
                    strokeWidth = strokeWidth,
                    strokeColor = strokeColor,
                    onStroke = { xs, ys -> viewModel.commitStroke(xs, ys, strokeWidth, strokeColor.toArgb()) }
                )
            }
        }
    }

    if (showUnsaved) {
        UnsavedChangesDialog(
            onSaveCopy = {
                showUnsaved = false
                closeAfterSave = true
                showExportChooser = true
            },
            onDiscard = {
                showUnsaved = false
                viewModel.discard(onClose)
            },
            onCancel = { showUnsaved = false }
        )
    }

    if (showExportChooser) {
        ExportChooser(
            width = state.width,
            height = state.height,
            onDismiss = {
                showExportChooser = false
                closeAfterSave = false
            },
            onPick = { format ->
                showExportChooser = false
                viewModel.stageExport(format, closeAfterSave)
            }
        )
    }
}

@Composable
private fun Workspace(
    state: ImageEditorState,
    crop: PxRect,
    onCropChange: (PxRect) -> Unit,
    previewRotation: Float,
    colorFilter: ColorFilter?,
    strokeWidth: Float,
    strokeColor: Color,
    onStroke: (FloatArray, FloatArray) -> Unit,
) {
    val bitmap = state.raster ?: return
    Column(Modifier.fillMaxSize()) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.animatedWebp) Notice(stringResource(R.string.img_animated_webp))
        if (state.downscaled) Notice(stringResource(R.string.img_downscaled, state.width, state.height))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Preview(
                bitmap = bitmap,
                state = state,
                crop = crop,
                onCropChange = onCropChange,
                previewRotation = previewRotation,
                colorFilter = colorFilter,
                onStroke = onStroke,
                strokeWidth = strokeWidth,
                strokeColor = strokeColor
            )
        }
    }
}

@Composable
private fun Preview(
    bitmap: Bitmap,
    state: ImageEditorState,
    crop: PxRect,
    onCropChange: (PxRect) -> Unit,
    previewRotation: Float,
    colorFilter: ColorFilter?,
    onStroke: (FloatArray, FloatArray) -> Unit,
    strokeWidth: Float,
    strokeColor: Color,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        if (boxWidth <= 0f || boxHeight <= 0f || state.width <= 0 || state.height <= 0) {
            return@BoxWithConstraints
        }
        val scale = min(boxWidth / state.width, boxHeight / state.height)
        val drawWidth = state.width * scale
        val drawHeight = state.height * scale
        Box(
            Modifier
                .size(with(density) { drawWidth.toDp() }, with(density) { drawHeight.toDp() })
                .align(Alignment.Center)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.img_title),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = previewRotation },
                colorFilter = colorFilter
            )
            when (state.tool) {
                ImageTool.CROP -> CropOverlay(
                    imageWidth = state.width,
                    imageHeight = state.height,
                    scale = scale,
                    crop = crop,
                    onCropChange = onCropChange
                )

                ImageTool.DRAW -> DrawOverlay(
                    scale = scale,
                    imageWidth = state.width,
                    imageHeight = state.height,
                    color = strokeColor,
                    width = strokeWidth,
                    onCommit = onStroke
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun CropOverlay(
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
    crop: PxRect,
    onCropChange: (PxRect) -> Unit,
) {
    val density = LocalDensity.current
    val handleTouch = with(density) { 28.dp.toPx() }
    val handleRadius = with(density) { 9.dp.toPx() }
    val handleInner = with(density) { 6.dp.toPx() }
    val borderWidth = with(density) { 2.dp.toPx() }
    val guideWidth = with(density) { 1.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)
    val innerColor = MaterialTheme.colorScheme.surfaceContainerLowest

    // The gesture detector must not restart mid-drag, so the live rect arrives through state.
    val currentCrop by rememberUpdatedState(crop)
    var dragMode by remember { mutableStateOf(CropDrag.NONE) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragStart by remember { mutableStateOf(crop) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(imageWidth, imageHeight, scale) {
                fun local(rect: PxRect): Triple<Offset, Size, Offset> {
                    val topLeft = Offset(rect.left * scale, rect.top * scale)
                    val size = Size(rect.width * scale, rect.height * scale)
                    return Triple(topLeft, size, Offset(topLeft.x + size.width, topLeft.y + size.height))
                }

                detectDragGestures(
                    onDragStart = { offset ->
                        val rect = currentCrop
                        val (topLeft, size, bottomRight) = local(rect)
                        dragStart = rect
                        dragOrigin = offset
                        dragMode = when {
                            (offset - topLeft).getDistance() <= handleTouch -> CropDrag.TOP_LEFT
                            (offset - Offset(bottomRight.x, topLeft.y)).getDistance() <= handleTouch ->
                                CropDrag.TOP_RIGHT

                            (offset - Offset(topLeft.x, bottomRight.y)).getDistance() <= handleTouch ->
                                CropDrag.BOTTOM_LEFT

                            (offset - bottomRight).getDistance() <= handleTouch -> CropDrag.BOTTOM_RIGHT
                            offset.x in topLeft.x..bottomRight.x &&
                                offset.y in topLeft.y..bottomRight.y -> CropDrag.MOVE

                            else -> CropDrag.NEW
                        }
                        if (dragMode == CropDrag.NEW) {
                            val px = offset.x / scale
                            val py = offset.y / scale
                            onCropChange(PxRect.fromDrag(px, py, px, py, imageWidth, imageHeight))
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val dx = dragAmount.x / scale
                        val dy = dragAmount.y / scale
                        val next = when (dragMode) {
                            CropDrag.NONE -> dragStart
                            CropDrag.NEW -> {
                                val endX = change.position.x / scale
                                val endY = change.position.y / scale
                                PxRect.fromDrag(
                                    dragOrigin.x / scale, dragOrigin.y / scale,
                                    endX, endY, imageWidth, imageHeight
                                )
                            }

                            CropDrag.MOVE -> {
                                val left = (dragStart.left + dx)
                                    .coerceIn(0f, (imageWidth - dragStart.width).toFloat()).toInt()
                                val top = (dragStart.top + dy)
                                    .coerceIn(0f, (imageHeight - dragStart.height).toFloat()).toInt()
                                PxRect(left, top, left + dragStart.width, top + dragStart.height)
                            }

                            CropDrag.TOP_LEFT -> PxRect(
                                (dragStart.left + dx).toInt().coerceIn(0, dragStart.right - PxRect.MIN_CROP),
                                (dragStart.top + dy).toInt().coerceIn(0, dragStart.bottom - PxRect.MIN_CROP),
                                dragStart.right,
                                dragStart.bottom,
                            ).clampedTo(imageWidth, imageHeight) ?: currentCrop

                            CropDrag.TOP_RIGHT -> PxRect(
                                dragStart.left,
                                (dragStart.top + dy).toInt().coerceIn(0, dragStart.bottom - PxRect.MIN_CROP),
                                (dragStart.right + dx).toInt()
                                    .coerceIn(dragStart.left + PxRect.MIN_CROP, imageWidth),
                                dragStart.bottom,
                            ).clampedTo(imageWidth, imageHeight) ?: currentCrop

                            CropDrag.BOTTOM_LEFT -> PxRect(
                                (dragStart.left + dx).toInt().coerceIn(0, dragStart.right - PxRect.MIN_CROP),
                                dragStart.top,
                                dragStart.right,
                                (dragStart.bottom + dy).toInt()
                                    .coerceIn(dragStart.top + PxRect.MIN_CROP, imageHeight),
                            ).clampedTo(imageWidth, imageHeight) ?: currentCrop

                            CropDrag.BOTTOM_RIGHT -> PxRect(
                                dragStart.left,
                                dragStart.top,
                                (dragStart.right + dx).toInt()
                                    .coerceIn(dragStart.left + PxRect.MIN_CROP, imageWidth),
                                (dragStart.bottom + dy).toInt()
                                    .coerceIn(dragStart.top + PxRect.MIN_CROP, imageHeight),
                            ).clampedTo(imageWidth, imageHeight) ?: currentCrop
                        }
                        onCropChange(next)
                    },
                    onDragEnd = { dragMode = CropDrag.NONE },
                    onDragCancel = { dragMode = CropDrag.NONE },
                )
            }
    ) {
        val left = crop.left * scale
        val top = crop.top * scale
        val width = crop.width * scale
        val height = crop.height * scale
        val canvasWidth = size.width
        val canvasHeight = size.height

        drawRect(scrim, size = Size(canvasWidth, top))
        drawRect(scrim, topLeft = Offset(0f, top + height), size = Size(canvasWidth, canvasHeight - top - height))
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, height))
        drawRect(scrim, topLeft = Offset(left + width, top), size = Size(canvasWidth - left - width, height))

        val guide = accent.copy(alpha = 0.35f)
        for (index in 1..2) {
            val x = left + width * index / 3f
            val y = top + height * index / 3f
            drawLine(guide, Offset(x, top), Offset(x, top + height), guideWidth)
            drawLine(guide, Offset(left, y), Offset(left + width, y), guideWidth)
        }
        drawRect(
            color = accent,
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = borderWidth)
        )
        listOf(
            Offset(left, top),
            Offset(left + width, top),
            Offset(left, top + height),
            Offset(left + width, top + height),
        ).forEach { corner ->
            drawCircle(color = accent, radius = handleRadius, center = corner)
            drawCircle(color = innerColor, radius = handleInner, center = corner)
        }
    }
}

private enum class CropDrag { NONE, NEW, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

@Composable
private fun DrawOverlay(
    scale: Float,
    imageWidth: Int,
    imageHeight: Int,
    color: Color,
    width: Float,
    onCommit: (FloatArray, FloatArray) -> Unit,
) {
    val points = remember { mutableStateListOf<Offset>() }
    val strokePx = width * scale
    val commit by rememberUpdatedState(onCommit)

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(scale, imageWidth, imageHeight) {
                detectDragGestures(
                    onDragStart = { offset ->
                        points.clear()
                        points.add(offset)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (points.size < MAX_STROKE_POINTS) points.add(change.position)
                    },
                    onDragEnd = {
                        if (points.isNotEmpty()) {
                            val xs = FloatArray(points.size) {
                                (points[it].x / scale).coerceIn(0f, imageWidth.toFloat())
                            }
                            val ys = FloatArray(points.size) {
                                (points[it].y / scale).coerceIn(0f, imageHeight.toFloat())
                            }
                            commit(xs, ys)
                        }
                        points.clear()
                    },
                    onDragCancel = { points.clear() }
                )
            }
    ) {
        if (points.isEmpty()) return@Canvas
        if (points.size == 1) {
            drawCircle(color = color, radius = strokePx / 2f, center = points[0])
            return@Canvas
        }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (index in 1 until points.size) lineTo(points[index].x, points[index].y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun EditorBottomBar(
    tool: ImageTool,
    enabled: Boolean,
    onTool: (ImageTool) -> Unit,
    cropRect: PxRect,
    onCropReset: () -> Unit,
    onCropApply: () -> Unit,
    onRotateQuarter: (Int) -> Unit,
    rotation: Float,
    onRotation: (Float) -> Unit,
    onRotateApply: () -> Unit,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    onBrightness: (Float) -> Unit,
    onContrast: (Float) -> Unit,
    onSaturation: (Float) -> Unit,
    onAdjustApply: () -> Unit,
    onAdjustReset: () -> Unit,
    strokeWidth: Float,
    onStrokeWidth: (Float) -> Unit,
    strokeColor: Color,
    onStrokeColor: (Color) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (tool) {
                ImageTool.CROP -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${cropRect.width} × ${cropRect.height}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCropReset) {
                        Text(stringResource(R.string.img_crop_reset))
                    }
                    TextButton(onClick = onCropApply, enabled = enabled) {
                        Text(stringResource(R.string.img_crop_apply))
                    }
                }

                ImageTool.ROTATE -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onRotateQuarter(-1) }, enabled = enabled) {
                            Icon(Icons.Default.RotateLeft, stringResource(R.string.img_rotate_left))
                        }
                        IconButton(onClick = { onRotateQuarter(1) }, enabled = enabled) {
                            Icon(Icons.Default.RotateRight, stringResource(R.string.img_rotate_right))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${rotation.toInt()}°",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onRotateApply, enabled = enabled && rotation != 0f) {
                            Text(stringResource(R.string.img_rotate_apply))
                        }
                    }
                    LabeledSlider(
                        label = stringResource(R.string.img_rotate_angle),
                        value = rotation,
                        range = -45f..45f,
                        enabled = enabled,
                        onChange = onRotation
                    )
                }

                ImageTool.ADJUST -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LabeledSlider(
                        stringResource(R.string.img_adjust_brightness), brightness, -0.6f..0.6f, enabled, onBrightness
                    )
                    LabeledSlider(
                        stringResource(R.string.img_adjust_contrast), contrast, -0.6f..0.6f, enabled, onContrast
                    )
                    LabeledSlider(
                        stringResource(R.string.img_adjust_saturation), saturation, -1f..1f, enabled, onSaturation
                    )
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = onAdjustReset, enabled = enabled) {
                            Text(stringResource(R.string.img_adjust_reset))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onAdjustApply, enabled = enabled) {
                            Text(stringResource(R.string.img_adjust_apply))
                        }
                    }
                }

                ImageTool.DRAW -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.img_draw_color),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        DRAW_COLORS.forEach { color ->
                            Box(
                                Modifier
                                    .padding(end = 8.dp)
                                    .size(36.dp)
                                    .border(
                                        width = if (color == strokeColor) 2.dp else 1.dp,
                                        color = if (color == strokeColor) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape
                                    )
                                    .padding(5.dp)
                                    .background(color, CircleShape)
                                    .clickable { onStrokeColor(color) }
                            )
                        }
                    }
                    LabeledSlider(
                        label = stringResource(R.string.img_draw_width),
                        value = strokeWidth,
                        range = 2f..96f,
                        enabled = enabled,
                        onChange = onStrokeWidth
                    )
                }

                ImageTool.NONE -> Unit
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolChip(ImageTool.CROP, Icons.Default.Crop, R.string.img_tool_crop, tool, onTool)
                ToolChip(ImageTool.ROTATE, Icons.Default.RotateRight, R.string.img_tool_rotate, tool, onTool)
                ToolChip(ImageTool.ADJUST, Icons.Default.Tune, R.string.img_tool_adjust, tool, onTool)
                ToolChip(ImageTool.DRAW, Icons.Default.Brush, R.string.img_tool_draw, tool, onTool)
            }
        }
    }
}

@Composable
private fun ToolChip(
    tool: ImageTool,
    icon: ImageVector,
    label: Int,
    selected: ImageTool,
    onTool: (ImageTool) -> Unit,
) {
    FilterChip(
        selected = selected == tool,
        onClick = { onTool(tool) },
        label = { Text(stringResource(label), maxLines = 1) },
        leadingIcon = { Icon(icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
        modifier = Modifier.heightIn(min = 48.dp)
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ExportChooser(
    width: Int,
    height: Int,
    onDismiss: () -> Unit,
    onPick: (ImageExportFormat) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.img_export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.img_export_note, width, height),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                ExportRow(
                    title = stringResource(R.string.img_export_png),
                    note = stringResource(R.string.img_export_png_note)
                ) { onPick(ImageExportFormat.PNG) }
                Spacer(Modifier.height(8.dp))
                ExportRow(
                    title = stringResource(R.string.img_export_jpeg),
                    note = stringResource(R.string.img_export_jpeg_note)
                ) { onPick(ImageExportFormat.JPEG) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.documents_dialog_cancel)) }
        }
    )
}

@Composable
private fun ExportRow(title: String, note: String, onPick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoadErrorState(error: ImageLoadError, onClose: () -> Unit) {
    val title = when (error) {
        ImageLoadError.HEIC_UNSUPPORTED -> R.string.img_heic_unsupported
        ImageLoadError.TOO_LARGE -> R.string.img_too_large
        ImageLoadError.DECODE_FAILED -> R.string.img_load_failed
    }
    val body = when (error) {
        ImageLoadError.HEIC_UNSUPPORTED -> R.string.img_heic_unsupported_body
        ImageLoadError.TOO_LARGE -> R.string.img_too_large_body
        ImageLoadError.DECODE_FAILED -> R.string.img_load_failed_body
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onClose) { Text(stringResource(R.string.documents_back)) }
    }
}

private fun initialCrop(width: Int, height: Int): PxRect {
    if (width <= PxRect.MIN_CROP || height <= PxRect.MIN_CROP) return PxRect(0, 0, width, height)
    val insetX = max(1, (width * 0.06f).toInt())
    val insetY = max(1, (height * 0.06f).toInt())
    return PxRect(insetX, insetY, width - insetX, height - insetY)
}

private fun suggestedExportName(displayName: String, format: ImageExportFormat): String {
    val base = displayName
        .substringBeforeLast('.')
        .map { if (it.isISOControl() || it in "\\/:*?\"<>|") ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(120)
    return "${base.ifEmpty { "Image" }}.${format.extension}"
}

private const val MAX_STROKE_POINTS = 4000

/** White first: it stays visible on a dark photo and on the graphitic editor surface. */
private val DRAW_COLORS = listOf(
    Color.White,
    Color(0xFF0B0B0D),
    Color(0xFFE7A7BE),
    Color(0xFFC97A9B),
    Color(0xFFD6B6C8),
)
