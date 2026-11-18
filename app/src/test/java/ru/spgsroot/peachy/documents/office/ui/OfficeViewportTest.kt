package ru.spgsroot.peachy.documents.office.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.spgsroot.peachy.documents.office.engine.OfficePageRect
import ru.spgsroot.peachy.documents.office.engine.OfficeTileLimits
import ru.spgsroot.peachy.documents.office.engine.violation

class OfficeViewportTest {

    private val pageWidthTwips = 12_240L // A4 width
    private val pageHeightTwips = 15_840L // A4 height
    private val phoneWidthPx = 1_080
    private val phoneHeightPx = 2_400

    @Test
    fun `fit width keeps the page width in the viewport`() {
        val zoom = OfficeViewportMath.fitZoom(
            pageWidthTwips,
            pageHeightTwips * 4,
            phoneWidthPx,
            phoneHeightPx,
            fitPage = false,
        )
        val contentWidth = OfficeViewportMath.contentWidthPx(pageWidthTwips, zoom)
        assertEquals(phoneWidthPx.toDouble(), contentWidth, 1.5)
        assertTrue(zoom > OfficeViewportLimits.MIN_ZOOM)
    }

    @Test
    fun `fit page keeps both edges inside the viewport`() {
        val zoom = OfficeViewportMath.fitZoom(
            14_400L,
            8_100L,
            phoneWidthPx,
            phoneHeightPx,
            fitPage = true,
        )
        assertTrue(OfficeViewportMath.contentWidthPx(14_400L, zoom) <= phoneWidthPx + 1.0)
        assertTrue(OfficeViewportMath.contentHeightPx(8_100L, zoom) <= phoneHeightPx + 1.0)
    }

    @Test
    fun `pan clamps inside a large document and centres a small one`() {
        assertEquals(0.0, OfficeViewportMath.clampOffset(-50.0, 10_000.0, 1_000), 0.0)
        assertEquals(9_000.0, OfficeViewportMath.clampOffset(20_000.0, 10_000.0, 1_000), 0.0)
        assertEquals(-200.0, OfficeViewportMath.clampOffset(0.0, 600.0, 1_000), 0.0)
    }

    @Test
    fun `zoom keeps the anchored document point fixed`() {
        val offset = OfficeViewportMath.rezoomOffset(400.0, 200f, 1f, 2f)
        assertEquals(1_000.0, offset, 0.001)
    }

    @Test
    fun `viewport tile stays inside the engine limits at every zoom`() {
        val zooms = listOf(
            OfficeViewportLimits.MIN_ZOOM,
            0.25f,
            1f,
            2.5f,
            OfficeViewportLimits.MAX_ZOOM,
        )
        zooms.forEach { zoom ->
            val offsetX = OfficeViewportMath.clampOffset(
                0.0,
                OfficeViewportMath.contentWidthPx(pageWidthTwips, zoom),
                phoneWidthPx,
            )
            val offsetY = OfficeViewportMath.clampOffset(
                0.0,
                OfficeViewportMath.contentHeightPx(pageHeightTwips * 6, zoom),
                phoneHeightPx,
            )
            val tile = OfficeViewportMath.tile(
                widthTwips = pageWidthTwips,
                heightTwips = pageHeightTwips * 6,
                zoom = zoom,
                offsetXPx = offsetX,
                offsetYPx = offsetY,
                viewportWidthPx = phoneWidthPx,
                viewportHeightPx = phoneHeightPx,
            )
            assertNotNull("zoom $zoom", tile)
            val request = tile!!.request
            assertNull("zoom $zoom", request.violation())
            assertTrue(request.tileWidthTwips in 1..OfficeTileLimits.MAX_TILE_TWIPS)
            assertTrue(request.tileHeightTwips in 1..OfficeTileLimits.MAX_TILE_TWIPS)
            assertTrue(
                request.canvasWidth.toLong() * request.canvasHeight <=
                    OfficeViewportLimits.MAX_TILE_PIXELS,
            )
            assertTrue(request.canvasWidth <= OfficeTileLimits.MAX_CANVAS_EDGE)
            assertTrue(request.canvasHeight <= OfficeTileLimits.MAX_CANVAS_EDGE)
        }
    }

    @Test
    fun `a huge document still yields one bounded tile`() {
        val huge = 100_000_000L
        val tile = OfficeViewportMath.tile(
            widthTwips = huge,
            heightTwips = huge,
            zoom = OfficeViewportLimits.MIN_ZOOM,
            offsetXPx = 0.0,
            offsetYPx = 0.0,
            viewportWidthPx = 1_600,
            viewportHeightPx = 2_560,
        )
        assertNotNull(tile)
        assertNull(tile!!.request.violation())
        assertTrue(
            tile.request.canvasWidth.toLong() * tile.request.canvasHeight <=
                OfficeViewportLimits.MAX_TILE_PIXELS,
        )
        assertTrue(tile.request.tileWidthTwips <= OfficeTileLimits.MAX_TILE_TWIPS)
        assertTrue(tile.request.tileHeightTwips <= OfficeTileLimits.MAX_TILE_TWIPS)
    }

    @Test
    fun `a region that cannot fit one tile is refused instead of split`() {
        val tile = OfficeViewportMath.tile(
            widthTwips = 100_000_000L,
            heightTwips = 100_000_000L,
            zoom = OfficeViewportLimits.MIN_ZOOM,
            offsetXPx = 0.0,
            offsetYPx = 0.0,
            viewportWidthPx = 8_192,
            viewportHeightPx = 8_192,
        )
        assertNull(tile)
    }

    @Test
    fun `tiny viewports and empty documents produce no request`() {
        assertNull(
            OfficeViewportMath.tile(
                widthTwips = pageWidthTwips,
                heightTwips = pageHeightTwips,
                zoom = 1f,
                offsetXPx = 0.0,
                offsetYPx = 0.0,
                viewportWidthPx = 8,
                viewportHeightPx = 8,
            ),
        )
        assertNull(
            OfficeViewportMath.tile(
                widthTwips = 0L,
                heightTwips = pageHeightTwips,
                zoom = 1f,
                offsetXPx = 0.0,
                offsetYPx = 0.0,
                viewportWidthPx = phoneWidthPx,
                viewportHeightPx = phoneHeightPx,
            ),
        )
    }

    @Test
    fun `page index follows the viewport centre`() {
        val pages = listOf(
            OfficePageRect(0, 0, 12_240, 15_840),
            OfficePageRect(0, 15_840, 12_240, 15_840),
            OfficePageRect(0, 31_680, 12_240, 15_840),
        )
        assertEquals(0, OfficeViewportMath.pageAt(pages, 0.0, 800, 1f))
        assertEquals(
            1,
            OfficeViewportMath.pageAt(
                pages,
                OfficeViewportMath.offsetForPage(pages[1], 1f),
                800,
                1f,
            ),
        )
        assertEquals(2, OfficeViewportMath.pageAt(pages, 10_000_000.0, 800, 1f))
    }
}
