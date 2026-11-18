package ru.spgsroot.peachy.documents.pdf

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Pure data and decisions for the PDF editor. Nothing in this file touches the MuPDF native
 * library, so coordinate maths, refusal rules, caps and the undo stack are unit-tested on the JVM.
 *
 * Coordinates are PDF page coordinates as MuPDF reports them: origin top-left, y grows down,
 * one unit = 1/72 inch, page rotation already applied to the reported bounds. The editor keeps
 * every rectangle in this space and converts to screen space in one place (the page canvas).
 */

data class PdfPoint(val x: Float, val y: Float)

data class PdfRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun translated(dx: Float, dy: Float) = PdfRect(left + dx, top + dy, right + dx, bottom + dy)

    fun union(other: PdfRect): PdfRect {
        if (isEmpty) return other
        if (other.isEmpty) return this
        return PdfRect(
            minOf(left, other.left),
            minOf(top, other.top),
            maxOf(right, other.right),
            maxOf(bottom, other.bottom),
        )
    }

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /** Shrinks every side by [fraction] of the corresponding dimension. */
    fun inset(fraction: Float): PdfRect {
        val dx = width * fraction
        val dy = height * fraction
        return PdfRect(left + dx, top + dy, right - dx, bottom - dy)
    }
}

data class PdfQuad(
    val upperLeft: PdfPoint,
    val upperRight: PdfPoint,
    val lowerLeft: PdfPoint,
    val lowerRight: PdfPoint,
) {
    val bounds: PdfRect
        get() = PdfRect(
            minOf(upperLeft.x, upperRight.x, lowerLeft.x, lowerRight.x),
            minOf(upperLeft.y, upperRight.y, lowerLeft.y, lowerRight.y),
            maxOf(upperLeft.x, upperRight.x, lowerLeft.x, lowerRight.x),
            maxOf(upperLeft.y, upperRight.y, lowerLeft.y, lowerRight.y),
        )

    companion object {
        fun of(rect: PdfRect) = PdfQuad(
            PdfPoint(rect.left, rect.top),
            PdfPoint(rect.right, rect.top),
            PdfPoint(rect.left, rect.bottom),
            PdfPoint(rect.right, rect.bottom),
        )
    }
}

/**
 * Redaction quads for removing one text run. MuPDF removes a glyph when its bounding box
 * (shrunk by 10%) intersects a redaction quad *at all*, and a zero-area intersection counts.
 * Glyph boxes of neighbours abut, so the redaction quads are shrunk well inside each glyph box;
 * otherwise the neighbouring character would be removed as well.
 */
fun redactionQuadsFor(chars: List<PdfQuad>, insetFraction: Float = 0.12f): List<PdfQuad> =
    chars.map { PdfQuad.of(it.bounds.inset(insetFraction)) }.filter { !it.bounds.isEmpty }

data class PdfChar(val codePoint: Int, val quad: PdfQuad)

data class PdfLine(
    val text: String,
    val chars: List<PdfChar>,
    val bounds: PdfRect,
    val baseline: PdfPoint,
    val fontSize: Float,
    val horizontal: Boolean,
)

data class PdfSelection(val text: String, val quads: List<PdfQuad>)

/**
 * Character-range text selection over the structured text of one page. The selection is derived
 * from the same quads that are used for highlights, so copy, highlight and text replacement all
 * address identical glyphs.
 */
object PdfTextSelection {

    /** Flattened index space: line 0 chars, then '\n', then line 1 chars, ... */
    private class Flattened(val lines: List<PdfLine>, val text: String, val charStarts: List<Int>)

    fun selection(lines: List<PdfLine>, anchor: PdfPoint, focus: PdfPoint): PdfSelection? {
        val flat = flatten(lines)
        if (flat.text.isEmpty()) return null
        val a = charIndexAt(lines, anchor) ?: return null
        val b = charIndexAt(lines, focus) ?: return null
        return range(flat, minOf(a, b), maxOf(a, b))
    }

    fun wordAt(lines: List<PdfLine>, point: PdfPoint): PdfSelection? {
        val flat = flatten(lines)
        if (flat.text.isEmpty()) return null
        val index = charIndexAt(lines, point) ?: return null
        var start = index
        var end = index
        fun isWordChar(i: Int): Boolean =
            i in flat.text.indices && !flat.text[i].isWhitespace()
        if (!isWordChar(index)) {
            // Tapping a space selects the word to its left, like a word processor.
            var i = index
            while (i >= 0 && flat.text[i].isWhitespace()) i--
            if (i < 0) return null
            start = i
            end = i
        }
        while (start > 0 && isWordChar(start - 1)) start--
        while (end + 1 < flat.text.length && isWordChar(end + 1)) end++
        return range(flat, start, end)
    }

