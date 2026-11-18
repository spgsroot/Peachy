package ru.spgsroot.peachy.documents.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Regression for the free-angle raster path on the real platform: [ImageRaster.render] is driven
 * with [ImageOp.RotateFree] over a synthetic asymmetric 128x96 bitmap, so the production matrix,
 * Bitmap filtering, transparent padding and bitmap ownership run exactly as in the editor.
 *
 * Geometry contract: `ImageGeometry.freeRotateSize` describes the upright box around a rotation
 * about the image centre, therefore the rotated raster has to be centred inside that box. The
 * regression exists because rotation used to pivot around (0,0), which shifted content up-left and
 * clipped the left/bottom edges; at +30 degrees marker m3 fell completely off the canvas and 16.4%
 * of the raster was lost.
 *
 * Expected pixels below are the centred-rotation results for the same raster that ships as
 * `.impeccable/fixtures/image/peachy-image-reference.png` (see `manifest.json`; `check_export.py`
 * holds the matching 158x147 expectation for a device export):
 *   +30 degrees -> 158x147, translate (15, 25.5), marker centres m1 (60,26), m2 (130,66),
 *                  m3 (28,81), m4 (98,121), centre marker (79,74)
 *   -20 degrees -> 153x133, translate (12.5, 18.5), m1 (28,50), m2 (103,23), m3 (50,110),
 *                  m4 (125,83), centre marker (77,67)
 * A +/-2 px search window absorbs sub-pixel rounding without hiding a wrong anchor: the bug
 * displaced markers by 20-40 px.
 */
@RunWith(AndroidJUnit4::class)
class ImageRasterFreeRotationTest {

    @Test
    fun plus30_rotatesIntoTheCentredBoundingBox() {
        val source = referenceBitmap()
        val rendered = ImageRaster.render(source, listOf(ImageOp.RotateFree(30f)))

        assertEquals(158, rendered.width)
        assertEquals(147, rendered.height)
        assertEquals(Bitmap.Config.ARGB_8888, rendered.config)
        assertTrue(rendered.hasAlpha())
        assertNotSame(source, rendered)
    }

    @Test
    fun plus30_keepsEveryMarkerIncludingTheFormerlyClippedOne() {
        val rendered = ImageRaster.render(referenceBitmap(), listOf(ImageOp.RotateFree(30f)))

        rendered.assertMarker("m1 white", Color.WHITE, 60, 26)
        rendered.assertMarker("m2 black", Color.BLACK, 130, 66)
        // Formerly rotated off the canvas at (-4.2, 106.8) with the origin-anchored matrix.
        rendered.assertMarker("m3 white (was clipped)", Color.WHITE, 28, 81)
        rendered.assertMarker("m4 black", Color.BLACK, 98, 121)
        rendered.assertMarker("centre magenta", Color.MAGENTA, 79, 74)
    }

    @Test
    fun plus30_padsTheCornersTransparently() {
        val rendered = ImageRaster.render(referenceBitmap(), listOf(ImageOp.RotateFree(30f)))

        for ((x, y) in corners(rendered)) {
            assertEquals(
                "corner ($x,$y) must stay transparent",
                0, Color.alpha(rendered.getPixel(x, y)),
            )
        }
    }

    @Test
    fun plus30_keepsTheWholeRasterAndCentresIt() {
        val rendered = ImageRaster.render(referenceBitmap(), listOf(ImageOp.RotateFree(30f)))

        val coverage = coverage(rendered)
        assertEquals(
            "x centroid must sit on the canvas centre",
            CENTRE_X, coverage.centroidX, CENTROID_TOLERANCE,
        )
        assertEquals(
            "y centroid must sit on the canvas centre",
            CENTRE_Y, coverage.centroidY, CENTROID_TOLERANCE,
        )
        assertTrue(
            "opaque area ${coverage.pixels} is outside 95-110% of the ${SOURCE_PIXELS} source pixels",
            coverage.pixels in (SOURCE_PIXELS * 0.95).toInt()..(SOURCE_PIXELS * 1.10).toInt(),
        )
    }

    @Test
    fun minus20_isCentredToo() {
        val rendered = ImageRaster.render(referenceBitmap(), listOf(ImageOp.RotateFree(-20f)))

        assertEquals(153, rendered.width)
        assertEquals(133, rendered.height)
        rendered.assertMarker("m2 black", Color.BLACK, 103, 23)
        rendered.assertMarker("m3 white", Color.WHITE, 50, 110)
        rendered.assertMarker("centre magenta", Color.MAGENTA, 77, 67)
        assertEquals("corner (0,0) must stay transparent", 0, Color.alpha(rendered.getPixel(0, 0)))

        val coverage = coverage(rendered)
        assertEquals(MINUS20_CENTRE_X, coverage.centroidX, CENTROID_TOLERANCE)
        assertEquals(MINUS20_CENTRE_Y, coverage.centroidY, CENTROID_TOLERANCE)
        assertTrue(
            "opaque area ${coverage.pixels} is outside 95-110% of the ${SOURCE_PIXELS} source pixels",
            coverage.pixels in (SOURCE_PIXELS * 0.95).toInt()..(SOURCE_PIXELS * 1.10).toInt(),
        )
    }

