package ru.spgsroot.peachy.manga

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max

/** Distinguishes a normal chapter/oneshot reading unit from a multi-chapter collection/omnibus. */
enum class UnitKind(val wire: String) {
    Chapter("chapter"),
    Collection("collection");

    companion object {
        fun fromWire(raw: String): UnitKind? = entries.firstOrNull { it.wire == raw }
    }
}

data class MangaWork(
    val workId: String,
    val title: String,
    val updatedAt: Long
)

data class MangaReadingUnit(
    val unitId: String,
    val workId: String,
    val label: String,
    val sortOrder: Double,
    val kind: UnitKind,
    val updatedAt: Long
)

data class MangaEditionLink(
    val galleryId: Int,
    val workId: String,
    val unitId: String,
    val updatedAt: Long
)

data class RejectedLinkPair(
    val leftGalleryId: Int,
    val rightGalleryId: Int,
    val createdAt: Long
) {
    companion object {
        fun of(a: Int, b: Int, createdAt: Long): RejectedLinkPair {
            val (left, right) = canonicalPair(a, b)
            return RejectedLinkPair(left, right, createdAt)
        }
    }
}

data class EditionReadMark(
    val galleryId: Int,
    val markedAt: Long
)

data class EditionMetadata(
    val galleryId: Int,
    val displayTitle: String,
    val japaneseTitle: String? = null,
    val thumbnailPath: String = "",
    val numPages: Int = 0,
    val artistIds: List<Int> = emptyList(),
    val language: String? = null,
    val tagsComplete: Boolean = true,
    val updatedAt: Long = 0L
)

data class MangaIdentitySnapshot(
    val works: Map<String, MangaWork> = emptyMap(),
    val units: Map<String, MangaReadingUnit> = emptyMap(),
    val links: Map<Int, MangaEditionLink> = emptyMap(),
    val rejections: Set<Pair<Int, Int>> = emptySet(),
    val readMarks: Map<Int, EditionReadMark> = emptyMap(),
    val editions: Map<Int, EditionMetadata> = emptyMap()
)

enum class MatchDecision {
    SameId,
    Separate,
    Review,
    Preview
}

sealed class EditionReadStatus {
    data object Unread : EditionReadStatus()
    data class ReadOwn(val markedAt: Long) : EditionReadStatus()
    data class ReadInOtherTranslation(
        val sourceGalleryIds: List<Int>,
        val unitLabel: String
    ) : EditionReadStatus()

    val isRead: Boolean get() = this !is Unread
}

data class WorkChapterProgress(
    val workId: String,
    val workTitle: String,
    val readChapters: Int,
    val knownChapters: Int,
    val collectionCount: Int
)

data class WorkUnitSummary(
    val unit: MangaReadingUnit,
    val galleryIds: List<Int>,
    val isRead: Boolean
)

data class SuggestedPeer(
    val metadata: EditionMetadata,
    val decision: MatchDecision,
    val samePages: Boolean
)

data class ProjectedGroup<T>(
    val key: String,
    val primary: T,
    val members: List<T>,
    val isConfirmed: Boolean,
    val workId: String? = null,
    val unitId: String? = null,
    val unitLabel: String? = null,
    val unitKind: UnitKind? = null,
    val workProgress: WorkChapterProgress? = null
)

fun canonicalPair(a: Int, b: Int): Pair<Int, Int> = if (a <= b) a to b else b to a

fun GallerySummary.toEditionMetadata(now: Long = 0L): EditionMetadata {
    val resolvedComplete = tagsComplete && (tagIds.isEmpty() || tagIds.all { id -> tags.any { it.id == id } })
    return EditionMetadata(
        galleryId = id,
        displayTitle = title,
        japaneseTitle = japaneseTitle,
        thumbnailPath = thumbnailPath,
        numPages = numPages,
        artistIds = tags.filter { it.type == "artist" }.map { it.id }.distinct().sorted(),
        language = tags.language(),
        tagsComplete = resolvedComplete,
        updatedAt = now
    )
}

