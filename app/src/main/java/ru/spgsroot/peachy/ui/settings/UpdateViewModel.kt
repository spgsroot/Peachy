package ru.spgsroot.peachy.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.spgsroot.peachy.data.update.*

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data object Current : UpdateCheckState
    data class Available(val release: AppRelease) : UpdateCheckState
    data class Failed(val reason: UpdateError) : UpdateCheckState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val client: GitHubReleaseClient,
    private val preferences: UpdatePreferences,
    private val downloader: AppUpdateDownloader
) : ViewModel() {
    val installedVersion: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    val options = preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdatePreferencesState())
    val download = downloader.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateDownloadState.Idle)
    private val _check = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val check: StateFlow<UpdateCheckState> = _check.asStateFlow()
    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible = _dialogVisible.asStateFlow()
    private val _actionBusy = MutableStateFlow(false)
    val actionBusy = _actionBusy.asStateFlow()
    private val _actionError = MutableStateFlow<UpdateError?>(null)
    val actionError = _actionError.asStateFlow()
    private val checkMutex = Mutex()

    fun showDialog() { _dialogVisible.value = true }
    fun hideDialog() { _dialogVisible.value = false }

    fun checkManually() {
        showDialog()
        viewModelScope.launch { checkLatest(automatic = false) }
    }

    // Called from the authenticated foreground lifecycle, not an eager application job.
    suspend fun checkAutomatically() = checkLatest(automatic = true)

    private suspend fun checkLatest(automatic: Boolean) {
        if (!checkMutex.tryLock()) return
        val previous = _check.value
        var started = false
        try {
            val now = System.currentTimeMillis()
            if (automatic) {
                val current = preferences.settings.first()
                if (!shouldCheckAutomatically(current.autoCheckEnabled, current.lastCheckAttemptMs, now)) return
            }
            _actionError.value = null
            _check.value = UpdateCheckState.Checking
            started = true
            preferences.recordCheckAttempt(now)
            val latest = client.fetchLatest()
            _check.value = if (isNewerVersion(latest.versionName, installedVersion)) {
                UpdateCheckState.Available(latest)
            } else UpdateCheckState.Current
        } catch (cancelled: CancellationException) {
            if (started) _check.value = previous
            throw cancelled
        } catch (failure: UpdateException) {
            _check.value = UpdateCheckState.Failed(failure.reason)
        } catch (_: java.io.IOException) {
            _check.value = UpdateCheckState.Failed(UpdateError.NETWORK)
        } finally {
            checkMutex.unlock()
        }
    }

    fun setAutomaticChecks(enabled: Boolean) {
        viewModelScope.launch {
            try {
                preferences.setAutoCheckEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                _actionError.value = UpdateError.DOWNLOAD_FAILED
            }
        }
    }

    fun downloadUpdate(release: AppRelease) = runAction { downloader.start(release) }
    fun cancelDownload() = runAction { downloader.cancel() }

    private fun runAction(action: suspend () -> Unit) {
        if (_actionBusy.value) return
        _actionBusy.value = true
        _actionError.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdateException) {
                _actionError.value = failure.reason
            } catch (_: java.io.IOException) {
                _actionError.value = UpdateError.DOWNLOAD_FAILED
            } finally {
                _actionBusy.value = false
            }
        }
    }

    suspend fun installationIntent(): Intent? {
        if (_actionBusy.value) return null
        _actionBusy.value = true
        _actionError.value = null
        return try {
            if (downloader.canInstallPackages()) downloader.installIntent() else downloader.permissionIntent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: UpdateException) {
            _actionError.value = failure.reason
            null
        } catch (_: java.io.IOException) {
            _actionError.value = UpdateError.DOWNLOAD_FAILED
            null
        } finally {
            _actionBusy.value = false
        }
    }

    fun installerUnavailable() { _actionError.value = UpdateError.INSTALL_UNAVAILABLE }
}