    @Test
    fun render_leavesTheSourceBitmapIntact() {
        val source = referenceBitmap()
        ImageRaster.render(source, listOf(ImageOp.RotateFree(30f)))

        assertEquals(WIDTH, source.width)
        assertEquals(HEIGHT, source.height)
        assertTrue("source must stay usable after render", !source.isRecycled)
        assertEquals(Color.MAGENTA, source.getPixel(64, 48))
        assertEquals(Color.WHITE, source.getPixel(24, 80))
    }

    // ---- synthetic raster -------------------------------------------------------------------

    private fun referenceBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint().apply { isAntiAlias = false }
        canvas.drawRect(0f, 0f, 64f, 48f, fill.apply { color = Color.RED })
        canvas.drawRect(64f, 0f, 128f, 48f, fill.apply { color = Color.GREEN })
        canvas.drawRect(0f, 48f, 64f, 96f, fill.apply { color = Color.BLUE })
        canvas.drawRect(64f, 48f, 128f, 96f, fill.apply { color = Color.YELLOW })
        // 8x8 squares, four pixels of margin from every quadrant edge: 4:2:0 chroma bleed in a
        // lossy derivative cannot move their centres.
        canvas.drawRect(20f, 12f, 28f, 20f, fill.apply { color = Color.WHITE }) // m1
        canvas.drawRect(100f, 12f, 108f, 20f, fill.apply { color = Color.BLACK }) // m2
        canvas.drawRect(20f, 76f, 28f, 84f, fill.apply { color = Color.WHITE }) // m3
        canvas.drawRect(100f, 76f, 108f, 84f, fill.apply { color = Color.BLACK }) // m4
        canvas.drawRect(60f, 44f, 68f, 52f, fill.apply { color = Color.MAGENTA }) // centre
        return bitmap
    }

    // ---- assertions -------------------------------------------------------------------------

    private fun Bitmap.assertMarker(name: String, expected: Int, x: Int, y: Int) {
        var best = Int.MAX_VALUE
        var bestAt = -1 to -1
        for (dy in -SEARCH..SEARCH) {
            for (dx in -SEARCH..SEARCH) {
                val px = x + dx
                val py = y + dy
                if (px !in 0 until width || py !in 0 until height) continue
                val distance = channelDistance(getPixel(px, py), expected)
                if (distance < best) {
                    best = distance
                    bestAt = px to py
                }
            }
        }
        if (bestAt.first < 0 || bestAt.second < 0) fail("$name: no sampled pixel near ($x,$y)")
        assertTrue(
            "$name: no pixel within ${SEARCH}px of ($x,$y) matched; nearest $bestAt " +
                "is ${describe(getPixel(bestAt.first, bestAt.second))} vs ${describe(expected)}",
            best <= MARKER_TOLERANCE,
        )
    }

    /** Largest per-channel difference, so alpha loss counts as a mismatch too. */
    private fun channelDistance(actual: Int, expected: Int): Int = maxOf(
        abs(Color.alpha(actual) - Color.alpha(expected)),
        abs(Color.red(actual) - Color.red(expected)),
        abs(Color.green(actual) - Color.green(expected)),
        abs(Color.blue(actual) - Color.blue(expected)),
    )

    private fun describe(color: Int): String =
        "argb(${Color.alpha(color)},${Color.red(color)},${Color.green(color)},${Color.blue(color)})"

    private fun corners(bitmap: Bitmap): List<Pair<Int, Int>> = listOf(
        0 to 0,
        bitmap.width - 1 to 0,
        0 to bitmap.height - 1,
        bitmap.width - 1 to bitmap.height - 1,
    )

    private data class Coverage(val pixels: Int, val centroidX: Double, val centroidY: Double)

    private fun coverage(bitmap: Bitmap): Coverage {
        var pixels = 0
        var sumX = 0L
        var sumY = 0L
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width) {
                if (Color.alpha(row[x]) != 0) {
                    pixels++
                    sumX += x
                    sumY += y
                }
            }
        }
        if (pixels == 0) return Coverage(0, -1.0, -1.0)
        return Coverage(pixels, sumX.toDouble() / pixels, sumY.toDouble() / pixels)
    }

    private companion object {
        const val WIDTH = 128
        const val HEIGHT = 96
        const val SOURCE_PIXELS = WIDTH * HEIGHT
        const val CENTRE_X = 158.0 / 2
        const val CENTRE_Y = 147.0 / 2
        const val MINUS20_CENTRE_X = 153.0 / 2
        const val MINUS20_CENTRE_Y = 133.0 / 2
        const val CENTROID_TOLERANCE = 2.5
        const val SEARCH = 2
        const val MARKER_TOLERANCE = 16
    }
}
