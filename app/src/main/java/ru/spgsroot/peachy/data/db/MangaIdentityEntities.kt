package ru.spgsroot.peachy.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "manga_works")
data class MangaWorkEntity(
    @PrimaryKey val workId: String,
    val title: String,
    val updatedAt: Long
)

@Entity(
    tableName = "manga_reading_units",
    foreignKeys = [
        ForeignKey(
            entity = MangaWorkEntity::class,
            parentColumns = ["workId"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workId")]
)
data class MangaReadingUnitEntity(
    @PrimaryKey val unitId: String,
    val workId: String,
    val label: String,
    val sortOrder: Double,
    val kind: String,
    val updatedAt: Long
)

@Entity(
    tableName = "manga_edition_links",
    foreignKeys = [
        ForeignKey(
            entity = MangaWorkEntity::class,
            parentColumns = ["workId"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MangaReadingUnitEntity::class,
            parentColumns = ["unitId"],
            childColumns = ["unitId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workId"), Index("unitId")]
)
data class MangaEditionLinkEntity(
    @PrimaryKey val galleryId: Int,
    val workId: String,
    val unitId: String,
    val updatedAt: Long
)

@Entity(
    tableName = "manga_rejected_links",
    primaryKeys = ["leftGalleryId", "rightGalleryId"]
)
data class MangaRejectedLinkEntity(
    val leftGalleryId: Int,
    val rightGalleryId: Int,
    val createdAt: Long
)

@Entity(tableName = "manga_read_marks")
data class MangaReadMarkEntity(
    @PrimaryKey val galleryId: Int,
    val markedAt: Long
)

@Entity(tableName = "manga_edition_index")
data class MangaEditionIndexEntity(
    @PrimaryKey val galleryId: Int,
    val displayTitle: String,
    val japaneseTitle: String?,
    val thumbnailPath: String,
    val numPages: Int,
    val artistIds: String,
    val language: String?,
    val tagsComplete: Boolean,
    val updatedAt: Long
)
