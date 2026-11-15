package ru.spgsroot.peachy.ui.manga

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.spgsroot.peachy.translate.LineKind
import ru.spgsroot.peachy.translate.TranslatedLine

internal class PlacedPlate(val rect: Rect, val layout: StaticLayout, val textTop: Float)

private const val MIN_READABLE_SP = 13f
private const val MIN_SP = 6f
private val GROWTH = arrayOf(1f to 1f, 1.4f to 1.15f, 1.9f to 1.3f, 2.6f to 1.5f, 3.2f to 1.8f)

internal fun plateLayout(text: String, kind: LineKind, sizePx: Float, widthPx: Int): StaticLayout {
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
        .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setLineSpacing(0f, 1.1f)
        .setIncludePad(false)
        .build()
}

internal fun layoutPlates(lines: List<TranslatedLine>, page: Rect, density: Density): List<PlacedPlate> {
    if (page.width <= 0f || page.height <= 0f) return emptyList()
    val visible = lines.filter { it.box != null && it.kind != LineKind.Sfx }
    val horizontalPadding = with(density) { 4.dp.toPx() }
    val verticalPadding = with(density) { 2.dp.toPx() }
    val rects = ArrayList<Rect>(visible.size)
    val sizes = ArrayList<Float>(visible.size)
    val probes = visible.map { line ->
        val longestWord = longestUnbreakableWord(line.translation)
        TextProbe { sp, width, height ->
            val layout = plateLayout(line.translation, line.kind, with(density) { sp.sp.toPx() }, width.toInt())
            layout.height <= height && layout.paint.measureText(longestWord) <= width
        }
    }
    visible.forEachIndexed { index, line ->
        val box = line.box!!
        val original = Rect(page.left + box.left * page.width, page.top + box.top * page.height,
            page.left + box.right * page.width, page.top + box.bottom * page.height)
        var bestRect = original
        var bestSize = 0f
        for ((widthFactor, heightFactor) in GROWTH) {
            val width = minOf(original.width * widthFactor, page.width)
            val height = minOf(original.height * heightFactor, page.height)
            val left = (original.center.x - width / 2f).coerceIn(page.left, page.right - width)
            val top = (original.center.y - height / 2f).coerceIn(page.top, page.bottom - height)
            val candidate = Rect(left, top, left + width, top + height)
            val size = largestFittingSp(probes[index], (width - horizontalPadding).coerceAtLeast(1f), (height - verticalPadding).coerceAtLeast(1f))
            if (size > bestSize || bestSize == 0f) { bestRect = candidate; bestSize = size }
            if (size >= MIN_READABLE_SP) break
        }
        rects.add(bestRect)
        sizes.add(bestSize.coerceAtLeast(MIN_SP))
    }
    val capped = capToMedian(sizes)
    val trimmed = resolveOverlaps(rects)
    return visible.mapIndexed { index, line ->
        val rect = trimmed[index]
        val width = (rect.width - horizontalPadding).toInt().coerceAtLeast(1)
        val height = (rect.height - verticalPadding).coerceAtLeast(1f)
        val sp = largestFittingSp(probes[index], width.toFloat(), height, maxSp = capped[index]).coerceAtLeast(MIN_SP)
        val layout = plateLayout(line.translation, line.kind, with(density) { sp.sp.toPx() }, width)
        PlacedPlate(rect, layout, rect.top + (rect.height - layout.height) / 2f)
    }
}

/** Shared with the overflow pixel test: even an impossible 6 sp fit cannot draw over the art. */
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