fun GalleryDetail.toEditionMetadata(now: Long = 0L): EditionMetadata = EditionMetadata(
    galleryId = id,
    displayTitle = title,
    japaneseTitle = japaneseTitle,
    thumbnailPath = thumbnailPath,
    numPages = pagePaths.size,
    artistIds = tags.filter { it.type == "artist" }.map { it.id }.distinct().sorted(),
    language = tags.language(),
    tagsComplete = true,
    updatedAt = now
)

fun RememberedGallery.toEditionMetadata(): EditionMetadata = EditionMetadata(
    galleryId = id,
    displayTitle = title,
    japaneseTitle = japaneseTitle,
    thumbnailPath = thumbnailPath,
    numPages = numPages,
    artistIds = tags.filter { it.type == "artist" }.map { it.id }.distinct().sorted(),
    language = tags.language(),
    tagsComplete = true,
    updatedAt = seenAt
)

fun LibraryGallery.toFallbackEditionMetadata(cached: EditionMetadata?): EditionMetadata {
    if (cached != null) {
        return cached.copy(
            displayTitle = cached.displayTitle.ifBlank { title },
            thumbnailPath = cached.thumbnailPath.ifBlank { thumbnailPath },
            numPages = if (cached.numPages > 0) cached.numPages else pagePaths.size
        )
    }
    return EditionMetadata(
        galleryId = id,
        displayTitle = title,
        japaneseTitle = null,
        thumbnailPath = thumbnailPath,
        numPages = pagePaths.size,
        artistIds = emptyList(),
        language = null,
        tagsComplete = false,
        updatedAt = 0L
    )
}

/**
 * Pure offline matcher for reversible preview grouping and manual link suggestions.
 * A preview group is strictly display-only and never grants authority to propagate read marks.
 */
object MangaIdentityMatcher {
    private val LANG_SUFFIX = Pattern.compile(
        """\s*\[(?:english|japanese|chinese|russian|英訳|中国翻訳|中国翻译|ロシア翻訳)\]$"""
    )
    private val CHAPTER = Pattern.compile(
        """(?:(?<![\p{L}\p{N}_])(?:ch(?:apter)?\.?|глава)\s*(\d+(?:\.\d+)?)(?![\p{L}\p{N}_.])|第\s*(\d+(?:\.\d+)?)\s*話)"""
    )
    private val COMPOSITE = Pattern.compile(
        """(?<![\p{L}\p{N}_])(?:ongoing|omnibus|anthology|complete collection)(?![\p{L}\p{N}_])|合本|(?:(?<![\p{L}\p{N}_])ch(?:apter)?\.?\s*|第)\d+(?:\.\d+)?\s*[-–~〜]\s*\d+"""
    )

