package ru.spgsroot.peachy.ui.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.data.storage.PendingDuplicate
import ru.spgsroot.peachy.data.storage.PendingDuplicates
import ru.spgsroot.peachy.data.storage.VideoImporter

@HiltViewModel
class DuplicateVideoViewModel @Inject constructor(
    pendingDuplicates: PendingDuplicates,
    private val importer: VideoImporter
) : ViewModel() {
    val pending: StateFlow<List<PendingDuplicate>> = pendingDuplicates.pending

    // The file is already moved by the time the import runs, so leaving the screen must not cancel it.
    fun addAnyway(id: Long) {
        viewModelScope.launch { withContext(NonCancellable) { importer.addPendingAnyway(id) } }
    }

    fun skip(id: Long) = importer.discardPending(id)
}

/** Asks about the oldest held-back duplicate; the next one appears once it is answered. */
@Composable
fun DuplicateVideoHost(viewModel: DuplicateVideoViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val duplicate = pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { viewModel.skip(duplicate.id) },
        title = { Text(stringResource(R.string.duplicate_dialog_title)) },
        text = { Text(stringResource(R.string.duplicate_dialog_text, duplicate.title, duplicate.existingTitle)) },
        confirmButton = {
            TextButton(onClick = { viewModel.addAnyway(duplicate.id) }) {
                Text(stringResource(R.string.duplicate_add_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.skip(duplicate.id) }) {
                Text(stringResource(R.string.duplicate_skip))
            }
        }
    )
}
