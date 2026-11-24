package ru.spgsroot.peachy.ui.manga

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.translate.Box
import ru.spgsroot.peachy.translate.LineKind
import ru.spgsroot.peachy.translate.OcrBlock
import ru.spgsroot.peachy.translate.TranslatedLine

@RunWith(AndroidJUnit4::class)
class TranslationOverlayRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inputChangesClearCompletedPlatesAndCancelledLayoutsCannotReplaceThem() {
        val first = line("First", Box(0f, 0f, 1f, 1f))
        val second = line("Second", Box(0f, 0f, 1f, 1f))
        val page = Rect(0f, 0f, 200f, 200f)
        val firstPlates = layoutPlates(listOf(first), page, Density(1f))
        val secondPlates = layoutPlates(listOf(second), page, Density(1f))
        val request = mutableIntStateOf(0)
        val gates = List(4) { CompletableDeferred<List<PlacedPlate>>() }
        val observed = AtomicReference<List<PlacedPlate>?>()
        compose.setContent {
            val current = request.intValue
            val plates = rememberPlateLayout(
                if (current == 0) listOf(first) else listOf(second),
                if (current < 2) page else Rect(0f, 0f, 100f, 200f),
                if (current < 3) Density(1f) else Density(1f, 1.3f)
            ) { gates[current].await() }.value
            SideEffect { observed.set(plates) }
        }
        gates[0].complete(firstPlates)
        compose.waitUntil { observed.get() === firstPlates }
        compose.runOnIdle { request.intValue = 1 }
        compose.runOnIdle { assertNull(observed.get()) }
        compose.runOnIdle { request.intValue = 2 }
        gates[1].complete(firstPlates)
        compose.runOnIdle { assertNull(observed.get()) }
        gates[2].complete(secondPlates)
        compose.waitUntil { observed.get() === secondPlates }
        compose.runOnIdle { request.intValue = 3 }
        compose.runOnIdle { assertNull(observed.get()) }
        gates[3].complete(secondPlates)
        compose.waitUntil { observed.get() === secondPlates }
        compose.runOnIdle { assertSame(secondPlates, observed.get()) }
    }

    @Test fun disablingOverlayImmediatelyRemovesItsPixelsAndSelection() {
        val translated = line("Visible", Box(0.1f, 0.1f, 0.9f, 0.9f))
        val enabled = mutableStateOf(true)
        var omitted = 0
        compose.setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(160.dp).background(Color.Black).testTag("overlay")) {
                TranslationOverlay(
                    if (enabled.value) listOf(translated) else emptyList(), 1f,
                    selectedLine = translated,
                    onSelectionOmitted = { omitted++ }
                )
            }
        }
        val overlay = compose.onNodeWithTag("overlay")
        compose.waitUntil(5_000) {
            val pixels = overlay.captureToImage().toPixelMap()
            pixels[pixels.width / 4, pixels.height / 4].red > 0.5f
        }
        compose.runOnIdle { enabled.value = false }
        val pixels = overlay.captureToImage().toPixelMap()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) assertEquals(Color.Black, pixels[x, y])
        compose.runOnIdle { assertEquals(0, omitted) }
    }

    @Test fun firstOverlayTapOnAnOmittedPlateOpensExistingFullTextPanel() {
        val box = Box(0.2f, 0.2f, 0.20001f, 0.20001f)
        val translated = line("The complete omitted translation", box)
        lateinit var session: BubbleTapSession
        var translations = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val lines = remember { mutableStateMapOf<Int, List<TranslatedLine>>() }
            session = remember {
                BubbleTapSession(scope, lines, { listOf(OcrBlock("Source", box)) }, { _, _ ->
                    translations++
                    translated
                }, { false })
            }
            Column {
                ReaderPage(
                    model = null, page = 0, active = false, zoom = remember { ReaderZoom() },
                    fillWidth = false, strip = true, tapOnY = false, modifier = Modifier.size(160.dp),
                    viewportHeight = 160.dp, startOffset = 0f, resetAt = 0, onResetConsumed = {},
                    onScroll = { _, _ -> }, onStripMeasured = {}, lines = lines[0].orEmpty(),
                    onBubbleTap = null, selectedLine = session.selection?.takeIf { it.first == 0 }?.second,
                    onSelectionOmitted = { session.showOmitted(0, it) }, onTap = { _, _ -> }
                )
                session.panel?.let { TranslationPanel(it, null, null, {}, {}, session::dismiss, bubble = true) }
            }
        }
        compose.runOnIdle { session.tap(0, Offset(0.2f, 0.2f), emptyList(), { true }) {} }
        compose.waitUntil(5_000) { session.panel != null }
        compose.onNodeWithText(translated.translation).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, translations) }
    }

    @Test fun ellipsizedPlateDoesNotAutomaticallyOpenFullTextPanel() {
        val translated = line("A translation with a readable beginning. ".repeat(100), Box(0f, 0f, 1f, 1f))
        val tinyPage = Rect(0f, 0f, 60f, 40f)
        val plate = layoutPlates(listOf(translated), tinyPage, Density(1f)).single()
        assertSame(translated, plate.line)
        org.junit.Assert.assertTrue((0 until plate.layout.lineCount).any { plate.layout.getEllipsisCount(it) > 0 })
        var omitted = 0
        compose.setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(60.dp, 40.dp).background(Color.Black).testTag("ellipsis")) {
                TranslationOverlay(
                    listOf(translated), 1.5f, selectedLine = translated, onSelectionOmitted = { omitted++ }
                )
            }
        }
        val overlay = compose.onNodeWithTag("ellipsis")
        compose.waitUntil(5_000) {
            val pixels = overlay.captureToImage().toPixelMap()
            pixels[pixels.width / 2, 1].red > 0.5f
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, omitted) }
    }

    @Test fun doubleTapReflowsAnOtherwiseTooShallowCaption() {
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        val caption = line("Été déjà, прочитано", Box(.2f, .49f, .8f, .51f))
        val zoom = ReaderZoom()
        compose.setContent {
            ReaderPage(image, 0, true, zoom, false, false, false, Modifier.size(200.dp).testTag("zoom"),
                200.dp, 0f, 0, {}, { _, _ -> }, {}, listOf(caption), null, onTap = { _, _ -> })
        }
        val page = compose.onNodeWithTag("zoom")
        compose.waitUntil(10_000) {
            val p = page.captureToImage().toPixelMap()
            p[p.width / 2, p.height / 2] == Color.Cyan
        }
        page.performTouchInput { doubleClick(center) }
        compose.waitUntil(10_000) {
            val p = page.captureToImage().toPixelMap()
            p[p.width / 2, p.height / 3] == Color.Cyan &&
                (p.height * 48 / 100..p.height * 52 / 100).any { y ->
                    (p.width / 4..p.width * 3 / 4).any { x -> p[x, y].green < .25f }
                }
        }
        page.performTouchInput { doubleClick(center) }
        compose.waitUntil(5_000) {
            val p = page.captureToImage().toPixelMap()
            !zoom.zoomed && (p.width / 4..p.width * 3 / 4).all { x -> p[x, p.height / 2] == Color.Cyan }
        }
    }

    private fun line(text: String, box: Box) = TranslatedLine(LineKind.Speech, "Source", text, box)
}
