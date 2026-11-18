package ru.spgsroot.peachy.documents.office.ui

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import ru.spgsroot.peachy.documents.office.engine.OfficeFamily
import ru.spgsroot.peachy.documents.office.engine.OfficePageRect
import ru.spgsroot.peachy.documents.office.engine.OfficeTileLimits
import ru.spgsroot.peachy.documents.office.engine.OfficeTileRequest
import ru.spgsroot.peachy.documents.office.engine.violation

/**
 * Geometry of the single document viewport.
 *
 * The viewport is rendered as one bounded tile: the bitmap is exactly the visible region, capped to
 * [OfficeViewportLimits.MAX_TILE_PIXELS] (≈ 12 MB at ARGB_8888) and to the engine's canvas edge.
 * A grid would need 20+ live bitmaps for the same area on a tall phone; one viewport tile keeps the
 * bitmap count at one and every `paintTile` request inside [OfficeTileLimits].
 *
 * Pure Kotlin/JVM, like the engine policy it builds on, so the bounds are testable without a device.
 */
object OfficeViewportLimits {
    const val MIN_ZOOM = 0.1f
    const val MAX_ZOOM = 8f

    /** 3 000 000 px * 4 B = 12 MB for the one live tile. Engine cap is 4 Mi px (16 MiB). */
    const val MAX_TILE_PIXELS = 3_000_000L

    /** Coalescing delay before a pan/zoom produces a paint request. */
    const val RENDER_SETTLE_MS = 90L
}

/** One bounded paint request plus where its twip rectangle sits on screen. */
data class OfficeViewportTile(
    val request: OfficeTileRequest,
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
)

object OfficeViewportMath {

    /** 96 dpi is the engine's rendering baseline; zoom 1.0 means one inch renders as 96 px. */
    private const val BASE_PIXELS_PER_TWIP = 96.0 / 1440.0

    fun pixelsPerTwip(zoom: Float): Double =
        BASE_PIXELS_PER_TWIP * zoom.coerceIn(OfficeViewportLimits.MIN_ZOOM, OfficeViewportLimits.MAX_ZOOM)

    fun contentWidthPx(widthTwips: Long, zoom: Float): Double = widthTwips * pixelsPerTwip(zoom)

    fun contentHeightPx(heightTwips: Long, zoom: Float): Double = heightTwips * pixelsPerTwip(zoom)

    /**
     * Fit zoom. Writer/Calc fit the width (a tall document keeps a readable line length);
     * presentations fit the whole slide.
     */
    fun fitZoom(
        widthTwips: Long,
        heightTwips: Long,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        fitPage: Boolean,
    ): Float {
        if (widthTwips <= 0L || heightTwips <= 0L) return 1f
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return 1f
        val widthAtOne = widthTwips * BASE_PIXELS_PER_TWIP
        val heightAtOne = heightTwips * BASE_PIXELS_PER_TWIP
        val fitWidth = viewportWidthPx / widthAtOne
        val fitHeight = viewportHeightPx / heightAtOne
        val zoom = if (fitPage) min(fitWidth, fitHeight) else fitWidth
        return zoom.toFloat().coerceIn(OfficeViewportLimits.MIN_ZOOM, OfficeViewportLimits.MAX_ZOOM)
    }

    /**
     * Keeps the pan inside the document. A document smaller than the viewport is centred: the
     * returned offset is negative and drawing subtracts it.
     */
    fun clampOffset(offsetPx: Double, contentPx: Double, viewportPx: Int): Double {
        val max = contentPx - viewportPx
        return if (max <= 0.0) max / 2.0 else offsetPx.coerceIn(0.0, max)
    }

    /** Offset after a zoom change that keeps the document point under [anchorPx] fixed. */
    fun rezoomOffset(offsetPx: Double, anchorPx: Float, oldZoom: Float, newZoom: Float): Double {
        if (oldZoom <= 0f) return offsetPx
        return (offsetPx + anchorPx) * (newZoom.toDouble() / oldZoom) - anchorPx
    }

