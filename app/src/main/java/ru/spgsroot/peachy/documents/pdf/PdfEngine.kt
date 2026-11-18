package ru.spgsroot.peachy.documents.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFWidget
import com.artifex.mupdf.fitz.PKCS7Signer
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.RectI
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File
import java.util.Locale

/** Raised with a typed [PdfError]; the UI maps the code to RU/EN copy. */
class PdfException(val error: PdfError, cause: Throwable? = null) : Exception(error.code.name, cause)

data class PdfRenderedPage(val bitmap: Bitmap, val scale: Float)

data class PdfSearchHit(val pageIndex: Int, val quads: List<PdfQuad>)

enum class PdfAnnotationKind { TEXT_NOTE, FREE_TEXT, HIGHLIGHT, UNDERLINE, STRIKEOUT, INK, OTHER }

data class PdfAnnotationRef(
    val index: Int,
    val kind: PdfAnnotationKind,
    val rect: PdfRect,
    val contents: String?,
    val author: String?,
    val own: Boolean,
)

enum class PdfFormKind { TEXT, CHECKBOX, RADIO, CHOICE, BUTTON, SIGNATURE, UNKNOWN }

data class PdfFormField(
    val pageIndex: Int,
    val widgetIndex: Int,
    val name: String,
    val label: String,
    val kind: PdfFormKind,
    val value: String,
    val options: List<String>,
    val rect: PdfRect,
    val readOnly: Boolean,
    val multiline: Boolean,
    val maxLength: Int,
    val signed: Boolean,
)

data class PdfSignatureInfo(
    val pageIndex: Int,
    val widgetIndex: Int,
    val signer: String,
    val reason: String,
    val date: String,
    val status: PdfSignatureStatus,
)

enum class PdfSignatureStatus { VALID, DIGEST_INVALID, NOT_TRUSTED, UNSIGNED, UNKNOWN }

/**
 * MuPDF 1.28.5 wrapper. Every call runs on the caller's single-thread dispatcher: the native
 * document handle is not thread-safe.
 *
 * The engine owns one cached page and its structured text, because selection, search and the text
 * editor all address page geometry; the cache is dropped after every mutating operation.
 */
