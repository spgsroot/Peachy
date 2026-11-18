package ru.spgsroot.peachy.documents.office.engine

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Static bounds for every value that reaches `paintTile` / `setClientZoom`. They exist so a broken
 * document or a bad UI calculation cannot ask the native renderer for a buffer it will overrun:
 * LOK writes `nCanvasWidth * nCanvasHeight * 4` bytes into the direct buffer (see
 * `include/LibreOfficeKit/LibreOfficeKit.hxx`, `paintTile`) and does not take a length argument.
 */
object OfficeTileLimits {
    /** Upstream Android client's tile buffer size (`android/source/.../LOKitTileProvider.java`). */
    const val TILE_PIXELS = 256

    /** A tile must be at least a few pixels; zero-sized tiles are a caller bug. */
    const val MIN_CANVAS_EDGE = 16
    const val MAX_CANVAS_EDGE = 4096

    /** 4 Mi pixels == 16 MiB RGBA, the largest single tile buffer the bridge will hand to LOK. */
    const val MAX_CANVAS_PIXELS = 4 * 1024 * 1024

    /** Sanity bound for a tile rectangle in twips (1/1440 inch); 1e6 twips ≈ 1.76 km. */
    const val MAX_TILE_TWIPS = 1_000_000

    /** Document size bound in twips; a larger "document" is refused rather than scrolled. */
    const val MAX_DOCUMENT_TWIPS = 100_000_000L

    const val MIN_ZOOM = 0.1
    const val MAX_ZOOM = 8.0

    /** 96 dpi is the upstream Android client's rendering baseline. */
    const val DOC_DPI = 96.0

    const val TWIPS_PER_INCH = 1440.0

    /** Cap on parsed page rectangles so a hostile document cannot grow the UI list unbounded. */
    const val MAX_PAGE_RECTS = 10_000
}

/**
 * Pixel contract between the pinned engine and `Bitmap.Config.ARGB_8888`.
 *
 * The pinned Android build is configured with `--enable-cairo-rgba`: `configure.ac` sets
 * `enable_cairo_rgba=yes` whenever the target OS is Android, which defines `ENABLE_CAIRO_RGBA`, so
 * `doc_getTileMode()` reports `LOK_TILEMODE_RGBA` (`desktop/source/lib/init.cxx`). The headless
 * backend then stores 32-bit pixels as `ScanlineFormat::N32BitTcRgba`
 * (`vcl/headless/svpbmp.cxx`, asserted in `vcl/headless/CairoCommon.cxx`), and the patched cairo
 * maps `CAIRO_FORMAT_ARGB32` to `PIXMAN_a8b8g8r8` (`external/cairo/cairo/cairo.GL_RGBA.patch`), so
 * `paintTile` writes premultiplied bytes `R,G,B,A` in memory order.
 *
 * Android's `ARGB_8888` is Skia's `kN32_SkColorType`, which is `kRGBA_8888_SkColorType` on Android
 * because Skia only defaults `SK_R32_SHIFT` to 16 for Windows builds, and
 * `Bitmap.copyPixelsFromBuffer` is a raw `memcpy` of the bitmap's byte size
 * (`frameworks/base/libs/hwui/jni/Bitmap.cpp`). The buffer is therefore copied verbatim: adding an
 * R/B swizzle here would itself invert red and blue.
 *
 * A build that declares any other mode is refused rather than rendered with guessed channels.
 */
object OfficeTilePixels {
    /** `paintTile` always writes 32 bits per pixel; no length argument accompanies the buffer. */
    const val BYTES_PER_PIXEL = 4

    /** The only tile mode the pinned Android build can produce. */
    const val SUPPORTED_TILE_MODE = "rgba"

    /** The only verified transfer into an Android bitmap: byte-for-byte, no swizzle. */
    enum class CopyStrategy { RAW }

    /** `null` means the declared mode is not the verified path and must not be rendered. */
    fun copyStrategy(tileMode: String?): CopyStrategy? =
        if (tileMode != null && tileMode.equals(SUPPORTED_TILE_MODE, ignoreCase = true)) {
            CopyStrategy.RAW
        } else {
            null
        }
}

enum class OfficeTileViolation {
    CANVAS_EDGE,
    CANVAS_PIXELS,
    NEGATIVE_TILE_ORIGIN,
    NON_POSITIVE_TILE,
    TILE_TOO_LARGE,
}

/**
 * The pixel/twip ratio passed to `Document.setClientZoom`, plus the arithmetic the UI needs to map
 * a touch point to a document coordinate. Derived values are computed every call from the pinned
 * integers, so there is no cached drift between zoom and coordinate mapping.
 */
