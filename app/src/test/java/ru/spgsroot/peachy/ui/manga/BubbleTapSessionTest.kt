package ru.spgsroot.peachy.ui.manga

import androidx.compose.ui.geometry.Offset
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.spgsroot.peachy.translate.Box
import ru.spgsroot.peachy.translate.OcrBlock
import ru.spgsroot.peachy.translate.LineKind
import ru.spgsroot.peachy.translate.PageState
import ru.spgsroot.peachy.translate.TranslatedLine
import ru.spgsroot.peachy.translate.TranslationError

@OptIn(ExperimentalCoroutinesApi::class)
class BubbleTapSessionTest {
    @Test fun tappingArtDuringRecognitionNavigatesWithoutOpeningTranslationOrScanningTwice() = runTest {
        val result = CompletableDeferred<List<OcrBlock>>()
        var scans = 0
        var navigations = 0
        val session = BubbleTapSession(this, mutableMapOf(), { scans++; result.await() },
            { _, _ -> error("Art must not be sent to the provider") }, { error("No consent needed for art") })
        launch { session.prepare(0) }
        runCurrent()
        session.tap(0, Offset(.8f, .8f), emptyList(), { true }) { navigations++ }
        runCurrent()
        assertNull(session.panel)
        result.complete(listOf(OcrBlock("Hello", Box(.1f, .1f, .2f, .2f))))
        runCurrent()
        assertEquals(1, navigations)
        session.tap(0, Offset(.5f, .5f), emptyList(), { true }) { navigations++ }
        runCurrent()
        assertEquals(2, navigations)
        assertEquals(1, scans)
        assertNull(session.panel)
    }

    @Test fun unreadablePageStillRespondsToReaderTaps() = runTest {
        var navigations = 0
        val session = BubbleTapSession(this, mutableMapOf(), { throw IOException("Unreadable page") },
            { _, _ -> error("No text selected") }, { error("No upload requested") })
        session.tap(0, Offset(.5f, .5f), emptyList(), { true }) { navigations++ }
        runCurrent()
        assertEquals(1, navigations)
        assertEquals(PageState.Failed(TranslationError.Recognition), session.panel)
    }

    @Test fun disablingOverlayWhileWaitingShowsTheFinishedTranslationInThePanel() = runTest {
        val block = OcrBlock("Hi", Box(.2f, .2f, .8f, .8f))
        val answer = CompletableDeferred<TranslatedLine>()
        var overlay = true
        val session = BubbleTapSession(this, mutableMapOf(), { listOf(block) }, { _, _ -> answer.await() }, { false })
        session.tap(0, Offset(.5f, .5f), emptyList(), { overlay }) { error("Expected text") }
        runCurrent()
        overlay = false
        val translated = TranslatedLine(LineKind.Speech, block.text, "Привет", block.box)
        answer.complete(translated)
        runCurrent()
        assertEquals(listOf(translated), (session.panel as PageState.Done).translation.lines)
        assertNull(session.selection)
    }

    @Test fun lateFailureFromCanceledTapDoesNotReplaceNewBubbleText() = runTest {
        val first = OcrBlock("First", Box(0f, 0f, .4f, .4f))
        val second = TranslatedLine(LineKind.Speech, "Second", "Вторая реплика", Box(.6f, .6f, 1f, 1f))
        val finish = CompletableDeferred<Unit>()
        val session = BubbleTapSession(this, mutableMapOf(), { listOf(first) }, { _, _ ->
            withContext(NonCancellable) { finish.await(); throw IOException("Late transport failure") }
        }, { false })
        session.tap(0, Offset(.2f, .2f), emptyList(), { false }) { error("Expected first bubble") }
        runCurrent()
        session.tap(0, Offset(.8f, .8f), listOf(second), { false }) { error("Expected second bubble") }
        finish.complete(Unit)
        runCurrent()
        assertEquals(listOf(second), (session.panel as PageState.Done).translation.lines)
    }
}
