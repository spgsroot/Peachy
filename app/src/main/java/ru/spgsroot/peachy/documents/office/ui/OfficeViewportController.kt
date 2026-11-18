package ru.spgsroot.peachy.documents.office.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import ru.spgsroot.peachy.documents.office.engine.OfficeDocumentInfo

/**
 * Compose-facing viewport state: zoom plus pan in document pixels. Kept outside the ViewModel so a
 * drag does not push a new UI state through the engine; only the settled tile request reaches it.
 */
@Stable
class OfficeViewportController(val info: OfficeDocumentInfo) {

    var zoom by mutableFloatStateOf(1f)
        private set

    var offsetX by mutableDoubleStateOf(0.0)
        private set

    var offsetY by mutableDoubleStateOf(0.0)
        private set

    var viewportWidth by mutableIntStateOf(0)
        private set

    var viewportHeight by mutableIntStateOf(0)
        private set

    val fitPage: Boolean get() = info.family.fitsPage

    fun setViewport(width: Int, height: Int) {
        if (width == viewportWidth && height == viewportHeight) return
        val first = viewportWidth <= 0 || viewportHeight <= 0
        viewportWidth = width
        viewportHeight = height
        if (first) fit() else clamp()
    }

    fun fit() {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        zoom = OfficeViewportMath.fitZoom(
            info.widthTwips,
            info.heightTwips,
            viewportWidth,
            viewportHeight,
            fitPage,
        )
        offsetX = OfficeViewportMath.clampOffset(
            0.0,
            OfficeViewportMath.contentWidthPx(info.widthTwips, zoom),
            viewportWidth,
        )
        offsetY = OfficeViewportMath.clampOffset(
            0.0,
            OfficeViewportMath.contentHeightPx(info.heightTwips, zoom),
            viewportHeight,
        )
    }

    fun panBy(dx: Double, dy: Double) {
        offsetX = OfficeViewportMath.clampOffset(
            offsetX + dx,
            OfficeViewportMath.contentWidthPx(info.widthTwips, zoom),
            viewportWidth,
        )
        offsetY = OfficeViewportMath.clampOffset(
            offsetY + dy,
            OfficeViewportMath.contentHeightPx(info.heightTwips, zoom),
            viewportHeight,
        )
    }

    fun zoomBy(factor: Float, anchorX: Float, anchorY: Float) {
        val next = (zoom * factor).coerceIn(OfficeViewportLimits.MIN_ZOOM, OfficeViewportLimits.MAX_ZOOM)
        if (next == zoom) return
        val nextWidth = OfficeViewportMath.contentWidthPx(info.widthTwips, next)
        val nextHeight = OfficeViewportMath.contentHeightPx(info.heightTwips, next)
        offsetX = OfficeViewportMath.clampOffset(
            OfficeViewportMath.rezoomOffset(offsetX, anchorX, zoom, next),
            nextWidth,
            viewportWidth,
        )
        offsetY = OfficeViewportMath.clampOffset(
            OfficeViewportMath.rezoomOffset(offsetY, anchorY, zoom, next),
            nextHeight,
            viewportHeight,
        )
        zoom = next
    }

    fun jumpToPage(index: Int) {
        val page = info.pageRects.getOrNull(index) ?: return
        offsetY = OfficeViewportMath.clampOffset(
            OfficeViewportMath.offsetForPage(page, zoom),
            OfficeViewportMath.contentHeightPx(info.heightTwips, zoom),
            viewportHeight,
        )
    }

    val currentPage: Int
        get() = OfficeViewportMath.pageAt(info.pageRects, offsetY, viewportHeight, zoom)

    val tile: OfficeViewportTile?
        get() = OfficeViewportMath.tile(
            widthTwips = info.widthTwips,
            heightTwips = info.heightTwips,
            zoom = zoom,
            offsetXPx = offsetX,
            offsetYPx = offsetY,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )

    /** Document coordinate for a viewport-relative touch, or null outside the document. */
    fun twipsAt(xPx: Float, yPx: Float): Pair<Int, Int>? {
        val ppt = OfficeViewportMath.pixelsPerTwip(zoom)
        val x = (offsetX + xPx) / ppt
        val y = (offsetY + yPx) / ppt
        if (!x.isFinite() || !y.isFinite()) return null
        if (x < 0.0 || y < 0.0) return null
        if (x >= info.widthTwips || y >= info.heightTwips) return null
        return x.toInt() to y.toInt()
    }

    private fun clamp() {
        offsetX = OfficeViewportMath.clampOffset(
            offsetX,
            OfficeViewportMath.contentWidthPx(info.widthTwips, zoom),
            viewportWidth,
        )
        offsetY = OfficeViewportMath.clampOffset(
            offsetY,
            OfficeViewportMath.contentHeightPx(info.heightTwips, zoom),
            viewportHeight,
        )
    }
}
