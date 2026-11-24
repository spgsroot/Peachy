package ru.spgsroot.peachy.translate

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.floor

/** Closed, near-white paper around text. A cell containing even one ink pixel is a barrier. */
internal class BubblePaper(bitmap: Bitmap) {
    private val step = (maxOf(bitmap.width, bitmap.height) + 959) / 960
    private val width = (bitmap.width + step - 1) / step
    private val height = (bitmap.height + step - 1) / step
    private val cells = IntArray(width * height)
    private var work: IntArray? = null
    private var nextLabel = 1

    init {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            val offset = y / step * width
            for (x in row.indices) {
                val rgb = row[x]
                if (rgb ushr 24 < 250 || (rgb ushr 16 and 255) < 238 || (rgb ushr 8 and 255) < 238 || (rgb and 255) < 238) {
                    cells[offset + x / step] = -1
                }
            }
        }
    }

    /** Four corners must see the same enclosed sheet, not gutter, page background or lettering. */
    fun owner(rect: PixelRect): Int {
        val l = rect.left / step - 1; val t = rect.top / step - 1
        val r = (rect.right - 1) / step + 1; val b = (rect.bottom - 1) / step + 1
        if (l < 0 || t < 0 || r >= width || b >= height || r <= l || b <= t) return 0
        val seed = t * width + l
        if (cells[seed] == 0) label(seed)
        val id = cells[seed]
        return if (id > 0 && cells[t * width + r] == id && cells[b * width + l] == id && cells[b * width + r] == id) id else 0
    }

    private fun label(seed: Int) {
        val queue = work ?: IntArray(cells.size).also { work = it }
        val id = nextLabel++
        var head = 0; var tail = 0; var touchesEdge = false
        fun visit(i: Int) { if (cells[i] == 0) { cells[i] = id; queue[tail++] = i } }
        visit(seed)
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            if (x == 0 || x == width - 1 || i < width || i >= cells.size - width) touchesEdge = true
            if (x > 0) visit(i - 1)
            if (x + 1 < width) visit(i + 1)
            if (i >= width) visit(i - width)
            if (i + width < cells.size) visit(i + width)
        }
        if (touchesEdge || tail > cells.size / 3) for (i in 0 until tail) cells[queue[i]] = -2
    }

    /** Inscribe a rectangle, not the outline's bounding box: every added band must be blank paper. */
    fun interior(rect: PixelRect, id: Int = owner(rect)): PixelRect? {
        if (id <= 0) return null
        val sl = rect.left / step; val st = rect.top / step
        val sr = (rect.right - 1) / step; val sb = (rect.bottom - 1) / step
        var l = sl - 1; var t = st - 1; var r = sr + 1; var b = sb + 1
        fun row(y: Int) = (l..r).all { cells[y * width + it] == id }
        fun column(x: Int) = (t..b).all { cells[it * width + x] == id }
        // OCR sometimes cuts through the last stroke of a glyph. Cross only ink running back
        // into the source, never a detached mark, an outline or another paper component.
        fun attachedInk(x: Int, y: Int): Boolean {
            var px = x; var py = y
            while (cells[py * width + px] == -1) {
                if (px in sl..sr && py in st..sb) return true
                px += when { px < sl -> 1; px > sr -> -1; else -> 0 }
                py += when { py < st -> 1; py > sb -> -1; else -> 0 }
            }
            return false
        }
        val fringe = (minOf(sr - sl + 1, sb - st + 1) / 5 + 1).coerceIn(1, 12)
        while (true) {
            if (l < 0 || t < 0 || r >= width || b >= height) return null
            if (row(t) && row(b) && column(l) && column(r)) break
            if (sl - l >= fringe) return null
            fun safe(x: Int, y: Int) = cells[y * width + x] == id || attachedInk(x, y)
            if ((l..r).any { !safe(it, t) || !safe(it, b) } || (t..b).any { !safe(l, it) || !safe(r, it) }) return null
            l--; r++; t--; b++
        }

        // At each height compare the common blank span of all rows. A tall, narrow balloon
        // does not spend its entire interior on a single wide line at the waist.
        var left = 0; var right = width - 1
        fun include(y: Int) {
            val offset = y * width
            var x = l
            while (x > left && cells[offset + x - 1] == id) x--
            left = maxOf(left, x)
            x = r
            while (x < right && cells[offset + x + 1] == id) x++
            right = minOf(right, x)
        }
        for (y in t..b) include(y)
        var best = rect
        var bestArea = 0
        while (true) {
            val area = (right - left - 1) * (b - t - 1)
            if (area > bestArea) {
                bestArea = area
                best = PixelRect((left + 1) * step, (t + 1) * step, right * step, b * step)
            }
            val up = t > 0 && row(t - 1)
            val down = b + 1 < height && row(b + 1)
            if (!up && !down) return best
            if (up) include(--t)
            if (down) include(++b)
        }
    }
}

internal fun fitModelBubbles(image: ByteArray, translation: PageTranslation): PageTranslation {
    if (translation.lines.none { it.box != null && it.kind.inBubble }) return translation
    val bitmap = PageDecode.decode(image, 960)
    try {
        val paper = BubblePaper(bitmap)
        return translation.copy(lines = translation.lines.map { line ->
            val box = line.box
            if (box == null || !line.kind.inBubble) return@map line
            val rect = PixelRect(floor(box.left * bitmap.width).toInt(), floor(box.top * bitmap.height).toInt(),
                ceil(box.right * bitmap.width).toInt(), ceil(box.bottom * bitmap.height).toInt())
            val interior = paper.interior(rect) ?: return@map line
            val area = interior.normalized(bitmap.width, bitmap.height)
            // A second reply in the same bubble still owns its source pixels.
            if (translation.lines.any { other -> other !== line && other.box?.let { blockShare(it, area) > 0f } == true }) line
            else line.copy(box = area)
        })
    } finally { bitmap.recycle() }
}

internal val LineKind.inBubble get() = this == LineKind.Speech || this == LineKind.Thought
internal fun PixelRect.normalized(width: Int, height: Int) =
    Box(left.toFloat() / width, top.toFloat() / height, right.toFloat() / width, bottom.toFloat() / height)
