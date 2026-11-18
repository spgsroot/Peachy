package ru.spgsroot.peachy.documents.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PdfTextSelectionTest {

    private fun char(c: Char, x: Float, y: Float = 0f) =
        PdfChar(c.code, PdfQuad.of(PdfRect(x, y, x + 6f, y + 10f)))

    private fun page(): List<PdfLine> = listOf(
        PdfLine(
            text = "ab",
            chars = listOf(char('a', 0f), char('b', 6f)),
            bounds = PdfRect(0f, 0f, 12f, 10f),
            baseline = PdfPoint(0f, 10f),
            fontSize = 10f,
            horizontal = true,
        ),
        PdfLine(
            text = "cd",
            chars = listOf(char('c', 0f, 20f), char('d', 6f, 20f)),
            bounds = PdfRect(0f, 20f, 12f, 30f),
            baseline = PdfPoint(0f, 30f),
            fontSize = 10f,
            horizontal = true,
        ),
    )

    @Test
    fun selectionAcrossLinesKeepsLineBreak() {
        val selection = PdfTextSelection.selection(page(), PdfPoint(1f, 5f), PdfPoint(7f, 25f))
        assertNotNull(selection)
        assertEquals("ab\ncd", selection!!.text)
        assertEquals(4, selection.quads.size)
    }

    @Test
    fun reversedDragSelectsSameRange() {
        val forward = PdfTextSelection.selection(page(), PdfPoint(1f, 5f), PdfPoint(7f, 25f))
        val backward = PdfTextSelection.selection(page(), PdfPoint(7f, 25f), PdfPoint(1f, 5f))
        assertEquals(forward?.text, backward?.text)
        assertEquals(forward?.quads?.size, backward?.quads?.size)
    }

    @Test
    fun wordAtSelectsWholeWordAndTappingSpaceSelectsPrevious() {
        val line = PdfLine(
            text = "hello world",
            chars = "hello world".mapIndexed { index, c -> char(c, index * 6f) },
            bounds = PdfRect(0f, 0f, 66f, 10f),
            baseline = PdfPoint(0f, 10f),
            fontSize = 10f,
            horizontal = true,
        )
        val word = PdfTextSelection.wordAt(listOf(line), PdfPoint(40f, 5f))
        assertEquals("world", word?.text)
        val leftOfSpace = PdfTextSelection.wordAt(listOf(line), PdfPoint(30.5f, 5f))
        assertEquals("hello", leftOfSpace?.text)
    }

    @Test
    fun lineAtMatchesGlyphRowAndRejectsFarPoint() {
        assertEquals(1, PdfTextSelection.lineAt(page(), PdfPoint(3f, 24f)))
        assertNull(PdfTextSelection.lineAt(page(), PdfPoint(3f, 400f)))
    }

    @Test
    fun emptyPageHasNoSelection() {
        assertNull(PdfTextSelection.selection(emptyList(), PdfPoint(1f, 1f), PdfPoint(2f, 2f)))
    }
}

class RedactionQuadsTest {

    @Test
    fun insetsStayInsideEachGlyphAndMissNeighbours() {
        val left = PdfQuad.of(PdfRect(0f, 0f, 10f, 10f))
        val right = PdfQuad.of(PdfRect(10f, 0f, 20f, 10f))
        val quads = redactionQuadsFor(listOf(left))
        assertEquals(1, quads.size)
        val rect = quads.first().bounds
        assertTrue(rect.left > left.bounds.left)
        assertTrue(rect.right < left.bounds.right)
        // MuPDF shrinks glyph boxes by 10% before the intersection test; the neighbour must not
        // touch the redaction rectangle or it would be removed as well.
        val neighbour = right.bounds.inset(0.1f)
        assertFalse(rect.right >= neighbour.left)
    }

    @Test
    fun emptyGlyphBoxesAreDropped() {
        val empty = PdfQuad.of(PdfRect(5f, 5f, 5f, 5f))
        assertTrue(redactionQuadsFor(listOf(empty)).isEmpty())
    }

    @Test
    fun insetNeverInvertsTheRectangle() {
        val thin = PdfQuad.of(PdfRect(0f, 0f, 1f, 1f))
        val rect = redactionQuadsFor(listOf(thin)).first().bounds
        assertTrue(rect.width > 0f)
        assertTrue(rect.height > 0f)
    }

    @Test
    fun quadBoundsCoverAllFourCorners() {
        val quad = PdfQuad(
            PdfPoint(2f, 4f),
            PdfPoint(12f, 3f),
            PdfPoint(1f, 14f),
            PdfPoint(11f, 15f),
        )
        val bounds = quad.bounds
        assertEquals(1f, bounds.left, 0.001f)
        assertEquals(3f, bounds.top, 0.001f)
        assertEquals(12f, bounds.right, 0.001f)
        assertEquals(15f, bounds.bottom, 0.001f)
    }
}

class TextEditPlannerTest {

    private val target = TextEditTarget(
        pageIndex = 0,
        lineIndex = 3,
        text = "Привет",
        bounds = PdfRect(10f, 20f, 110f, 40f),
        baseline = PdfPoint(10f, 36f),
        fontSize = 14f,
        horizontal = true,
        pageTransform = PageTransform.ALIGNED,
    )

