package ru.spgsroot.peachy.ui.settings

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.data.update.UpdateDownloadState
import ru.spgsroot.peachy.data.update.UpdateError

@Composable
fun UpdatesSection(viewModel: UpdateViewModel) {
    val options by viewModel.options.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.updates_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.updates_installed, viewModel.installedVersion), style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = options.autoCheckEnabled,
                role = Role.Switch,
                onValueChange = viewModel::setAutomaticChecks
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.updates_automatic), modifier = Modifier.weight(1f))
            Switch(checked = options.autoCheckEnabled, onCheckedChange = null)
        }
        Text(stringResource(R.string.updates_automatic_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = viewModel::checkManually,
            enabled = check !is UpdateCheckState.Checking,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.updates_check)) }
        if (check is UpdateCheckState.Available) {
            TextButton(onClick = viewModel::showDialog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.updates_available, (check as UpdateCheckState.Available).release.versionName))
            }
        }
        TextButton(onClick = viewModel::showDialog, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.updates_status))
        }
    }
}

@Composable
fun UpdateDialogHost(viewModel: UpdateViewModel) {
    val visible by viewModel.dialogVisible.collectAsStateWithLifecycle()
    // Observe for the authenticated app's lifetime, including while the dialog is closed.
    val download by viewModel.download.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val busy by viewModel.actionBusy.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    if (!visible) return

    val release = when (val state = download) {
        is UpdateDownloadState.Downloading -> state.release
        is UpdateDownloadState.Verifying -> state.release
        is UpdateDownloadState.Ready -> state.release
        else -> (check as? UpdateCheckState.Available)?.release
    }
    val downloading = download is UpdateDownloadState.Downloading || download is UpdateDownloadState.Verifying
    val failure = visibleUpdateError(actionError, download, check)
    AlertDialog(
        onDismissRequest = viewModel::hideDialog,
        title = { Text(stringResource(R.string.updates_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.updates_installed, viewModel.installedVersion))
                when (val state = download) {
                    is UpdateDownloadState.Downloading -> {
                        Text(stringResource(R.string.updates_downloading))
                        if (state.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(stringResource(R.string.updates_download_progress,
                                Formatter.formatFileSize(context, state.downloadedBytes),
                                Formatter.formatFileSize(context, state.totalBytes)))
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateDownloadState.Verifying -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.updates_verifying))
                    }
                    is UpdateDownloadState.Ready -> Text(stringResource(R.string.updates_ready))
                    else -> when (check) {
                        UpdateCheckState.Checking -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.updates_checking))
                        }
                        UpdateCheckState.Current -> Text(stringResource(R.string.updates_current))
                        else -> Unit
                    }
                }
                if (release != null) {
                    Text(stringResource(R.string.updates_available, release.versionName), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.updates_apk_size, Formatter.formatFileSize(context, release.apkSize)))
                    if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodySmall)
                }
                if (failure != null) Text(stringResource(updateErrorText(failure)), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.updates_install_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            when {
                download is UpdateDownloadState.Ready -> Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                val intent = viewModel.installationIntent() ?: return@launch
                                try {
                                    installer.launch(intent)
                                } catch (_: ActivityNotFoundException) {
                                    viewModel.installerUnavailable()
                                } catch (_: SecurityException) {
                                    viewModel.installerUnavailable()
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.updates_install)) }
                    TextButton(enabled = !busy, onClick = viewModel::cancelDownload) {
                        Text(stringResource(R.string.updates_discard))
                    }
                }
                downloading -> TextButton(enabled = !busy, onClick = viewModel::cancelDownload) {
                    Text(stringResource(R.string.updates_cancel_download))
                }
                download is UpdateDownloadState.Failed -> Column(horizontalAlignment = Alignment.End) {
                    if (release != null) {
                        TextButton(enabled = !busy, onClick = { viewModel.downloadUpdate(release) }) {
                            Text(stringResource(R.string.updates_download))
                        }
                    } else {
                        TextButton(enabled = check !is UpdateCheckState.Checking && !busy, onClick = viewModel::checkManually) {
                            Text(stringResource(R.string.updates_check))
                        }
                    }
                    TextButton(enabled = !busy, onClick = viewModel::cancelDownload) {
                        Text(stringResource(R.string.updates_reset_download))
                    }
                }
                release != null -> TextButton(enabled = !busy, onClick = { viewModel.downloadUpdate(release) }) {
                    Text(stringResource(R.string.updates_download))
                }
                else -> TextButton(enabled = check !is UpdateCheckState.Checking && !busy, onClick = viewModel::checkManually) {
                    Text(stringResource(R.string.updates_check))
                }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::hideDialog) { Text(stringResource(R.string.updates_close)) } }
    )
}

internal fun visibleUpdateError(actionError: UpdateError?, download: UpdateDownloadState, check: UpdateCheckState): UpdateError? {
    if (actionError != null) return actionError
    return when (check) {
        UpdateCheckState.Checking, UpdateCheckState.Current -> null
        is UpdateCheckState.Failed -> check.reason
        else -> (download as? UpdateDownloadState.Failed)?.reason
    }
}

private fun updateErrorText(error: UpdateError): Int = when (error) {
    UpdateError.NETWORK -> R.string.updates_error_network
    UpdateError.RATE_LIMIT -> R.string.updates_error_rate_limit
    UpdateError.INVALID_RELEASE, UpdateError.NO_APK -> R.string.updates_error_release
    UpdateError.HTTP_ERROR -> R.string.updates_error_http
    UpdateError.DOWNLOAD_FAILED -> R.string.updates_error_download
    UpdateError.INTEGRITY -> R.string.updates_error_integrity
    UpdateError.INVALID_APK -> R.string.updates_error_apk
    UpdateError.INCOMPATIBLE -> R.string.updates_error_incompatible
    UpdateError.INSTALL_UNAVAILABLE -> R.string.updates_error_install
}
