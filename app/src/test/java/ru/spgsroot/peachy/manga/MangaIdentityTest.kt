package ru.spgsroot.peachy.manga

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MangaIdentityTest {
    private fun edition(
        id: Int,
        original: String?,
        display: String = "",
        artists: List<Int> = listOf(17, 29),
        language: String? = "japanese",
        pages: Int = 24,
        tagsComplete: Boolean = true
    ) = EditionMetadata(
        galleryId = id,
        displayTitle = display,
        japaneseTitle = original,
        thumbnailPath = "t/$id.jpg",
        numPages = pages,
        artistIds = artists,
        language = language,
        tagsComplete = tagsComplete,
        updatedAt = 100L
    )

    @Test
    fun matcherMatchesAllLinkProbeControlCasesSymmetrically() {
        val a = edition(1, original = "星図 第１話", display = "Star Map Ch. 1", artists = listOf(17, 29), language = "japanese")
        val b = edition(2, original = "星図\u3000第1話 [英訳]", display = "Atlas — Chapter 1", artists = listOf(29, 17), language = "english")

        val cases = listOf(
            Triple("width, language suffix, author order and translated display", a to b, MatchDecision.Preview),
            Triple("same gallery", a to a, MatchDecision.SameId),
            Triple("homonym by another artist", a to b.copy(artistIds = listOf(39)), MatchDecision.Separate),
            Triple("one shared coauthor", a to b.copy(artistIds = listOf(17)), MatchDecision.Separate),
            Triple("no resolved artists", a.copy(artistIds = emptyList()) to b.copy(artistIds = emptyList()), MatchDecision.Separate),
            Triple("unknown original, identical display", a.copy(japaneseTitle = "") to b.copy(japaneseTitle = "", displayTitle = a.displayTitle), MatchDecision.Separate),
            Triple("chapter 1 versus 10", a to b.copy(japaneseTitle = "星図 第10話"), MatchDecision.Separate),
            Triple("decimal chapter", a to b.copy(japaneseTitle = "星図 第1.5話"), MatchDecision.Separate),
            Triple("bonus", a to b.copy(japaneseTitle = "星図 第1話 番外編"), MatchDecision.Separate),
            Triple("collected edition", a to b.copy(japaneseTitle = "星図 第1話 [合本]"), MatchDecision.Separate),
            Triple("same-language reissue", a to b.copy(language = "japanese"), MatchDecision.Review),
            Triple("unknown language", a to b.copy(language = ""), MatchDecision.Review),
            Triple("conflicting translated chapter", a to b.copy(displayTitle = "Atlas — глава 2"), MatchDecision.Review),
            Triple("two different translations without original", a.copy(japaneseTitle = "Карта звёзд") to b.copy(japaneseTitle = "Star Atlas"), MatchDecision.Separate),
            Triple("meaningful bracket stays", a to b.copy(japaneseTitle = "星図 第1話 [Afterword]"), MatchDecision.Separate),
            Triple("bare title collides even for the same artist", a.copy(japaneseTitle = "星図", displayTitle = "") to b.copy(japaneseTitle = "星図", displayTitle = ""), MatchDecision.Preview)
        )

        for ((name, pair, expected) in cases) {
            val (left, right) = pair
            assertEquals("$name (forward)", expected, MangaIdentityMatcher.classifyPair(left, right))
            assertEquals("$name (reverse)", expected, MangaIdentityMatcher.classifyPair(right, left))
        }

        assertEquals(listOf(listOf(1, 2)), MangaIdentityMatcher.previewGroups(listOf(b, a)))
        assertTrue(MangaIdentityMatcher.previewGroups(listOf(a, b, b.copy(galleryId = 3))).isEmpty())
        assertTrue(MangaIdentityMatcher.previewGroups(listOf(a, b.copy(numPages = 25))).isEmpty())
        assertTrue(MangaIdentityMatcher.previewGroups(listOf(a.copy(numPages = 0), b.copy(numPages = 0))).isEmpty())
        assertTrue(MangaIdentityMatcher.previewGroups(listOf(a, b.copy(displayTitle = "Ch. 1–3"))).isEmpty())
        assertTrue(MangaIdentityMatcher.previewGroups(listOf(a, b.copy(displayTitle = "Ch. 1 (ongoing)"))).isEmpty())
    }

    @Test
    fun partialTagResolutionNeverAutoGroupsOrSuggests() {
        val a = edition(1, original = "星図 第１話", display = "Star Map Ch. 1", language = "japanese", tagsComplete = true)
        val b = edition(2, original = "星図 第1話 [英訳]", display = "Star Map Ch. 1", language = "english", tagsComplete = false)

        assertEquals(MatchDecision.Separate, MangaIdentityMatcher.classifyPair(a, b))
        assertTrue(MangaIdentityMatcher.previewGroups(listOf(a, b)).isEmpty())
        val snapshot = MangaIdentitySnapshot(editions = mapOf(1 to a, 2 to b))
        assertTrue(MangaIdentityProjector.suggestedPeers(1, snapshot).isEmpty())
    }

    @Test
    fun controlDatasetTitleCollisionsMatchesExpectedAggregateCounts() {
        val fixture = listOf(
            File("tools/manga-research/title-collisions.json"),
            File("../tools/manga-research/title-collisions.json")
        ).firstOrNull { it.isFile }
        assertTrue("Expected control fixture tools/manga-research/title-collisions.json", fixture != null)
        val arr = JSONArray(fixture!!.readText(Charsets.UTF_8))
        val editions = List(arr.length()) { index ->
            val obj = arr.getJSONObject(index)
            val artistsJson = obj.getJSONArray("artists")
            val artists = List(artistsJson.length()) { i -> artistsJson.getInt(i) }
            EditionMetadata(
                galleryId = obj.getInt("id"),
                displayTitle = obj.optString("display", ""),
                japaneseTitle = obj.optString("original", ""),
                thumbnailPath = "",
                numPages = obj.optInt("pages", 0),
                artistIds = artists,
                language = obj.optString("language", "").takeIf { it.isNotBlank() },
                tagsComplete = true,
                updatedAt = 0L
            )
        }

        val byOriginal = editions.groupBy { MangaIdentityMatcher.normalizeOriginalTitle(it.japaneseTitle) }
            .filterKeys { it.isNotEmpty() }
        var previewPairs = 0
        var reviewPairs = 0
        var separatePairs = 0
        for (bucket in byOriginal.values) {
            for (i in bucket.indices) {
                for (j in i + 1 until bucket.size) {
                    when (MangaIdentityMatcher.classifyPair(bucket[i], bucket[j])) {
                        MatchDecision.Preview -> previewPairs++
                        MatchDecision.Review -> reviewPairs++
                        MatchDecision.Separate -> separatePairs++
                        MatchDecision.SameId -> Unit
                    }
                }
            }
        }
        val groups = MangaIdentityMatcher.previewGroups(editions)
        assertEquals(14, previewPairs)
        assertEquals(7, reviewPairs)
        assertEquals(1, separatePairs)
        assertEquals(5, groups.size)
        assertEquals(11, groups.sumOf { it.size })
    }

    @Test
    fun autoGroupNeverPropagatesReadStatusAndRejectionSplitsGroup() {
        val ru = edition(101, original = "星図 第1話", display = "Star Map RU", language = "russian", pages = 20)
        val en = edition(102, original = "星図 第1話 [英訳]", display = "Star Map EN", language = "english", pages = 20)
        val snapshot = MangaIdentitySnapshot(
            readMarks = mapOf(101 to EditionReadMark(101, 500L)),
            editions = mapOf(101 to ru, 102 to en)
        )

        val projected = MangaIdentityProjector.projectList(
            items = listOf(ru, en),
            idOf = { it.galleryId },
            metadataOf = { it },
            preferredLanguages = listOf("russian", "english"),
            snapshot = snapshot
        )
        assertEquals(1, projected.size)
        assertFalse(projected.single().isConfirmed)
        assertEquals("auto:101-102", projected.single().key)
        assertEquals(EditionReadStatus.ReadOwn(500L), MangaIdentityProjector.readStatus(101, snapshot))
        assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(102, snapshot))

        val rejectedSnapshot = snapshot.copy(rejections = setOf(canonicalPair(101, 102)))
        val split = MangaIdentityProjector.projectList(
            items = listOf(ru, en),
            idOf = { it.galleryId },
            metadataOf = { it },
            preferredLanguages = listOf("russian", "english"),
            snapshot = rejectedSnapshot
        )
        assertEquals(2, split.size)
        assertTrue(MangaIdentityProjector.suggestedPeers(101, rejectedSnapshot).isEmpty())
    }

    @Test
    fun confirmedChaptersShareReadMarkOnlyWithinUnitAndPreserveFractionalOrderAndCollections() {
        val workId = "work-atlas"
        val prologue = MangaReadingUnit("u-pro", workId, "Пролог", 0.0, UnitKind.Chapter, 1L)
        val ch1 = MangaReadingUnit("u-1", workId, "1", 1.0, UnitKind.Chapter, 2L)
        val ch15 = MangaReadingUnit("u-15", workId, "1.5", 1.5, UnitKind.Chapter, 3L)
        val vol2Ch1 = MangaReadingUnit("u-v2c1", workId, "Том 2 / Глава 1", 2.0, UnitKind.Chapter, 4L)
        val omnibus = MangaReadingUnit("u-col", workId, "Сборник 1-2", 1.0, UnitKind.Collection, 5L)

        val base = MangaIdentitySnapshot(
            works = mapOf(workId to MangaWork(workId, "Atlas", 10L)),
            units = listOf(vol2Ch1, ch15, omnibus, ch1, prologue).associateBy { it.unitId },
            links = mapOf(
                101 to MangaEditionLink(101, workId, ch1.unitId, 10L),
                102 to MangaEditionLink(102, workId, ch1.unitId, 11L),
                103 to MangaEditionLink(103, workId, ch15.unitId, 12L),
                104 to MangaEditionLink(104, workId, vol2Ch1.unitId, 13L),
                105 to MangaEditionLink(105, workId, prologue.unitId, 14L),
                200 to MangaEditionLink(200, workId, omnibus.unitId, 15L)
            ),
            readMarks = mapOf(
                101 to EditionReadMark(101, 50L),
                200 to EditionReadMark(200, 60L) // reading collection must NOT mark individual chapters read
            )
        )

        // Confirmed translation inherits read mark; other chapters stay unread despite collection being read.
        assertEquals(EditionReadStatus.ReadOwn(50L), MangaIdentityProjector.readStatus(101, base))
        assertEquals(EditionReadStatus.ReadInOtherTranslation(listOf(101), "1"), MangaIdentityProjector.readStatus(102, base))
        assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(103, base))
        assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(104, base))
        assertEquals(EditionReadStatus.ReadOwn(60L), MangaIdentityProjector.readStatus(200, base))

        // Ordering preserves 0.0 < 1.0 < 1.5 < 2.0 and places collections after chapters.
        val ordered = MangaIdentityProjector.workUnits(workId, base)
        assertEquals(
            listOf("Пролог", "1", "1.5", "Том 2 / Глава 1", "Сборник 1-2"),
            ordered.map { it.unit.label }
        )

        // Known chapters counter counts only Chapter units, never collections.
        val progress = MangaIdentityProjector.workProgress(workId, base)!!
        assertEquals(1, progress.readChapters)
        assertEquals(4, progress.knownChapters)
        assertEquals(1, progress.collectionCount)

        // Marking all 4 chapters read gives 4/4, then adding chapter 5 changes it to 4/5.
        val allFourRead = base.copy(
            readMarks = base.readMarks + mapOf(
                103 to EditionReadMark(103, 70L),
                104 to EditionReadMark(104, 71L),
                105 to EditionReadMark(105, 72L)
            )
        )
        assertEquals(4, MangaIdentityProjector.workProgress(workId, allFourRead)!!.readChapters)
        assertEquals(4, MangaIdentityProjector.workProgress(workId, allFourRead)!!.knownChapters)

        val ch5 = MangaReadingUnit("u-5", workId, "5", 5.0, UnitKind.Chapter, 80L)
        val withCh5 = allFourRead.copy(
            units = allFourRead.units + (ch5.unitId to ch5),
            links = allFourRead.links + (106 to MangaEditionLink(106, workId, ch5.unitId, 80L))
        )
        assertEquals(4, MangaIdentityProjector.workProgress(workId, withCh5)!!.readChapters)
        assertEquals(5, MangaIdentityProjector.workProgress(workId, withCh5)!!.knownChapters)

        // Unlinking 102 immediately removes inherited status without touching 101.
        val unlinked = base.copy(links = base.links - 102)
        assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(102, unlinked))
        assertEquals(EditionReadStatus.ReadOwn(50L), MangaIdentityProjector.readStatus(101, unlinked))
    }

    @Test
    fun legacyLastPageOnlyShowsWhenUnreadAndKnownLength() {
        assertTrue(MangaIdentityProjector.isLegacyLastPageOnly(page = 11, pageCount = 12, status = EditionReadStatus.Unread))
        assertFalse(MangaIdentityProjector.isLegacyLastPageOnly(page = 10, pageCount = 12, status = EditionReadStatus.Unread))
        assertFalse(MangaIdentityProjector.isLegacyLastPageOnly(page = -1, pageCount = 0, status = EditionReadStatus.Unread))
        assertFalse(MangaIdentityProjector.isLegacyLastPageOnly(page = 11, pageCount = 12, status = EditionReadStatus.ReadOwn(1L)))
    }

    @Test
    fun projectionPicksPreferredLanguageFromFilteredItemsWithLanguageIndependentKeys() {
        val workId = "w1"
        val unitId = "u1"
        val ru = edition(101, original = "星図", display = "RU", language = "russian")
        val en = edition(102, original = "星図", display = "EN", language = "english")
        val ja = edition(103, original = "星図", display = "JA", language = "japanese")
        val snapshot = MangaIdentitySnapshot(
            works = mapOf(workId to MangaWork(workId, "Atlas", 1L)),
            units = mapOf(unitId to MangaReadingUnit(unitId, workId, "1", 1.0, UnitKind.Chapter, 1L)),
            links = mapOf(
                101 to MangaEditionLink(101, workId, unitId, 1L),
                102 to MangaEditionLink(102, workId, unitId, 1L),
                103 to MangaEditionLink(103, workId, unitId, 1L)
            )
        )

        val enFirst = MangaIdentityProjector.projectList(
            items = listOf(ja, en, ru),
            idOf = { it.galleryId },
            metadataOf = { it },
            preferredLanguages = MangaIdentityProjector.preferredLanguagesFor(
                listOf(TagFilter("language", "english", TagMode.Include)),
                appLocaleLanguage = "ru"
            ),
            snapshot = snapshot
        ).single()

        val ruFirst = MangaIdentityProjector.projectList(
            items = listOf(ja, en, ru),
            idOf = { it.galleryId },
            metadataOf = { it },
            preferredLanguages = MangaIdentityProjector.preferredLanguagesFor(emptyList(), appLocaleLanguage = "ru"),
            snapshot = snapshot
        ).single()

        assertEquals(102, enFirst.primary.galleryId)
        assertEquals(101, ruFirst.primary.galleryId)
        assertEquals(enFirst.key, ruFirst.key)
        assertEquals("confirmed:u1", enFirst.key)

        // Filtering out RU (e.g. blacklist or language filter) never resurrects RU into projected members.
        val filteredOnlyJa = MangaIdentityProjector.projectList(
            items = listOf(ja),
            idOf = { it.galleryId },
            metadataOf = { it },
            preferredLanguages = listOf("russian", "english"),
            snapshot = snapshot
        ).single()
        assertEquals(103, filteredOnlyJa.primary.galleryId)
        assertEquals(listOf(103), filteredOnlyJa.members.map { it.galleryId })
    }

    @Test
    fun tasteSignalsDeduplicatePerConfirmedUnitAndExcludeConfirmedReadTranslations() {
        val workId = "w1"
        val unitId = "u1"
        val snapshot = MangaIdentitySnapshot(
            works = mapOf(workId to MangaWork(workId, "Atlas", 1L)),
            units = mapOf(unitId to MangaReadingUnit(unitId, workId, "1", 1.0, UnitKind.Chapter, 1L)),
            links = mapOf(
                101 to MangaEditionLink(101, workId, unitId, 1L),
                102 to MangaEditionLink(102, workId, unitId, 1L)
            ),
            readMarks = mapOf(101 to EditionReadMark(101, 200L))
        )
        val tagA = GalleryTag(1, "tag", "fantasy", 100)
        val tagB = GalleryTag(2, "artist", "alice", 50)
        val signals = listOf(
            TasteSignal(101, listOf(tagA), weight = 3.0, at = 200L),
            TasteSignal(102, listOf(tagA, tagB), weight = 0.4, at = 250L),
            TasteSignal(999, listOf(tagA), weight = 1.5, at = 150L)
        )

        val consolidated = MangaIdentityProjector.consolidateTasteSignals(signals, snapshot)
        assertEquals(2, consolidated.size)
        val unitSignal = consolidated.first { it.galleryId == 101 }
        assertEquals(3.0, unitSignal.weight, 0.0001)
        assertEquals(250L, unitSignal.at)
        assertEquals(setOf(1, 2), unitSignal.tags.map { it.id }.toSet())

        val excluded = MangaIdentityProjector.excludedGalleryIds(
            readHistoryIds = emptySet(),
            savedIds = emptySet(),
            dismissedIds = emptySet(),
            snapshot = snapshot
        )
        assertTrue(101 in excluded)
        assertTrue(102 in excluded)
        assertFalse(999 in excluded)
        assertNotEquals(signals.size, consolidated.size)
    }
}