    private val encode: (String) -> String? = { text -> "00" + text.length }
    private val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.55f }

    @Test
    fun alignedHorizontalLineIsAccepted() {
        val decision = TextEditPlanner.plan(target, "Hello", encode, measure)
        assertTrue(decision is TextEditDecision.Apply)
        val plan = (decision as TextEditDecision.Apply).plan
        assertEquals("Hello", plan.text)
        assertEquals(10f, plan.startX, 0.001f)
        assertEquals(36f, plan.startY, 0.001f)
        assertEquals(14f, plan.sizePt, 0.001f)
    }

    @Test
    fun rotatedPageIsRefused() {
        val decision = TextEditPlanner.plan(
            target.copy(pageTransform = PageTransform.ROTATED), "Hello", encode, measure,
        )
        assertEquals(PdfEditRefusal.ROTATED_PAGE, (decision as TextEditDecision.Refuse).reason)
    }

    @Test
    fun offsetPageIsRefused() {
        val decision = TextEditPlanner.plan(
            target.copy(pageTransform = PageTransform.OFFSET), "Hello", encode, measure,
        )
        assertEquals(PdfEditRefusal.OFFSET_PAGE, (decision as TextEditDecision.Refuse).reason)
    }

    @Test
    fun verticalTextIsRefused() {
        val decision = TextEditPlanner.plan(target.copy(horizontal = false), "Hello", encode, measure)
        assertEquals(PdfEditRefusal.VERTICAL_TEXT, (decision as TextEditDecision.Refuse).reason)
    }

    @Test
    fun emptyUnchangedAndMultilineAreRefused() {
        assertEquals(
            PdfEditRefusal.EMPTY_TEXT,
            (TextEditPlanner.plan(target, "", encode, measure) as TextEditDecision.Refuse).reason,
        )
        assertEquals(
            PdfEditRefusal.UNCHANGED_TEXT,
            (TextEditPlanner.plan(target, "Привет", encode, measure) as TextEditDecision.Refuse).reason,
        )
        assertEquals(
            PdfEditRefusal.MULTILINE_TEXT,
            (TextEditPlanner.plan(target, "a\nb", encode, measure) as TextEditDecision.Refuse).reason,
        )
    }

    @Test
    fun longTextIsRefusedBeforeAnyNativeCall() {
        val text = "x".repeat(TextEditPlanner.MAX_TEXT_LENGTH + 1)
        assertEquals(
            PdfEditRefusal.TEXT_TOO_LONG,
            (TextEditPlanner.plan(target, text, encode, measure) as TextEditDecision.Refuse).reason,
        )
    }

    @Test
    fun missingGlyphsAreRefused() {
        val decision = TextEditPlanner.plan(target, "Привет", encode, { _, _ -> 0f })
        // The text equals the original, so unchanged wins; use different text for the glyph path.
        assertEquals(PdfEditRefusal.UNCHANGED_TEXT, (decision as TextEditDecision.Refuse).reason)
        val other = TextEditPlanner.plan(target, "Пока", { null }, measure)
        assertEquals(PdfEditRefusal.MISSING_GLYPHS, (other as TextEditDecision.Refuse).reason)
    }

    @Test
    fun widerReplacementIsReportedNotRefused() {
        val decision = TextEditPlanner.plan(target, "Expanded replacement text", encode, measure)
        val plan = (decision as TextEditDecision.Apply).plan
        assertTrue(plan.overflows)
        assertTrue(plan.overflowPt > 0f)
        assertTrue(abs(plan.originalWidth - 100f) < 0.001f)
    }

    @Test
    fun noLineIsRefused() {
        val decision = TextEditPlanner.plan(null, "x", encode, measure)
        assertEquals(PdfEditRefusal.NO_LINE, (decision as TextEditDecision.Refuse).reason)
    }
}

class PdfCapsTest {

    @Test
    fun fileCapIsOneHundredMiB() {
        assertNull(PdfCaps.checkFileLength(PdfCaps.MAX_FILE_BYTES))
        assertEquals(
            PdfErrorCode.TOO_LARGE,
            PdfCaps.checkFileLength(PdfCaps.MAX_FILE_BYTES + 1)?.code,
        )
    }

    @Test
    fun pageCapRejectsMoreThanTheLimit() {
        assertNull(PdfCaps.checkPageCount(PdfCaps.MAX_PAGES))
        assertEquals(PdfErrorCode.PAGE_LIMIT, PdfCaps.checkPageCount(PdfCaps.MAX_PAGES + 1)?.code)
    }

    @Test
    fun renderScaleRespectsPixelAndSideCaps() {
        val scale = PdfCaps.renderScaleFor(8f, 595f, 842f)
        val width = 595f * scale
        val height = 842f * scale
        assertTrue(width <= PdfCaps.MAX_RENDER_SIDE)
        assertTrue(height <= PdfCaps.MAX_RENDER_SIDE)
        assertTrue(width * height <= PdfCaps.MAX_RENDER_PIXELS + 1f)
        assertTrue(scale > 0f)
    }

    @Test
    fun smallScaleIsNotInflated() {
        assertEquals(0.5f, PdfCaps.renderScaleFor(0.5f, 300f, 300f), 0.0001f)
    }
}
