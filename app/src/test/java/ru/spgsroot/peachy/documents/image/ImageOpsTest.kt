package ru.spgsroot.peachy.documents.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOpsTest {

    // ---- crop geometry -------------------------------------------------------------------

    @Test
    fun aDragIsNormalisedWhateverDirectionItWent() {
        val forward = PxRect.fromDrag(10f, 20f, 90f, 80f, 100, 100)
        val reversed = PxRect.fromDrag(90f, 80f, 10f, 20f, 100, 100)
        assertEquals(PxRect(10, 20, 90, 80), forward)
        assertEquals(forward, reversed)
    }

    @Test
    fun aTapSizedSelectionGrowsToTheMinimumCrop() {
        val rect = PxRect.fromDrag(50f, 50f, 50f, 50f, 100, 100)
        assertTrue(rect.width >= PxRect.MIN_CROP)
        assertTrue(rect.height >= PxRect.MIN_CROP)
        assertTrue(rect.right <= 100 && rect.bottom <= 100)
    }

    @Test
    fun aSelectionIsClampedInsideTheImage() {
        val rect = PxRect.fromDrag(-40f, -40f, 500f, 500f, 100, 60)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(100, rect.right)
        assertEquals(60, rect.bottom)
    }

    @Test
    fun aClampedRectThatCannotKeepTheMinimumIsRejected() {
        assertNull(PxRect(0, 0, 10, 10).clampedTo(10, 10))
        assertNotNull(PxRect(0, 0, 10, 10).clampedTo(100, 100))
    }

    // ---- rotation geometry ---------------------------------------------------------------

    @Test
    fun quarterTurnsSwapTheDimensionsOnlyForOddTurns() {
        assertEquals(200 to 100, ImageGeometry.rotateQuarterSize(100, 200, 1))
        assertEquals(100 to 200, ImageGeometry.rotateQuarterSize(100, 200, 2))
        assertEquals(200 to 100, ImageGeometry.rotateQuarterSize(100, 200, 3))
        assertEquals(100 to 200, ImageGeometry.rotateQuarterSize(100, 200, 4))
    }

    @Test
    fun aFreeRotationUsesTheBoundingBoxOfTheTurnedImage() {
        assertEquals(200 to 100, ImageGeometry.freeRotateSize(100, 200, 90f))
        val diagonal = ImageGeometry.freeRotateSize(100, 100, 45f)
        assertEquals(141, diagonal.first)
        assertEquals(141, diagonal.second)
        assertEquals(100 to 100, ImageGeometry.freeRotateSize(100, 100, 0f))
    }

    @Test
    fun pointsFollowAClockwiseQuarterTurn() {
        // 90° clockwise: the top-left corner becomes the top-right corner of a 100x200 result.
        assertEquals(200f to 0f, ImageGeometry.mapPointAfterQuarterTurns(0f, 0f, 100, 200, 1))
        assertEquals(100f to 200f, ImageGeometry.mapPointAfterQuarterTurns(0f, 0f, 100, 200, 2))
        assertEquals(0f to 100f, ImageGeometry.mapPointAfterQuarterTurns(0f, 0f, 100, 200, 3))
    }

    @Test
    fun degreesWrapIntoMinus180To180() {
        assertEquals(-90f, ImageGeometry.normalizeDegrees(270f))
        assertEquals(90f, ImageGeometry.normalizeDegrees(-270f))
        assertEquals(0f, ImageGeometry.normalizeDegrees(360f))
        assertEquals(45f, ImageGeometry.normalizeDegrees(405f))
    }

    @Test
    fun replayedDimensionsFollowTheOperationOrder() {
        val ops = listOf(
            ImageOp.Crop(PxRect(10, 10, 110, 110)),
            ImageOp.RotateQuarter(1),
            ImageOp.RotateFree(90f),
        )
        assertEquals(100 to 100, ImageGeometry.dimensions(200, 100, ops))
    }

    // ---- history -------------------------------------------------------------------------

    @Test
    fun undoAndRedoWalkTheOperationList() {
        val history = OperationHistory()
        history.commit(ImageOp.RotateQuarter(1))
        history.commit(ImageOp.RotateQuarter(2))

        assertTrue(history.undo())
        assertEquals(listOf<ImageOp>(ImageOp.RotateQuarter(1)), history.ops)
        assertTrue(history.canRedo)

        assertTrue(history.redo())
        assertEquals(2, history.ops.size)

        assertTrue(history.undo())
        history.commit(ImageOp.RotateQuarter(3))
        assertEquals(
            listOf<ImageOp>(ImageOp.RotateQuarter(1), ImageOp.RotateQuarter(3)),
            history.ops
        )
        assertFalse(history.canRedo)
    }

    @Test
    fun anEmptyHistoryHasNothingToUndo() {
        val history = OperationHistory()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertFalse(history.undo())
        assertFalse(history.redo())
    }

    // ---- persistence ---------------------------------------------------------------------

    @Test
    fun theOperationListSurvivesAJsonRoundTrip() {
        val ops = listOf<ImageOp>(
            ImageOp.Crop(PxRect(4, 8, 64, 128)),
            ImageOp.RotateQuarter(3),
            ImageOp.RotateFree(-12.5f),
            ImageOp.Adjust(0.25f, -0.5f, 0.75f),
            ImageOp.Stroke(floatArrayOf(1.5f, 20f, 300f), floatArrayOf(2.5f, 40f, 90f), 12f, 0xFFFF00FF.toInt()),
        )

        val decoded = ImageOpsCodec.decode(ImageOpsCodec.encode(ops))

        assertEquals(ops, decoded)
    }

    @Test
    fun aMalformedHistoryIsRejectedInsteadOfApplied() {
        assertNull(ImageOpsCodec.decode(null))
        assertNull(ImageOpsCodec.decode(""))
        assertNull(ImageOpsCodec.decode("{\"v\":9,\"ops\":[]}"))
        assertNull(ImageOpsCodec.decode("not json"))
        assertNull(ImageOpsCodec.decode("{\"v\":1,\"ops\":[{\"t\":\"magic\"}]}"))
        assertNull(ImageOpsCodec.decode("{\"v\":1,\"ops\":[{\"t\":\"crop\",\"l\":0,\"o\":0,\"r\":1,\"b\":1}]}"))
        assertNull(ImageOpsCodec.decode("{\"v\":1,\"ops\":[{\"t\":\"adj\",\"b\":4,\"c\":0,\"s\":0}]}"))
    }

    @Test
    fun aHistoryWithMoreOperationsThanTheEditorCanProduceIsRejected() {
        val ops = (0 until 201).joinToString(",") { "{\"t\":\"r90\",\"n\":1}" }
        assertNull(ImageOpsCodec.decode("{\"v\":1,\"ops\":[$ops]}"))
        assertNotNull(ImageOpsCodec.decode("{\"v\":1,\"ops\":[{\"t\":\"r90\",\"n\":1}]}"))
    }

    @Test
    fun aStrokeWithAnOddNumberOfCoordinatesIsRejected() {
        assertNull(ImageOpsCodec.decode("{\"v\":1,\"ops\":[{\"t\":\"stroke\",\"w\":4,\"c\":-1,\"p\":[1,2,3]}]}"))
    }

    // ---- colour --------------------------------------------------------------------------

    @Test
    fun theNeutralAdjustmentIsTheIdentityMatrix() {
        val identity = ColorMath.matrix(0f, 0f, 0f)
        val expected = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        expected.indices.forEach { index ->
            assertEquals("cell $index", expected[index], identity[index], 1e-5f)
        }
    }

    @Test
    fun brightnessLiftsTheOffsetAndContrastScalesAroundMidGrey() {
        val brighter = ColorMath.matrix(0.5f, 0f, 0f)
        assertEquals(127.5f, brighter[4], 0.01f)
        assertEquals(127.5f, brighter[9], 0.01f)
        assertEquals(1f, brighter[0], 0.001f)

        val contrast = ColorMath.matrix(0f, 0.5f, 0f)
        assertEquals(1.5f, contrast[0], 0.001f)
        assertEquals(128f * (1f - 1.5f), contrast[4], 0.01f)
    }

    @Test
    fun fullDesaturationMapsEveryChannelThroughTheLuminanceWeights() {
        val grey = ColorMath.matrix(0f, 0f, -1f)
        for (row in 0 until 3) {
            assertEquals(0.213f, grey[row * 5 + 0], 0.001f)
            assertEquals(0.715f, grey[row * 5 + 1], 0.001f)
            assertEquals(0.072f, grey[row * 5 + 2], 0.001f)
            assertEquals(0f, grey[row * 5 + 4], 0.001f)
        }
    }

    @Test
    fun theExportFormatsCarryTheMimeAndExtensionThePickerNeeds() {
        assertEquals("image/png", ImageExportFormat.PNG.mime)
        assertEquals("png", ImageExportFormat.PNG.extension)
        assertEquals("image/jpeg", ImageExportFormat.JPEG.mime)
        assertEquals("jpg", ImageExportFormat.JPEG.extension)
    }
}