    fun lineAt(lines: List<PdfLine>, point: PdfPoint, tolerance: Float = 2f): Int? {
        lines.forEachIndexed { index, line ->
            val b = line.bounds
            if (point.x >= b.left - tolerance && point.x <= b.right + tolerance &&
                point.y >= b.top - tolerance && point.y <= b.bottom + tolerance
            ) {
                return index
            }
        }
        // Nearest line vertically, so a tap slightly above or below the glyphs still lands.
        var best = -1
        var bestDistance = Float.MAX_VALUE
        lines.forEachIndexed { index, line ->
            val distance = when {
                point.y < line.bounds.top -> line.bounds.top - point.y
                point.y > line.bounds.bottom -> point.y - line.bounds.bottom
                else -> 0f
            } + kotlin.math.abs(point.x - line.bounds.centerX).coerceAtMost(200f) * 0.01f
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return if (best >= 0 && bestDistance <= 24f) best else null
    }

    private fun flatten(lines: List<PdfLine>): Flattened {
        val text = StringBuilder()
        val starts = ArrayList<Int>(lines.size)
        lines.forEachIndexed { index, line ->
            if (index > 0) text.append('\n')
            starts += text.length
            text.append(line.text)
        }
        return Flattened(lines, text.toString(), starts)
    }

    private fun charIndexAt(lines: List<PdfLine>, point: PdfPoint): Int? {
        val flat = flatten(lines)
        val lineIndex = lineAt(lines, point) ?: return null
        var base = flat.charStarts[lineIndex]
        val line = lines[lineIndex]
        if (line.chars.isEmpty()) return base
        // Nearest char horizontally inside the chosen line.
        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        line.chars.forEachIndexed { i, char ->
            val b = char.quad.bounds
            val distance = when {
                point.x < b.left -> b.left - point.x
                point.x > b.right -> point.x - b.right
                else -> -b.width * 0.5f
            }
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }
        base += bestIndex
        return base.coerceIn(0, flat.text.lastIndex.coerceAtLeast(0))
    }

    private fun range(flat: Flattened, from: Int, to: Int): PdfSelection {
        var offset = 0
        val quads = ArrayList<PdfQuad>()
        for (line in flat.lines) {
            val end = offset + line.chars.size
            for (i in line.chars.indices) {
                val flatIndex = offset + i
                if (flatIndex in from..to) quads += line.chars[i].quad
            }
            offset = end + 1
        }
        val text = flat.text.substring(from.coerceAtLeast(0), (to + 1).coerceAtMost(flat.text.length))
        return PdfSelection(text, quads)
    }
}

// ---------------------------------------------------------------- caps

/**
 * Hard limits for hostile or huge input. Exceeding one is a typed error, never a partial open.
 * The 100 MiB byte cap matches the existing photo import cap and the document plan (report §6.2).
 */
object PdfCaps {
    const val MAX_FILE_BYTES: Long = 100L * 1024 * 1024
    const val MAX_PAGES = 5_000
    const val MAX_OBJECTS = 2_000_000
    /** Upper bound on a rendered page bitmap; larger requests are downscaled. */
    const val MAX_RENDER_PIXELS = 8_000_000
    const val MAX_RENDER_SIDE = 4_096
    const val MAX_IMPORT_PAGE_BYTES: Long = 100L * 1024 * 1024
    const val MAX_FORM_FIELDS = 2_000
    const val MAX_SEARCH_HITS = 1_000

    fun renderScaleFor(scale: Float, pageWidthPt: Float, pageHeightPt: Float): Float {
        if (pageWidthPt <= 0f || pageHeightPt <= 0f || scale <= 0f) return 1f
        var result = scale
        val maxSide = maxOf(pageWidthPt, pageHeightPt) * result
        if (maxSide > MAX_RENDER_SIDE) result *= MAX_RENDER_SIDE / maxSide
        val pixels = pageWidthPt * result * pageHeightPt * result
        if (pixels > MAX_RENDER_PIXELS) result *= kotlin.math.sqrt(MAX_RENDER_PIXELS / pixels)
        return result.coerceAtLeast(0.01f)
    }

    fun checkFileLength(bytes: Long): PdfError? = if (bytes > MAX_FILE_BYTES) {
        PdfError(PdfErrorCode.TOO_LARGE, bytes.toString())
    } else {
        null
    }

