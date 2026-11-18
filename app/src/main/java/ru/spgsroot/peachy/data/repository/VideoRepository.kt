package ru.spgsroot.peachy.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.spgsroot.peachy.data.db.VideoDao
import ru.spgsroot.peachy.data.db.VideoEntity
import ru.spgsroot.peachy.domain.model.Video
import javax.inject.Inject

class VideoRepository @Inject constructor(
    private val videoDao: VideoDao
) {
    fun getAll(): Flow<List<Video>> = videoDao.getAll().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getById(id: Long): Video? = videoDao.getById(id)?.toDomain()

    suspend fun insert(video: Video): Long = videoDao.insert(video.toEntity())

    internal suspend fun rename(id: Long, title: String) = videoDao.rename(id, title)

    internal suspend fun replaceThumbnail(id: Long, expected: String?, replacement: String): Boolean =
        videoDao.replaceThumbnail(id, expected, replacement) == 1

    internal suspend fun replaceFile(id: Long, expected: String, replacement: String, size: Long, duration: Long): Boolean =
        videoDao.replaceFile(id, expected, replacement, size, duration) == 1

    suspend fun delete(video: Video) = videoDao.delete(video.toEntity())

    internal suspend fun remove(id: Long): Video? = videoDao.remove(id)?.toDomain()

    suspend fun markAsCorrupted(id: Long) = videoDao.markAsCorrupted(id)

    suspend fun findByHash(hash: String): Video? = videoDao.findByHash(hash)?.toDomain()

    suspend fun unhashedWithSize(size: Long): List<Video> = videoDao.unhashedWithSize(size).map { it.toDomain() }

    suspend fun setContentHash(id: Long, hash: String) = videoDao.setContentHash(id, hash)

    private fun VideoEntity.toDomain() = Video(
        id = id,
        title = title,
        filePath = filePath,
        duration = duration,
        thumbnailPath = thumbnailPath,
        addedAt = addedAt,
        fileSize = fileSize,
        isCorrupted = isCorrupted,
        albumId = albumId,
        contentHash = contentHash
    )

    private fun Video.toEntity() = VideoEntity(
        id = id,
        title = title,
        filePath = filePath,
        duration = duration,
        thumbnailPath = thumbnailPath,
        addedAt = addedAt,
        fileSize = fileSize,
        isCorrupted = isCorrupted,
        albumId = albumId,
        contentHash = contentHash
    )
}