data class OfficeZoom(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val twipWidth: Int,
    val twipHeight: Int,
) {
    init {
        require(pixelWidth > 0 && pixelHeight > 0) { "zoom pixels must be positive" }
        require(twipWidth > 0 && twipHeight > 0) { "zoom twips must be positive" }
    }

    val pixelsPerTwipX: Double get() = pixelWidth.toDouble() / twipWidth
    val pixelsPerTwipY: Double get() = pixelHeight.toDouble() / twipHeight

    fun twipXForPixel(pixels: Int): Int = (pixels * twipWidth.toDouble() / pixelWidth).roundToInt()

    fun twipYForPixel(pixels: Int): Int = (pixels * twipHeight.toDouble() / pixelHeight).roundToInt()

    fun pixelXForTwip(twips: Int): Double = twips * pixelsPerTwipX

    fun pixelYForTwip(twips: Int): Double = twips * pixelsPerTwipY

    /** Whole pixels needed to show [twips] on the X axis (rounding up, never zero for negatives). */
    fun pixelSpanX(twips: Int): Int = ceil(twips * pixelsPerTwipX).toInt()

    fun pixelSpanY(twips: Int): Int = ceil(twips * pixelsPerTwipY).toInt()

    companion object {
        /**
         * The ratio that renders at [zoom] (1.0 == 100 %) on a [dpi] display. Mirrors the upstream
         * client's `pixelToTwip(TILE_SIZE, dpi) / zoomFactor` and `setClientZoom(TILE_SIZE, ...)`.
         */
        fun forZoom(
            zoom: Double,
            tilePixels: Int = OfficeTileLimits.TILE_PIXELS,
            dpi: Double = OfficeTileLimits.DOC_DPI,
        ): OfficeZoom {
            require(zoom.isFinite() && zoom in OfficeTileLimits.MIN_ZOOM..OfficeTileLimits.MAX_ZOOM) {
                "zoom out of range"
            }
            require(tilePixels in OfficeTileLimits.MIN_CANVAS_EDGE..OfficeTileLimits.MAX_CANVAS_EDGE) {
                "tile pixels out of range"
            }
            require(dpi > 0.0) { "dpi must be positive" }
            val twips = (tilePixels * OfficeTileLimits.TWIPS_PER_INCH / (dpi * zoom)).roundToInt()
            val safe = twips.coerceIn(1, OfficeTileLimits.MAX_TILE_TWIPS)
            return OfficeZoom(tilePixels, tilePixels, safe, safe)
        }
    }
}

/** Pure validation of a tile request. `null` means the request is safe to hand to the engine. */
fun OfficeTileRequest.violation(): OfficeTileViolation? {
    if (canvasWidth < OfficeTileLimits.MIN_CANVAS_EDGE || canvasHeight < OfficeTileLimits.MIN_CANVAS_EDGE) {
        return OfficeTileViolation.CANVAS_EDGE
    }
    if (canvasWidth > OfficeTileLimits.MAX_CANVAS_EDGE || canvasHeight > OfficeTileLimits.MAX_CANVAS_EDGE) {
        return OfficeTileViolation.CANVAS_EDGE
    }
    if (canvasWidth.toLong() * canvasHeight > OfficeTileLimits.MAX_CANVAS_PIXELS) {
        return OfficeTileViolation.CANVAS_PIXELS
    }
    if (tileXTwips < 0 || tileYTwips < 0) return OfficeTileViolation.NEGATIVE_TILE_ORIGIN
    if (tileWidthTwips <= 0 || tileHeightTwips <= 0) return OfficeTileViolation.NON_POSITIVE_TILE
    if (tileWidthTwips > OfficeTileLimits.MAX_TILE_TWIPS || tileHeightTwips > OfficeTileLimits.MAX_TILE_TWIPS) {
        return OfficeTileViolation.TILE_TOO_LARGE
    }
    return null
}

/** Bytes LOK writes into the direct buffer for this request; the renderer allocates exactly this. */
val OfficeTileRequest.bufferBytes: Int
    get() = canvasWidth * canvasHeight * OfficeTilePixels.BYTES_PER_PIXEL

fun documentSizeIsRenderable(widthTwips: Long, heightTwips: Long): Boolean =
    widthTwips in 1..OfficeTileLimits.MAX_DOCUMENT_TWIPS &&
        heightTwips in 1..OfficeTileLimits.MAX_DOCUMENT_TWIPS

/**
 * Parses `Document.getPartPageRectangles()`. The pinned writer emits groups of four integers
 * (`x y width height`, whitespace or comma separated); anything malformed yields an empty list so
 * the UI never receives half a page layout.
 */
fun parsePageRects(raw: String?): List<OfficePageRect> {
    if (raw.isNullOrBlank()) return emptyList()
    val tokens = raw.split(',', ' ', '\t', '\r', '\n').filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size % 4 != 0) return emptyList()
    if (tokens.size / 4 > OfficeTileLimits.MAX_PAGE_RECTS) return emptyList()
    val values = IntArray(tokens.size)
    for (index in tokens.indices) {
        values[index] = tokens[index].toIntOrNull() ?: return emptyList()
    }
    val rects = ArrayList<OfficePageRect>(tokens.size / 4)
    var index = 0
    while (index < values.size) {
        val rect = OfficePageRect(values[index], values[index + 1], values[index + 2], values[index + 3])
        if (rect.width <= 0 || rect.height <= 0) return emptyList()
        rects.add(rect)
        index += 4
    }
    return rects
}
