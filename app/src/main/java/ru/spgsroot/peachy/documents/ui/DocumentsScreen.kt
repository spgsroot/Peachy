package ru.spgsroot.peachy.documents.ui

import android.text.format.Formatter
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.ui.gallery.MediaFilterButton
import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.DocumentFormatError
import ru.spgsroot.peachy.documents.DocumentKind
import ru.spgsroot.peachy.documents.DocumentSummary

/**
 * The documents hub: every imported session, one import action, and the empty state.
 * The list carries no document preview on purpose — thumbnails of private documents would be a
 * second, unrequested copy of user content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: DocumentsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val shown = remember(documents, filter) { filterDocuments(documents, filter) }
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<DocumentSummary?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DocumentsEvent.Opened -> onOpen(event.id)
                is DocumentsEvent.ImportFailed -> snackbar.showSnackbar(importErrorText(context, event.error))
                DocumentsEvent.DeleteFailed -> snackbar.showSnackbar(
                    context.getString(R.string.documents_delete_failed)
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.documents_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.documents_back)
                            )
                        }
                    },
                    actions = {
                        MediaFilterButton(filter, viewModel::setFilter)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                )
                if (importing) {
                    // A 100 MiB copy takes a moment; the bar says the tap was accepted.
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // One import at a time: the copy runs in the view model, not in the picker result.
                onClick = { if (!importing) picker.launch(DocumentFormat.pickerMimeTypes) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.documents_open_file)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    ) { padding ->
        when {
            documents.isEmpty() -> DocumentsEmptyState(Modifier.fillMaxSize().padding(padding))
            shown.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.gallery_search_empty),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(shown, key = { it.id }) { document ->
                    DocumentRow(
                        document = document,
                        onOpen = { onOpen(document.id) },
                        onDelete = { deleteTarget = document }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.documents_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.documents_delete_confirm_body, target.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.documents_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.documents_dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun DocumentsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.documents_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.documents_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.86f)
        )
    }
}

@Composable
private fun DocumentRow(
    document: DocumentSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val relative = remember(document.updatedAt) {
        DateUtils.getRelativeTimeSpanString(
            document.updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
    val kindLabelText = stringResource(kindLabel(document.kind))
    val supporting = listOf(
        kindLabelText,
        document.format.uppercase(),
        Formatter.formatShortFileSize(context, document.bytes),
        stringResource(R.string.documents_updated, relative),
    ).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = kindIcon(document.kind),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = document.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (document.dirty) {
                    Text(
                        stringResource(R.string.documents_dirty),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        " · ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Default.Delete,
                stringResource(R.string.documents_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun kindLabel(kind: DocumentKind): Int = when (kind) {
    DocumentKind.PDF -> R.string.documents_kind_pdf
    DocumentKind.WORD -> R.string.documents_kind_word
    DocumentKind.EXCEL -> R.string.documents_kind_excel
    DocumentKind.POWERPOINT -> R.string.documents_kind_powerpoint
    DocumentKind.IMAGE -> R.string.documents_kind_image
}

private fun kindIcon(kind: DocumentKind): ImageVector = when (kind) {
    DocumentKind.PDF -> Icons.Default.PictureAsPdf
    DocumentKind.WORD -> Icons.Default.Description
    DocumentKind.EXCEL -> Icons.Default.TableChart
    DocumentKind.POWERPOINT -> Icons.Default.Slideshow
    DocumentKind.IMAGE -> Icons.Default.Image
}

@Composable
internal fun kindLabelText(kind: DocumentKind): String = stringResource(kindLabel(kind))

/** One copy of each import failure, so the list and the editors report the same thing. */
internal fun importErrorText(context: android.content.Context, error: DocumentsError): String =
    when (error.reason) {
        DocumentFormatError.EMPTY -> context.getString(R.string.documents_error_empty)
        DocumentFormatError.TOO_LARGE -> error.detail
            ?.let { context.getString(R.string.documents_error_too_large, it) }
            ?: context.getString(R.string.documents_error_too_large_generic)

        DocumentFormatError.NOT_ENOUGH_SPACE -> context.getString(R.string.documents_error_no_space)
        DocumentFormatError.UNKNOWN_FORMAT -> context.getString(R.string.documents_error_unknown)
        DocumentFormatError.UNSUPPORTED_FORMAT -> context.getString(
            R.string.documents_error_unsupported,
            error.detail ?: context.getString(R.string.documents_error_unknown)
        )

        DocumentFormatError.MISMATCH -> context.getString(
            R.string.documents_error_mismatch,
            error.detail ?: context.getString(R.string.documents_error_unknown)
        )

        DocumentFormatError.CORRUPT -> context.getString(R.string.documents_error_corrupt)
        DocumentFormatError.CANCELLED -> context.getString(R.string.documents_import_failed)
    }

@Composable
internal fun MissingSessionState(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.documents_missing_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.documents_missing_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text(stringResource(R.string.documents_back)) }
    }
}

@Composable
internal fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
