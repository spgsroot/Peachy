package ru.spgsroot.peachy.data.manga

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import ru.spgsroot.peachy.data.db.AppDatabase
import ru.spgsroot.peachy.data.db.MangaEditionIndexEntity
import ru.spgsroot.peachy.data.db.MangaEditionLinkEntity
import ru.spgsroot.peachy.data.db.MangaReadMarkEntity
import ru.spgsroot.peachy.data.db.MangaReadingUnitEntity
import ru.spgsroot.peachy.data.db.MangaRejectedLinkEntity
import ru.spgsroot.peachy.data.db.MangaWorkEntity
import ru.spgsroot.peachy.manga.EditionMetadata
import ru.spgsroot.peachy.manga.EditionReadMark
import ru.spgsroot.peachy.manga.GalleryDetail
import ru.spgsroot.peachy.manga.GallerySummary
import ru.spgsroot.peachy.manga.MangaEditionLink
import ru.spgsroot.peachy.manga.MangaIdentityMatcher
import ru.spgsroot.peachy.manga.MangaIdentitySnapshot
import ru.spgsroot.peachy.manga.MangaReadingUnit
import ru.spgsroot.peachy.manga.MangaWork
import ru.spgsroot.peachy.manga.UnitKind
import ru.spgsroot.peachy.manga.canonicalPair
import ru.spgsroot.peachy.manga.toEditionMetadata
import ru.spgsroot.peachy.nsfw.NsfwGate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns manga work/chapter/edition links, pair rejections, explicit read marks, and the passive
 * edition metadata index built strictly from already-received API responses. Respects [NsfwGate].
 */
