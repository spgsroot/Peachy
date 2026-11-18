package ru.spgsroot.peachy.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos ORDER BY addedAt DESC")
    fun getAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos")
    suspend fun getAllNow(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun getById(id: Long): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(video: VideoEntity): Long

    @Update
    suspend fun update(video: VideoEntity)

    @Query("UPDATE videos SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE videos SET thumbnailPath = :replacement WHERE id = :id AND thumbnailPath IS :expected")
    suspend fun replaceThumbnail(id: Long, expected: String?, replacement: String): Int

    @Query("UPDATE videos SET filePath = :replacement, fileSize = :size, duration = :duration, isCorrupted = 0, contentHash = NULL WHERE id = :id AND filePath = :expected")
    suspend fun replaceFile(id: Long, expected: String, replacement: String, size: Long, duration: Long): Int

    @Query("SELECT * FROM videos WHERE contentHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE fileSize = :size AND contentHash IS NULL")
    suspend fun unhashedWithSize(size: Long): List<VideoEntity>

    @Query("UPDATE videos SET contentHash = :hash WHERE id = :id")
    suspend fun setContentHash(id: Long, hash: String)

    @Delete
    suspend fun delete(video: VideoEntity)

    @Query("DELETE FROM videos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Transaction
    suspend fun remove(id: Long): VideoEntity? {
        val video = getById(id) ?: return null
        deleteById(id)
        return video
    }

    @Query("UPDATE videos SET isCorrupted = 1 WHERE id = :id")
    suspend fun markAsCorrupted(id: Long)
}