    /**
     * The visible tile for the current viewport, or `null` when nothing can be painted inside the
     * engine limits (empty viewport, document outside the view, or a region larger than one tile).
     */
    fun tile(
        widthTwips: Long,
        heightTwips: Long,
        zoom: Float,
        offsetXPx: Double,
        offsetYPx: Double,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): OfficeViewportTile? {
        if (widthTwips <= 0L || heightTwips <= 0L) return null
        if (viewportWidthPx < OfficeTileLimits.MIN_CANVAS_EDGE ||
            viewportHeightPx < OfficeTileLimits.MIN_CANVAS_EDGE
        ) {
            return null
        }
        val ppt = pixelsPerTwip(zoom)
        val docWidthPx = widthTwips * ppt
        val docHeightPx = heightTwips * ppt

        val visibleLeft = maxOf(0.0, offsetXPx)
        val visibleTop = maxOf(0.0, offsetYPx)
        val visibleRight = min(docWidthPx, offsetXPx + viewportWidthPx)
        val visibleBottom = min(docHeightPx, offsetYPx + viewportHeightPx)
        if (visibleRight - visibleLeft < 1.0 || visibleBottom - visibleTop < 1.0) return null

        val startXTwips = floor(visibleLeft / ppt).toLong().coerceIn(0L, widthTwips - 1)
        val startYTwips = floor(visibleTop / ppt).toLong().coerceIn(0L, heightTwips - 1)
        val endXTwips = ceil(visibleRight / ppt).toLong().coerceIn(startXTwips + 1, widthTwips)
        val endYTwips = ceil(visibleBottom / ppt).toLong().coerceIn(startYTwips + 1, heightTwips)
        val regionWidthTwips = endXTwips - startXTwips
        val regionHeightTwips = endYTwips - startYTwips
        if (regionWidthTwips > OfficeTileLimits.MAX_TILE_TWIPS ||
            regionHeightTwips > OfficeTileLimits.MAX_TILE_TWIPS
        ) {
            return null
        }

        val widthPx = regionWidthTwips * ppt
        val heightPx = regionHeightTwips * ppt
        val canvas = canvasPixels(widthPx, heightPx) ?: return null
        val request = OfficeTileRequest(
            canvasWidth = canvas.first,
            canvasHeight = canvas.second,
            tileXTwips = startXTwips.toInt(),
            tileYTwips = startYTwips.toInt(),
            tileWidthTwips = regionWidthTwips.toInt(),
            tileHeightTwips = regionHeightTwips.toInt(),
        )
        if (request.violation() != null) return null
        return OfficeViewportTile(
            request = request,
            leftPx = (startXTwips * ppt - offsetXPx).toFloat(),
            topPx = (startYTwips * ppt - offsetYPx).toFloat(),
            widthPx = widthPx.toFloat(),
            heightPx = heightPx.toFloat(),
        )
    }

    /** Page under the viewport centre, used by the Writer page indicator. */
    fun pageAt(
        pageRects: List<OfficePageRect>,
        offsetYPx: Double,
        viewportHeightPx: Int,
        zoom: Float,
    ): Int {
        if (pageRects.isEmpty()) return 0
        val ppt = pixelsPerTwip(zoom)
        val centerTwips = (offsetYPx + viewportHeightPx / 2.0) / ppt
        val index = pageRects.indexOfFirst { centerTwips >= it.y && centerTwips < it.y + it.height }
        return when {
            index >= 0 -> index
            centerTwips < pageRects.first().y -> 0
            else -> pageRects.lastIndex
        }
    }

    /** Top offset that puts [page] at the top of the viewport. */
    fun offsetForPage(page: OfficePageRect, zoom: Float): Double =
        page.y * pixelsPerTwip(zoom)

    private fun canvasPixels(widthPx: Double, heightPx: Double): Pair<Int, Int>? {
        if (!widthPx.isFinite() || !heightPx.isFinite()) return null
        if (widthPx <= 0.0 || heightPx <= 0.0) return null
        var scale = 1.0
        val pixels = widthPx * heightPx
        if (pixels > OfficeViewportLimits.MAX_TILE_PIXELS) {
            scale = sqrt(OfficeViewportLimits.MAX_TILE_PIXELS / pixels)
        }
        val longest = maxOf(widthPx, heightPx) * scale
        if (longest > OfficeTileLimits.MAX_CANVAS_EDGE) {
            scale *= OfficeTileLimits.MAX_CANVAS_EDGE / longest
        }
        var canvasWidth = (widthPx * scale).roundToInt().coerceAtLeast(OfficeTileLimits.MIN_CANVAS_EDGE)
        var canvasHeight = (heightPx * scale).roundToInt().coerceAtLeast(OfficeTileLimits.MIN_CANVAS_EDGE)
        var total = canvasWidth.toLong() * canvasHeight
        if (total > OfficeViewportLimits.MAX_TILE_PIXELS) {
            val shrink = sqrt(OfficeViewportLimits.MAX_TILE_PIXELS.toDouble() / total)
            canvasWidth = (canvasWidth * shrink).toInt().coerceAtLeast(OfficeTileLimits.MIN_CANVAS_EDGE)
            canvasHeight = (canvasHeight * shrink).toInt().coerceAtLeast(OfficeTileLimits.MIN_CANVAS_EDGE)
            total = canvasWidth.toLong() * canvasHeight
        }
        if (canvasWidth > OfficeTileLimits.MAX_CANVAS_EDGE ||
            canvasHeight > OfficeTileLimits.MAX_CANVAS_EDGE
        ) {
            return null
        }
        if (total > OfficeTileLimits.MAX_CANVAS_PIXELS) return null
        return canvasWidth to canvasHeight
    }
}

/** Fit behaviour per family: slides fit the page, text and sheets fit the width. */
val OfficeFamily.fitsPage: Boolean get() = this == OfficeFamily.PRESENTATION
