package ru.spgsroot.peachy.ui.manga

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.ui.theme.PeachyTheme
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RecommendationActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readIsLeftOfDismissOnAPhoneCard() = checkLayout(1f, sameLine = true)
    @Test fun largeRussianLabelsWrapWithoutOverlappingTouchTargets() = checkLayout(1.5f, sameLine = false)

    private fun checkLayout(fontScale: Float, sameLine: Boolean) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(target.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        val context = target.createConfigurationContext(config)
        val cardWidth = 176.dp
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)
            ) {
                PeachyTheme {
                    Surface { Box(Modifier.width(cardWidth)) { RecommendationActions(true, {}, {}) } }
                }
            }
        }
        fun button(id: Int) = compose.onNodeWithText(context.getString(id)).assertIsDisplayed()
            .fetchSemanticsNode().touchBoundsInRoot
        val read = button(R.string.manga_for_you_read)
        val dismiss = button(R.string.manga_for_you_not_interested)
        val minTouchPx = with(compose.density) { 48.dp.roundToPx() }
        assertTrue(read.height >= minTouchPx && dismiss.height >= minTouchPx)
        assertTrue(dismiss.right - read.left <= with(compose.density) { cardWidth.roundToPx() })
        if (sameLine) assertTrue(read.top == dismiss.top && read.right < dismiss.left)
        else assertTrue(read.bottom <= dismiss.top)
    }
}
