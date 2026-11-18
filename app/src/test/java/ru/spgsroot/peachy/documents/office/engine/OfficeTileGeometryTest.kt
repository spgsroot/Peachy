package ru.spgsroot.peachy.documents.office.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeTileGeometryTest {

    @Test
    fun zoomRatioMatchesTheUpstreamPixelToTwipConversion() {
        val zoom = OfficeZoom.forZoom(1.0, OfficeTileLimits.TILE_PIXELS, 96.0)
        assertEquals(256, zoom.pixelWidth)
        assertEquals(3840, zoom.twipWidth)
        assertEquals(3840, zoom.twipXForPixel(256))
        assertEquals(3840, zoom.twipYForPixel(256))
        assertEquals(256.0, zoom.pixelXForTwip(3840), 1e-9)
        assertEquals(1.0, zoom.pixelsPerTwipX * 3840 / 256, 1e-9)
    }

    @Test
    fun zoomHalvesAndDoublesTheTwipSpan() {
        assertEquals(1920, OfficeZoom.forZoom(2.0).twipWidth)
        assertEquals(7680, OfficeZoom.forZoom(0.5).twipWidth)
        assertEquals(480, OfficeZoom.forZoom(8.0).twipWidth)
    }

    @Test
    fun aTouchPointMapsToTheSameTwipsItWasRenderedFrom() {
        val zoom = OfficeZoom.forZoom(1.0)
        val tileOriginTwips = 3840
        val offsetPixels = 128
        val twips = tileOriginTwips + zoom.twipXForPixel(offsetPixels)
        assertEquals(3840 + 1920, twips)
        assertEquals(offsetPixels.toDouble(), zoom.pixelXForTwip(1920), 1e-9)
    }

    @Test
    fun zoomOutsideTheSupportedRangeIsRejected() {
        for (zoom in listOf(0.0, 0.05, 8.5, Double.NaN)) {
            try {
                OfficeZoom.forZoom(zoom)
                throw AssertionError("accepted zoom $zoom")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun aValidTileRequestPasses() {
        val request = OfficeTileRequest(256, 256, 0, 0, 3840, 3840)
        assertNull(request.violation())
        assertEquals(256 * 256 * 4, request.bufferBytes)
    }

    @Test
    fun canvasBoundsAndTheBufferCapAreEnforced() {
        assertEquals(
            OfficeTileViolation.CANVAS_EDGE,
            OfficeTileRequest(15, 256, 0, 0, 3840, 3840).violation(),
        )
        assertEquals(
            OfficeTileViolation.CANVAS_EDGE,
            OfficeTileRequest(4097, 256, 0, 0, 3840, 3840).violation(),
        )
        assertNull(OfficeTileRequest(2048, 2048, 0, 0, 3840, 3840).violation())
        assertEquals(
            OfficeTileViolation.CANVAS_PIXELS,
            OfficeTileRequest(2049, 2048, 0, 0, 3840, 3840).violation(),
        )
    }

    @Test
    fun tileRectangleBoundsAreEnforced() {
        assertEquals(
            OfficeTileViolation.NEGATIVE_TILE_ORIGIN,
            OfficeTileRequest(256, 256, -1, 0, 3840, 3840).violation(),
        )
        assertEquals(
            OfficeTileViolation.NON_POSITIVE_TILE,
            OfficeTileRequest(256, 256, 0, 0, 0, 3840).violation(),
        )
        assertEquals(
            OfficeTileViolation.TILE_TOO_LARGE,
            OfficeTileRequest(256, 256, 0, 0, OfficeTileLimits.MAX_TILE_TWIPS + 1, 3840).violation(),
        )
    }

    @Test
    fun documentSizesAreBounded() {
        assertTrue(documentSizeIsRenderable(1, 1))
        assertTrue(documentSizeIsRenderable(OfficeTileLimits.MAX_DOCUMENT_TWIPS, 1))
        assertFalse(documentSizeIsRenderable(0, 1))
        assertFalse(documentSizeIsRenderable(1, OfficeTileLimits.MAX_DOCUMENT_TWIPS + 1))
        assertFalse(documentSizeIsRenderable(-1, 1))
    }

    @Test
    fun pageRectanglesAreParsedPerLine() {
        val rects = parsePageRects("0,0,11906,16838\n0,16838,11906,16838")
        assertEquals(2, rects.size)
        assertEquals(OfficePageRect(0, 0, 11906, 16838), rects[0])
        assertEquals(OfficePageRect(0, 16838, 11906, 16838), rects[1])
        assertEquals(listOf(OfficePageRect(0, 0, 100, 200)), parsePageRects("0 0 100 200"))
    }

    @Test
    fun malformedPageRectanglesYieldNothingInsteadOfHalfALayout() {
        assertTrue(parsePageRects(null).isEmpty())
        assertTrue(parsePageRects("").isEmpty())
        assertTrue(parsePageRects("0,0,100").isEmpty())
        assertTrue(parsePageRects("0,0,0,200").isEmpty())
        assertTrue(parsePageRects("x,0,1,2").isEmpty())
        assertTrue(parsePageRects("0,0,1,2;3,4").isEmpty())
    }

    @Test
    fun onlyThePinnedRgbaTileModeIsRenderable() {
        assertEquals(OfficeTilePixels.CopyStrategy.RAW, OfficeTilePixels.copyStrategy("rgba"))
        assertEquals(OfficeTilePixels.CopyStrategy.RAW, OfficeTilePixels.copyStrategy("RGBA"))
        for (mode in listOf(null, "", " ", " rgba", "rgba ", "bgra", "bgr", "argb", "rgbx", "rgba32")) {
            assertNull(mode, OfficeTilePixels.copyStrategy(mode))
        }
        assertEquals(4, OfficeTilePixels.BYTES_PER_PIXEL)
        assertEquals(
            100 * 50 * OfficeTilePixels.BYTES_PER_PIXEL,
            OfficeTileRequest(100, 50, 0, 0, 3840, 3840).bufferBytes,
        )
    }

    @Test
    fun aDocumentWithTooManyPagesIsRefused() {
        val count = OfficeTileLimits.MAX_PAGE_RECTS + 1
        val raw = StringBuilder(count * 8)
        repeat(count) { raw.append("0,0,1,1\n") }
        assertTrue(parsePageRects(raw.toString()).isEmpty())
    }
}
