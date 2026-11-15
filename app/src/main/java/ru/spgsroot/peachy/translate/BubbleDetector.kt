package ru.spgsroot.peachy.translate

/** Local text regions in page fractions; null means unavailable/failed, empty means no text. */
interface BubbleDetector {
    suspend fun detect(image: ByteArray): List<Region>?

    /** Drops idle native resources; never interrupts a detection that owns them. */
    fun release()
}