    fun checkPageCount(pages: Int): PdfError? = if (pages > MAX_PAGES) {
        PdfError(PdfErrorCode.PAGE_LIMIT, pages.toString())
    } else {
        null
    }
}

// ---------------------------------------------------------------- typed errors

enum class PdfErrorCode {
    TOO_LARGE,
    PAGE_LIMIT,
    OBJECT_LIMIT,
    NOT_A_PDF,
    CORRUPT,
    PASSWORD_REQUIRED,
    WRONG_PASSWORD,
    READ_ONLY,
    RENDER_FAILED,
    NO_TEXT_LAYER,
    EDIT_FAILED,
    EDIT_UNSUPPORTED,
    SAVE_FAILED,
    EXPORT_FAILED,
    IMPORT_FAILED,
    IMPORT_NOT_PDF,
    FORM_FIELD_FAILED,
    SIGN_FAILED,
    SIGNATURE_MISSING_CERT,
    SIGNATURE_WRONG_PASSWORD,
    SIGNATURE_UNSUPPORTED,
    NO_ANNOTATION,
}

data class PdfError(val code: PdfErrorCode, val detail: String? = null)

// ---------------------------------------------------------------- in-place text replacement

enum class PdfEditRefusal {
    ROTATED_PAGE,
    OFFSET_PAGE,
    VERTICAL_TEXT,
    EMPTY_TEXT,
    UNCHANGED_TEXT,
    MULTILINE_TEXT,
    TEXT_TOO_LONG,
    MISSING_GLYPHS,
    NO_LINE,
}

/** How the page display transform relates to the unrotated content stream space. */
enum class PageTransform {
    /** Identity rotation and zero origin: structured-text coordinates equal content coordinates. */
    ALIGNED,
    /** The page is rotated; structured-text and content-stream coordinates differ. */
    ROTATED,
    /** Crop/media box origin is offset, so the two coordinate spaces differ by a translation. */
    OFFSET,
}

data class TextEditTarget(
    val pageIndex: Int,
    val lineIndex: Int,
    val text: String,
    val bounds: PdfRect,
    val baseline: PdfPoint,
    val fontSize: Float,
    val horizontal: Boolean,
    val pageTransform: PageTransform,
)

data class TextEditPlan(
    val pageIndex: Int,
    val lineIndex: Int,
    val text: String,
    val glyphHex: String,
    val sizePt: Float,
    val startX: Float,
    val startY: Float,
    val measuredWidth: Float,
    val originalWidth: Float,
) {
    val overflowPt: Float get() = (measuredWidth - originalWidth).coerceAtLeast(0f)
    val overflows: Boolean get() = overflowPt > 0.5f
}

sealed interface TextEditDecision {
    data class Apply(val plan: TextEditPlan) : TextEditDecision
    data class Refuse(val reason: PdfEditRefusal) : TextEditDecision
}

/**
 * Decides whether a selected line can be replaced in place.
 *
 * The replacement is glyph-level: the original glyphs are removed with a MuPDF redaction
 * (text only; images and line art are left alone) and the new text is written into the same
 * baseline with an embedded font. There is no reflow, so the surrounding layout does not move.
 * Cases that cannot be represented safely are refused and the page is left untouched.
 */
object TextEditPlanner {
    const val MAX_TEXT_LENGTH = 512

