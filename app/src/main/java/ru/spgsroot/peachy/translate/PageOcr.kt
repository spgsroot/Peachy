package ru.spgsroot.peachy.translate

/** A rectangle on a page as fractions of the page: every value is in 0..1, left < right, top < bottom. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One piece of text found on a page by the on-device reader, with where it sits. */
data class OcrBlock(val text: String, val box: Box)

/**
 * Reads the text on a page on the device, without sending anything anywhere.
 * [source] is `auto` or an ISO 639-1 code (`ja`, `zh`, `ko`, `en`, ...). Blocks use geometric
 * reading order: ja/zh and mostly-vertical auto pages use right-to-left columns within rows;
 * other pages read rows left-to-right. A page without text gives an empty list.
 */
interface PageOcr {
    /** Throws [java.io.IOException] when the page cannot be decoded and [kotlinx.coroutines.CancellationException] when cancelled. */
    suspend fun recognize(image: ByteArray, source: String): List<OcrBlock>
}
