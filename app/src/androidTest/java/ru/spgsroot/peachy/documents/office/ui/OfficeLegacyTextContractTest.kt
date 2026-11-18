package ru.spgsroot.peachy.documents.office.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.documents.office.engine.OfficeLegacyBlock
import ru.spgsroot.peachy.documents.office.engine.OfficeLegacySlide
import ru.spgsroot.peachy.ui.theme.PeachyTheme

/**
 * Contract for the read-only legacy `.ppt` surface: the fixture model is fed straight into the
 * presentational composable, so the test proves what the extracted text state renders without a
 * mock of the engine or a fake extraction.
 */
@RunWith(AndroidJUnit4::class)
class OfficeLegacyTextContractTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hiddenSlideTableAndNoteRenderAsTextOnlyWithNoActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = context.getString(R.string.office_legacy_scope)
        val hidden = context.getString(R.string.office_legacy_hidden)
        val notes = context.getString(R.string.office_legacy_notes)

        compose.setContent {
            PeachyTheme {
                OfficeLegacyText(
                    state = OfficeLegacyTextState.Ready(SLIDES),
                    onBack = {},
                )
            }
        }

        // File order: the first visible slide, then the hidden one with its table and notes.
        compose.onNodeWithText(scope).assertExists()
        compose.onNodeWithText("Слайд 1 — Начало").assertExists()
        compose.onNodeWithText("Кириллица: привет, мир").assertExists()

        // The slide list is lazy: drive it to the second item before asserting its text.
        compose.onNodeWithTag(OFFICE_LEGACY_SLIDES_TAG)
            .performScrollToNode(hasText("Слайд 2 — Таблица"))

        compose.onNodeWithText("Слайд 2 — Таблица").assertExists()

        compose.onAllNodesWithText(hidden).assertCountEquals(1)

        // Row-major cells, each one real selectable text.
        listOf("Ключ", "Значение", "альфа", "42").forEach { cell ->
            compose.onNodeWithText(cell).assertExists()
        }
        compose.waitForIdle()
        val rendered = compose.onAllNodes(
            SemanticsMatcher("has text") { node ->
                node.config.contains(SemanticsProperties.Text)
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { span -> span.text }
        }
        assertEquals(TABLE_CELLS, rendered.filter { it in TABLE_CELLS })

        compose.onNodeWithText(notes).assertExists()
        compose.onNodeWithText("Заметки докладчика: проверка кириллицы.").assertExists()

        compose.onNode(
            hasText("Слайд 2 — Таблица") and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        ).assertExists()
        compose.onNode(
            hasText(notes) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        ).assertExists()

        // Nothing to edit, export or share leaks out of the text surface.
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    private companion object {
        /** Authored row-major order from the legacy fixture: two rows of two cells. */
        val TABLE_CELLS = listOf("Ключ", "Значение", "альфа", "42")

        val SLIDES = listOf(
            OfficeLegacySlide(
                title = "Слайд 1 — Начало",
                hidden = false,
                blocks = listOf(OfficeLegacyBlock.Paragraph("Кириллица: привет, мир")),
                notes = emptyList(),
            ),
            OfficeLegacySlide(
                title = "Слайд 2 — Таблица",
                hidden = true,
                blocks = listOf(
                    OfficeLegacyBlock.Paragraph("Таблица: проверка ячеек"),
                    OfficeLegacyBlock.Table(rows = TABLE_CELLS.chunked(2)),
                ),
                notes = listOf("Заметки докладчика: проверка кириллицы."),
            ),
        )
    }
}
