package ru.spgsroot.peachy.ui.manga

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.spgsroot.peachy.manga.CatalogQuery
import ru.spgsroot.peachy.manga.PagesFilter
import ru.spgsroot.peachy.manga.SortKey
import ru.spgsroot.peachy.manga.TagFilter
import ru.spgsroot.peachy.manga.TagMode

/**
 * The sheet has no Apply button: every add, remove, cycle and reset becomes a draft that the debounced
 * gate turns into a request. Invalid and reversed date ranges never leave the sheet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogFilterDraftTest {
    @Test
    fun cyclingATagAddsThenExcludesThenRemovesIt() {
        val base = CatalogFilterDraft()

        val included = base.cycleTag("language", "english")
        val excluded = included.cycleTag("language", "english")
        val removed = excluded.cycleTag("language", "english")

        assertEquals(listOf(TagFilter("language", "english", TagMode.Include)), included.toQuery().tags)
        assertEquals(listOf(TagFilter("language", "english", TagMode.Exclude)), excluded.toQuery().tags)
        assertTrue(removed.toQuery().tags.isEmpty())
        assertEquals(base.toQuery(), removed.toQuery())
    }

    @Test
    fun addingAnAlreadyPresentTagDoesNotDuplicateIt() {
        val draft = CatalogFilterDraft().addTag("tag", "school uniform").addTag("tag", "school uniform")

        assertEquals(listOf(TagFilter("tag", "school uniform", TagMode.Include)), draft.tags)
    }

    @Test
    fun resetClearsTagsSortDatesAndPages() {
        val filtered = CatalogQuery(
            text = "text",
            tags = listOf(TagFilter("tag", "school uniform", TagMode.Exclude)),
            sort = SortKey.PopularMonth,
            uploaded = "uploaded:>2024-01-01 uploaded:<2024-03-01",
            pages = PagesFilter(min = 10, max = 50, exact = 7)
        )

        assertEquals(CatalogQuery(text = "text"), CatalogFilterDraft.from(filtered).reset().toQuery("text"))
    }

    @Test
    fun presetSelectionIsRestoredAndCustomDatesReplaceIt() {
        val preset = CatalogFilterDraft.from(CatalogQuery(uploaded = "uploaded:>7d"))
        assertEquals("uploaded:>7d", preset.preset)
        assertEquals("", preset.start)

        val custom = CatalogFilterDraft.from(CatalogQuery(uploaded = "uploaded:>2024-01-01 uploaded:<2024-02-01"))
        assertEquals("", custom.preset)
        assertEquals("2024-01-01", custom.start)
        assertEquals("2024-02-01", custom.end)
    }

    @Test
    fun invalidDateRangeIsNeverSubmittedAndDoesNotWaitForTheDebounce() = runTest {
        val draft = CatalogFilterDraft(start = "2024-02-30")
        val submitted = mutableListOf<CatalogQuery>()

        assertFalse(draft.datesValid)
        val result = settledFilterQuery(draft.toQuery(), draft.datesValid, draft.reversed, CatalogQuery()) { submitted += it }

        assertNull(result)
        assertTrue(submitted.isEmpty())
        assertEquals(0L, currentTime)
    }

    @Test
    fun reversedRangeIsNeverSubmittedEvenThoughBothDatesAreValid() = runTest {
        val draft = CatalogFilterDraft(start = "2024-03-01", end = "2024-01-01")
        val submitted = mutableListOf<CatalogQuery>()

        assertTrue(draft.datesValid)
        assertTrue(draft.reversed)
        assertEquals("", draft.toQuery().uploaded)

        val result = settledFilterQuery(draft.toQuery(), draft.datesValid, draft.reversed, CatalogQuery()) { submitted += it }

        assertNull(result)
        assertTrue(submitted.isEmpty())
        assertEquals(0L, currentTime)
    }

    @Test
    fun addRemoveAndResetAreEachSubmittedOnceWithoutApply() = runTest {
        var applied = CatalogQuery()
        val submitted = mutableListOf<CatalogQuery>()

        suspend fun submit(draft: CatalogFilterDraft) {
            settledFilterQuery(draft.toQuery(), draft.datesValid, draft.reversed, applied) { applied = it }
                ?.let { submitted += it }
        }

        var draft = CatalogFilterDraft()
        draft = draft.cycleTag("tag", "school uniform")
        submit(draft)
        draft = draft.cycleTag("tag", "school uniform")
        submit(draft)
        draft = draft.cycleTag("tag", "school uniform")
        submit(draft)

        assertEquals(3, submitted.size)
        assertEquals(listOf(TagFilter("tag", "school uniform", TagMode.Exclude)), submitted[1].tags)
        assertTrue(submitted[2].tags.isEmpty())

        draft = draft.copy(sort = SortKey.Popular)
        submit(draft)
        assertEquals(SortKey.Popular, submitted.last().sort)

        draft = draft.reset()
        submit(draft)
        assertEquals(CatalogQuery(), submitted.last())

        val sent = submitted.size
        submit(draft)
        assertEquals(sent, submitted.size)
    }

    @Test
    fun onlyTheNewestDraftIsSubmittedOnceTheDebounceElapses() = runTest {
        var applied = CatalogQuery()
        val submitted = mutableListOf<CatalogQuery>()

        suspend fun submit(draft: CatalogFilterDraft) {
            settledFilterQuery(draft.toQuery(), draft.datesValid, draft.reversed, applied) { applied = it }
                ?.let { submitted += it }
        }

        val first = CatalogFilterDraft(tags = listOf(TagFilter("tag", "one", TagMode.Include)))
        val second = CatalogFilterDraft(tags = listOf(TagFilter("tag", "two", TagMode.Include)))

        val earlier = launch { submit(first) }
        advanceTimeBy(FILTER_SETTLE_MS / 2)
        earlier.cancel()
        launch { submit(second) }
        advanceTimeBy(FILTER_SETTLE_MS - 1)
        assertTrue(submitted.isEmpty())

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(listOf(second.toQuery()), submitted)
        assertEquals(second.toQuery(), applied)
    }
}
