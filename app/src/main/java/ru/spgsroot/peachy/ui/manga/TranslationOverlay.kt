package ru.spgsroot.peachy.ui.manga

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.translate.TranslatedLine
import kotlin.math.max

/**
 * Draws the translated lines over the page, each on a pale plate at the place of the original text.
 * The overlay has the size of the image view; the page itself may be letterboxed inside it (the
 * image is fitted, not stretched), so boxes, which are fractions of the page, are mapped onto the
 * fitted picture, not onto the view. A selected line without a plate is returned to the full-text panel.
 */
@Composable
fun TranslationOverlay(
    lines: List<TranslatedLine>,
    pageAspect: Float,
    modifier: Modifier = Modifier,
    layoutZoom: Float = 1f,
    selectedLine: TranslatedLine? = null,
    onSelectionOmitted: (TranslatedLine) -> Unit = {}
) {
    // Removing the composition also cancels a pending layout when the overlay is disabled.
    if (lines.isEmpty()) return
    val latestOnSelectionOmitted by rememberUpdatedState(onSelectionOmitted)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val page = fittedPage(constraints.maxWidth * layoutZoom, constraints.maxHeight * layoutZoom, pageAspect)
        val plates by rememberPlateLayout(lines, page, density) {
            withContext(Dispatchers.Default) {
                Log.d("PageTrace", "layoutPlates ${lines.size} lines")
                layoutPlates(lines, page, density)
            }
        }
        LaunchedEffect(plates, selectedLine) {
            val completed = plates ?: return@LaunchedEffect
            if (selectedLine != null && completed.none { it.line == selectedLine }) {
                latestOnSelectionOmitted(selectedLine)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            scale(1f / layoutZoom, pivot = Offset.Zero) {
                drawIntoCanvas { canvas -> plates?.forEach { drawPlate(canvas.nativeCanvas, it) } }
            }
        }
    }
}

/** A new input owns fresh state: produceState's initialValue alone does not reset on key changes. */
@Composable
internal fun rememberPlateLayout(
    lines: List<TranslatedLine>,
    page: Rect,
    density: Density,
    layout: suspend () -> List<PlacedPlate>
): State<List<PlacedPlate>?> = key(lines, page, density) {
    produceState<List<PlacedPlate>?>(null) { value = layout() }
}

/** Where the page sits inside a view of [width] x [height] when it is fitted, in view pixels. */
internal fun fittedPage(width: Float, height: Float, aspect: Float): Rect {
    if (width <= 0f || height <= 0f || aspect <= 0f) return Rect(0f, 0f, max(width, 0f), max(height, 0f))
    return if (width / height > aspect) {
        val fitted = height * aspect
        Rect((width - fitted) / 2f, 0f, (width + fitted) / 2f, height)
    } else {
        val fitted = width / aspect
        Rect(0f, (height - fitted) / 2f, width, (height + fitted) / 2f)
    }
}
