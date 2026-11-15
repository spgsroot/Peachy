package ru.spgsroot.peachy.data.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.data.downloader.ExternalDownloader
import ru.spgsroot.peachy.data.downloader.completeDownloadedVideo
import ru.spgsroot.peachy.data.repository.VideoRepository
import ru.spgsroot.peachy.domain.model.Video

/**
 * Turns a downloaded or copied file into a library video. Shared by the player, the browser and
 * incoming shares. A file whose content is already in the library is not added: it is held back and
 * reported as a [DuplicateVideoException], and the user may still add it through [addPendingAnyway].
 */
@Singleton
class VideoImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoRepository: VideoRepository,
    private val externalDownloader: ExternalDownloader,
    private val storageManager: InternalStorageManager,
    private val pendingDuplicates: PendingDuplicates
) {
    private val stagingDir = File(context.cacheDir, "duplicates")

    init {
        // Pending duplicates live in memory only, so whatever is staged now was left by an earlier run.
        stagingDir.deleteRecursively()
    }

    /**
     * Downloads [url] and adds it to the library; a failed download or unreadable file leaves nothing behind.
     * Does its own network work on the IO dispatcher, so callers on the main thread are safe.
     */
    suspend fun importUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        title: String? = null
    ): Result<Unit> = importUrlWithCopy(url, headers, title).map { Unit }

    suspend fun importUrlWithCopy(
        url: String,
        headers: Map<String, String> = emptyMap(),
        title: String? = null,
        copy: ((File) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        externalDownloader
            .download(url = url, fileName = downloadFileName(url), headers = headers)
            .fold(
                onSuccess = { file -> completeDownloadedVideo(file, { persist(file, title) }, copy) },
                onFailure = { Result.failure(it) }
            )
    }

    /**
     * Adds the already saved [savedFile] to the library, deleting it when it cannot be read. A
     * duplicate is moved out of the way instead and reported as a [DuplicateVideoException].
     */
    suspend fun persist(
        savedFile: File,
        title: String? = null,
        allowDuplicate: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val hash = FileHash.sha256(savedFile)
            if (!allowDuplicate) {
                findDuplicate(savedFile, hash)?.let { existing ->
                    throw holdBack(savedFile, title, existing)
                }
            }
            val metadata = extractMetadata(savedFile)
            val video = Video(
                id = 0,
                title = title?.takeIf { it.isNotBlank() } ?: metadata.title,
                filePath = savedFile.absolutePath,
                duration = metadata.duration,
                thumbnailPath = null,
                addedAt = System.currentTimeMillis(),
                fileSize = savedFile.length(),
                isCorrupted = false,
                contentHash = hash
            )
            videoRepository.insert(video)
            Unit
        }.onFailure {
            if (it !is DuplicateVideoException) savedFile.delete()
        }
    }

    /** The user chose to keep a held-back duplicate. A no-op when it was already resolved. */
    suspend fun addPendingAnyway(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val duplicate = pendingDuplicates.take(id) ?: return@withContext Result.success(Unit)
        val adopted = runCatching { storageManager.adopt(duplicate.file) }
            .getOrElse { duplicate.file.delete(); return@withContext Result.failure(it) }
        persist(adopted, duplicate.title, allowDuplicate = true)
    }

    fun discardPending(id: Long) {
        pendingDuplicates.take(id)?.file?.delete()
    }

    /**
     * The library video with the same content as [file] (whose hash is [hash]), or null. Videos
     * stored before hashes existed are hashed on demand, and only when their size matches, so the
     * library is never read in full.
     */
    private suspend fun findDuplicate(file: File, hash: String): Video? {
        videoRepository.findByHash(hash)?.let { return it }
        for (candidate in videoRepository.unhashedWithSize(file.length())) {
            val stored = File(candidate.filePath)
            if (!stored.isFile) continue
            val storedHash = FileHash.sha256(stored)
            videoRepository.setContentHash(candidate.id, storedHash)
            if (storedHash == hash) return candidate
        }
        return null
    }

    /** Parks the duplicate outside the library for the user's decision; without a place to park it, it is dropped. */
    private fun holdBack(file: File, title: String?, existing: Video): DuplicateVideoException {
        val shownTitle = title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val parked = runCatching {
            stagingDir.mkdirs()
            val target = File(stagingDir, "${UUID.randomUUID()}.${file.extension.ifEmpty { "mp4" }}")
            if (!file.renameTo(target)) {
                file.copyTo(target)
                file.delete()
            }
            target
        }.getOrNull()
        if (parked != null) {
            pendingDuplicates.add(PendingDuplicate(System.nanoTime(), shownTitle, existing.title, parked))
        } else {
            file.delete()
        }
        return DuplicateVideoException(context.getString(R.string.duplicate_video_message, existing.title), existing.title)
    }

    private fun downloadFileName(url: String): String {
        val lastSegment = runCatching { Uri.parse(url).lastPathSegment.orEmpty() }
            .getOrDefault("")

        val extension = lastSegment
            .substringAfterLast('.', "mp4")
            .substringBefore('?')
            .substringBefore('#')
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
            ?: "mp4"

        return "${System.currentTimeMillis()}.$extension"
    }

    private class Metadata(val title: String, val duration: Long)

    private fun extractMetadata(file: File): Metadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            Metadata(title = file.nameWithoutExtension, duration = duration)
        } finally {
            retriever.release()
        }
    }
}
