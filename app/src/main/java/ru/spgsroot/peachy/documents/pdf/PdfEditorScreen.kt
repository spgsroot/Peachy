package ru.spgsroot.peachy.documents.pdf

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.documents.UnsavedChangesDialog
import kotlin.math.abs

private enum class PdfSheet { ANNOTATIONS, FORMS, PAGES, SIGNATURES, SEARCH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfEditorScreen(sessionId: String, onClose: () -> Unit) {
    val viewModel: PdfEditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var sheet by remember { mutableStateOf<PdfSheet?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var unsavedDialog by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf<PdfPoint?>(null) }
    var textBoxDialog by remember { mutableStateOf<PdfRect?>(null) }
    var imageExportDialog by remember { mutableStateOf(false) }
    var passwordDialog by remember { mutableStateOf<PdfPasswordAction?>(null) }
    var p12PasswordDialog by remember { mutableStateOf<Uri?>(null) }
    var pendingImport by remember { mutableStateOf(false) }
    var exportTree by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(sessionId) { viewModel.attach(sessionId) }

    LaunchedEffect(state.notice?.id) {
        state.notice?.let { notice ->
            val text = context.getString(notice.messageRes) +
                (notice.detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            snackbar.showSnackbar(text)
            viewModel.consumeNotice()
        }
    }

    LaunchedEffect(state.savedCopy) {
        if (state.savedCopy) onClose()
    }

    LaunchedEffect(state.passwordAction) {
        when (state.passwordAction) {
            PdfPasswordAction.OPEN -> passwordDialog = PdfPasswordAction.OPEN
            PdfPasswordAction.UNDO -> passwordDialog = PdfPasswordAction.UNDO
            null -> passwordDialog = null
        }
    }

    var closeAfterSave by remember { mutableStateOf(false) }
    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportPdfCopy(uri, state.suggestedName("pdf"), closeWhenDone = closeAfterSave)
        }
        unsavedDialog = false
    }
    val saveTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) viewModel.exportText(uri, state.suggestedName("txt"))
    }
    val exportFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            state.session?.id?.let { id ->
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            exportTree = uri
            imageExportDialog = true
        }
    }
    val importPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importPages(uri)
        pendingImport = false
    }
    val p12Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) p12PasswordDialog = uri
    }

    BackHandler(enabled = true) {
        when {
            state.signatureMutationPending -> viewModel.cancelSignatureMutation()
            passwordDialog != null -> {
                passwordDialog = null
                viewModel.cancelPassword()
            }
            state.textEditor != null -> viewModel.dismissTextEditor()
            sheet != null -> sheet = null
            state.selection != null -> viewModel.clearSelection()
            state.dirty -> unsavedDialog = true
            else -> onClose()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (state.phase) {
                PdfPhase.LOADING -> LoadingState()
                PdfPhase.LOCKED -> LockedState(
                    rejected = state.passwordRejected,
                    onSubmit = { viewModel.submitPassword(it) },
                )
                PdfPhase.FAILED -> FailedState(state.error, onClose)
                PdfPhase.READY -> EditorBody(
                    state = state,
                    onBack = { if (state.dirty) unsavedDialog = true else onClose() },
                    onMenu = { menuOpen = true },
                    onUndo = viewModel::undo,
                    onPageTap = { point ->
                        when (state.tool) {
                            PdfTool.SELECT -> viewModel.selectAt(point)
                            PdfTool.NOTE -> noteDialog = point
                            PdfTool.TEXT_BOX -> textBoxDialog =
                                PdfRect(point.x, point.y, point.x + 220f, point.y + 80f)
                            PdfTool.SIGNATURE -> viewModel.placePendingSignature(signatureRectAt(point))
                            else -> Unit
                        }
                    },
                    onPageDragSelect = viewModel::selectRange,
                    onAnnotationDrag = viewModel::moveSelectedAnnotation,
                    onInk = { points -> viewModel.addInkStroke(points) },
                    onInkDone = viewModel::finishInkStrokes,
                    onZoom = viewModel::setZoom,
                    onViewport = viewModel::setViewport,
                    onPreviousPage = viewModel::previousPage,
                    onNextPage = viewModel::nextPage,
                    onOpenPages = { sheet = PdfSheet.PAGES },
                    onSearch = { sheet = PdfSheet.SEARCH },
                    onAnnotations = { sheet = PdfSheet.ANNOTATIONS },
                    onForms = { sheet = PdfSheet.FORMS },
                    onCopySelection = {
                        state.selection?.text?.takeIf { it.isNotBlank() }?.let { text ->
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("PDF", text)),
                                )
                                snackbar.showSnackbar(context.getString(R.string.pdf_notice_copied))
                            }
                        }
                    },
                    onEditText = viewModel::beginTextEdit,
                    onHighlight = { viewModel.addMarkup(PdfTool.HIGHLIGHT) },
                    onUnderline = { viewModel.addMarkup(PdfTool.UNDERLINE) },
                    onStrikeout = { viewModel.addMarkup(PdfTool.STRIKEOUT) },
                    onDeleteAnnotation = viewModel::deleteSelectedAnnotation,
                    onSetTool = viewModel::setTool,
                    onLoadThumbnail = viewModel::thumbnail,
                    onToggleThumbnails = viewModel::toggleThumbnails,
                    onSelectPage = viewModel::goToPage,
                )
            }
        }
    }

    if (menuOpen) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pdf_menu_save_copy)) },
                leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    savePdfLauncher.launch(state.suggestedName("pdf"))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pdf_menu_export_text)) },
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    saveTextLauncher.launch(state.suggestedName("txt"))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pdf_menu_export_images)) },
                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                enabled = state.phase == PdfPhase.READY,
                onClick = {
                    menuOpen = false
                    exportFolderLauncher.launch(null)
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pdf_menu_signatures)) },
                leadingIcon = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) },
                enabled = state.phase == PdfPhase.READY,
                onClick = {
                    menuOpen = false
                    sheet = PdfSheet.SIGNATURES
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pdf_thumbnails_menu)) },
                leadingIcon = { Icon(Icons.Outlined.ViewCarousel, contentDescription = null) },
                trailingIcon = if (state.showThumbnails) {
                    { Icon(Icons.Outlined.Check, contentDescription = null) }
                } else {
                    null
                },
                onClick = {
                    menuOpen = false
                    viewModel.toggleThumbnails()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pdf_menu_rotate)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = null) },
                enabled = state.phase == PdfPhase.READY && !state.readOnly,
                onClick = {
                    menuOpen = false
                    viewModel.rotateCurrentPage(90)
                },
            )
        }
    }

    when (sheet) {
        PdfSheet.ANNOTATIONS -> AnnotationsSheet(
            state = state,
            onDismiss = { sheet = null },
            onTool = { tool -> viewModel.setTool(tool); sheet = null },
            onDelete = viewModel::deleteSelectedAnnotation,
            onSelect = viewModel::selectAnnotation,
        )
        PdfSheet.FORMS -> FormsSheet(
            state = state,
            onDismiss = { sheet = null },
            onText = viewModel::setFormText,
            onToggle = viewModel::toggleForm,
            onChoice = viewModel::setFormChoice,
            onFlatten = { viewModel.flattenForms(); sheet = null },
        )
        PdfSheet.PAGES -> PagesSheet(
            state = state,
            onDismiss = { sheet = null },
            onGoTo = { viewModel.goToPage(it); sheet = null },
            onInsertBlank = viewModel::insertBlankPage,
            onImport = { pendingImport = true; importPdfLauncher.launch(arrayOf("application/pdf")) },
            onDelete = viewModel::deleteCurrentPage,
            onMoveUp = { viewModel.moveCurrentPage(-1) },
            onMoveDown = { viewModel.moveCurrentPage(1) },
            onRotate = { viewModel.rotateCurrentPage(90) },
        )
        PdfSheet.SIGNATURES -> SignaturesSheet(
            state = state,
            onDismiss = { sheet = null },
            onDrawnSignature = { strokes ->
                viewModel.stashSignatureStrokes(strokes)
                sheet = null
            },
            onCryptoSignature = {
                sheet = null
                viewModel.requestCertificatePicker {
                    p12Launcher.launch(arrayOf("application/x-pkcs12", "application/octet-stream"))
                }
            },
        )
        PdfSheet.SEARCH -> SearchSheet(
            state = state,
            onDismiss = { sheet = null },
            onQuery = viewModel::setQuery,
            onSearch = viewModel::runSearch,
            onNext = viewModel::nextHit,
            onPrevious = viewModel::previousHit,
        )
        null -> Unit
    }

    state.textEditor?.let { editor ->
        TextEditDialog(
            editor = editor,
            onDraft = viewModel::updateTextDraft,
            onDismiss = viewModel::dismissTextEditor,
            onApply = viewModel::requestApplyTextEdit,
        )
        if (editor.confirming && !state.signatureMutationPending) {
            DestructiveEditDialog(
                onConfirm = viewModel::applyTextEdit,
                onCancel = viewModel::cancelApplyTextEdit,
            )
        }
    }

    if (state.signatureMutationPending) {
        AlertDialog(
            onDismissRequest = viewModel::cancelSignatureMutation,
            title = { Text(stringResource(R.string.pdf_signatures_title)) },
            text = { Text(stringResource(R.string.pdf_edit_signed_warning)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSignatureMutation) {
                    Text(stringResource(R.string.pdf_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelSignatureMutation) {
                    Text(stringResource(R.string.pdf_cancel))
                }
            },
        )
    }

    noteDialog?.let { point ->
        TextInputDialog(
            title = stringResource(R.string.pdf_note_title),
            label = stringResource(R.string.pdf_note_label),
            hint = stringResource(R.string.pdf_note_hint),
            onDismiss = { noteDialog = null },
            onConfirm = { text ->
                viewModel.addTextNote(point, text)
                noteDialog = null
            },
        )
    }

    textBoxDialog?.let { rect ->
        TextInputDialog(
            title = stringResource(R.string.pdf_textbox_title),
            label = stringResource(R.string.pdf_textbox_label),
            hint = stringResource(R.string.pdf_textbox_hint),
            onDismiss = { textBoxDialog = null },
            onConfirm = { text ->
                viewModel.addTextBox(rect, text)
                textBoxDialog = null
            },
        )
    }

    if (imageExportDialog) {
        ImageExportDialog(
            onDismiss = { imageExportDialog = false },
            onExport = { dpi, jpeg ->
                exportTree?.let { viewModel.exportImages(it, dpi, jpeg) }
                imageExportDialog = false
            },
        )
    }

    passwordDialog?.let { action ->
        PasswordDialog(
            title = when (action) {
                PdfPasswordAction.OPEN -> stringResource(R.string.pdf_password_title)
                PdfPasswordAction.UNDO -> stringResource(R.string.pdf_password_undo_title)
            },
            message = when (action) {
                PdfPasswordAction.OPEN -> stringResource(R.string.pdf_password_message)
                PdfPasswordAction.UNDO -> stringResource(R.string.pdf_password_undo_message)
            },
            rejected = state.passwordRejected,
            onDismiss = {
                passwordDialog = null
                viewModel.cancelPassword()
            },
            onSubmit = { password ->
                viewModel.submitPassword(password)
                if (action == PdfPasswordAction.UNDO) passwordDialog = null
            },
        )
    }

    p12PasswordDialog?.let { uri ->
        P12PasswordDialog(
            onDismiss = { p12PasswordDialog = null },
            onSubmit = { password ->
                viewModel.signWithPkcs12(uri, password, "")
                p12PasswordDialog = null
            },
        )
    }

    if (unsavedDialog) {
        UnsavedChangesDialog(
            onSaveCopy = {
                closeAfterSave = true
                savePdfLauncher.launch(state.suggestedName("pdf"))
            },
            onDiscard = { unsavedDialog = false; viewModel.discardSession(onClose) },
            onCancel = { unsavedDialog = false },
        )
    }

    if (pendingImport && state.phase != PdfPhase.READY) pendingImport = false
}

/** A signature-sized box for the autograph placement tap. */
private fun signatureRectAt(point: PdfPoint) = PdfRect(point.x - 8f, point.y - 60f, point.x + 172f, point.y)

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.pdf_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LockedState(rejected: Boolean, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.pdf_password_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.pdf_password_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.pdf_password_field)) },
            singleLine = true,
            isError = rejected,
            supportingText = if (rejected) {
                { Text(stringResource(R.string.pdf_error_wrong_password)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = { onSubmit(password) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.pdf_password_unlock))
        }
    }
}

