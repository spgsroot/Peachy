package ru.spgsroot.peachy.manga

import kotlin.math.roundToInt

/**
 * Where the reader reopens: a page and how far into that page the reader is scrolled.
 * [offset] is a share of the page height in every supported paged and continuous layout, so it
 * survives a different screen width.
 */
data class ReaderPosition(val page: Int, val offset: Float = 0f)

/**
 * The page the reader may open on. An explicit page (a deliberate jump from the detail screen or a saved
 * page) wins and starts at the top; otherwise the durable position is resumed, clamped to the pages that
 * still exist. A page that no longer exists keeps the last page but drops the old in-page offset.
 */
fun readerStart(explicitPage: Int?, saved: ReaderPosition?, pageCount: Int): ReaderPosition {
    if (pageCount <= 0) return ReaderPosition(0)
    if (explicitPage != null && explicitPage in 0..pageCount - 1) return ReaderPosition(explicitPage)
    return saved?.let { clampPosition(it, pageCount) } ?: ReaderPosition(0)
}

/** Keeps a position inside the pages that still exist; a clamped page cannot keep another page's offset. */
fun clampPosition(position: ReaderPosition, pageCount: Int): ReaderPosition {
    if (pageCount <= 0) return ReaderPosition(0)
    val page = position.page.coerceIn(0, pageCount - 1)
    return ReaderPosition(page, if (page == position.page) offsetFraction(position.offset) else 0f)
}

/** Keeps a stored offset usable even if it came from an older or broken value. */
fun offsetFraction(value: Float): Float =
    if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

/** Scroll offset in pixels for a page [pageHeight] tall; positive scrolls the page past the viewport top. */
fun offsetPixels(fraction: Float, pageHeight: Int): Int =
    (offsetFraction(fraction) * pageHeight).roundToInt()

/** How far a page at [pageTop] has been scrolled, as a share of [pageHeight]. */
fun pageOffsetFraction(pageTop: Int, pageHeight: Int): Float =
    if (pageHeight <= 0) 0f else offsetFraction(-pageTop.toFloat() / pageHeight)

/** How far a paged page has been scrolled past its top, as a share of the whole page height. */
fun pagedOffsetFraction(scrolled: Int, pageHeight: Int): Float =
    pageOffsetFraction(-scrolled, pageHeight)

/**
 * One paged page's in-page scroll restore. A paged page starts its scroll at zero before the image has
 * been measured; until [applied] is called, samples describe that placeholder, not the reader's spot.
 * A page that had no stored offset is already restored, so a fresh page is free to report zero.
 */
class PagedScrollRestore(startFraction: Float) {
    private val target = offsetFraction(startFraction)

    var restored: Boolean = target <= 0f
        private set

    /** Pixels to scroll a [pageHeight] tall page to, or null while it has no measured height. */
    fun pixels(pageHeight: Int): Int? =
        if (restored || pageHeight <= 0) null else offsetPixels(target, pageHeight)

    /** Marks the stored offset as applied; scroll samples become the reader's position. */
    fun applied() {
        restored = true
    }
}

/** Durable form of [ReaderPosition] for app-private preferences: "page:offset". */
object ReaderOffsetCodec {
    fun encode(position: ReaderPosition): String =
        "${position.page}:${offsetFraction(position.offset)}"

    fun decode(raw: String?): ReaderPosition? {
        val value = raw ?: return null
        val parts = value.split(':', limit = 2)
        if (parts.size != 2) return null
        val page = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val offset = parts[1].toFloatOrNull()?.takeIf { !it.isNaN() && it != Float.POSITIVE_INFINITY && it != Float.NEGATIVE_INFINITY }
            ?: return null
        return ReaderPosition(page, offsetFraction(offset))
    }
}

/**
 * A page the user deliberately tapped in this process. Route arguments survive process death in the
 * navigation back stack, so a restored `?page=` must not override newer durable progress; only a tap
 * made here counts as an explicit page. A fresh mark lasts long enough for the reader to open.
 */
object ReaderPageJump {
    private const val FRESH_MS = 2 * 60 * 1000L
    private var galleryId = 0
    private var page = -1
    private var at = 0L

    @Synchronized
    fun mark(galleryId: Int, page: Int, now: Long = System.currentTimeMillis()) {
        this.galleryId = galleryId
        this.page = page
        at = now
    }

    @Synchronized
    fun isFresh(galleryId: Int, page: Int, now: Long = System.currentTimeMillis()): Boolean =
        this.galleryId == galleryId && this.page == page && now - at in 0..FRESH_MS
}
