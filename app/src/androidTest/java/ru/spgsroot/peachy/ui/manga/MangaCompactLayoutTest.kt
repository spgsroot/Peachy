package ru.spgsroot.peachy.ui.manga

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.manga.GalleryDetail
import ru.spgsroot.peachy.ui.theme.PeachyTheme
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MangaCompactLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun russianContinueAndSecondaryActionsAt320dp() = checkActions(320, savedPage = 5)

    @Test fun russianContinueAndSecondaryActionsAt411dp() = checkActions(411, savedPage = 5)

    @Test fun russianReadAndSecondaryActionsAt320dp() = checkActions(320, savedPage = null)

    @Test fun russianDrawerScrollsToAndClicksRandomAt320x569dp() {
        var randomClicks = 0
        val context = setCompactContent(320) {
            ModalNavigationDrawer(
                drawerState = rememberDrawerState(DrawerValue.Open),
                drawerContent = {
                    MangaHubDrawerContent(
                        destination = HubDestination.Catalog,
                        onDestination = {},
                        onTags = {},
                        onCharacters = {},
                        onRandom = { randomClicks++ }
                    )
                }
            ) { Box(Modifier.fillMaxSize()) }
        }
        val label = context.getString(R.string.manga_drawer_random)
        val random = compose.onNodeWithText(label)
        random.assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        assertTextFits(label)
        random.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, randomClicks) }
    }

    private fun checkActions(widthDp: Int, savedPage: Int?) {
        var reads = 0
        var downloads = 0
        var removals = 0
        var favorites = 0
        var notes = 0
        val saved = mutableStateOf(false)
        val detail = GalleryDetail(1, "Fixture", "", List(12) { "page-$it.jpg" })
        val context = setCompactContent(widthDp) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                GalleryDetailActions(
                    detail = detail,
                    savedPage = savedPage,
                    saved = saved.value,
                    favorite = false,
                    progress = null,
                    onRead = { reads++ },
                    onDownload = { downloads++; saved.value = true },
                    onDeleteDownload = { removals++; saved.value = false },
                    onFavorite = { favorites++ },
                    hasNote = false,
                    onNote = { notes++ }
                )
            }
        }
        val primaryLabel = if (savedPage == null) context.getString(R.string.manga_detail_read)
        else context.getString(R.string.manga_detail_continue, savedPage + 1)
        val primary = compose.onNodeWithText(primaryLabel).assertIsDisplayed()
        assertTextFits(primaryLabel)
        val bounds = primary.fetchSemanticsNode().touchBoundsInRoot
        val density = context.resources.displayMetrics.density
        assertTrue("Read action must be at least 48dp wide", bounds.width >= 48 * density - 1)
        assertTrue("Read action must be at least 48dp high", bounds.height >= 48 * density - 1)
        primary.performTouchInput { click() }

        val downloadLabel = context.getString(R.string.manga_detail_download)
        compose.onNodeWithText(downloadLabel).assertIsDisplayed()
        assertTextFits(downloadLabel)
        compose.onNodeWithText(downloadLabel).performTouchInput { click() }
        val removeLabel = context.getString(R.string.manga_detail_delete_download)
        compose.onNodeWithText(removeLabel).assertIsDisplayed()
        assertTextFits(removeLabel)
        // Saved downloads must not take width back from the resume action.
        assertTextFits(primaryLabel)
        compose.onNodeWithText(removeLabel).performTouchInput { click() }
        compose.onNodeWithContentDescription(context.getString(R.string.manga_favorites))
            .assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithContentDescription(context.getString(R.string.content_description_note))
            .assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, reads)
            assertEquals(1, downloads)
            assertEquals(1, removals)
            assertEquals(1, favorites)
            assertEquals(1, notes)
        }
    }

    private fun assertTextFits(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        // Compose measures glyphs in fractional pixels but reports the text node in whole pixels.
        repeat(layout.lineCount) { line ->
            assertFalse("Ellipsized label: $label", layout.isLineEllipsized(line))
            assertTrue("Clipped line in $label: ${layout.getLineLeft(line)}..${layout.getLineRight(line)} of ${layout.size}",
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
        }
        val last = layout.lineCount - 1
        assertEquals(label.length, layout.getLineEnd(last))
        assertTrue("Clipped final line of $label", layout.getLineBottom(last) <= layout.size.height + 1f)
    }

    private fun setCompactContent(widthDp: Int, content: @Composable () -> Unit): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(target.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru"))
            screenWidthDp = widthDp
            screenHeightDp = 569
            fontScale = 1.3f
        }
        val context = target.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, 1.3f)
            ) {
                PeachyTheme {
                    Surface(Modifier.requiredSize(widthDp.dp, 569.dp)) { content() }
                }
            }
        }
        return context
    }
}
