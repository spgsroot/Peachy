package ru.spgsroot.peachy.ui.manga

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.spgsroot.peachy.translate.LineKind
import ru.spgsroot.peachy.translate.TranslatedLine
import ru.spgsroot.peachy.translate.distinctSourceRegions
import kotlin.math.ceil
import kotlin.math.floor

internal class PlacedPlate(
    val rect: Rect,
    val layout: StaticLayout,
    val textTop: Float,
    val line: TranslatedLine
) {
    val visibleEnd: Int get() {
        val last = layout.lineCount - 1
        return if (layout.getEllipsisCount(last) == 0) layout.getLineEnd(last)
        else layout.getLineStart(last) + layout.getEllipsisStart(last)
    }
}

private const val MIN_SINGLE_LINE_SP = 8f
private const val MIN_READABLE_SP = 12f
private const val MAX_SP = 22f

internal fun plateLayout(text: String, kind: LineKind, sizePx: Float, widthPx: Int, maxLines: Int = Int.MAX_VALUE): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = sizePx
        typeface = Typeface.create(Typeface.DEFAULT, when (kind) {
            LineKind.Sign -> Typeface.BOLD
            LineKind.Thought -> Typeface.ITALIC
            else -> Typeface.NORMAL
        })
    }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setIncludePad(false)
        .setMaxLines(maxLines)
        .setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
        .build()
}

private fun fitText(line: TranslatedLine, rect: Rect, width: Int, singlePx: Int, readablePx: Int, maxPx: Int): PlacedPlate? {
    val text = line.translation
    // A fractional font size can rasterize a row beyond the tight bounds reported by TextPaint.
    fun measure(px: Int, lines: Int = Int.MAX_VALUE, wrapWidth: Int = width) =
        plateLayout(text, line.kind, px.toFloat(), wrapWidth, lines)

    val ink = android.graphics.Rect()
    val firstRow = ceil(rect.top).toInt()
    val rows = floor(rect.bottom).toInt() - firstRow
    fun place(layout: StaticLayout): PlacedPlate? {
        val last = layout.lineCount - 1
        if (last == 0 && layout.getEllipsisCount(0) != 0 && layout.getEllipsisStart(0) == 0) return null
        val fitsLineBox = layout.height + 2 <= rect.height
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (i in 0..last) {
            if (layout.getLineMax(i) > width) return null
            if (fitsLineBox) continue
            // A low caption may fit all its ink but not the font's unused ascent and descent.
            val start = layout.getLineStart(i)
            val clipped = layout.getEllipsisCount(i) != 0
            var end = if (clipped) start + layout.getEllipsisStart(i) else layout.getLineEnd(i)
            while (end > start && text[end - 1].isWhitespace()) end--
            val baseline = layout.getLineBaseline(i)
            layout.paint.getTextBounds(text, start, end, ink)
            if (!ink.isEmpty) {
                top = minOf(top, baseline + ink.top)
                bottom = maxOf(bottom, baseline + ink.bottom)
            }
            if (clipped) {
                layout.paint.getTextBounds("…", 0, 1, ink)
                top = minOf(top, baseline + ink.top)
                bottom = maxOf(bottom, baseline + ink.bottom)
            }
            if (top <= bottom && bottom - top > rows) return null
        }
        val textTop = if (fitsLineBox) rect.center.y - layout.height * 0.5f else {
            if (top > bottom) return null
            (firstRow - top + (rows - (bottom - top)) / 2).toFloat()
        }
        return PlacedPlate(rect, layout, textTop, line)
    }

    place(measure(maxPx))?.let { return it }
    var low = readablePx + 1
    var high = maxPx - 1
    val minimum = measure(readablePx)
    var best = place(minimum)
    if (best == null) {
        val compact = measure(singlePx, 2)
        if (compact.lineCount == 1 && compact.getEllipsisCount(0) == 0) best = place(compact)
        if (best != null) {
            high = readablePx - 1
            low = singlePx + 1
        } else {
            // The ellipsized last line can be shorter than the intermediate line it replaces.
            val last = minimum.getLineForVertical((rect.height + minimum.getLineBaseline(0)).toInt())
            for (count in minOf(last + 1, minimum.lineCount - 1) downTo 1) {
                place(measure(readablePx, count))?.let { return it }
            }
            // A shorter prefix can exclude a tall glyph that prevents the width-based preview from fitting.
            minimum.paint.getTextBounds("…", 0, 1, ink)
            if (ink.height() > rows) return null
            var narrow = ceil(minimum.paint.measureText("…")).toInt().coerceAtLeast(1)
            var wide = width
            while (narrow <= wide) {
                val w = (narrow + wide) ushr 1
                val cut = measure(readablePx, 1, w)
                if (cut.getEllipsisCount(0) != 0 && cut.getEllipsisStart(0) == 0) {
                    narrow = w + 1
                } else {
                    val preview = place(cut)
                    if (preview == null) wide = w - 1 else { best = preview; narrow = w + 1 }
                }
            }
            return best
        }
    }
    while (low <= high) {
        val mid = (low + high) ushr 1
        val layout = measure(mid)
        val p = if (mid >= readablePx || layout.lineCount == 1) place(layout) else null
        if (p == null) high = mid - 1 else { low = mid + 1; best = p }
    }
    return best
}

internal fun layoutPlates(lines: List<TranslatedLine>, page: Rect, density: Density): List<PlacedPlate> {
    if (page.width <= 0f || page.height <= 0f) return emptyList()
    val visible = ArrayList<TranslatedLine>(lines.size)
    val rects = ArrayList<Rect>(lines.size)
    for (line in lines.distinctSourceRegions()) {
        val box = line.box ?: continue
        val original = Rect(
            page.left + box.left * page.width, page.top + box.top * page.height,
            page.left + box.right * page.width, page.top + box.bottom * page.height,
        )
        val rect = boundedPlateRect(original, page) ?: continue
        visible.add(line)
        rects.add(rect)
    }
    val horizontalPadding = with(density) { 2.dp.toPx() }
    val singlePx = ceil(with(density) { MIN_SINGLE_LINE_SP.sp.toPx() }).toInt()
    val readablePx = ceil(with(density) { MIN_READABLE_SP.sp.toPx() }).toInt()
    val maxPx = floor(with(density) { MAX_SP.sp.toPx() }).toInt()
    return visible.mapIndexedNotNull { index, line ->
        if (line.kind == LineKind.Sfx || line.translation.isBlank()) return@mapIndexedNotNull null
        var best: PlacedPlate? = null
        forEachFreeRect(rects, index) { rect ->
            val width = (rect.width - horizontalPadding).toInt()
            if (width <= 0) return@forEachFreeRect
            val next = fitText(line, rect, width, singlePx, readablePx, maxPx) ?: return@forEachFreeRect
            val prior = best
            if (prior == null || next.visibleEnd > prior.visibleEnd ||
                (next.visibleEnd == prior.visibleEnd && next.layout.paint.textSize > prior.layout.paint.textSize)) best = next
        }
        best
    }
}

/** Clip both the opaque plate and any glyph overhang to the same verified region. */
internal fun drawPlate(canvas: Canvas, plate: PlacedPlate) {
    val rect = plate.rect
    val save = canvas.save()
    try {
        canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
        canvas.drawColor(Color.WHITE)
        canvas.translate(rect.center.x - plate.layout.width * 0.5f, plate.textTop)
        plate.layout.draw(canvas)
    } finally { canvas.restoreToCount(save) }
}
