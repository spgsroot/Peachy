package ru.spgsroot.peachy.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One imported document session. No document body lives in SQL: [workingPath] and the session
 * directory are the content, this row is the index plus the durable dirty flag and last position.
 */
@Entity(tableName = "document_sessions")
data class DocumentSessionEntity(
    @PrimaryKey
    val id: String,
    val displayName: String,
    val sourceMime: String?,
    val format: String,
    val kind: String,
    val workingPath: String,
    val dirty: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastPage: Int?,
    val lastScroll: Int?,
)
