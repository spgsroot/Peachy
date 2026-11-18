package ru.spgsroot.peachy.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentSessionDao {
    @Query("SELECT * FROM document_sessions ORDER BY updatedAt DESC, id ASC")
    fun observeAll(): Flow<List<DocumentSessionEntity>>

    @Query("SELECT * FROM document_sessions ORDER BY updatedAt DESC, id ASC")
    suspend fun getAll(): List<DocumentSessionEntity>

    @Query("SELECT * FROM document_sessions WHERE id = :id")
    suspend fun getById(id: String): DocumentSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: DocumentSessionEntity)

    @Query("UPDATE document_sessions SET dirty = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markDirty(id: String, updatedAt: Long)

    @Query("UPDATE document_sessions SET lastPage = :page, lastScroll = :scroll, updatedAt = :updatedAt WHERE id = :id")
    suspend fun saveLastPosition(id: String, page: Int, scroll: Int, updatedAt: Long)

    @Query("UPDATE document_sessions SET workingPath = :path, dirty = 1, updatedAt = :updatedAt WHERE id = :id AND workingPath = :expected")
    suspend fun publishWorkingPath(id: String, expected: String, path: String, updatedAt: Long): Int

    @Query("DELETE FROM document_sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}
