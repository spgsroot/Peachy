package ru.spgsroot.peachy.data.manga

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.spgsroot.peachy.data.db.AppDatabase
import ru.spgsroot.peachy.data.db.GalleryMemoryEntity
import ru.spgsroot.peachy.data.db.MangaReadMarkEntity
import ru.spgsroot.peachy.data.db.ReadHistoryEntity
import ru.spgsroot.peachy.manga.GalleryDetail
import ru.spgsroot.peachy.manga.GallerySummary
import ru.spgsroot.peachy.manga.RememberedGallery
import ru.spgsroot.peachy.manga.TagCodec
import ru.spgsroot.peachy.manga.toRemembered
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryMemoryStore @Inject constructor(private val database: AppDatabase) {
    private val dao = database.galleryMemoryDao()
    val all: Flow<List<RememberedGallery>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** Upload dates of every gallery whose page has been opened; search results do not carry one. */
    val uploadDates: Flow<Map<Int, Long>> = all.map { galleries ->
        buildMap { galleries.forEach { gallery -> gallery.uploadedAt?.let { put(gallery.id, it) } } }
    }

    /** Opening a gallery shows interest, so it also lifts an earlier "not interested". */
    suspend fun remember(detail: GalleryDetail, now: Long = System.currentTimeMillis()) {
        dao.upsert(detail.toRemembered(now).toEntity())
    }

    /** Hides [gallery] from recommendations for good and teaches the profile to avoid its tags. */
    suspend fun dismiss(gallery: GallerySummary, now: Long = System.currentTimeMillis()) {
        val known = dao.getById(gallery.id)
        val merged = (known ?: gallery.toRemembered(now).toEntity()).copy(
            dismissed = true,
            seenAt = now,
            japaneseTitle = known?.japaneseTitle?.takeIf { it.isNotBlank() } ?: gallery.japaneseTitle
        )
        dao.upsert(merged)
    }

    /**
     * Records completion and the card metadata together, so history also excludes this gallery.
     * Detailed metadata wins over a sparse catalog card; zero pages means the count is unknown.
     */
    suspend fun markRead(gallery: GallerySummary, now: Long = System.currentTimeMillis()) {
        database.withTransaction {
            val known = dao.getById(gallery.id)
            val historyDao = database.readHistoryDao()
            val history = historyDao.getById(gallery.id)
            val pageCount = known?.numPages?.takeIf { it > 0 }
                ?: history?.pageCount?.takeIf { it > 0 }
                ?: gallery.numPages.coerceAtLeast(0)
            val title = known?.title?.takeIf { it.isNotBlank() }
                ?: history?.title?.takeIf { it.isNotBlank() }
                ?: gallery.title
            val thumbnail = known?.thumbnailPath?.takeIf { it.isNotBlank() }
                ?: history?.thumbnailPath?.takeIf { it.isNotBlank() }
                ?: gallery.thumbnailPath
            val japaneseTitle = known?.japaneseTitle?.takeIf { it.isNotBlank() } ?: gallery.japaneseTitle
            dao.upsert(
                GalleryMemoryEntity(
                    galleryId = gallery.id,
                    title = title,
                    thumbnailPath = thumbnail,
                    numPages = pageCount,
                    tags = known?.tags?.takeIf { it.isNotBlank() } ?: TagCodec.encode(gallery.tags),
                    uploadedAt = known?.uploadedAt,
                    numFavorites = known?.numFavorites ?: gallery.numFavorites,
                    seenAt = known?.seenAt ?: now,
                    dismissed = false,
                    japaneseTitle = japaneseTitle
                )
            )
            historyDao.upsert(
                ReadHistoryEntity(
                    galleryId = gallery.id,
                    title = title,
                    thumbnailPath = thumbnail,
                    page = pageCount - 1,
                    pageCount = pageCount,
                    readAt = now
                )
            )
            database.mangaIdentityDao().upsertReadMark(MangaReadMarkEntity(gallery.id, now))
        }
    }
}

private fun GalleryMemoryEntity.toDomain() = RememberedGallery(
    id = galleryId,
    title = title,
    thumbnailPath = thumbnailPath,
    numPages = numPages,
    tags = TagCodec.decode(tags),
    uploadedAt = uploadedAt,
    numFavorites = numFavorites,
    seenAt = seenAt,
    dismissed = dismissed,
    japaneseTitle = japaneseTitle
)

private fun RememberedGallery.toEntity() = GalleryMemoryEntity(
    galleryId = id,
    title = title,
    thumbnailPath = thumbnailPath,
    numPages = numPages,
    tags = TagCodec.encode(tags),
    uploadedAt = uploadedAt,
    numFavorites = numFavorites,
    seenAt = seenAt,
    dismissed = dismissed,
    japaneseTitle = japaneseTitle
)