@Composable
private fun FailedState(error: PdfError?, onClose: () -> Unit) {
    val message = when (error?.code) {
        PdfErrorCode.TOO_LARGE -> stringResource(R.string.pdf_error_too_large)
        PdfErrorCode.PAGE_LIMIT -> stringResource(R.string.pdf_error_page_limit)
        PdfErrorCode.OBJECT_LIMIT -> stringResource(R.string.pdf_error_object_limit)
        PdfErrorCode.NOT_A_PDF -> stringResource(R.string.pdf_error_not_pdf)
        null, PdfErrorCode.CORRUPT -> stringResource(R.string.pdf_error_corrupt)
        else -> stringResource(R.string.pdf_error_corrupt)
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        error?.detail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onClose, modifier = Modifier.height(48.dp)) {
            Text(stringResource(R.string.pdf_close))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorBody(
    state: PdfUiState,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onUndo: () -> Unit,
    onPageTap: (PdfPoint) -> Unit,
    onPageDragSelect: (PdfPoint, PdfPoint) -> Unit,
    onAnnotationDrag: (Float, Float) -> Unit,
    onInk: (List<PdfPoint>) -> Unit,
    onInkDone: () -> Unit,
    onZoom: (Float) -> Unit,
    onViewport: (Int, Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onOpenPages: () -> Unit,
    onSearch: () -> Unit,
    onAnnotations: () -> Unit,
    onForms: () -> Unit,
    onCopySelection: () -> Unit,
    onEditText: () -> Unit,
    onLoadThumbnail: suspend (Int, Int) -> ImageBitmap?,
    onToggleThumbnails: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onHighlight: () -> Unit,
    onUnderline: () -> Unit,
    onStrikeout: () -> Unit,
    onDeleteAnnotation: () -> Unit,
    onSetTool: (PdfTool) -> Unit,
) {
    val density = LocalDensity.current
    val bottomInset = WindowInsets.navigationBars
        .union(WindowInsets.mandatorySystemGestures)
        .only(WindowInsetsSides.Bottom)
        .let { insets ->
            with(density) {
                insets.getBottom(density).toDp()
            }
        }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = state.session?.displayName ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(R.string.pdf_page_indicator, state.pageIndex + 1, state.pageCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.pdf_back),
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = onUndo,
                    enabled = state.undoDepth > 0 && !state.readOnly && !state.busy,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.pdf_undo))
                }
                IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.pdf_more))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        if (state.readOnly) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.pdf_read_only_banner),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            PageCanvas(
                state = state,
                onViewport = onViewport,
                onTap = onPageTap,
                onDragSelect = onPageDragSelect,
                onAnnotationDrag = onAnnotationDrag,
                onInk = onInk,
                onInkDone = onInkDone,
                onZoom = onZoom,
            )
            if (state.page == null) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            when (state.tool) {
                PdfTool.INK -> ToolHint(
                    stringResource(R.string.pdf_hint_ink),
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
                PdfTool.NOTE -> ToolHint(
                    stringResource(R.string.pdf_hint_note),
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
                PdfTool.TEXT_BOX -> ToolHint(
                    stringResource(R.string.pdf_hint_textbox),
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
                PdfTool.SIGNATURE -> ToolHint(
                    stringResource(R.string.pdf_hint_place_signature),
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
                else -> Unit
            }
        }

        if (state.selection != null || state.selectedAnnotation != null) {
            SelectionBar(
                selection = state.selection,
                canDeleteAnnotation = state.selectedAnnotation != null,
                onCopy = onCopySelection,
                onEditText = onEditText,
                onHighlight = onHighlight,
                onUnderline = onUnderline,
                onStrikeout = onStrikeout,
                onDelete = onDeleteAnnotation,
                onClose = { onSetTool(PdfTool.SELECT) },
            )
        }

        if (state.showThumbnails) {
            ThumbnailStrip(
                state = state,
                load = onLoadThumbnail,
                onSelect = onSelectPage,
            )
        }

        PageBar(
            page = state.pageIndex + 1,
            pages = state.pageCount,
            enabled = !state.busy,
            onPrevious = onPreviousPage,
            onNext = onNextPage,
            onOpenPages = onOpenPages,
        )

        BottomToolBar(
            state = state,
            bottomInset = bottomInset,
            onSetTool = onSetTool,
            onSearch = onSearch,
            onAnnotations = onAnnotations,
            onForms = onForms,
            onPages = onOpenPages,
        )
    }
}

@Composable
private fun ThumbnailStrip(
    state: PdfUiState,
    load: suspend (Int, Int) -> ImageBitmap?,
    onSelect: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val heightPx = with(density) { 56.dp.roundToPx() }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(state.pageCount) { index ->
            ThumbnailItem(
                index = index,
                selected = index == state.pageIndex,
                heightPx = heightPx,
                load = load,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun ThumbnailItem(
    index: Int,
    selected: Boolean,
    heightPx: Int,
    load: suspend (Int, Int) -> ImageBitmap?,
    onSelect: (Int) -> Unit,
) {
    var thumb by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, heightPx, selected) {
        if (thumb == null) thumb = load(index, heightPx)
    }
    val label = stringResource(R.string.pdf_page_thumbnail, index + 1)
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 56.dp)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = { onSelect(index) },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(width = 40.dp, height = 48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(4.dp),
                    ),
            )
        } else {
            Box(
                Modifier
                    .size(width = 40.dp, height = 48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

@Composable
private fun ToolHint(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PageCanvas(
    state: PdfUiState,
    onViewport: (Int, Int) -> Unit,
    onTap: (PdfPoint) -> Unit,
    onDragSelect: (PdfPoint, PdfPoint) -> Unit,
    onAnnotationDrag: (Float, Float) -> Unit,
    onInk: (List<PdfPoint>) -> Unit,
    onInkDone: () -> Unit,
    onZoom: (Float) -> Unit,
) {
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val scale = state.renderedScale
    val page = state.page

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .onSizeChanged {
                viewport = it
                onViewport(it.width, it.height)
            }
            .pointerInput(state.pageIndex, state.tool, state.selectedAnnotation, scale, page?.width, page?.height) {
                // Centre offset of the drawn page inside the viewport, in pixels. It mirrors the
                // draw pass exactly so a tap maps to the glyph under the finger at every zoom.
                fun toPagePoint(position: Offset): PdfPoint {
                    val contentWidth = page?.width?.toFloat() ?: 0f
                    val contentHeight = page?.height?.toFloat() ?: 0f
                    val centerX = (size.width - contentWidth) / 2f
                    val centerY = (size.height - contentHeight) / 2f
                    return PdfPoint(
                        (position.x - pan.x - centerX).coerceAtLeast(0f) / scale,
                        (position.y - pan.y - centerY).coerceAtLeast(0f) / scale,
                    )
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPage = toPagePoint(down.position)
                    var dragged = false
                    var multi = false
                    var last = down.position
                    val points = mutableListOf<Offset>()
                    points += down.position
                    var zoomAccum = 1f
                    var panAccum = Offset.Zero
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val active = event.changes.count { it.pressed }
                        if (active > 1) {
                            multi = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f) zoomAccum *= zoomChange
                            panAccum += panChange
                            event.changes.forEach { it.consume() }
                        } else {
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null) {
                                if (!dragged &&
                                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                ) {
                                    dragged = true
                                }
                                if (dragged) {
                                    points += change.position
                                    change.consume()
                                }
                                last = change.position
                            }
                        }
                        pressed = event.changes.any { it.pressed }
                    }

                    if (multi) {
                        pan += panAccum
                        val next = (state.zoom * zoomAccum).coerceIn(1f, 8f)
                        if (abs(next - state.zoom) > 0.05f) onZoom(next)
                    } else if (!dragged) {
                        onTap(startPage)
                    } else {
                        when (state.tool) {
                            PdfTool.SELECT -> {
                                if (state.selectedAnnotation != null) {
                                    onAnnotationDrag(
                                        (last.x - down.position.x) / scale,
                                        (last.y - down.position.y) / scale,
                                    )
                                } else {
                                    onDragSelect(startPage, toPagePoint(last))
                                }
                            }
                            PdfTool.INK -> {
                                onInk(points.map { toPagePoint(it) })
                                onInkDone()
                            }
                            else -> Unit
                        }
                    }
                }
            },
    ) {
        if (page != null) {
            Canvas(Modifier.fillMaxSize()) {
                val contentWidth = page.width.toFloat()
                val contentHeight = page.height.toFloat()
                val offset = IntOffset(
                    pan.x.toInt() + ((size.width - contentWidth) / 2f).toInt(),
                    pan.y.toInt() + ((size.height - contentHeight) / 2f).toInt(),
                )
                drawImage(
                    image = page,
                    dstOffset = offset,
                    dstSize = IntSize(contentWidth.toInt(), contentHeight.toInt()),
                )
                val pageTopLeft = Offset(offset.x.toFloat(), offset.y.toFloat())

                // Page-edge hairline keeps the paper visibly on the desk surface.
                drawRect(
                    color = Color(0x33FFFFFF),
                    topLeft = pageTopLeft,
                    size = androidx.compose.ui.geometry.Size(contentWidth, contentHeight),
                    style = Stroke(width = 1f),
                )

                state.activeHitQuads.forEach { quad ->
                    drawQuad(pageTopLeft, quad, scale, Color(0x66D6B6C8))
                }
                state.selection?.quads?.forEach { quad ->
                    drawQuad(pageTopLeft, quad, scale, Color(0x66E7A7BE))
                }
                val selected = state.selectedAnnotation
                if (selected != null) {
                    state.annotations.getOrNull(selected)?.let { annotation ->
                        drawRect(
                            color = Color(0xFFE7A7BE),
                            topLeft = Offset(
                                pageTopLeft.x + annotation.rect.left * scale,
                                pageTopLeft.y + annotation.rect.top * scale,
                            ),
                            size = androidx.compose.ui.geometry.Size(
                                annotation.rect.width * scale,
                                annotation.rect.height * scale,
                            ),
                            style = Stroke(width = 2f),
                        )
                    }
                }
            }

            if (state.zoom > 1f) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Text(
                        text = "${(state.zoom * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQuad(
    origin: Offset,
    quad: PdfQuad,
    scale: Float,
    color: Color,
) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(origin.x + quad.upperLeft.x * scale, origin.y + quad.upperLeft.y * scale)
        lineTo(origin.x + quad.upperRight.x * scale, origin.y + quad.upperRight.y * scale)
        lineTo(origin.x + quad.lowerRight.x * scale, origin.y + quad.lowerRight.y * scale)
        lineTo(origin.x + quad.lowerLeft.x * scale, origin.y + quad.lowerLeft.y * scale)
        close()
    }
    drawPath(path, color)
}

@Composable
private fun SelectionBar(
    selection: PdfSelection?,
    canDeleteAnnotation: Boolean,
    onCopy: () -> Unit,
    onEditText: () -> Unit,
    onHighlight: () -> Unit,
    onUnderline: () -> Unit,
    onStrikeout: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canDeleteAnnotation) {
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.pdf_delete_annotation),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                BarAction(Icons.Outlined.ContentCopy, R.string.pdf_copy, onCopy)
                BarAction(Icons.Outlined.Edit, R.string.pdf_edit_text, onEditText)
                BarAction(Icons.Outlined.TextFields, R.string.pdf_highlight, onHighlight)
                BarAction(Icons.Outlined.Gesture, R.string.pdf_underline, onUnderline)
                BarAction(Icons.Outlined.Draw, R.string.pdf_strikeout, onStrikeout)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.pdf_clear_selection))
            }
        }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, labelRes: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = stringResource(labelRes))
    }
}

