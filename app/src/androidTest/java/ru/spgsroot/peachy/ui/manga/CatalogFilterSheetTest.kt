package ru.spgsroot.peachy.ui.manga

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.manga.CatalogQuery
import ru.spgsroot.peachy.manga.TagFilter
import ru.spgsroot.peachy.manga.TagMode
import ru.spgsroot.peachy.ui.theme.PeachyTheme

/**
 * Modal wiring for [FilterSheet] as the user touches it: the real sheet is hosted in a plain Compose
 * activity (no MainActivity, no app data, no catalog network, no auth or production seam), every
 * control is located by its localized resource text, and taps/text entry are real touch and IME
 * actions. The JVM `CatalogFilterDraftTest`/`CatalogSearchTest` cover query construction and
 * cancellation; these tests prove the controls are actually connected to that debounced path.
 *
 * No wall-clock sleep is needed. `settledFilterQuery` validates and compares the draft *before* its
 * 250 ms delay (FILTER_SETTLE_MS), so a rejected draft (invalid or reversed range, or the query already
 * shown) never has a pending request to wait out. A valid edit is observed by waiting for the exact
 * emission count, and the same assertion fails if an edit emits twice.
 */
@RunWith(AndroidJUnit4::class)
class CatalogFilterSheetTest {

    @get:Rule
    val compose = createComposeRule()

    /** Queries the sheet hands to the screen through `onQueryChange`; the sheet's only outward seam. */
    private val applied = mutableListOf<CatalogQuery>()

    private fun res(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun res(id: Int, arg: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, arg)

    private fun queries(): List<CatalogQuery> = synchronized(applied) { applied.toList() }

    /**
     * Hosts the real sheet. `ModalBottomSheet` renders in its own window, which is added after the
     * first frame, so the title waits for that window instead of assuming it exists immediately.
     */
    private fun showSheet() {
        compose.setContent {
            PeachyTheme {
                FilterSheet(
                    query = CatalogQuery(),
                    blockedIds = emptySet(),
                    blockedNames = emptySet(),
                    onBlockId = {},
                    onUnblockId = {},
                    onBlockName = {},
                    onUnblockName = {},
                    onDismiss = {},
                    onQueryChange = { synchronized(applied) { applied.add(it) } }
                )
            }
        }
        compose.waitUntil(SHEET_TIMEOUT_MS) {
            compose.onAllNodesWithText(res(R.string.manga_filters)).fetchSemanticsNodes().isNotEmpty()
        }
        // Advances past the sheet's show animation on the Compose test clock, so the first tap lands
        // on a settled window. The Compose clock does not drive the production 250 ms coroutine delay.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** The sheet body is a LazyColumn; the date controls sit below the fold on short screens. */
    private fun scrollSheetTo(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
    }

    /** Waits for [expected] emissions, then proves the count is exact, i.e. one query per edit. */
    private fun awaitApplied(expected: Int) {
        compose.waitUntil(SHEET_TIMEOUT_MS) { synchronized(applied) { applied.size } >= expected }
        compose.waitForIdle()
        assertEquals("Unexpected emissions: ${queries()}", expected, queries().size)
    }

    @Test
    fun tagAddThenCycleThenRemoveEmitsOneQueryPerEdit() {
        showSheet()
        compose.waitForIdle()
        assertEquals("Opening the sheet must not repeat the query it was opened with", 0, queries().size)

        // The first "Tag name" field belongs to the visible Tags section; the blacklist has its own.
        compose.onAllNodes(hasSetTextAction() and hasText(res(R.string.manga_tag_name)))[0]
            .performTextInput(TAG_NAME)
        compose.onNodeWithText(res(R.string.manga_add_tag)).performClick()

        awaitApplied(1)
        assertEquals(
            CatalogQuery(tags = listOf(TagFilter("tag", TAG_NAME, TagMode.Include))),
            queries()[0]
        )

        compose.onNodeWithText(res(R.string.manga_tag_included, "tag:$TAG_NAME")).performClick()
        awaitApplied(2)
        assertEquals(
            CatalogQuery(tags = listOf(TagFilter("tag", TAG_NAME, TagMode.Exclude))),
            queries()[1]
        )

        compose.onNodeWithText(res(R.string.manga_tag_excluded, "tag:$TAG_NAME")).performClick()
        awaitApplied(3)
        assertEquals("Removing the only filter returns the unfiltered query", CatalogQuery(), queries()[2])
    }

    @Test
    fun resetClearsAnAppliedFilterWithOneQuery() {
        showSheet()
        val english = res(R.string.manga_language_english)
        scrollSheetTo(hasText(english))
        compose.onNodeWithText(english).performClick()

        awaitApplied(1)
        assertEquals(
            CatalogQuery(tags = listOf(TagFilter("language", "english", TagMode.Include))),
            queries()[0]
        )

        compose.onNodeWithText(res(R.string.manga_reset_filters)).performClick()
        awaitApplied(2)
        assertEquals(
            "Reset returns every sheet-owned filter to its default without an Apply step",
            CatalogQuery(),
            queries()[1]
        )
    }

    @Test
    fun impossibleThenReversedDatesKeepTheLastValidQueryAndShowTheErrors() {
        showSheet()
        val startLabel = res(R.string.manga_date_start)
        val endLabel = res(R.string.manga_date_end)
        scrollSheetTo(hasText(startLabel))

        compose.onNodeWithText(startLabel).performTextInput(IMPOSSIBLE_DATE)
        scrollSheetTo(hasText(res(R.string.manga_date_invalid)))
        compose.onNodeWithText(res(R.string.manga_date_invalid)).assertIsDisplayed()
        compose.waitForIdle()
        assertEquals("An impossible date must not reach the catalog", 0, queries().size)

        compose.onNodeWithText(startLabel).performTextReplacement(START_DATE)
        awaitApplied(1)
        assertEquals(CatalogQuery(uploaded = "uploaded:>$START_DATE"), queries()[0])

        compose.onNodeWithText(endLabel).performTextInput(EARLY_END_DATE)
        scrollSheetTo(hasText(res(R.string.manga_date_reversed)))
        compose.onNodeWithText(res(R.string.manga_date_reversed)).assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(
            "A reversed range must leave the last valid request in place",
            listOf(CatalogQuery(uploaded = "uploaded:>$START_DATE")),
            queries()
        )

        compose.onNodeWithText(endLabel).performTextReplacement(LATE_END_DATE)
        awaitApplied(2)
        assertEquals(
            CatalogQuery(uploaded = "uploaded:>$START_DATE uploaded:<$LATE_END_DATE"),
            queries()[1]
        )
    }

    private companion object {
        const val SHEET_TIMEOUT_MS = 10_000L
        const val TAG_NAME = "peachy instrumented tag"
        const val IMPOSSIBLE_DATE = "2024-02-30"
        const val START_DATE = "2024-03-01"
        const val EARLY_END_DATE = "2024-01-01"
        const val LATE_END_DATE = "2024-06-01"
    }
}