    fun normalizeText(raw: String): String {
        if (raw.isEmpty()) return ""
        val halfWidth = StringBuilder(raw.length)
        for (ch in raw) {
            val code = ch.code
            halfWidth.append(if (code in 0xFF01..0xFF5E) (code - 0xFEE0).toChar() else ch)
        }
        val nfc = Normalizer.normalize(halfWidth.toString(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val collapsed = StringBuilder(nfc.length)
        var inSpace = true
        for (ch in nfc) {
            if (Character.isWhitespace(ch)) {
                if (!inSpace) {
                    collapsed.append(' ')
                    inSpace = true
                }
            } else {
                collapsed.append(ch)
                inSpace = false
            }
        }
        if (collapsed.isNotEmpty() && collapsed.last() == ' ') {
            collapsed.setLength(collapsed.length - 1)
        }
        return collapsed.toString()
    }

    fun normalizeOriginalTitle(japaneseTitle: String?): String {
        if (japaneseTitle.isNullOrBlank()) return ""
        val normalized = normalizeText(japaneseTitle)
        return LANG_SUFFIX.matcher(normalized).replaceFirst("").trim()
    }

    fun extractChapterNumbers(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val matcher = CHAPTER.matcher(normalizeText(text))
        val found = mutableListOf<String>()
        while (matcher.find()) {
            val token = matcher.group(1) ?: matcher.group(2)
            if (token != null) found += token
        }
        return found
    }

    fun isCompositeOrOngoing(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return COMPOSITE.matcher(normalizeText(text)).find()
    }

    /** Suggests an initial chapter label and decimal sort order when creating a manual chapter link. */
    fun suggestChapterLabelAndOrder(metadata: EditionMetadata?, fallbackLabel: String, fallbackOrder: Double): Pair<String, Double> {
        if (metadata == null) return fallbackLabel to fallbackOrder
        val numbers = extractChapterNumbers(metadata.japaneseTitle).ifEmpty {
            extractChapterNumbers(metadata.displayTitle)
        }
        val first = numbers.firstOrNull() ?: return fallbackLabel to fallbackOrder
        val parsed = first.toDoubleOrNull() ?: return fallbackLabel to fallbackOrder
        return first to parsed
    }

    fun classifyPair(a: EditionMetadata, b: EditionMetadata): MatchDecision {
        if (a.galleryId == b.galleryId) return MatchDecision.SameId
        if (!a.tagsComplete || !b.tagsComplete) return MatchDecision.Separate
        val origA = normalizeOriginalTitle(a.japaneseTitle)
        val origB = normalizeOriginalTitle(b.japaneseTitle)
        if (origA.isEmpty() || origA != origB) return MatchDecision.Separate
        if (a.artistIds.isEmpty() || a.artistIds.toSet() != b.artistIds.toSet()) return MatchDecision.Separate
        if (a.language.isNullOrBlank() || b.language.isNullOrBlank() || a.language == b.language) {
            return MatchDecision.Review
        }
        val signatures = buildSet {
            for (edition in listOf(a, b)) {
                for (field in listOf(edition.japaneseTitle, edition.displayTitle)) {
                    val seq = extractChapterNumbers(field)
                    if (seq.isNotEmpty()) add(seq)
                }
            }
        }
        return if (signatures.size > 1) MatchDecision.Review else MatchDecision.Preview
    }

    fun previewGroups(
        editions: List<EditionMetadata>,
        rejectedPairs: Set<Pair<Int, Int>> = emptySet()
    ): List<List<Int>> {
        val distinct = editions.distinctBy { it.galleryId }
        val buckets = LinkedHashMap<Pair<String, List<Int>>, MutableList<EditionMetadata>>()
        for (edition in distinct) {
            if (!edition.tagsComplete) continue
            val orig = normalizeOriginalTitle(edition.japaneseTitle)
            val artists = edition.artistIds.distinct().sorted()
            if (orig.isNotEmpty() && artists.isNotEmpty()) {
                buckets.getOrPut(orig to artists) { mutableListOf() }.add(edition)
            }
        }
        val groups = mutableListOf<List<Int>>()
        for (bucket in buckets.values) {
            if (bucket.size < 2) continue
            var allPreview = true
            outer@ for (i in 0 until bucket.size) {
                for (j in i + 1 until bucket.size) {
                    val left = bucket[i]
                    val right = bucket[j]
                    if (canonicalPair(left.galleryId, right.galleryId) in rejectedPairs ||
                        classifyPair(left, right) != MatchDecision.Preview
                    ) {
                        allPreview = false
                        break@outer
                    }
                }
            }
            if (!allPreview) continue
            val pageCounts = bucket.map { it.numPages }.toSet()
            if (pageCounts.size != 1 || pageCounts.single() <= 0) continue
            val hasComposite = bucket.any {
                isCompositeOrOngoing(it.japaneseTitle) || isCompositeOrOngoing(it.displayTitle)
            }
            if (hasComposite) continue
            groups += bucket.map { it.galleryId }.sorted()
        }
        return groups
    }
}

/**
 * Shared projection across Catalog, Author search, Detail, Library, History and For You.
 * Read marks propagate strictly within confirmed reading units; auto-groups never share read status.
 */
object MangaIdentityProjector {
    fun readStatus(galleryId: Int, snapshot: MangaIdentitySnapshot): EditionReadStatus {
        snapshot.readMarks[galleryId]?.let { return EditionReadStatus.ReadOwn(it.markedAt) }
        val link = snapshot.links[galleryId] ?: return EditionReadStatus.Unread
        val sources = snapshot.links.values
            .filter {
                it.workId == link.workId &&
                    it.unitId == link.unitId &&
                    it.galleryId != galleryId &&
                    it.galleryId in snapshot.readMarks
            }
            .map { it.galleryId }
            .sorted()
        if (sources.isEmpty()) return EditionReadStatus.Unread
        val label = snapshot.units[link.unitId]?.label.orEmpty()
        return EditionReadStatus.ReadInOtherTranslation(sources, label)
    }

    /**
     * Known-length legacy history at the last page is shown only as previous progress on that edition,
     * never promoted to an explicit cross-translation read confirmation.
     */
    fun isLegacyLastPageOnly(page: Int, pageCount: Int, status: EditionReadStatus): Boolean =
        status is EditionReadStatus.Unread && pageCount > 0 && page >= pageCount - 1

    fun workProgress(workId: String, snapshot: MangaIdentitySnapshot): WorkChapterProgress? {
        val work = snapshot.works[workId] ?: return null
        val units = snapshot.units.values.filter { it.workId == workId }
        val chapters = units.filter { it.kind == UnitKind.Chapter }
        val collections = units.count { it.kind == UnitKind.Collection }
        val linksByUnit = snapshot.links.values
            .filter { it.workId == workId }
            .groupBy { it.unitId }
        val readChapters = chapters.count { chapter ->
            linksByUnit[chapter.unitId].orEmpty().any { it.galleryId in snapshot.readMarks }
        }
        return WorkChapterProgress(
            workId = workId,
            workTitle = work.title,
            readChapters = readChapters,
            knownChapters = chapters.size,
            collectionCount = collections
        )
    }

    fun workUnits(workId: String, snapshot: MangaIdentitySnapshot): List<WorkUnitSummary> {
        val linksByUnit = snapshot.links.values
            .filter { it.workId == workId }
            .groupBy { it.unitId }
        return snapshot.units.values
            .filter { it.workId == workId }
            .sortedWith(
                compareBy<MangaReadingUnit> { if (it.kind == UnitKind.Chapter) 0 else 1 }
                    .thenBy { it.sortOrder }
                    .thenBy { it.updatedAt }
                    .thenBy { it.unitId }
            )
            .map { unit ->
                val ids = linksByUnit[unit.unitId].orEmpty().map { it.galleryId }.sorted()
                val isRead = ids.any { it in snapshot.readMarks }
                WorkUnitSummary(unit = unit, galleryIds = ids, isRead = isRead)
            }
    }

    fun confirmedTranslations(galleryId: Int, snapshot: MangaIdentitySnapshot): List<Int> {
        val link = snapshot.links[galleryId] ?: return emptyList()
        return snapshot.links.values
            .filter { it.workId == link.workId && it.unitId == link.unitId && it.galleryId != galleryId }
            .map { it.galleryId }
            .sorted()
    }

    fun suggestedPeers(galleryId: Int, snapshot: MangaIdentitySnapshot): List<SuggestedPeer> {
        val current = snapshot.editions[galleryId] ?: return emptyList()
        if (!current.tagsComplete) return emptyList()
        val myLink = snapshot.links[galleryId]
        val peers = mutableListOf<SuggestedPeer>()
        for (other in snapshot.editions.values) {
            if (other.galleryId == galleryId) continue
            if (canonicalPair(galleryId, other.galleryId) in snapshot.rejections) continue
            val otherLink = snapshot.links[other.galleryId]
            if (myLink != null && otherLink != null) {
                // Already explicitly placed (same unit -> already confirmed, different unit/work -> user decided).
                continue
            }
            val decision = MangaIdentityMatcher.classifyPair(current, other)
            if (decision == MatchDecision.Preview || decision == MatchDecision.Review) {
                val samePages = current.numPages > 0 && current.numPages == other.numPages
                peers += SuggestedPeer(other, decision, samePages)
            }
        }
        return peers.sortedWith(
            compareBy<SuggestedPeer> { if (it.decision == MatchDecision.Preview) 0 else 1 }
                .thenBy { if (it.samePages) 0 else 1 }
                .thenBy { it.metadata.galleryId }
        )
    }

    fun preferredLanguagesFor(queryTags: List<TagFilter>, appLocaleLanguage: String): List<String> = buildList {
        for (tag in queryTags) {
            if (tag.type == "language" && tag.mode == TagMode.Include && tag.name != "translated") {
                add(tag.name.lowercase(Locale.ROOT))
            }
        }
        when (appLocaleLanguage.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) {
            "ru" -> add("russian")
            "en" -> add("english")
            "ja" -> add("japanese")
            "zh" -> add("chinese")
        }
        if ("english" !in this) add("english")
    }.distinct()

    /**
     * Collapses visible (already-filtered, non-blacklisted) items into confirmed reading-unit groups
     * and reversible display-only auto-preview groups. The group key never depends on cover or language.
     */
    fun <T> projectList(
        items: List<T>,
        idOf: (T) -> Int,
        metadataOf: (T) -> EditionMetadata,
        preferredLanguages: List<String>,
        snapshot: MangaIdentitySnapshot
    ): List<ProjectedGroup<T>> {
        if (items.isEmpty()) return emptyList()
        val itemsById = LinkedHashMap<Int, T>()
        val metaById = HashMap<Int, EditionMetadata>()
        for (item in items) {
            val id = idOf(item)
            if (id in itemsById) continue
            itemsById[id] = item
            metaById[id] = mergeMetadata(live = metadataOf(item), cached = snapshot.editions[id])
        }

        val confirmedByUnit = LinkedHashMap<Pair<String, String>, MutableList<Int>>()
        val unlinkedIds = mutableListOf<Int>()
        for (id in itemsById.keys) {
            val link = snapshot.links[id]
            if (link != null && link.workId in snapshot.works && link.unitId in snapshot.units) {
                confirmedByUnit.getOrPut(link.workId to link.unitId) { mutableListOf() }.add(id)
            } else {
                unlinkedIds += id
            }
        }

        val autoGroups = MangaIdentityMatcher.previewGroups(
            editions = unlinkedIds.mapNotNull(metaById::get),
            rejectedPairs = snapshot.rejections
        )
        val autoGroupById = HashMap<Int, List<Int>>()
        for (groupIds in autoGroups) {
            for (id in groupIds) autoGroupById[id] = groupIds
        }

        val emittedIds = HashSet<Int>()
        val result = mutableListOf<ProjectedGroup<T>>()
        for (id in itemsById.keys) {
            if (id in emittedIds) continue
            val link = snapshot.links[id]
            if (link != null && (link.workId to link.unitId) in confirmedByUnit) {
                val memberIds = confirmedByUnit.getValue(link.workId to link.unitId)
                emittedIds.addAll(memberIds)
                val members = memberIds.map(itemsById::getValue)
                val primary = pickRepresentative(members, idOf, metaById, preferredLanguages)
                val unit = snapshot.units[link.unitId]
                result += ProjectedGroup(
                    key = "confirmed:${link.unitId}",
                    primary = primary,
                    members = members,
                    isConfirmed = true,
                    workId = link.workId,
                    unitId = link.unitId,
                    unitLabel = unit?.label,
                    unitKind = unit?.kind,
                    workProgress = workProgress(link.workId, snapshot)
                )
            } else {
                val autoIds = autoGroupById[id]
                if (autoIds != null && autoIds.size >= 2) {
                    val orderedIds = itemsById.keys.filter { it in autoIds }
                    emittedIds.addAll(orderedIds)
                    val members = orderedIds.map(itemsById::getValue)
                    val primary = pickRepresentative(members, idOf, metaById, preferredLanguages)
                    result += ProjectedGroup(
                        key = "auto:${orderedIds.sorted().joinToString("-")}",
                        primary = primary,
                        members = members,
                        isConfirmed = false
                    )
                } else {
                    emittedIds += id
                    val single = itemsById.getValue(id)
                    result += ProjectedGroup(
                        key = "single:$id",
                        primary = single,
                        members = listOf(single),
                        isConfirmed = false
                    )
                }
            }
        }
        return result
    }

    /**
     * Ensures one chapter read does not emit multiple taste signals across confirmed translations
     * of the same reading unit. Unlinked editions remain independent.
     */
    fun consolidateTasteSignals(
        signals: List<TasteSignal>,
        snapshot: MangaIdentitySnapshot
    ): List<TasteSignal> {
        val grouped = LinkedHashMap<Pair<String, String>, MutableList<TasteSignal>>()
        val result = mutableListOf<TasteSignal>()
        for (signal in signals) {
            val link = snapshot.links[signal.galleryId]
            if (link == null) {
                result += signal
            } else {
                grouped.getOrPut(link.workId to link.unitId) { mutableListOf() }.add(signal)
            }
        }
        for (unitSignals in grouped.values) {
            val positive = unitSignals.filter { it.weight > 0.0 }
            val chosen = if (positive.isNotEmpty()) {
                positive.maxBy { it.weight }
            } else {
                unitSignals.minBy { it.weight }
            }
            val mergedTags = unitSignals.flatMap { it.tags }.distinctBy { it.id to (it.type to it.name.lowercase(Locale.ROOT)) }
            val latestAt = unitSignals.fold(0L) { acc, s -> max(acc, s.at) }
            result += chosen.copy(tags = mergedTags, at = latestAt)
        }
        return result
    }

    fun excludedGalleryIds(
        readHistoryIds: Set<Int>,
        savedIds: Set<Int>,
        dismissedIds: Set<Int>,
        snapshot: MangaIdentitySnapshot
    ): Set<Int> = buildSet {
        addAll(readHistoryIds)
        addAll(savedIds)
        addAll(dismissedIds)
        addAll(snapshot.readMarks.keys)
        for (galleryId in snapshot.links.keys) {
            if (readStatus(galleryId, snapshot).isRead) add(galleryId)
        }
    }

    private fun mergeMetadata(live: EditionMetadata, cached: EditionMetadata?): EditionMetadata {
        if (cached == null) return live
        val useCachedArtists = !live.tagsComplete && cached.tagsComplete
        return EditionMetadata(
            galleryId = live.galleryId,
            displayTitle = live.displayTitle.ifBlank { cached.displayTitle },
            japaneseTitle = live.japaneseTitle?.takeIf { it.isNotBlank() } ?: cached.japaneseTitle,
            thumbnailPath = live.thumbnailPath.ifBlank { cached.thumbnailPath },
            numPages = if (live.numPages > 0) live.numPages else cached.numPages,
            artistIds = when {
                useCachedArtists -> cached.artistIds
                live.artistIds.isNotEmpty() -> live.artistIds
                else -> cached.artistIds
            },
            language = live.language?.takeIf { it.isNotBlank() } ?: cached.language,
            tagsComplete = live.tagsComplete || cached.tagsComplete,
            updatedAt = max(live.updatedAt, cached.updatedAt)
        )
    }

    private fun <T> pickRepresentative(
        members: List<T>,
        idOf: (T) -> Int,
        metaById: Map<Int, EditionMetadata>,
        preferredLanguages: List<String>
    ): T {
        for (lang in preferredLanguages) {
            val match = members.firstOrNull { item ->
                metaById[idOf(item)]?.language?.equals(lang, ignoreCase = true) == true
            }
            if (match != null) return match
        }
        return members.first()
    }
}
