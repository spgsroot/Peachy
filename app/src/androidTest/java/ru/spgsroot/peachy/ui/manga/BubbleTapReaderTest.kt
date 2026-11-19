package ru.spgsroot.peachy.ui.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.ui.dialog.TranslateConsentDialog
import ru.spgsroot.peachy.translate.LineKind
import ru.spgsroot.peachy.translate.MlKitPageOcr
import ru.spgsroot.peachy.translate.TranslatedLine

@RunWith(AndroidJUnit4::class)
class BubbleTapReaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tapSelectsZoomedTextAndLeavesLetterboxForNavigation() {
        val image = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 56f; textAlign = Paint.Align.CENTER }
        Canvas(image).apply {
            drawText("FIRST", 200f, 120f, paint)
            drawText("HELLO", 640f, 320f, paint)
        }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val requests = mutableListOf<String>()
        var navigations = 0
        var consented = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val allow = context.getString(R.string.consent_accept)
        val decline = context.getString(R.string.consent_decline)
        val zoom = ReaderZoom()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val session = remember {
                BubbleTapSession(scope, mutableStateMapOf(), { MlKitPageOcr().recognize(bytes, "en") }, { _, block ->
                    requests.add(block.text)
                    TranslatedLine(LineKind.Speech, block.text, if (block.text == "HELLO") "Привет" else "Первый", block.box)
                }, { !consented })
            }
            LaunchedEffect(session) { session.prepare(0) }
            session.afterConsent?.let { approve ->
                TranslateConsentDialog("https://fixture.invalid", { consented = true; approve() }, session::dismiss)
            }
            Column {
                ReaderPage(image, 0, true, zoom, fillWidth = false, strip = false, tapOnY = false,
                    modifier = Modifier.size(240.dp).background(androidx.compose.ui.graphics.Color.Black).testTag("page"), viewportHeight = 240.dp,
                    startOffset = 0f, resetAt = 0, onResetConsumed = {}, onScroll = { _, _ -> }, onStripMeasured = {},
                    lines = emptyList(), onBubbleTap = { at, miss -> session.tap(0, at, emptyList(), { false }, miss) },
                    onTap = { _, _ -> navigations++ })
                session.panel?.let { TranslationPanel(it, null, null, {}, {}, session::dismiss, bubble = true) }
            }
        }
        val page = compose.onNodeWithTag("page")
        compose.waitUntil(10_000) {
            val pixels = page.captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2].green > 0.95f && pixels[pixels.width / 2, 1] != pixels[pixels.width / 2, pixels.height / 2]
        }
        page.performTouchInput { click(Offset(width * .2f, height * .08f)) }
        compose.waitUntil(3_000) { navigations == 1 }
        page.performTouchInput { click(center) }
        compose.waitUntil(30_000) { navigations == 2 }
        page.performTouchInput { doubleClick(center) }
        compose.waitUntil(3_000) { zoom.zoomed }
        page.performTouchInput { click(Offset(width * .85f, height * .625f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(decline).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(decline).performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), requests) }
        page.performTouchInput { click(Offset(width * .85f, height * .625f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(allow).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(allow).performClick()
        compose.waitUntil(30_000) { requests.isNotEmpty() }
        compose.onNodeWithText("Привет").assertIsDisplayed()
        page.performTouchInput { click(Offset(width * .85f, height * .625f)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("HELLO"), requests)
            assertEquals(2, navigations)
        }
    }
}
