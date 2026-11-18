package ru.spgsroot.peachy.documents

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import ru.spgsroot.peachy.data.db.DocumentSessionDao
import ru.spgsroot.peachy.data.db.DocumentSessionEntity

/** In-memory DAO for the substrate tests; it can be told to fail inserts and deletes. */
internal class FakeDocumentSessionDao : DocumentSessionDao {

    val rows = linkedMapOf<String, DocumentSessionEntity>()
    var failInsert = false
    var failDelete = false
    var failPublication = false

    override fun observeAll(): Flow<List<DocumentSessionEntity>> = flowOf(rows.values.toList())

    override suspend fun getAll(): List<DocumentSessionEntity> = rows.values.toList()

    override suspend fun getById(id: String): DocumentSessionEntity? = rows[id]

    override suspend fun insert(session: DocumentSessionEntity) {
        if (failInsert) error("insert refused")
        rows[session.id] = session
    }

    override suspend fun markDirty(id: String, updatedAt: Long) {
        rows[id]?.let { rows[id] = it.copy(dirty = true, updatedAt = updatedAt) }
    }

    override suspend fun saveLastPosition(id: String, page: Int, scroll: Int, updatedAt: Long) {
        rows[id]?.let { rows[id] = it.copy(lastPage = page, lastScroll = scroll, updatedAt = updatedAt) }
    }

    override suspend fun publishWorkingPath(id: String, expected: String, path: String, updatedAt: Long): Int {
        if (failPublication) error("publication refused")
        val row = rows[id] ?: return 0
        if (row.workingPath != expected) return 0
        rows[id] = row.copy(workingPath = path, dirty = true, updatedAt = updatedAt)
        return 1
    }

    override suspend fun deleteById(id: String) {
        if (failDelete) error("delete refused")
        rows.remove(id)
    }
}
