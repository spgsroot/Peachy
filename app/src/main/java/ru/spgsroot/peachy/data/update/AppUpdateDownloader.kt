package ru.spgsroot.peachy.data.update

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val release: AppRelease,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateDownloadState
    data class Verifying(val release: AppRelease) : UpdateDownloadState
    data class Ready(val release: AppRelease, val apkFile: File) : UpdateDownloadState
    data class Failed(val reason: UpdateError) : UpdateDownloadState
}

@Singleton
class AppUpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UpdatePreferences
) {
    private val mutex = Mutex()
    private val manager: DownloadManager by lazy {
        context.getSystemService(DownloadManager::class.java)
            ?: throw UpdateException(UpdateError.DOWNLOAD_FAILED, "DownloadManager is unavailable")
    }
    private val cacheDirectory get() = File(context.cacheDir, "updates")

    // Retain the precise terminal error across collectors/relocking, without a polling service.
    // The persisted ID remains a tombstone until explicit retry/cancel (or an actual app upgrade).
    private val failure = MutableStateFlow<Pair<Long, UpdateError>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<UpdateDownloadState> = combine(
        preferences.settings.map { it.pendingDownload() }.distinctUntilChanged(),
        failure
    ) { pending, terminalFailure -> pending to terminalFailure }
        .transformLatest { (pending, _) ->
            if (pending == null) {
                emit(UpdateDownloadState.Idle)
                return@transformLatest
            }
            while (true) {
                val state = mutex.withLock {
                    if (!isCurrentLocked(pending)) return@withLock null
                    try {
                        if (removeIfInstalledLocked(pending)) {
                            UpdateDownloadState.Idle
                        } else {
                            failure.value?.takeIf { it.first == pending.id }?.let {
                                UpdateDownloadState.Failed(it.second)
                            } ?: if (apkFile(pending.id).isFile) {
                                UpdateDownloadState.Verifying(pending.release)
                            } else {
                                val queried = managerState(pending)
                                if (queried is UpdateDownloadState.Failed) failLocked(pending, queried.reason)
                                else queried
                            }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        failLocked(pending, exception.updateReason(UpdateError.DOWNLOAD_FAILED))
                    }
                } ?: return@transformLatest
                emit(state)
                when (state) {
                    is UpdateDownloadState.Downloading -> delay(POLL_INTERVAL_MS)
                    is UpdateDownloadState.Verifying -> {
                        val completed = mutex.withLock {
                            if (!isCurrentLocked(pending)) return@withLock null
                            try {
                                UpdateDownloadState.Ready(pending.release, completeLocked(pending))
                            } catch (exception: CancellationException) {
                                // Losing the collector never cancels DownloadManager or deletes a ready APK.
                                throw exception
                            } catch (exception: Exception) {
                                failLocked(pending, exception.updateReason(UpdateError.DOWNLOAD_FAILED))
                            }
                        }
                        if (completed != null) emit(completed)
                        return@transformLatest
                    }
                    else -> return@transformLatest
                }
            }
        }
        .catch { exception ->
            if (exception is CancellationException) throw exception
            emit(UpdateDownloadState.Failed(exception.updateReason(UpdateError.DOWNLOAD_FAILED)))
        }
        .flowOn(Dispatchers.IO)

    suspend fun start(release: AppRelease) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                validateDownloadRelease(release)
                val installed = installedPackage()
                val old = preferences.settings.first().pendingDownload()
                if (old != null && !isNewerVersion(old.release.versionName, installed.versionName.orEmpty())) {
                    discardLocked(old, clearRecord = true)
                }
                if (!isNewerVersion(release.versionName, installed.versionName.orEmpty())) {
                    throw UpdateException(UpdateError.INCOMPATIBLE, "Release is already installed")
                }
                val existing = preferences.settings.first().pendingDownload()
                if (existing?.release == release && failure.value?.first != existing.id) {
                    if (apkFile(existing.id).isFile || managerState(existing) !is UpdateDownloadState.Failed) {
                        return@withLock
                    }
                }
                if (existing != null) discardLocked(existing, clearRecord = true)
                currentCoroutineContext().ensureActive()
                val externalDirectory = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: throw UpdateException(UpdateError.DOWNLOAD_FAILED, "App-private downloads are unavailable"),
                    "updates"
                )
                if (!externalDirectory.isDirectory && !externalDirectory.mkdirs()) {
                    throw UpdateException(UpdateError.DOWNLOAD_FAILED, "Cannot create download directory")
                }
                val destination = File(externalDirectory, "pending-${UUID.randomUUID()}.apk")
                val request = DownloadManager.Request(release.apkUrl.toUri())
                    .setMimeType(APK_MIME_TYPE)
                    .setTitle("Peachy ${release.versionName}")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationUri(Uri.fromFile(destination))
                // Enqueue + DataStore cannot be one transaction. Make this short transition immune
                // to caller cancellation, and roll back the OS job if persistence fails.
                withContext(NonCancellable) {
                    val id = manager.enqueue(request)
                    try {
                        preferences.saveDownload(id, release)
                        failure.value = null
                    } catch (exception: Exception) {
                        try {
                            manager.remove(id)
                        } finally {
                            destination.delete()
                            if (preferences.settings.first().downloadId == id) preferences.clearDownload()
                        }
                        throw exception
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: UpdateException) {
                throw exception
            } catch (exception: Exception) {
                throw UpdateException(UpdateError.DOWNLOAD_FAILED, "Cannot start the update download", exception)
            }
        }
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pending = preferences.settings.first().pendingDownload()
            if (pending != null) discardLocked(pending, clearRecord = true)
            else preferences.clearDownload()
            failure.value = null
        }
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun installIntent(): Intent = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pending = preferences.settings.first().pendingDownload()
                ?: throw UpdateException(UpdateError.INSTALL_UNAVAILABLE, "No downloaded update")
            if (removeIfInstalledLocked(pending)) {
                throw UpdateException(UpdateError.INSTALL_UNAVAILABLE, "Update is already installed")
            }
            if (!canInstallPackages()) {
                // Do not discard a verified APK when the user still needs to grant permission.
                throw UpdateException(UpdateError.INSTALL_UNAVAILABLE, "Allow installation from this app first")
            }
            val file = try {
                // Re-read size, digest and PackageManager metadata immediately before sharing.
                completeLocked(pending)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val reason = exception.updateReason(UpdateError.INSTALL_UNAVAILABLE)
                failLocked(pending, reason)
                throw UpdateException(reason, "Downloaded update cannot be installed", exception)
            }
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME_TYPE)
                    clipData = ClipData.newRawUri("APK update", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (exception: Exception) {
                throw UpdateException(UpdateError.INSTALL_UNAVAILABLE, "System installer intent is unavailable", exception)
            }
        }
    }

    private fun managerState(pending: PendingDownload): UpdateDownloadState {
        manager.query(DownloadManager.Query().setFilterById(pending.id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                return when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        UpdateDownloadState.Downloading(pending.release, downloaded.coerceAtLeast(0), total.takeIf { it > 0 } ?: pending.release.apkSize)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadState.Verifying(pending.release)
                    else -> UpdateDownloadState.Failed(UpdateError.DOWNLOAD_FAILED)
                }
            }
        }
        return UpdateDownloadState.Failed(UpdateError.DOWNLOAD_FAILED)
    }

    /** Called only with [mutex] held, on IO; never calls another lock-taking public method. */
    private suspend fun completeLocked(pending: PendingDownload): File {
        val target = apkFile(pending.id)
        if (target.isFile) {
            verifyCachedApkIntegrity(target, pending.release)
            validateIdentity(target, pending.release)
        } else {
            if (managerState(pending) !is UpdateDownloadState.Verifying) {
                throw UpdateException(UpdateError.INSTALL_UNAVAILABLE, "Download is not complete")
            }
            if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
                throw UpdateException(UpdateError.DOWNLOAD_FAILED, "Cannot create APK cache")
            }
            val partial = partialFile(pending.id)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(pending.id)).use { input ->
                    copyApkWithIntegrityCheck(input, partial, pending.release)
                }
                validateIdentity(partial, pending.release)
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(target)) {
                    throw UpdateException(UpdateError.DOWNLOAD_FAILED, "Cannot publish verified APK")
                }
            } finally {
                partial.delete()
            }
        }
        // The persisted ID now identifies the internal APK. No external file is ever shared.
        manager.remove(pending.id)
        return target
    }

    private fun validateIdentity(file: File, release: AppRelease) {
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw UpdateException(UpdateError.INVALID_APK, "Cannot parse APK metadata")
        val application = archive.applicationInfo
            ?: throw UpdateException(UpdateError.INVALID_APK, "APK application metadata is missing")
        val installed = installedPackage()
        validateApkIdentity(
            ApkIdentity(archive.packageName, archive.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(archive), application.minSdkVersion),
            release,
            context.packageName,
            PackageInfoCompat.getLongVersionCode(installed),
            Build.VERSION.SDK_INT
        )
    }

    private fun installedPackage(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    private suspend fun isCurrentLocked(pending: PendingDownload): Boolean =
        preferences.settings.first().pendingDownload() == pending

    private suspend fun removeIfInstalledLocked(pending: PendingDownload): Boolean {
        if (isNewerVersion(pending.release.versionName, installedPackage().versionName.orEmpty())) return false
        discardLocked(pending, clearRecord = true)
        return true
    }

    private suspend fun failLocked(pending: PendingDownload, reason: UpdateError): UpdateDownloadState.Failed {
        discardLocked(pending, clearRecord = false)
        failure.value = pending.id to reason
        return UpdateDownloadState.Failed(reason)
    }

    private suspend fun discardLocked(pending: PendingDownload, clearRecord: Boolean) {
        withContext(NonCancellable) {
            try {
                manager.remove(pending.id)
            } finally {
                apkFile(pending.id).delete()
                partialFile(pending.id).delete()
                if (clearRecord) {
                    preferences.clearDownload()
                    failure.value = null
                }
            }
        }
    }

    private fun apkFile(id: Long) = File(cacheDirectory, "update-$id.apk")
    private fun partialFile(id: Long) = File(cacheDirectory, "update-$id.part.apk")

    private fun validateDownloadRelease(release: AppRelease) {
        val uri = release.apkUrl.toUri()
        val expectedPath = "/spgsroot/Peachy/releases/download/${Uri.encode(release.tagName)}/app-release-unsigned-signed.apk"
        if (uri.scheme != "https" || uri.encodedAuthority != "github.com" || uri.encodedPath != expectedPath ||
            uri.encodedQuery != null || uri.encodedFragment != null || release.apkSize <= 0 ||
            release.sha256.length != 64 || release.sha256.any { it.digitToIntOrNull(16) == null }
        ) {
            throw UpdateException(UpdateError.INVALID_RELEASE, "Release does not contain a trusted APK download")
        }
    }

    private data class PendingDownload(val id: Long, val release: AppRelease)

    private fun UpdatePreferencesState.pendingDownload(): PendingDownload? {
        val id = downloadId ?: return null
        val release = downloadRelease ?: return null
        return PendingDownload(id, release)
    }

    private fun Throwable.updateReason(fallback: UpdateError): UpdateError =
        (this as? UpdateException)?.reason ?: fallback

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val POLL_INTERVAL_MS = 1_000L
    }
}
