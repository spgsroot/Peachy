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

    @Query("UPDATE videos SET title = :title, filePath = :filePath, duration = :duration, thumbnailPath = :thumbnailPath, addedAt = :addedAt, fileSize = :fileSize, isCorrupted = :isCorrupted, contentHash = :contentHash WHERE id = :id")
    suspend fun updateContent(id: Long, title: String, filePath: String, duration: Long, thumbnailPath: String?, addedAt: Long, fileSize: Long, isCorrupted: Boolean, contentHash: String?)

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

    @Query("UPDATE videos SET isCorrupted = 1 WHERE id = :id")
    suspend fun markAsCorrupted(id: Long)
}
