package ru.spgsroot.peachy.data.manga

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.spgsroot.peachy.manga.ReaderOffsetCodec
import ru.spgsroot.peachy.manga.ReaderPosition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the in-page reading offset that the read-history table does not carry. Only a page number and
 * a fraction of the page height per gallery are stored in app-private preferences: no page contents, no
 * account data and no URLs. The page itself stays in the read-history table, which owns the durable progress.
 */
@Singleton
class ReaderPositionStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("manga_progress", Context.MODE_PRIVATE)

    fun load(galleryId: Int): ReaderPosition? =
        ReaderOffsetCodec.decode(prefs.getString(key(galleryId), null))

    fun save(galleryId: Int, position: ReaderPosition) {
        prefs.edit().putString(key(galleryId), ReaderOffsetCodec.encode(position)).apply()
    }

    private fun key(galleryId: Int) = "gallery_$galleryId"
}
