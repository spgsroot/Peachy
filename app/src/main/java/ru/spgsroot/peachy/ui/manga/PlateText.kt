package ru.spgsroot.peachy.ui.manga

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
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

internal class PlacedPlate(
    val rect: Rect,
    val layout: StaticLayout,
    val textTop: Float,
    val line: TranslatedLine
)

private const val MIN_READABLE_SP = 13f
private const val MIN_SP = 6f

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
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setLineSpacing(0f, 1.1f)
        .setIncludePad(false)
        .setMaxLines(maxLines)
        .setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
        .build()
}

internal fun layoutPlates(lines: List<TranslatedLine>, page: Rect, density: Density): List<PlacedPlate> {
    if (page.width <= 0f || page.height <= 0f) return emptyList()
    val visible = ArrayList<TranslatedLine>(lines.size)
    val rects = ArrayList<Rect>(lines.size)
    for (line in lines) {
        val box = line.box ?: continue
        if (line.kind == LineKind.Sfx || line.translation.isBlank()) continue
        val original = Rect(
            page.left + box.left * page.width, page.top + box.top * page.height,
            page.left + box.right * page.width, page.top + box.bottom * page.height,
        )
        val rect = boundedPlateRect(original, page) ?: continue
        visible.add(line)
        rects.add(rect)
    }
    val horizontalPadding = with(density) { 4.dp.toPx() }
    val verticalPadding = with(density) { 2.dp.toPx() }
    val trimmed = resolveOverlaps(rects)
    val sizes = visible.mapIndexed { index, line ->
        val rect = trimmed[index]
        val longestWord = longestUnbreakableWord(line.translation)
        largestFittingSp(
            TextProbe { sp, width, height ->
                val layout = plateLayout(line.translation, line.kind, with(density) { sp.sp.toPx() }, width.toInt())
                layout.height <= height && (
                    layout.paint.measureText(longestWord) <= width ||
                        (sp == MIN_SP && (0 until layout.lineCount).all { layout.getLineWidth(it) <= width })
                    )
            },
            (rect.width - horizontalPadding).toInt().toFloat(),
            rect.height - verticalPadding,
            minSp = MIN_SP,
        )
    }
    val capped = capToMedian(sizes)
    return visible.mapIndexedNotNull { index, line ->
        val rect = trimmed[index]
        val width = (rect.width - horizontalPadding).toInt()
        val height = rect.height - verticalPadding
        if (width <= 0 || height <= 0f) return@mapIndexedNotNull null
        val layout = if (capped[index] > 0f) {
            plateLayout(line.translation, line.kind, with(density) { capped[index].sp.toPx() }, width)
        } else {
            // Keep a readable beginning and signal omitted text, never a clipped middle.
            val full = plateLayout(line.translation, line.kind, with(density) { MIN_READABLE_SP.sp.toPx() }, width)
            var maxLines = 0
            while (maxLines < full.lineCount && full.getLineBottom(maxLines) <= height) maxLines++
            if (maxLines == 0 || full.paint.measureText("\u2026") > width) return@mapIndexedNotNull null
            val shortened = plateLayout(line.translation, line.kind, full.paint.textSize, width, maxLines)
            if (shortened.height > height ||
                (shortened.lineCount == 1 && shortened.getEllipsisCount(0) > 0 && shortened.getEllipsisStart(0) == 0)
            ) return@mapIndexedNotNull null
            shortened
        }
        PlacedPlate(rect, layout, rect.top + (rect.height - layout.height) / 2f, line)
    }
}

/** Clip both the background and glyph overhangs to the original text bounds. */
internal fun drawPlate(canvas: Canvas, plate: PlacedPlate, density: Density) {
    val rect = plate.rect
    val save = canvas.save()
    try {
        canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
        val radius = with(density) { 6.dp.toPx() }
        canvas.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius, radius, plateBackground)
        canvas.translate(rect.left + with(density) { 2.dp.toPx() }, plate.textTop)
        plate.layout.draw(canvas)
    } finally { canvas.restoreToCount(save) }
}

private val plateBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(237,255,255,255) }