@Singleton
class MangaIdentityStore @Inject constructor(
    private val database: AppDatabase,
    private val nsfwGate: NsfwGate
) {
    private val dao = database.mangaIdentityDao()

    val snapshot: Flow<MangaIdentitySnapshot> = combine(
        combine(dao.observeWorks(), dao.observeUnits(), dao.observeLinks()) { works, units, links ->
            Triple(works, units, links)
        },
        combine(dao.observeRejections(), dao.observeReadMarks(), dao.observeEditions()) { rejections, marks, editions ->
            Triple(rejections, marks, editions)
        }
    ) { (works, units, links), (rejections, marks, editions) ->
        MangaIdentitySnapshot(
            works = works.associate { it.workId to MangaWork(it.workId, it.title, it.updatedAt) },
            units = units.mapNotNull { entity ->
                val kind = UnitKind.fromWire(entity.kind) ?: return@mapNotNull null
                entity.unitId to MangaReadingUnit(entity.unitId, entity.workId, entity.label, entity.sortOrder, kind, entity.updatedAt)
            }.toMap(),
            links = links.associate { it.galleryId to MangaEditionLink(it.galleryId, it.workId, it.unitId, it.updatedAt) },
            rejections = rejections.map { canonicalPair(it.leftGalleryId, it.rightGalleryId) }.toSet(),
            readMarks = marks.associate { it.galleryId to EditionReadMark(it.galleryId, it.markedAt) },
            editions = editions.associate { it.galleryId to it.toDomain() }
        )
    }

    /** Records already-fetched catalog/related/recommendation summaries without issuing any network call. */
    suspend fun indexSummaries(summaries: List<GallerySummary>, now: Long = System.currentTimeMillis()) {
        if (!nsfwGate.allows() || summaries.isEmpty()) return
        val incoming = summaries.distinctBy { it.id }.map { it.toEditionMetadata(now) }
        indexMetadata(incoming)
    }

    /** Records an already-fetched gallery detail without issuing any network call. */
    suspend fun indexDetail(detail: GalleryDetail, now: Long = System.currentTimeMillis()) {
        if (!nsfwGate.allows()) return
        indexMetadata(listOf(detail.toEditionMetadata(now)))
    }

    suspend fun indexMetadata(editions: List<EditionMetadata>) {
        if (!nsfwGate.allows() || editions.isEmpty()) return
        database.withTransaction {
            val merged = editions.map { incoming ->
                val existing = dao.getEdition(incoming.galleryId)?.toDomain()
                mergeForStorage(existing, incoming).toEntity()
            }
            dao.upsertEditions(merged)
        }
    }

    /**
     * Records or clears an explicit read mark on [galleryId] only. Never writes fake history rows
     * and never touches another edition's read mark or reading position.
     */
    suspend fun setReadMark(galleryId: Int, read: Boolean, now: Long = System.currentTimeMillis()) {
        if (!nsfwGate.allows()) return
        database.withTransaction {
            if (read) dao.upsertReadMark(MangaReadMarkEntity(galleryId, now))
            else dao.deleteReadMark(galleryId)
        }
    }

    /**
     * Confirms that [galleryId] and [targetGalleryId] are translations of the same reading unit.
     * Reuses an existing unit if either edition is already linked; otherwise creates a new work and unit.
     */
    suspend fun confirmSameChapter(
        galleryId: Int,
        targetGalleryId: Int,
        defaultUnitLabel: String = "1",
        now: Long = System.currentTimeMillis()
    ): MangaEditionLinkEntity? {
        if (!nsfwGate.allows() || galleryId == targetGalleryId) return null
        return database.withTransaction {
            val previousSource = dao.getLink(galleryId)
            val existingTarget = dao.getLink(targetGalleryId)
            val link = when {
                existingTarget != null -> {
                    val updated = MangaEditionLinkEntity(galleryId, existingTarget.workId, existingTarget.unitId, now)
                    dao.upsertLink(updated)
                    updated
                }
                previousSource != null -> {
                    val peer = MangaEditionLinkEntity(targetGalleryId, previousSource.workId, previousSource.unitId, now)
                    dao.upsertLink(peer)
                    previousSource
                }
                else -> {
                    val targetMeta = dao.getEdition(targetGalleryId)?.toDomain()
                    val sourceMeta = dao.getEdition(galleryId)?.toDomain()
                    val workId = UUID.randomUUID().toString()
                    val unitId = UUID.randomUUID().toString()
                    val workTitle = targetMeta?.displayTitle?.takeIf { it.isNotBlank() }
                        ?: sourceMeta?.displayTitle?.takeIf { it.isNotBlank() }
                        ?: "#$targetGalleryId"
                    val (label, order) = MangaIdentityMatcher.suggestChapterLabelAndOrder(
                        targetMeta ?: sourceMeta,
                        fallbackLabel = defaultUnitLabel,
                        fallbackOrder = 1.0
                    )
                    dao.upsertWork(MangaWorkEntity(workId, workTitle, now))
                    dao.upsertUnit(MangaReadingUnitEntity(unitId, workId, label, order, UnitKind.Chapter.wire, now))
                    dao.upsertLink(MangaEditionLinkEntity(targetGalleryId, workId, unitId, now))
                    val created = MangaEditionLinkEntity(galleryId, workId, unitId, now)
                    dao.upsertLink(created)
                    created
                }
            }
            val (left, right) = canonicalPair(galleryId, targetGalleryId)
            dao.deleteRejection(left, right)
            if (previousSource != null && previousSource.unitId != link.unitId) {
                pruneEmptyUnitAndWork(previousSource.workId, previousSource.unitId)
            }
            link
        }
    }

    /** Attaches [galleryId] as another translation of an existing confirmed reading unit. */
    suspend fun linkAsTranslationOfUnit(
        galleryId: Int,
        workId: String,
        unitId: String,
        now: Long = System.currentTimeMillis()
    ): MangaEditionLinkEntity? {
        if (!nsfwGate.allows()) return null
        return database.withTransaction {
            val unit = dao.getUnit(unitId) ?: return@withTransaction null
            if (unit.workId != workId) return@withTransaction null
            val previous = dao.getLink(galleryId)
            val peers = dao.getLinksForUnit(unitId)
            val updated = MangaEditionLinkEntity(galleryId, workId, unitId, now)
            dao.upsertLink(updated)
            for (peer in peers) {
                if (peer.galleryId != galleryId) {
                    val (left, right) = canonicalPair(galleryId, peer.galleryId)
                    dao.deleteRejection(left, right)
                }
            }
            if (previous != null && previous.unitId != unitId) {
                pruneEmptyUnitAndWork(previous.workId, previous.unitId)
            }
            updated
        }
    }

    /**
     * Links [galleryId] as a distinct chapter (or collection) within an existing [workId],
     * or creates a new work anchored on [anchorGalleryId] when linking two unlinked editions.
     * Preserves fractional sort orders (e.g. 1.5, 0.0 for prologue, etc.) without integer rounding.
     */
    suspend fun linkAsAnotherChapter(
        galleryId: Int,
        workId: String?,
        anchorGalleryId: Int?,
        workTitle: String?,
        unitLabel: String,
        sortOrder: Double,
        kind: UnitKind = UnitKind.Chapter,
        anchorUnitLabel: String = "1",
        anchorSortOrder: Double = 1.0,
        now: Long = System.currentTimeMillis()
    ): MangaEditionLinkEntity? {
        if (!nsfwGate.allows()) return null
        val cleanLabel = unitLabel.trim()
        if (cleanLabel.isEmpty() || !sortOrder.isFinite()) return null
        return database.withTransaction {
            val previous = dao.getLink(galleryId)
            val resolvedWorkId = when {
                !workId.isNullOrBlank() && dao.getWork(workId) != null -> workId
                anchorGalleryId != null && anchorGalleryId != galleryId -> {
                    val anchorExisting = dao.getLink(anchorGalleryId)
                    if (anchorExisting != null) {
                        anchorExisting.workId
                    } else {
                        val newWorkId = UUID.randomUUID().toString()
                        val anchorMeta = dao.getEdition(anchorGalleryId)?.toDomain()
                        val title = workTitle?.trim()?.takeIf { it.isNotEmpty() }
                            ?: anchorMeta?.displayTitle?.takeIf { it.isNotBlank() }
                            ?: "#$anchorGalleryId"
                        val anchorUnitId = UUID.randomUUID().toString()
                        val (inferredLabel, inferredOrder) = MangaIdentityMatcher.suggestChapterLabelAndOrder(
                            anchorMeta,
                            fallbackLabel = anchorUnitLabel,
                            fallbackOrder = anchorSortOrder
                        )
                        dao.upsertWork(MangaWorkEntity(newWorkId, title, now))
                        dao.upsertUnit(
                            MangaReadingUnitEntity(
                                unitId = anchorUnitId,
                                workId = newWorkId,
                                label = inferredLabel,
                                sortOrder = inferredOrder,
                                kind = UnitKind.Chapter.wire,
                                updatedAt = now
                            )
                        )
                        dao.upsertLink(MangaEditionLinkEntity(anchorGalleryId, newWorkId, anchorUnitId, now))
                        newWorkId
                    }
                }
                else -> return@withTransaction null
            }

            val newUnitId = UUID.randomUUID().toString()
            dao.upsertUnit(
                MangaReadingUnitEntity(
                    unitId = newUnitId,
                    workId = resolvedWorkId,
                    label = cleanLabel,
                    sortOrder = sortOrder,
                    kind = kind.wire,
                    updatedAt = now
                )
            )
            val created = MangaEditionLinkEntity(galleryId, resolvedWorkId, newUnitId, now)
            dao.upsertLink(created)
            if (previous != null && previous.unitId != newUnitId) {
                pruneEmptyUnitAndWork(previous.workId, previous.unitId)
            }
            created
        }
    }

    /**
     * Unlinks [galleryId] from its reading unit and work. Removes inherited read status immediately
     * without removing [galleryId]'s own explicit read mark or reading progress.
     */
    suspend fun unlink(galleryId: Int) {
        if (!nsfwGate.allows()) return
        database.withTransaction {
            val previous = dao.getLink(galleryId) ?: return@withTransaction
            dao.deleteLink(galleryId)
            pruneEmptyUnitAndWork(previous.workId, previous.unitId)
        }
    }

    /** Permanently suppresses auto-grouping and link suggestions between [aGalleryId] and [bGalleryId]. */
    suspend fun rejectSuggestion(aGalleryId: Int, bGalleryId: Int, now: Long = System.currentTimeMillis()) {
        if (!nsfwGate.allows() || aGalleryId == bGalleryId) return
        val (left, right) = canonicalPair(aGalleryId, bGalleryId)
        dao.upsertRejection(MangaRejectedLinkEntity(left, right, now))
    }

    suspend fun clearRejection(aGalleryId: Int, bGalleryId: Int) {
        if (!nsfwGate.allows() || aGalleryId == bGalleryId) return
        val (left, right) = canonicalPair(aGalleryId, bGalleryId)
        dao.deleteRejection(left, right)
    }

    private suspend fun pruneEmptyUnitAndWork(workId: String, unitId: String) {
        if (dao.countLinksInUnit(unitId) == 0) {
            dao.deleteUnit(unitId)
            if (dao.countUnitsInWork(workId) == 0) {
                dao.deleteWork(workId)
            }
        }
    }

    private fun mergeForStorage(existing: EditionMetadata?, incoming: EditionMetadata): EditionMetadata {
        if (existing == null) return incoming
        val keepExistingTags = existing.tagsComplete && !incoming.tagsComplete
        return EditionMetadata(
            galleryId = incoming.galleryId,
            displayTitle = incoming.displayTitle.ifBlank { existing.displayTitle },
            japaneseTitle = incoming.japaneseTitle?.takeIf { it.isNotBlank() } ?: existing.japaneseTitle,
            thumbnailPath = incoming.thumbnailPath.ifBlank { existing.thumbnailPath },
            numPages = if (incoming.numPages > 0) incoming.numPages else existing.numPages,
            artistIds = when {
                keepExistingTags -> existing.artistIds
                incoming.tagsComplete -> incoming.artistIds
                existing.artistIds.isNotEmpty() -> existing.artistIds
                else -> incoming.artistIds
            },
            language = incoming.language?.takeIf { it.isNotBlank() } ?: existing.language,
            tagsComplete = incoming.tagsComplete || existing.tagsComplete,
            updatedAt = maxOf(incoming.updatedAt, existing.updatedAt)
        )
    }
}

private fun MangaEditionIndexEntity.toDomain() = EditionMetadata(
    galleryId = galleryId,
    displayTitle = displayTitle,
    japaneseTitle = japaneseTitle,
    thumbnailPath = thumbnailPath,
    numPages = numPages,
    artistIds = artistIds.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().sorted(),
    language = language,
    tagsComplete = tagsComplete,
    updatedAt = updatedAt
)

private fun EditionMetadata.toEntity() = MangaEditionIndexEntity(
    galleryId = galleryId,
    displayTitle = displayTitle,
    japaneseTitle = japaneseTitle,
    thumbnailPath = thumbnailPath,
    numPages = numPages,
    artistIds = artistIds.distinct().sorted().joinToString(","),
    language = language,
    tagsComplete = tagsComplete,
    updatedAt = updatedAt
)
