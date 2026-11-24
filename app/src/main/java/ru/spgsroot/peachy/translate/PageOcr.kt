package ru.spgsroot.peachy.translate

/** A rectangle on a page as fractions of the page: every value is in 0..1, left < right, top < bottom. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** [box] always encloses the source text (also used for crops); [interior] is verified blank balloon space. */
data class OcrBlock(val text: String, val box: Box, val interior: Box? = null) {
    internal fun translated(kind: LineKind, translation: String) =
        TranslatedLine(kind, text, translation, if (kind.inBubble) interior ?: box else box)
}

/**
 * Reads the text on a page on the device, without sending anything anywhere.
 * [source] is `auto` or an ISO 639-1 code (`ja`, `zh`, `ko`, `en`, ...). Blocks use geometric
 * reading order: ja/zh and mostly-vertical auto pages use right-to-left columns within rows;
 * other pages read rows left-to-right. A page without text gives an empty list.
 *
 * Tall strips are read as overlapping full-width tiles; seam duplicates keep the larger box.
 * Boxes and final reading order always refer to the original page, not an individual tile.
 */
interface PageOcr {
    /** Throws [java.io.IOException] when the page cannot be decoded and [kotlinx.coroutines.CancellationException] when cancelled. */
    suspend fun recognize(image: ByteArray, source: String): List<OcrBlock>

    /** Reads 10%-padded, page-clipped regions; boxes use page fractions. Empty input never decodes. */
    suspend fun recognizeRegions(image: ByteArray, source: String, regions: List<Box>): List<OcrBlock>
}
