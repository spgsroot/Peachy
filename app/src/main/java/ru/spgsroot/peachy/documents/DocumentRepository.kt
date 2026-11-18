package ru.spgsroot.peachy.documents

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.spgsroot.peachy.data.db.DocumentSessionDao

/** One row of the Documents screen: the stored index plus the working file's size. */
data class DocumentSummary(
    val id: String,
    val displayName: String,
    val format: String,
    val kind: DocumentKind,
    val bytes: Long,
    val updatedAt: Long,
    val dirty: Boolean,
)

/** Read model for the Documents screen. Writes live in [DocumentSessions]. */
@Singleton
class DocumentRepository @Inject constructor(
    private val dao: DocumentSessionDao,
) {
    fun observe(): Flow<List<DocumentSummary>> = dao.observeAll().map { rows -> rows.map(::toSummary) }

    private fun toSummary(row: ru.spgsroot.peachy.data.db.DocumentSessionEntity): DocumentSummary =
        DocumentSummary(
            id = row.id,
            displayName = row.displayName,
            format = row.format,
            kind = DocumentKind.entries.firstOrNull { it.name == row.kind } ?: DocumentKind.WORD,
            bytes = File(row.workingPath).length().coerceAtLeast(0L),
            updatedAt = row.updatedAt,
            dirty = row.dirty,
        )
}
