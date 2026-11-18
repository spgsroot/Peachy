package ru.spgsroot.peachy.data.storage

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InternalStorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val videosDir: File
        get() = File(context.filesDir, "videos").apply { mkdirs() }

    private val thumbnailsDir: File
        get() = File(context.filesDir, "thumbnails").apply { mkdirs() }

    fun save(
        inputStream: InputStream,
        fileName: String,
        onProgress: ((Int) -> Unit)? = null,
        totalBytes: Long = -1L,
        maxBytes: Long = DEFAULT_MAX_VIDEO_BYTES
    ): File {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (totalBytes > maxBytes) {
            throw IllegalArgumentException("File is too large: $totalBytes bytes")
        }
        val available = getAvailableSpace()
        if (totalBytes > 0 && (available < MIN_FREE_BYTES || totalBytes > available - MIN_FREE_BYTES)) {
            throw IllegalStateException("Not enough free space to save file")
        }

        val file = File(videosDir, File(fileName).name)
        val part = File.createTempFile(".${file.name}.", ".part", videosDir)
        try {
            inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var bytesCopied = 0L
                    var read: Int
                    var lastProgressUpdate = 0L

                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read == 0) continue

                        bytesCopied += read
                        if (bytesCopied > maxBytes) {
                            throw IllegalArgumentException("File is too large: $bytesCopied bytes")
                        }

                        output.write(buffer, 0, read)

                        if (onProgress != null && totalBytes > 0) {
                            val currentTime = System.currentTimeMillis()
                            // Обновляем прогресс не чаще раза в 500 мс, чтобы не спамить UI и нотификации
                            if (currentTime - lastProgressUpdate > PROGRESS_UPDATE_INTERVAL_MS) {
                                lastProgressUpdate = currentTime
                                val progress = ((bytesCopied * 100) / totalBytes).toInt().coerceIn(0, 100)
                                onProgress(progress)
                            }
                        }
                    }
                    if (totalBytes >= 0 && bytesCopied != totalBytes) {
                        throw IOException("File size changed while reading: expected $totalBytes, got $bytesCopied")
                    }
                    if (onProgress != null && totalBytes > 0) onProgress(100)
                }
            }
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return file
        } catch (failure: Throwable) {
            // Never remove an existing destination when a replacement could not be completed.
            part.delete()
            throw failure
        }
    }

    /** Returns a complete, uniquely named cover; the caller publishes it by updating the video row. */
    fun saveThumbnail(inputStream: InputStream, videoId: Long): String {
        val file = File.createTempFile("${videoId}_", ".jpg", thumbnailsDir)
        try {
            inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            return file.absolutePath
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    fun delete(videoId: Long): Boolean {
        var deleted = false
        thumbnailsDir.listFiles()?.forEach { file ->
            if (file.isThumbnailFor(videoId) && file.delete()) deleted = true
        }
        return deleted
    }

    fun deleteFile(filePath: String): Boolean {
        val file = VideoFileDeletion.resolve(videosDir, filePath) ?: return false
        return file.delete()
    }

    /** Moves a file that lives elsewhere into the video directory under a fresh name. */
    fun adopt(file: File): File {
        val target = File(videosDir, "${java.util.UUID.randomUUID()}.${file.extension.ifEmpty { "mp4" }}")
        if (!file.renameTo(target)) {
            file.copyTo(target)
            file.delete()
        }
        return target
    }

    fun deleteThumbnail(thumbnailPath: String): Boolean {
        val file = VideoFileDeletion.resolve(thumbnailsDir, thumbnailPath) ?: return false
        return file.delete()
    }

    fun getFile(videoId: Long): File? {
        val files = videosDir.listFiles() ?: return null
        return files.find { it.nameWithoutExtension == videoId.toString() }
    }

    fun getThumbnailFile(videoId: Long): File? {
        val files = thumbnailsDir.listFiles() ?: return null
        return files.find { it.nameWithoutExtension == videoId.toString() }
            ?: files.find { it.isThumbnailFor(videoId) }
    }

    private fun File.isThumbnailFor(videoId: Long): Boolean =
        nameWithoutExtension == videoId.toString() || nameWithoutExtension.startsWith("${videoId}_")

    fun getTotalSize(): Long {
        val videoSize = videosDir.listFiles()?.sumOf { it.length() } ?: 0L
        val thumbnailSize = thumbnailsDir.listFiles()?.sumOf { it.length() } ?: 0L
        return videoSize + thumbnailSize
    }

    fun getAvailableSpace(): Long {
        val stat = StatFs(videosDir.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    fun cleanup() {
        videosDir.listFiles()?.forEach { it.delete() }
        thumbnailsDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val DEFAULT_MAX_VIDEO_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MIN_FREE_BYTES = 50L * 1024L * 1024L
        private const val COPY_BUFFER_SIZE = 8192
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }
}
