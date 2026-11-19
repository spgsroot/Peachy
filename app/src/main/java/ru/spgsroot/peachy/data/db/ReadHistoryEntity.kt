package ru.spgsroot.peachy.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "read_history")
data class ReadHistoryEntity(
    @PrimaryKey val galleryId: Int,
    val title: String,
    val thumbnailPath: String,
    val page: Int,
    val pageCount: Int,
    val readAt: Long
) {
    /**
     * Unknown-length explicit completion is stored as page -1 / count 0, never a made-up page count.
     * Other rows retain the reader's zero-based progress semantics.
     */
    val readFraction: Double
        get() = when {
            page == -1 && pageCount == 0 -> 1.0
            pageCount > 0 -> ((page.toDouble() + 1.0) / pageCount).coerceIn(0.0, 1.0)
            else -> 0.0
        }
}
