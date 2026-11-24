package ru.spgsroot.peachy.ui.manga

import android.content.ClipDescription
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.manga.GalleryDetail

class CopyMangaTitleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun copyFromEllipsizedToolbarWritesTheEntirePrimaryTitle() {
        val title = "За последней страницей — ".repeat(8) + "終・the end"
        val detail = GalleryDetail(1, title, "", emptyList(), japaneseTitle = "別の題名")
        val written = AtomicReference<ClipEntry>()
        compose.setContent {
            val isolated = object : Clipboard by LocalClipboard.current {
                override suspend fun getClipEntry(): ClipEntry? = error("The copy action must not read the clipboard")
                override suspend fun setClipEntry(clipEntry: ClipEntry?) { written.set(clipEntry) }
            }
            CompositionLocalProvider(LocalClipboard provides isolated) { GalleryDetailTopBar(detail) {} }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().isLineEllipsized(0))
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.manga_detail_copy_title)
        compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction().performClick()
        compose.waitUntil { written.get() != null }
        val clip = written.get().clipData
        assertEquals(title, clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }
}
