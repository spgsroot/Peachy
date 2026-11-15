package ru.spgsroot.peachy.ui.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import ru.spgsroot.peachy.ui.gallery.MediaFilter
import ru.spgsroot.peachy.ui.gallery.GalleryItem
import ru.spgsroot.peachy.ui.gallery.galleryItems
import ru.spgsroot.peachy.ui.gallery.filterGalleryItems
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.data.storage.UrlImporter
import ru.spgsroot.peachy.data.repository.SettingsRepository
import ru.spgsroot.peachy.data.repository.VideoRepository
import ru.spgsroot.peachy.data.storage.InternalStorageManager
import ru.spgsroot.peachy.data.storage.VideoImporter
import ru.spgsroot.peachy.data.storage.VideoExporter
import ru.spgsroot.peachy.data.storage.ExportItem
import ru.spgsroot.peachy.domain.model.Settings
import ru.spgsroot.peachy.domain.model.Video
import ru.spgsroot.peachy.playback.player.ExoPlayerWrapper
import ru.spgsroot.peachy.playback.player.PlayerState
import ru.spgsroot.peachy.playback.queue.PlaylistManager
import ru.spgsroot.peachy.playback.service.WebViewAudioCaptureService
import ru.spgsroot.peachy.playback.timer.TimerController
import java.io.File
import java.io.IOException
import javax.inject.Inject