    fun plan(
        target: TextEditTarget?,
        newText: String,
        encodeGlyphs: (String) -> String?,
        measureWidth: (String, Float) -> Float,
    ): TextEditDecision {
        if (target == null) return TextEditDecision.Refuse(PdfEditRefusal.NO_LINE)
        when (target.pageTransform) {
            PageTransform.ALIGNED -> Unit
            PageTransform.ROTATED -> return TextEditDecision.Refuse(PdfEditRefusal.ROTATED_PAGE)
            PageTransform.OFFSET -> return TextEditDecision.Refuse(PdfEditRefusal.OFFSET_PAGE)
        }
        if (!target.horizontal) return TextEditDecision.Refuse(PdfEditRefusal.VERTICAL_TEXT)
        val text = newText
        if (text.isEmpty()) return TextEditDecision.Refuse(PdfEditRefusal.EMPTY_TEXT)
        if (text == target.text) return TextEditDecision.Refuse(PdfEditRefusal.UNCHANGED_TEXT)
        if (text.any { it == '\n' || it == '\r' }) {
            return TextEditDecision.Refuse(PdfEditRefusal.MULTILINE_TEXT)
        }
        if (text.length > MAX_TEXT_LENGTH) return TextEditDecision.Refuse(PdfEditRefusal.TEXT_TOO_LONG)
        val size = target.fontSize.takeIf { it > 0f } ?: 10f
        val hex = encodeGlyphs(text) ?: return TextEditDecision.Refuse(PdfEditRefusal.MISSING_GLYPHS)
        val width = measureWidth(text, size)
        return TextEditDecision.Apply(
            TextEditPlan(
                pageIndex = target.pageIndex,
                lineIndex = target.lineIndex,
                text = text,
                glyphHex = hex,
                sizePt = size,
                startX = target.baseline.x,
                startY = target.baseline.y,
                measuredWidth = width,
                originalWidth = target.bounds.width,
            ),
        )
    }
}

// ---------------------------------------------------------------- undo

/**
 * Undo for committed edits is file-snapshot based: before each commit the current working file
 * is copied into the session's undo directory, so a destructive operation (text redaction is
 * destructive by nature) can be reverted exactly. Snapshots are capped by count and total bytes,
 * and the oldest ones are evicted first. The granularity is one snapshot per committed operation.
 */
class PdfUndoStack(
    private val dir: File,
    private val maxEntries: Int = 8,
    private val maxTotalBytes: Long = 150L * 1024 * 1024,
) {
    data class Entry(val file: File, val size: Long)

    fun snapshots(): List<Entry> = dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".pdf") }
        ?.sortedBy { it.name }
        ?.map { Entry(it, it.length()) }
        ?: emptyList()

    fun depth(): Int = snapshots().size

    /** Copies [source] into the stack, evicting the oldest snapshots to stay within the caps. */
    @Throws(IOException::class)
    fun push(source: File) {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create undo directory")
        val sequence = (snapshots().lastOrNull()?.file?.nameWithoutExtension?.toLongOrNull() ?: 0L) + 1
        val target = File(dir, "%06d.pdf".format(sequence))
        copyAtomic(source, target)
        var entries = snapshots()
        while (entries.size > maxEntries ||
            entries.sumOf { it.size } > maxTotalBytes
        ) {
            val oldest = entries.firstOrNull() ?: break
            if (entries.size == 1) break
            oldest.file.delete()
            entries = snapshots()
        }
    }

    /** Restores the newest snapshot into [target] and removes it. False when empty. */
    @Throws(IOException::class)
    fun popInto(target: File): Boolean {
        val newest = snapshots().lastOrNull() ?: return false
        copyAtomic(newest.file, target)
        newest.file.delete()
        return true
    }

    /** Drops a snapshot whose commit never replaced the working file. */
    fun discardLatest() {
        snapshots().lastOrNull()?.file?.delete()
    }

    fun clear() {
        snapshots().forEach { it.file.delete() }
    }
}

/**
 * Page order where the entry at [from] ends up at [to], for both directions. Moving a page up one
 * slot used to pass a negative length to `System.arraycopy` and threw.
 */
internal fun movedPageOrder(count: Int, from: Int, to: Int): IntArray {
    if (from !in 0 until count || to !in 0 until count) return IntArray(count) { it }
    val order = (0 until count).toMutableList()
    order.add(to, order.removeAt(from))
    return order.toIntArray()
}

/**
 * Writes [source] to [target] via a sibling temp file and an atomic replace. The destination is
 * never deleted beforehand: if the move is rejected, the previously committed file remains intact.
 */
@Throws(IOException::class)
fun copyAtomic(source: File, target: File) {
    val parent = target.parentFile ?: throw IOException("no parent directory")
    if (!parent.exists() && !parent.mkdirs()) throw IOException("cannot create directory")
    val temp = File(parent, target.name + ".tmp")
    temp.delete()
    try {
        source.inputStream().use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
                output.fd.sync()
            }
        }
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        temp.delete()
    }
}

/** True when the first 1 KiB contains the %PDF- header (some producers prepend junk). */
fun hasPdfSignature(file: File): Boolean = try {
    RandomAccessFile(file, "r").use { raf ->
        val buffer = ByteArray(minOf(1024L, file.length()).toInt())
        if (buffer.size < 5 || raf.read(buffer) < 5) return false
        val header = "%PDF-".toByteArray(Charsets.US_ASCII)
        var found = false
        var start = 0
        while (!found && start <= buffer.size - header.size) {
            found = true
            for (i in header.indices) {
                if (buffer[start + i] != header[i]) {
                    found = false
                    break
                }
            }
            start++
        }
        found
    }
} catch (_: IOException) {
    false
}

/**
 * MuPDF's JNI array getters (page annotations, page widgets, search quads, structured-text
 * blocks/lines/chars) return `null` — not an empty array — when there is nothing to report.
 * Reading such a result as an array throws NullPointerException, so every call site funnels
 * through this helper instead of indexing the platform value directly.
 */
fun <T> Array<T>?.orEmptyList(): List<T> = this?.toList() ?: emptyList()
