package ru.spgsroot.peachy.ui.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.spgsroot.peachy.translate.Box
import ru.spgsroot.peachy.translate.LineKind
import ru.spgsroot.peachy.translate.OcrBlock
import ru.spgsroot.peachy.translate.PageState
import ru.spgsroot.peachy.translate.PageTranslation
import ru.spgsroot.peachy.translate.TranslatedLine
import ru.spgsroot.peachy.translate.TranslationError
import ru.spgsroot.peachy.translate.TranslationException

/** The scope ends when the visible page or translation choices change. Only finished lines survive a page turn. */
internal class BubbleTapSession(
    private val scope: CoroutineScope,
    private val lines: MutableMap<Int, List<TranslatedLine>>,
    private val recognize: suspend (Int) -> List<OcrBlock>,
    private val translate: suspend (Int, OcrBlock) -> TranslatedLine,
    private val needsConsent: () -> Boolean
) {
    var panel by mutableStateOf<PageState?>(null)
        private set
    var afterConsent by mutableStateOf<(() -> Unit)?>(null)
        private set
    var selection by mutableStateOf<Pair<Int, TranslatedLine>?>(null)
        private set
    private var job: Job? = null
    private var selected: Pair<Int, OcrBlock>? = null
    private val scans = mutableMapOf<Int, List<OcrBlock>>()
    private val scanLock = Mutex()

    private suspend fun blocks(page: Int): List<OcrBlock> = scanLock.withLock {
        scans[page] ?: recognize(page).also { scans[page] = it }
    }

    /** A background scan never opens the panel. A later tap can retry a failed scan. */
    suspend fun prepare(page: Int) {
        try {
            blocks(page)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Only report recognition failures when a reader actually asks for a translation.
        }
    }

    fun dismiss() {
        job?.cancel()
        selected = null
        afterConsent = null
        selection = null
        panel = null
    }

    fun tap(page: Int, at: Offset, pageLines: List<TranslatedLine>, showOnPage: () -> Boolean, miss: () -> Unit) {
        selected?.let { (previousPage, block) ->
            if (page == previousPage && block.box.contains(at) && job?.isActive == true) return
        }
        dismiss()
        val cached = hit(pageLines, at) { it.box } ?: hit(lines[page].orEmpty(), at) { it.box }
        if (cached != null) {
            show(cached)
            return
        }
        run {
            val recognized = try {
                blocks(page)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                miss()
                panel = PageState.Failed(TranslationError.Recognition)
                return@run
            }
            currentCoroutineContext().ensureActive()
            val block = hit(recognized, at) { it.box }
            if (block == null) {
                miss()
            } else {
                selected = page to block
                if (needsConsent()) {
                    val approval = CompletableDeferred<Unit>()
                    afterConsent = { approval.complete(Unit) }
                    approval.await()
                    afterConsent = null
                }
                panel = PageState.Loading
                val line = translate(page, block)
                currentCoroutineContext().ensureActive()
                lines[page] = lines[page].orEmpty() + line
                if (!showOnPage() || line.kind == LineKind.Sfx) {
                    show(line)
                } else {
                    selection = page to line
                    panel = null
                }
            }
        }
    }

    fun showOmitted(page: Int, line: TranslatedLine) {
        val current = selection ?: return
        if (current.first != page || current.second != line) return
        selection = null
        show(line)
    }

    private fun show(line: TranslatedLine) {
        panel = PageState.Done(PageTranslation("", listOf(line)))
    }

    private fun run(action: suspend () -> Unit) {
        job = scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                panel = PageState.Failed(when (e) {
                    is TranslationException -> e.error
                    is IOException -> TranslationError.Network
                    else -> TranslationError.BadResponse
                })
            }
        }
    }
}

private fun Box.contains(at: Offset) = at.x in left..right && at.y in top..bottom

/** Nested OCR boxes occur on dense pages. Prefer the most specific hit, never the closest miss. */
private inline fun <T> hit(items: List<T>, at: Offset, boxOf: (T) -> Box?): T? {
    var best: T? = null
    var smallest = Float.POSITIVE_INFINITY
    for (item in items) {
        val box = boxOf(item) ?: continue
        if (!box.contains(at)) continue
        val area = (box.right - box.left) * (box.bottom - box.top)
        if (area < smallest) {
            best = item
            smallest = area
        }
    }
    return best
}