@Composable
private fun PageBar(
    page: Int,
    pages: Int,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenPages: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPrevious, enabled = enabled && page > 1, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = stringResource(R.string.pdf_previous_page))
            }
            TextButton(onClick = onOpenPages, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.pdf_page_indicator, page, pages))
            }
            IconButton(onClick = onNext, enabled = enabled && page < pages, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = stringResource(R.string.pdf_next_page))
            }
        }
    }
}

@Composable
private fun BottomToolBar(
    state: PdfUiState,
    bottomInset: androidx.compose.ui.unit.Dp,
    onSetTool: (PdfTool) -> Unit,
    onSearch: () -> Unit,
    onAnnotations: () -> Unit,
    onForms: () -> Unit,
    onPages: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // Explicit system-bar clearance: gesture navigation reports a zero navigation-bar
                // inset but a non-zero mandatory gesture strip, so the union is the real surface.
                .padding(bottom = bottomInset)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToolButton(
                icon = Icons.Outlined.TextFields,
                label = stringResource(R.string.pdf_tool_text),
                selected = state.tool == PdfTool.SELECT,
                onClick = { onSetTool(PdfTool.SELECT) },
            )
            ToolButton(
                icon = Icons.Outlined.Search,
                label = stringResource(R.string.pdf_tool_search),
                selected = false,
                onClick = onSearch,
            )
            ToolButton(
                icon = Icons.Outlined.Edit,
                label = stringResource(R.string.pdf_tool_annotate),
                selected = state.tool != PdfTool.SELECT && state.tool != PdfTool.SIGNATURE,
                onClick = onAnnotations,
            )
            ToolButton(
                icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
                label = stringResource(R.string.pdf_tool_pages),
                selected = false,
                onClick = onPages,
            )
            ToolButton(
                icon = Icons.Outlined.Check,
                label = stringResource(R.string.pdf_tool_forms),
                selected = false,
                enabled = state.forms.isNotEmpty(),
                onClick = onForms,
            )
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(56.dp)
            .semantics { contentDescription = label },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Shared sheet chrome: explicit bottom inset because ModalBottomSheet's own inset is unreliable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfSheetFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val bottom = with(density) {
        WindowInsets.navigationBars
            .union(WindowInsets.mandatorySystemGestures)
            .only(WindowInsetsSides.Bottom)
            .getBottom(density)
            .toDp()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = bottom)
                .padding(horizontal = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AnnotationsSheet(
    state: PdfUiState,
    onDismiss: () -> Unit,
    onTool: (PdfTool) -> Unit,
    onDelete: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    PdfSheetFrame(stringResource(R.string.pdf_annotations_title), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.pdf_annotations_select_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnnotationTool(Icons.Outlined.TextFields, R.string.pdf_highlight, state.tool == PdfTool.HIGHLIGHT) {
                    onTool(PdfTool.HIGHLIGHT)
                }
                AnnotationTool(Icons.Outlined.Gesture, R.string.pdf_underline, state.tool == PdfTool.UNDERLINE) {
                    onTool(PdfTool.UNDERLINE)
                }
                AnnotationTool(Icons.Outlined.Draw, R.string.pdf_strikeout, state.tool == PdfTool.STRIKEOUT) {
                    onTool(PdfTool.STRIKEOUT)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnnotationTool(Icons.Outlined.Gesture, R.string.pdf_ink, state.tool == PdfTool.INK) {
                    onTool(PdfTool.INK)
                }
                AnnotationTool(Icons.Outlined.Edit, R.string.pdf_note, state.tool == PdfTool.NOTE) {
                    onTool(PdfTool.NOTE)
                }
                AnnotationTool(Icons.Outlined.Add, R.string.pdf_textbox, state.tool == PdfTool.TEXT_BOX) {
                    onTool(PdfTool.TEXT_BOX)
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            if (state.annotations.none { it.own }) {
                Text(
                    stringResource(R.string.pdf_annotations_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                state.annotations.forEachIndexed { index, annotation ->
                    if (annotation.own) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { onSelect(index) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) {
                                Text(
                                    "${annotationKindLabel(annotation.kind)} · ${annotation.contents ?: ""}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                            }
                            IconButton(
                                onClick = {
                                    // Deleting acts on the selected annotation; select this row first.
                                    onSelect(index)
                                    onDelete()
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.pdf_delete_annotation),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun annotationKindLabel(kind: PdfAnnotationKind): String = when (kind) {
    PdfAnnotationKind.HIGHLIGHT -> stringResource(R.string.pdf_highlight)
    PdfAnnotationKind.UNDERLINE -> stringResource(R.string.pdf_underline)
    PdfAnnotationKind.STRIKEOUT -> stringResource(R.string.pdf_strikeout)
    PdfAnnotationKind.INK -> stringResource(R.string.pdf_ink)
    PdfAnnotationKind.TEXT_NOTE -> stringResource(R.string.pdf_note)
    PdfAnnotationKind.FREE_TEXT -> stringResource(R.string.pdf_textbox)
    PdfAnnotationKind.OTHER -> stringResource(R.string.pdf_annotation)
}

@Composable
private fun AnnotationTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PagesSheet(
    state: PdfUiState,
    onDismiss: () -> Unit,
    onGoTo: (Int) -> Unit,
    onInsertBlank: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRotate: () -> Unit,
) {
    PdfSheetFrame(stringResource(R.string.pdf_pages_title), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedAction(Icons.Outlined.Add, R.string.pdf_insert_page, onInsertBlank, !state.readOnly)
                OutlinedAction(Icons.AutoMirrored.Outlined.InsertDriveFile, R.string.pdf_import_page, onImport, !state.readOnly)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedAction(Icons.Outlined.Delete, R.string.pdf_delete_page, onDelete, !state.readOnly && state.pageCount > 1)
                OutlinedAction(Icons.AutoMirrored.Outlined.RotateRight, R.string.pdf_rotate_page, onRotate, !state.readOnly)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedAction(Icons.Outlined.ChevronLeft, R.string.pdf_move_page_up, onMoveUp, !state.readOnly && state.pageIndex > 0)
                OutlinedAction(Icons.Outlined.ChevronRight, R.string.pdf_move_page_down, onMoveDown, !state.readOnly && state.pageIndex < state.pageCount - 1)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.pdf_pages_jump),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until state.pageCount).toList()) { index ->
                    PageChip(
                        label = "${index + 1}",
                        selected = index == state.pageIndex,
                        onClick = { onGoTo(index) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pdf_pages_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun OutlinedAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(48.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FormsSheet(
    state: PdfUiState,
    onDismiss: () -> Unit,
    onText: (PdfFormField, String) -> Unit,
    onToggle: (PdfFormField) -> Unit,
    onChoice: (PdfFormField, String) -> Unit,
    onFlatten: () -> Unit,
) {
    PdfSheetFrame(stringResource(R.string.pdf_forms_title), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (state.forms.isEmpty()) {
                Text(
                    stringResource(R.string.pdf_forms_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.forms.forEach { field ->
                    FormFieldRow(
                        field = field,
                        enabled = !state.readOnly && !field.readOnly,
                        onText = { onText(field, it) },
                        onToggle = { onToggle(field) },
                        onChoice = { onChoice(field, it) },
                    )
                }
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(
                    onClick = onFlatten,
                    enabled = !state.readOnly,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(stringResource(R.string.pdf_flatten_forms))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.pdf_flatten_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FormFieldRow(
    field: PdfFormField,
    enabled: Boolean,
    onText: (String) -> Unit,
    onToggle: () -> Unit,
    onChoice: (String) -> Unit,
) {
    var draft by remember(field.widgetIndex, field.value) { mutableStateOf(field.value) }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 6.dp)) {
        when (field.kind) {
            PdfFormKind.TEXT -> {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        if (field.maxLength <= 0 || it.length <= field.maxLength) draft = it
                    },
                    label = { Text(field.label) },
                    singleLine = !field.multiline,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onText(draft) }, enabled = enabled, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.pdf_form_apply))
                }
            }
            PdfFormKind.CHECKBOX, PdfFormKind.RADIO -> {
                FilledTonalButton(onClick = onToggle, enabled = enabled, modifier = Modifier.height(48.dp)) {
                    Text(
                        if (field.value.isNotBlank() && field.value != "Off") {
                            "✓ ${field.label}"
                        } else {
                            field.label
                        },
                    )
                }
            }
            PdfFormKind.CHOICE -> {
                Box {
                    FilledTonalButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.height(48.dp)) {
                        Text("${field.label}: ${field.value}")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        field.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    expanded = false
                                    onChoice(option)
                                },
                            )
                        }
                    }
                }
            }
            PdfFormKind.BUTTON -> Text(
                field.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PdfFormKind.SIGNATURE -> Text(
                "${field.label} · " + stringResource(
                    if (field.signed) R.string.pdf_signature_signed else R.string.pdf_signature_unsigned,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            PdfFormKind.UNKNOWN -> Unit
        }
    }
}

@Composable
private fun SearchSheet(
    state: PdfUiState,
    onDismiss: () -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val search = state.search
    PdfSheetFrame(stringResource(R.string.pdf_search_title), onDismiss) {
        Column {
            OutlinedTextField(
                value = search.query,
                onValueChange = onQuery,
                label = { Text(stringResource(R.string.pdf_search_field)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.pdf_search_run))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            when {
                search.searching -> Text(
                    stringResource(R.string.pdf_search_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                search.hits.isEmpty() && search.query.isNotBlank() -> Text(
                    stringResource(R.string.pdf_search_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                search.hits.isNotEmpty() -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.pdf_search_hits, search.hits.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.ChevronLeft,
                            contentDescription = stringResource(R.string.pdf_search_previous),
                        )
                    }
                    Text(
                        stringResource(
                            R.string.pdf_search_position,
                            (search.index + 1).coerceAtLeast(1),
                            search.hits.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = stringResource(R.string.pdf_search_next),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignaturesSheet(
    state: PdfUiState,
    onDismiss: () -> Unit,
    onDrawnSignature: (List<List<PdfPoint>>) -> Unit,
    onCryptoSignature: () -> Unit,
) {
    PdfSheetFrame(stringResource(R.string.pdf_signatures_title), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (state.signatures.isEmpty()) {
                Text(
                    stringResource(R.string.pdf_signatures_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.signatures.forEach { signature ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            signature.signer.ifBlank { stringResource(R.string.pdf_signature_unknown_signer) },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            signatureStatusLabel(signature.status),
                            style = MaterialTheme.typography.bodySmall,
                            color = when (signature.status) {
                                PdfSignatureStatus.VALID -> MaterialTheme.colorScheme.primary
                                PdfSignatureStatus.UNSIGNED -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                        if (signature.reason.isNotBlank()) {
                            Text(
                                signature.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            DrawnSignaturePad(onUse = onDrawnSignature)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.pdf_crypto_signature_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.pdf_crypto_signature_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = onCryptoSignature,
                enabled = !state.readOnly,
                modifier = Modifier.height(48.dp),
            ) {
                Icon(Icons.Outlined.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pdf_crypto_signature_action))
            }
        }
    }
}

@Composable
private fun signatureStatusLabel(status: PdfSignatureStatus): String = when (status) {
    PdfSignatureStatus.VALID -> stringResource(R.string.pdf_signature_valid)
    PdfSignatureStatus.DIGEST_INVALID -> stringResource(R.string.pdf_signature_digest_invalid)
    PdfSignatureStatus.NOT_TRUSTED -> stringResource(R.string.pdf_signature_not_trusted)
    PdfSignatureStatus.UNSIGNED -> stringResource(R.string.pdf_signature_unsigned)
    PdfSignatureStatus.UNKNOWN -> stringResource(R.string.pdf_signature_unknown)
}