@OptIn(UnstableApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistManager: PlaylistManager,
    val exoPlayerWrapper: ExoPlayerWrapper,
    private val settingsRepository: SettingsRepository,
    private val timerController: TimerController,
    private val videoRepository: VideoRepository,
    private val storageManager: InternalStorageManager,
    private val videoImporter: VideoImporter,
    private val urlImporter: UrlImporter,
    private val videoExporter: VideoExporter
) : ViewModel() {

    private var currentSettings: Settings? = null

    val videos: StateFlow<List<Video>> = videoRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Empty)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val playerState: StateFlow<PlayerState> = exoPlayerWrapper.state
    val queue: StateFlow<List<Video>> = playlistManager.playlist
    val filter = MutableStateFlow(MediaFilter())

    init {
        observePlaylist()
        observeSettings()
        loadVideosToPlaylist()
        setupCurrentVideoRemovedListener()
    }

    private fun setupCurrentVideoRemovedListener() {
        playlistManager.setOnCurrentVideoRemovedListener { nextVideo ->
            viewModelScope.launch {
                nextVideo?.let {
                    stopWebViewCapture()
                    exoPlayerWrapper.play(nextVideo)
                    startTimer()
                } ?: run {
                    exoPlayerWrapper.pause()
                    timerController.stop()
                }
            }
        }
    }

    private fun loadVideosToPlaylist() {
        viewModelScope.launch {
            combine(videos, filter) { videos, filter ->
                galleryItems(videos, emptyList(), "", filter)
                    .filterIsInstance<GalleryItem.VideoItem>().map { it.video }
            }.flowOn(Dispatchers.Default).collectLatest { playlist ->
                playlistManager.setPlaylist(playlist)
            }
        }
    }

    private fun observePlaylist() {
        viewModelScope.launch {
            videos.collectLatest { videos ->
                _uiState.value = if (videos.isEmpty()) {
                    PlayerUiState.Empty
                } else {
                    PlayerUiState.Ready
                }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.getSettings().collectLatest { settings ->
                currentSettings = settings
                settings?.let {
                    exoPlayerWrapper.setSpeed(it.playbackSpeed)
                    if (playerState.value is PlayerState.Playing) {
                        timerController.start(viewModelScope, it.timerMs)
                    }
                }
            }
        }
    }

    fun onPlayPause() {
        when (val state = playerState.value) {
            is PlayerState.Playing -> {
                exoPlayerWrapper.pause()
                timerController.stop()
            }

            is PlayerState.Paused -> {
                stopWebViewCapture()
                exoPlayerWrapper.play()
                startTimer()
            }

            is PlayerState.Idle -> {
                playlistManager.current()?.let {
                    stopWebViewCapture()
                    exoPlayerWrapper.play(it)
                    startTimer()
                }
            }

            else -> Unit
        }
    }

    fun onNext() {
        playlistManager.next()?.let {
            stopWebViewCapture()
            exoPlayerWrapper.play(it)
            startTimer()
        }
    }

    fun onPrevious() {
        playlistManager.previous()?.let {
            stopWebViewCapture()
            exoPlayerWrapper.play(it)
            startTimer()
        }
    }

    fun onSeek(positionMs: Long) {
        exoPlayerWrapper.seekTo(positionMs)
    }

    fun pauseForWebView() {
        if (playerState.value is PlayerState.Playing) {
            exoPlayerWrapper.pause()
            timerController.stop()
        }
    }

    fun playVideo(video: Video) {
        playlistManager.selectVideo(video)
        stopWebViewCapture()
        exoPlayerWrapper.play(video)
        startTimer()
    }

    fun getVideoById(id: Long): Video? = videos.value.find { it.id == id }

    /** A video that came back paused keeps the timer off until the user resumes it. */
    private fun startTimer() {
        if (playerState.value !is PlayerState.Playing) {
            timerController.stop()
            return
        }
        currentSettings?.let {
            timerController.start(viewModelScope, it.timerMs)
        }
    }

    private fun stopWebViewCapture() {
        runCatching {
            context.stopService(WebViewAudioCaptureService.stopIntent(context))
        }
    }

    fun importVideoFromUri(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext
                val fileName = "${System.currentTimeMillis()}.mp4"
                val savedFile = storageManager.save(inputStream, fileName)
                videoImporter.persist(savedFile)
            }
        }
    }

    fun downloadVideoFromUrl(url: String) {
        viewModelScope.launch {
            importVideoFromUrl(url)
        }
    }

    /**
     * Imports [url]. A link of a supported file host brings every video it points to (all videos of an album);
     * the result is a success when at least one of them was saved.
     */
    suspend fun importVideoFromUrl(url: String): Result<Unit> =
        importVideoFromUrlWithFolder(url, null).map { Unit }

    /** The returned count is a copy warning, never a failed library import. */
    suspend fun importVideoFromUrlWithFolder(url: String, folderUri: String?): Result<Int> =
        withContext(Dispatchers.IO) {
            val normalizedUrl = url.trim()
            if (normalizedUrl.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Пустой URL"))
            }
            val copy: ((File) -> Unit)? = folderUri?.let { tree ->
                { file ->
                    val exported = videoExporter.export(
                        Uri.parse(tree), listOf(ExportItem(file.nameWithoutExtension, file.path))
                    )
                    if (exported.failed.isNotEmpty()) throw IOException("Folder copy failed")
                }
            }
            val results = urlImporter.itemsFor(normalizedUrl, copy).map { it.run() }
            val result = results.firstOrNull { it.isSuccess } ?: results.first()
            result.map { results.sumOf { it.getOrDefault(0) } }
        }

    fun downloadVideosFromUrls(urls: List<String>) {
        viewModelScope.launch {
            importVideosFromUrls(urls)
        }
    }

    suspend fun importVideosFromUrls(
        urls: List<String>,
        onProgress: (BatchImportProgress) -> Unit = {}
    ): BatchImportSummary = withContext(Dispatchers.IO) {
        val normalizedUrls = urls
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Album links expand to one item per video, so the totals count videos, not links.
        val items = normalizedUrls.flatMap { urlImporter.itemsFor(it) }

        val failedUrls = mutableListOf<String>()
        var succeeded = 0
        var failed = 0

        onProgress(
            BatchImportProgress(
                total = items.size,
                completed = 0,
                currentUrl = "",
                succeeded = 0,
                failed = 0
            )
        )

        items.forEachIndexed { index, item ->
            val result = item.run()

            if (result.isSuccess) {
                succeeded++
            } else {
                failed++
                failedUrls += item.label
            }

            onProgress(
                BatchImportProgress(
                    total = items.size,
                    completed = index + 1,
                    currentUrl = item.label,
                    succeeded = succeeded,
                    failed = failed,
                    lastError = result.exceptionOrNull()?.message
                )
            )
        }

        BatchImportSummary(
            total = items.size,
            succeeded = succeeded,
            failed = failed,
            failedUrls = failedUrls
        )
    }


    override fun onCleared() {
        timerController.stop()
        super.onCleared()
    }
}

sealed interface PlayerUiState {
    data object Empty : PlayerUiState
    data object Ready : PlayerUiState
}