class PdfEngine private constructor(
    private val pdf: PDFDocument,
    val file: File,
    val encrypted: Boolean,
    private var pages: Int,
    private val editFontFile: File,
) : Closeable {

    companion object {
        /** Author recorded on annotations this editor creates; used to decide what "own" means. */
        const val AUTHOR = "Peachy"

        private const val TAG = "PdfEngine"

        fun open(file: File, password: String? = null, editFont: File): PdfEngine {
            PdfCaps.checkFileLength(file.length())?.let { throw PdfException(it) }
            if (!file.isFile) throw PdfException(PdfError(PdfErrorCode.CORRUPT))
            if (!hasPdfSignature(file)) throw PdfException(PdfError(PdfErrorCode.NOT_A_PDF))

            val document = try {
                Document.openDocument(file.absolutePath)
            } catch (e: RuntimeException) {
                Log.e(TAG, "MuPDF could not open the document", e)
                throw PdfException(PdfError(PdfErrorCode.CORRUPT), e)
            }
            if (!document.isPDF) {
                document.destroy()
                throw PdfException(PdfError(PdfErrorCode.NOT_A_PDF))
            }
            val pdf = document.asPDF() ?: run {
                document.destroy()
                throw PdfException(PdfError(PdfErrorCode.NOT_A_PDF))
            }
            val encrypted = pdf.needsPassword()
            if (encrypted) {
                if (password == null) {
                    pdf.destroy()
                    throw PdfException(PdfError(PdfErrorCode.PASSWORD_REQUIRED))
                }
                val accepted = try {
                    pdf.authenticatePassword(password)
                } catch (e: RuntimeException) {
                    false
                }
                if (!accepted) {
                    pdf.destroy()
                    throw PdfException(PdfError(PdfErrorCode.WRONG_PASSWORD))
                }
            }
            val pageCount = try {
                pdf.countPages()
            } catch (e: RuntimeException) {
                Log.e(TAG, "MuPDF could not count pages", e)
                pdf.destroy()
                throw PdfException(PdfError(PdfErrorCode.CORRUPT), e)
            }
            PdfCaps.checkPageCount(pageCount)?.let {
                pdf.destroy()
                throw PdfException(it)
            }
            if (pdf.countObjects() > PdfCaps.MAX_OBJECTS) {
                pdf.destroy()
                throw PdfException(PdfError(PdfErrorCode.OBJECT_LIMIT, pdf.countObjects().toString()))
            }
            return PdfEngine(pdf, file, encrypted, pageCount, editFont)
        }
    }

    val pageCount: Int get() = pages
    val hasAcroForm: Boolean get() = try { pdf.hasAcroForm() } catch (e: RuntimeException) { false }
    val isRedacted: Boolean get() = try { pdf.isRedacted() } catch (e: RuntimeException) { false }

    val canEdit: Boolean get() = permission(Document.PERMISSION_EDIT)
    val canAnnotate: Boolean get() = permission(Document.PERMISSION_ANNOTATE)
    val canForm: Boolean get() = permission(Document.PERMISSION_FORM)
    val canAssemble: Boolean get() = permission(Document.PERMISSION_ASSEMBLE)

    private fun permission(flag: Int): Boolean = try {
        pdf.hasPermission(flag)
    } catch (e: RuntimeException) {
        false
    }

    // ------------------------------------------------------------ page cache

    private var cachedIndex = -1
    private var cachedPage: PDFPage? = null
    private var cachedText: StructuredText? = null
    private var cachedLines: List<PdfLine> = emptyList()
    private var editFont: Font? = null
    private var editFontRef: PDFObject? = null
    private var pendingSigner: PKCS7Signer? = null

    private fun dropCache() {
        cachedText?.destroy()
        cachedText = null
        cachedPage?.destroy()
        cachedPage = null
        cachedLines = emptyList()
        cachedIndex = -1
    }

    private fun page(index: Int): PDFPage {
        val existing = cachedPage
        if (existing != null && cachedIndex == index) return existing
        dropCache()
        if (index !in 0 until pages) throw PdfException(PdfError(PdfErrorCode.CORRUPT))
        cachedIndex = index
        val loaded = try {
            pdf.loadPage(index) as PDFPage
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.RENDER_FAILED))
        }
        cachedPage = loaded
        return loaded
    }

    private fun structuredText(index: Int): StructuredText? {
        if (cachedIndex == index && cachedText != null) return cachedText
        val page = page(index)
        val text = try {
            page.toStructuredText("preserve-whitespace")
        } catch (e: RuntimeException) {
            null
        }
        cachedText?.destroy()
        cachedText = text
        cachedLines = text?.let { readLines(it) } ?: emptyList()
        return text
    }

    fun pageBounds(index: Int): PdfRect = toPdfRect(page(index).getBounds())

    fun renderPage(index: Int, requestedScale: Float): PdfRenderedPage {
        val page = page(index)
        val bounds = page.getBounds()
        val scale = PdfCaps.renderScaleFor(
            requestedScale,
            bounds.x1 - bounds.x0,
            bounds.y1 - bounds.y0,
        )
        val matrix = Matrix(scale, scale)
        return try {
            // AndroidDrawDevice composites the page into an existing bitmap, so the sheet is
            // filled white first: a PDF page has no background of its own, and Pixmap.getPixels
            // refuses a pixmap without an alpha channel.
            val deviceBounds = RectI(bounds.transform(matrix))
            val width = (deviceBounds.x1 - deviceBounds.x0).coerceAtLeast(1)
            val height = (deviceBounds.y1 - deviceBounds.y0).coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val device = AndroidDrawDevice(bitmap, deviceBounds.x0, deviceBounds.y0)
            try {
                page.run(device, matrix, null)
            } finally {
                device.close()
                device.destroy()
            }
            PdfRenderedPage(bitmap, scale)
        } catch (e: RuntimeException) {
            Log.e(TAG, "MuPDF page render failed", e)
            throw PdfException(PdfError(PdfErrorCode.RENDER_FAILED), e)
        } catch (e: OutOfMemoryError) {
            throw PdfException(PdfError(PdfErrorCode.RENDER_FAILED, "oom"))
        }
    }

    // ------------------------------------------------------------ text

    fun lines(index: Int): List<PdfLine> {
        structuredText(index)
        return cachedLines
    }

    fun hasTextLayer(index: Int): Boolean = lines(index).any { it.text.isNotBlank() }

    fun findLine(index: Int, point: PdfPoint): Int? = PdfTextSelection.lineAt(lines(index), point)

    fun selection(index: Int, anchor: PdfPoint, focus: PdfPoint): PdfSelection? =
        PdfTextSelection.selection(lines(index), anchor, focus)

    fun wordSelection(index: Int, point: PdfPoint): PdfSelection? =
        PdfTextSelection.wordAt(lines(index), point)

    fun searchPage(index: Int, needle: String): List<PdfSearchHit> {
        if (needle.isEmpty()) return emptyList()
        val page = page(index)
        val hits = try {
            page.search(needle).orEmptyList()
        } catch (e: RuntimeException) {
            Log.e(TAG, "MuPDF search failed on page $index", e)
            emptyList()
        }
        return hits.map { quads ->
            PdfSearchHit(index, quads.map { toPdfQuad(it) })
        }
    }

    fun search(needle: String, maxHits: Int = PdfCaps.MAX_SEARCH_HITS, cancelled: () -> Boolean): List<PdfSearchHit> {
        if (needle.isBlank()) return emptyList()
        val result = ArrayList<PdfSearchHit>()
        for (index in 0 until pages) {
            if (cancelled() || result.size >= maxHits) break
            result += searchPage(index, needle)
        }
        return result.take(maxHits)
    }

    /** UTF-8 text of the whole document with an explicit page separator line. */
    fun exportText(): String {
        val out = StringBuilder()
        for (index in 0 until pages) {
            if (index > 0) out.append("\n\n--- page ").append(index + 1).append(" ---\n\n")
            val text = try {
                structuredText(index)?.asText() ?: ""
            } catch (e: RuntimeException) {
                ""
            }
            out.append(text)
        }
        return out.toString()
    }

    fun textEditTarget(pageIndex: Int, lineIndex: Int): TextEditTarget? {
        val line = lines(pageIndex).getOrNull(lineIndex) ?: return null
        return TextEditTarget(
            pageIndex = pageIndex,
            lineIndex = lineIndex,
            text = line.text,
            bounds = line.bounds,
            baseline = line.baseline,
            fontSize = line.fontSize,
            horizontal = line.horizontal,
            pageTransform = pageTransform(pageIndex),
        )
    }

    /**
     * How the content-stream axes relate to the page axes. Every PDF page carries a y-flip
     * (MuPDF reports [1 0 0 -1 0 height] for an unrotated page), so the flip itself must not be
     * mistaken for rotation: only a 90/270 degree turn makes the text run across the page axes.
     */
    private fun pageTransform(index: Int): PageTransform {
        val m = try {
            page(index).getTransform()
        } catch (e: RuntimeException) {
            return PageTransform.OFFSET
        }
        val orthogonal = kotlin.math.abs(m.a * m.a + m.b * m.b - 1f) < 0.001f &&
            kotlin.math.abs(m.c * m.c + m.d * m.d - 1f) < 0.001f &&
            kotlin.math.abs(m.a * m.c + m.b * m.d) < 0.001f
        if (!orthogonal) return PageTransform.OFFSET
        return if (kotlin.math.abs(m.b) < 0.001f && kotlin.math.abs(m.c) < 0.001f) {
            PageTransform.ALIGNED
        } else {
            PageTransform.ROTATED
        }
    }

    /** Maps a page-space point (top-left origin, y down) into content-stream coordinates. */
    private fun contentPoint(index: Int, point: PdfPoint): PdfPoint {
        val m = page(index).getTransform()
        val determinant = m.a * m.d - m.b * m.c
        if (kotlin.math.abs(determinant) < 1e-6f) return point
        val dx = point.x - m.e
        val dy = point.y - m.f
        return PdfPoint(
            (m.d * dx - m.c * dy) / determinant,
            (m.a * dy - m.b * dx) / determinant,
        )
    }

    /** 4-hex-digit glyph ids for a Type0/Identity-H stream, or null when a glyph is missing. */
    fun encodeGlyphs(text: String): String? = try {
        val font = editFont()
        val hex = StringBuilder(text.length * 4)
        var i = 0
        var missing = false
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val glyph = font.encodeCharacter(codePoint)
            if (glyph <= 0) {
                missing = true
                break
            }
            hex.append(String.format(Locale.ROOT, "%04x", glyph))
            i += Character.charCount(codePoint)
        }
        if (missing) null else hex.toString()
    } catch (e: RuntimeException) {
        null
    }

    /** Advance width of [text] at [size] points in the embedded replacement font. */
    fun measureText(text: String, size: Float): Float = try {
        val font = editFont()
        var width = 0f
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val glyph = font.encodeCharacter(codePoint)
            if (glyph > 0) width += font.advanceGlyph(glyph)
            i += Character.charCount(codePoint)
        }
        width * size
    } catch (e: RuntimeException) {
        0f
    }

    private fun editFont(): Font {
        editFont?.let { return it }
        val font = Font(editFontFile.absolutePath)
        editFont = font
        return font
    }

    /**
     * In-place replacement: remove only the selected run's glyphs (text redaction with images
     * and line art preserved), then write the replacement into the same baseline with the
     * embedded substitute font. No reflow; the page stays vector.
     */
    /**
     * Removes the original glyphs of [plan]'s line (text only; images and line art survive).
     * The replacement itself is written by [insertText] after the document was saved and
     * reopened: MuPDF hands the redacted content stream back as an in-memory object that can
     * neither be read nor extended, and writing into it drops the whole page.
     */
    fun redactLine(plan: TextEditPlan) {
        val target = lines(plan.pageIndex).getOrNull(plan.lineIndex)
            ?: throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "line"))
        if (pageTransform(plan.pageIndex) != PageTransform.ALIGNED) {
            throw PdfException(PdfError(PdfErrorCode.EDIT_UNSUPPORTED, "transform"))
        }
        val page = page(plan.pageIndex)
        val quads = redactionQuadsFor(target.chars.map { it.quad })
        if (quads.isEmpty()) throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "quads"))

        try {
            val redaction = page.createAnnotation(PDFAnnotation.TYPE_REDACT)
            redaction.setRect(toRect(target.bounds))
            redaction.setQuadPoints(quads.map { toQuad(it) }.toTypedArray())
            redaction.update()
            val redacted = page.applyRedactions(
                false,
                PDFPage.REDACT_IMAGE_NONE,
                PDFPage.REDACT_LINE_ART_NONE,
                PDFPage.REDACT_TEXT_REMOVE,
            )
            if (!redacted) throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "redact-empty"))
        } catch (e: RuntimeException) {
            dropCache()
            throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "redact"))
        }
        dropCache()
    }

    /** Writes [plan]'s replacement text on the original baseline with the embedded font. */
    fun insertText(plan: TextEditPlan) {
        if (pageTransform(plan.pageIndex) != PageTransform.ALIGNED) {
            throw PdfException(PdfError(PdfErrorCode.EDIT_UNSUPPORTED, "transform"))
        }
        try {
            appendText(plan.pageIndex, plan.glyphHex, plan.sizePt, plan.startX, plan.startY)
        } catch (e: RuntimeException) {
            Log.e(TAG, "replacement text could not be written", e)
            dropCache()
            throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "insert"))
        }
        dropCache()
    }

    private fun appendText(pageIndex: Int, glyphHex: String, size: Float, x: Float, y: Float) {
        val page = page(pageIndex)
        val fontRef = editFontRef ?: pdf.addFont(editFont()).also { editFontRef = it }
        val pageObj = page.getObject()

        val resources = copyDictionary(pageObj.getInheritable("Resources").resolve())
        val fonts = copyDictionary(resources.get("Font").resolve())
        fonts.put("PdfEditFont", fontRef)
        resources.put("Font", fonts)
        pageObj.put("Resources", resources)

        // The plan works in page space; the content stream has its own (flipped, possibly
        // rotated) axes, so the anchor is mapped back before the text matrix is written.
        val origin = contentPoint(pageIndex, PdfPoint(x, y))
        val operators = buildString {
            append("q\nBT\n/PdfEditFont ").append(number(size)).append(" Tf\n")
            append("1 0 0 1 ").append(number(origin.x)).append(' ').append(number(origin.y)).append(" Tm\n")
            append('<').append(glyphHex).append("> Tj\nET\nQ\n")
        }
        // The page's streams are concatenated (readers concatenate them anyway) and the new
        // operators appended into a single stream. A stream is read through its reference: a
        // resolved content object is a bare dictionary and readStream() rejects it.
        val contents = pageObj.get("Contents")
        val resolved = contents.resolve()
        val parts = if (resolved.isArray) resolved.toList() else listOf(contents)
        val combined = Buffer()
        for (part in parts) {
            val stream = if (part.isIndirect()) part else part.resolve()
            combined.writeBytes(stream.readStream())
        }
        combined.writeBytes(operators.toByteArray(Charsets.ISO_8859_1))
        pageObj.put("Contents", pdf.addStream(combined))
        page.update()
    }

    private fun copyDictionary(source: PDFObject): PDFObject {
        val copy = pdf.newDictionary()
        if (!source.isNull) for (key in source) copy.put(key, source.get(key))
        return copy
    }

    // ------------------------------------------------------------ annotations

    fun annotations(pageIndex: Int): List<PdfAnnotationRef> {
        val page = page(pageIndex)
        return try {
            val annots = page.getAnnotations().orEmptyList()
            annots.mapIndexed { index, annotation ->
                val type = annotation.type
                val author = if (annotation.hasAuthor()) annotation.author else null
                PdfAnnotationRef(
                    index = index,
                    kind = when (type) {
                        PDFAnnotation.TYPE_TEXT -> PdfAnnotationKind.TEXT_NOTE
                        PDFAnnotation.TYPE_FREE_TEXT -> PdfAnnotationKind.FREE_TEXT
                        PDFAnnotation.TYPE_HIGHLIGHT -> PdfAnnotationKind.HIGHLIGHT
                        PDFAnnotation.TYPE_UNDERLINE -> PdfAnnotationKind.UNDERLINE
                        PDFAnnotation.TYPE_STRIKE_OUT -> PdfAnnotationKind.STRIKEOUT
                        PDFAnnotation.TYPE_INK -> PdfAnnotationKind.INK
                        else -> PdfAnnotationKind.OTHER
                    },
                    rect = toPdfRect(annotation.bounds),
                    contents = try { annotation.contents } catch (e: RuntimeException) { null }
                        ?.takeIf { it.isNotEmpty() },
                    author = author,
                    own = author == AUTHOR,
                )
            }
        } catch (e: RuntimeException) {
            emptyList()
        }
    }

    fun addMarkup(pageIndex: Int, quads: List<PdfQuad>, type: Int, color: FloatArray) {
        if (quads.isEmpty()) throw PdfException(PdfError(PdfErrorCode.NO_ANNOTATION))
        val page = page(pageIndex)
        try {
            val annotation = page.createAnnotation(type)
            // Markup annotations derive their rectangle from the quad points; setting /Rect on
            // them is rejected by MuPDF ("Highlight annotations have no Rect property").
            annotation.setQuadPoints(quads.map { toQuad(it) }.toTypedArray())
            annotation.setColor(color)
            annotation.setOpacity(1f)
            annotation.setAuthor(AUTHOR)
            annotation.update()
        } catch (e: RuntimeException) {
            Log.e(TAG, "ink annotation could not be created", e)
            throw PdfException(PdfError(PdfErrorCode.NO_ANNOTATION))
        }
        dropCache()
    }

    fun addInk(pageIndex: Int, strokes: List<List<PdfPoint>>, widthPt: Float, color: FloatArray) {
        val points = strokes.flatten()
        if (points.size < 2) throw PdfException(PdfError(PdfErrorCode.NO_ANNOTATION))
        val page = page(pageIndex)
        try {
            val annotation = page.createAnnotation(PDFAnnotation.TYPE_INK)
            // An ink annotation's rectangle is derived from its ink list; MuPDF rejects /Rect on
            // it ("Ink annotations have no Rect property").
            annotation.setInkList(
                strokes.map { stroke -> stroke.map { Point(it.x, it.y) }.toTypedArray() }.toTypedArray(),
            )
            annotation.setBorderWidth(widthPt)
            annotation.setColor(color)
            annotation.setOpacity(1f)
            annotation.setAuthor(AUTHOR)
            annotation.update()
        } catch (e: RuntimeException) {
            Log.e(TAG, "ink annotation could not be created", e)
            throw PdfException(PdfError(PdfErrorCode.NO_ANNOTATION))
        }
        dropCache()
    }

    fun addTextNote(pageIndex: Int, at: PdfPoint, text: String) {
        val page = page(pageIndex)
        try {
            val annotation = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
            annotation.setRect(Rect(at.x, at.y, at.x + 24f, at.y + 24f))
            annotation.setContents(text)
            annotation.setAuthor(AUTHOR)
            annotation.setIcon("Comment")
            annotation.setColor(floatArrayOf(0.91f, 0.65f, 0.75f))
            annotation.update()
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.NO_ANNOTATION))
        }
        dropCache()
    }

    /**
     * Text box as a FreeText annotation. The appearance stream is written by the editor with the
     * embedded font, so Cyrillic renders the same way on every reader instead of relying on the
     * reader's base-14 fonts.
     */
    fun addTextBox(pageIndex: Int, rect: PdfRect, text: String) {
        val page = page(pageIndex)
        val fontRef = editFontRef ?: pdf.addFont(editFont()).also { editFontRef = it }
        val size = 11f
        try {
            val annotation = page.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
            annotation.setRect(toRect(rect))
            annotation.setContents(text)
            annotation.setAuthor(AUTHOR)
            annotation.setColor(floatArrayOf(0f, 0f, 0f))
            annotation.setDefaultAppearance("PdfEditFont", size, floatArrayOf(0f, 0f, 0f))

            val resources = pdf.newDictionary()
            val fonts = pdf.newDictionary()
            fonts.put("PdfEditFont", fontRef)
            resources.put("Font", fonts)

            val lines = wrapText(text, size, rect.width - 8f)
            val buffer = Buffer()
            val content = buildString {
                append("q\nBT\n/PdfEditFont ").append(number(size)).append(" Tf\n0 0 0 rg\n")
                var y = rect.height - size - 4f
                for (line in lines.take(maxOf(1, ((rect.height - 4f) / (size * 1.25f)).toInt()))) {
                    val hex = encodeGlyphs(line) ?: continue
                    append("1 0 0 1 4 ").append(number(y)).append(" Tm\n<").append(hex).append("> Tj\n")
                    y -= size * 1.25f
                }
                append("ET\nQ\n")
            }
            buffer.writeBytes(content.toByteArray(Charsets.US_ASCII))
            annotation.setAppearance(
                null,
                Matrix(1f, 0f, 0f, 1f, 0f, 0f),
                Rect(0f, 0f, rect.width, rect.height),
                resources,
                buffer,
            )
            buffer.destroy()
            annotation.update()
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.NO_ANNOTATION))
        }
        dropCache()
    }

    fun moveAnnotation(pageIndex: Int, annotationIndex: Int, dx: Float, dy: Float) {
        val page = page(pageIndex)
        val annotation = page.getAnnotations().orEmptyList().getOrNull(annotationIndex)
            ?: throw PdfException(PdfError(PdfErrorCode.NO_ANNOTATION))
        try {
            annotation.setRect(toRect(toPdfRect(annotation.bounds).translated(dx, dy)))
            if (annotation.hasQuadPoints()) {
                annotation.setQuadPoints(
                    annotation.quadPoints.map { quad ->
                        Quad(
                            quad.ul_x + dx, quad.ul_y + dy,
                            quad.ur_x + dx, quad.ur_y + dy,
                            quad.ll_x + dx, quad.ll_y + dy,
                            quad.lr_x + dx, quad.lr_y + dy,
                        )
                    }.toTypedArray(),
                )
            }
            if (annotation.hasInkList()) {
                val ink = annotation.inkList.map { stroke ->
                    stroke.map { Point(it.x + dx, it.y + dy) }.toTypedArray()
                }.toTypedArray()
                annotation.setInkList(ink)
            }
            annotation.update()
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.NO_ANNOTATION))
        }
        dropCache()
    }

    fun deleteAnnotation(pageIndex: Int, annotationIndex: Int) {
        val page = page(pageIndex)
        val annotation = page.getAnnotations().orEmptyList().getOrNull(annotationIndex)
            ?: throw PdfException(PdfError(PdfErrorCode.NO_ANNOTATION))
        try {
            page.deleteAnnotation(annotation)
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.NO_ANNOTATION))
        }
        dropCache()
    }

    // ------------------------------------------------------------ forms

    fun formFields(): List<PdfFormField> {
        if (!hasAcroForm) return emptyList()
        val result = ArrayList<PdfFormField>()
        for (index in 0 until pages) {
            val page = page(index)
            val widgets = try {
                page.getWidgets().orEmptyList()
            } catch (e: RuntimeException) {
                emptyList()
            }
            widgets.forEachIndexed { widgetIndex, widget ->
                if (result.size >= PdfCaps.MAX_FORM_FIELDS) return result
                result += PdfFormField(
                    pageIndex = index,
                    widgetIndex = widgetIndex,
                    name = widget.name ?: "",
                    label = (widget.label ?: widget.name ?: "").ifBlank { "field" },
                    kind = when (widget.fieldType) {
                        PDFWidget.TYPE_TEXT -> PdfFormKind.TEXT
                        PDFWidget.TYPE_CHECKBOX -> PdfFormKind.CHECKBOX
                        PDFWidget.TYPE_RADIOBUTTON -> PdfFormKind.RADIO
                        PDFWidget.TYPE_COMBOBOX, PDFWidget.TYPE_LISTBOX -> PdfFormKind.CHOICE
                        PDFWidget.TYPE_BUTTON -> PdfFormKind.BUTTON
                        PDFWidget.TYPE_SIGNATURE -> PdfFormKind.SIGNATURE
                        else -> PdfFormKind.UNKNOWN
                    },
                    value = widget.value ?: "",
                    options = widget.options?.toList() ?: emptyList(),
                    rect = toPdfRect(widget.bounds),
                    readOnly = widget.isReadOnly,
                    multiline = widget.isText && widget.isMultiline,
                    maxLength = if (widget.isText) widget.maxLen else 0,
                    signed = widget.fieldType == PDFWidget.TYPE_SIGNATURE && widget.isSigned(),
                )
            }
        }
        return result
    }

    private fun widget(pageIndex: Int, widgetIndex: Int): PDFWidget {
        val page = page(pageIndex)
        return page.getWidgets().orEmptyList().getOrNull(widgetIndex)
            ?: throw PdfException(PdfError(PdfErrorCode.FORM_FIELD_FAILED))
    }

    /**
     * MuPDF only accepts a text-field value while the widget is in editing state, so the sequence
     * is startEditing -> commitEditing. Calling setTextValue on an idle widget returns false and
     * silently changes nothing.
     */
    fun setFormText(pageIndex: Int, widgetIndex: Int, value: String): Boolean {
        val widget = widget(pageIndex, widgetIndex)
        val accepted = if (widget.isText) {
            widget.startEditing()
            widget.commitEditing(value)
        } else {
            widget.setValue(value)
        }
        if (!accepted) {
            dropCache()
            throw PdfException(PdfError(PdfErrorCode.FORM_FIELD_FAILED, "value"))
        }
        widget.update()
        dropCache()
        return true
    }

    fun toggleForm(pageIndex: Int, widgetIndex: Int): Boolean {
        val widget = widget(pageIndex, widgetIndex)
        val accepted = widget.toggle()
        if (!accepted) {
            dropCache()
            throw PdfException(PdfError(PdfErrorCode.FORM_FIELD_FAILED, "toggle"))
        }
        widget.update()
        dropCache()
        return true
    }

    fun setFormChoice(pageIndex: Int, widgetIndex: Int, value: String): Boolean {
        val widget = widget(pageIndex, widgetIndex)
        val accepted = widget.setChoiceValue(value) || widget.setValue(value)
        if (!accepted) {
            dropCache()
            throw PdfException(PdfError(PdfErrorCode.FORM_FIELD_FAILED, "choice"))
        }
        widget.update()
        dropCache()
        return true
    }

    /** Flattens widgets into page content after an explicit user request. */
    fun flattenForms() {
        if (!hasAcroForm) return
        try {
            pdf.bake(false, true)
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.FORM_FIELD_FAILED, "flatten"))
        }
        dropCache()
    }

    // ------------------------------------------------------------ signatures

    fun signatures(): List<PdfSignatureInfo> {
        val result = ArrayList<PdfSignatureInfo>()
        for (index in 0 until pages) {
            val page = page(index)
            val widgets = try {
                page.getWidgets().orEmptyList()
            } catch (e: RuntimeException) {
                emptyList()
            }
            widgets.forEachIndexed { widgetIndex, widget ->
                if (widget.fieldType != PDFWidget.TYPE_SIGNATURE) return@forEachIndexed
                val signed = try { widget.isSigned() } catch (e: RuntimeException) { false }
                val dictionary = try { widget.getObject().get("V") } catch (e: RuntimeException) { null }
                val signer = dictionary?.let { safeString(it, "Name") } ?: ""
                val reason = dictionary?.let { safeString(it, "Reason") } ?: ""
                val date = dictionary?.let { safeString(it, "M") } ?: ""
                val status = if (!signed) {
                    PdfSignatureStatus.UNSIGNED
                } else {
                    when (try { widget.validateSignature() } catch (e: RuntimeException) { 8 }) {
                        PDFWidget.SIGNATURE_ERROR_OKAY -> PdfSignatureStatus.VALID
                        PDFWidget.SIGNATURE_ERROR_DIGEST_FAILURE -> PdfSignatureStatus.DIGEST_INVALID
                        PDFWidget.SIGNATURE_ERROR_SELF_SIGNED,
                        PDFWidget.SIGNATURE_ERROR_SELF_SIGNED_IN_CHAIN,
                        PDFWidget.SIGNATURE_ERROR_NOT_TRUSTED -> PdfSignatureStatus.NOT_TRUSTED
                        else -> PdfSignatureStatus.UNKNOWN
                    }
                }
                result += PdfSignatureInfo(index, widgetIndex, signer, reason, date, status)
            }
        }
        return result
    }

    private fun safeString(dictionary: PDFObject, key: String): String? = try {
        val value = dictionary.get(key)
        if (value.isNull || !value.isString) null else value.asString().takeIf { it.isNotBlank() }
    } catch (e: RuntimeException) {
        null
    }

    fun hasSignedSignatures(): Boolean = signatures().any { it.status != PdfSignatureStatus.UNSIGNED }

    /**
     * Adds a cryptographic signature widget. The document must then be saved; the signer is kept
     * alive until the save so the native digest callback can address it.
     */
    fun sign(
        pageIndex: Int,
        rect: PdfRect,
        signer: PKCS7Signer,
        reason: String,
        location: String,
    ) {
        val page = page(pageIndex)
        try {
            val widget = page.createSignature()
            widget.setRect(toRect(rect))
            widget.update()
            val flags = PDFWidget.SIGNATURE_SHOW_LABELS or
                PDFWidget.SIGNATURE_SHOW_DN or
                PDFWidget.SIGNATURE_SHOW_DATE
            if (!widget.sign(signer, flags, null, reason, location)) {
                Log.e(TAG, "MuPDF refused to sign the widget")
                throw PdfException(PdfError(PdfErrorCode.SIGN_FAILED))
            }
            widget.update()
        } catch (e: PdfException) {
            throw e
        } catch (e: RuntimeException) {
            Log.e(TAG, "the document could not be signed", e)
            throw PdfException(PdfError(PdfErrorCode.SIGN_FAILED))
        }
        pendingSigner = signer
        dropCache()
    }

    // ------------------------------------------------------------ pages

    fun insertBlankPage(at: Int, width: Float, height: Float) {
        val clamped = at.coerceIn(0, pages)
        try {
            val pageObject = pdf.addPage(Rect(0f, 0f, width, height), 0, null, "")
            pdf.insertPage(clamped, pageObject)
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "insert-page"))
        }
        pages = pdf.countPages()
        dropCache()
    }

    fun importPage(source: PdfEngine, sourcePage: Int, at: Int) {
        if (sourcePage !in 0 until source.pageCount) {
            throw PdfException(PdfError(PdfErrorCode.IMPORT_FAILED))
        }
        val clamped = at.coerceIn(0, pages)
        try {
            pdf.graftPage(clamped, source.pdf, sourcePage)
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.IMPORT_FAILED))
        }
        pages = pdf.countPages()
        dropCache()
    }

    fun deletePage(at: Int) {
        if (pages <= 1) throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "last-page"))
        if (at !in 0 until pages) throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "range"))
        try {
            pdf.deletePage(at)
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "delete-page"))
        }
        pages = pdf.countPages()
        dropCache()
    }

    fun movePage(from: Int, to: Int) {
        if (from !in 0 until pages || to !in 0 until pages || from == to) return
        val order = movedPageOrder(pages, from, to)
        try {
            pdf.rearrangePages(order)
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "move-page"))
        }
        dropCache()
    }

    fun rotatePage(at: Int, degrees: Int) {
        if (at !in 0 until pages) throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "range"))
        val page = page(at)
        try {
            val pageObject = page.getObject()
            val current = try {
                pageObject.get("Rotate").resolve().asInteger()
            } catch (e: RuntimeException) {
                0
            }
            val next = (((current + degrees) % 360) + 360) % 360
            pageObject.put("Rotate", next)
            page.update()
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.EDIT_FAILED, "rotate"))
        }
        dropCache()
    }

    // ------------------------------------------------------------ save

    /** Writes the current document into [target] as a complete file (no incremental tail). */
    fun saveTo(target: File) {
        try {
            pdf.save(target.absolutePath, "compress")
        } catch (e: RuntimeException) {
            throw PdfException(PdfError(PdfErrorCode.SAVE_FAILED))
        } finally {
            pendingSigner = null
        }
    }

    override fun close() {
        pendingSigner = null
        dropCache()
        editFontRef = null
        try { editFont?.destroy() } catch (_: RuntimeException) { }
        editFont = null
        try { pdf.destroy() } catch (_: RuntimeException) { }
    }

    // ------------------------------------------------------------ helpers

    private fun readLines(text: StructuredText): List<PdfLine> {
        val sizes = lineSizes(text)
        val lines = ArrayList<PdfLine>()
        var flattened = 0
        try {
            for (block in text.blocks.orEmptyList()) {
                for (line in block.lines.orEmptyList()) {
                    val chars = line.chars.orEmptyList().map { char ->
                        PdfChar(char.c, toPdfQuad(char.quad))
                    }
                    val builder = StringBuilder(chars.size)
                    chars.forEach { builder.appendCodePoint(it.codePoint) }
                    val direction = line.dir
                    lines += PdfLine(
                        text = builder.toString(),
                        chars = chars,
                        bounds = toPdfRect(line.bbox),
                        baseline = line.chars.orEmptyList().firstOrNull()?.let { PdfPoint(it.origin.x, it.origin.y) }
                            ?: PdfPoint(line.bbox.x0, line.bbox.y1),
                        fontSize = sizes.getOrNull(flattened) ?: estimateSize(line.bbox.y1 - line.bbox.y0),
                        horizontal = kotlin.math.abs(direction.y) < 0.01f && direction.x >= 0f,
                    )
                    flattened++
                }
            }
        } catch (e: RuntimeException) {
            // Structured text can fail on damaged pages; an empty list means "no text layer".
        }
        return lines
    }

    private fun estimateSize(lineHeight: Float): Float = (lineHeight * 0.85f).coerceIn(4f, 200f)

    /** Line font sizes, read from the structured-text JSON at 10x precision. */
    private fun lineSizes(text: StructuredText): List<Float> = try {
        val root = Json.parseToJsonElement(text.asJSON(10f)).jsonObject
        val sizes = ArrayList<Float>()
        root["blocks"]?.jsonArray?.forEach { block ->
            val objectBlock = block.jsonObject
            if (objectBlock["type"]?.jsonPrimitive?.content != "text") return@forEach
            objectBlock["lines"]?.jsonArray?.forEach { line ->
                val size = line.jsonObject["font"]?.jsonObject?.get("size")?.jsonPrimitive?.int
                sizes += if (size != null && size > 0) size / 10f else 0f
            }
        }
        sizes
    } catch (e: Exception) {
        emptyList()
    }

    private fun wrapText(text: String, size: Float, maxWidth: Float): List<String> {
        val words = text.replace('\n', ' ').split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = ArrayList<String>()
        val current = StringBuilder()
        var width = 0f
        for (word in words) {
            val wordWidth = measureText(word, size)
            val space = if (current.isEmpty()) 0f else measureText(" ", size)
            if (width + space + wordWidth > maxWidth && current.isNotEmpty()) {
                lines += current.toString()
                current.setLength(0)
                width = wordWidth
                current.append(word)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
                width += space + wordWidth
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}

// ---------------------------------------------------------------- conversions

internal fun toPdfRect(rect: Rect) = PdfRect(rect.x0, rect.y0, rect.x1, rect.y1)

internal fun toRect(rect: PdfRect) = Rect(rect.left, rect.top, rect.right, rect.bottom)

internal fun toPdfQuad(quad: Quad) = PdfQuad(
    PdfPoint(quad.ul_x, quad.ul_y),
    PdfPoint(quad.ur_x, quad.ur_y),
    PdfPoint(quad.ll_x, quad.ll_y),
    PdfPoint(quad.lr_x, quad.lr_y),
)

internal fun toQuad(quad: PdfQuad) = Quad(
    quad.upperLeft.x, quad.upperLeft.y,
    quad.upperRight.x, quad.upperRight.y,
    quad.lowerLeft.x, quad.lowerLeft.y,
    quad.lowerRight.x, quad.lowerRight.y,
)

internal fun number(value: Float): String = String.format(Locale.ROOT, "%.3f", value)
