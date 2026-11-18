package ru.spgsroot.peachy.data.manga

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.data.db.SavedPageDao
import ru.spgsroot.peachy.data.db.SavedPageEntity
import ru.spgsroot.peachy.manga.GalleryDetail
import ru.spgsroot.peachy.manga.NhentaiCdn
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pages the user keeps one by one. Each has its own copy in private storage, so removing the
 * gallery from the library never takes a saved page with it.
 */
@Singleton
class SavedPagesRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val dao: SavedPageDao,
    private val api: NhentaiApi,
    private val files: MangaFiles
) {
    private val dir = File(context.filesDir, "manga_pages")

    fun observe(): Flow<List<SavedPageEntity>> = dao.observeAll()

    fun fileOf(page: SavedPageEntity): File = File(dir, page.fileName)

    /** Keeps page [page] (0-based) of [detail]; uses the downloaded copy when the gallery is saved. */
    suspend fun save(detail: GalleryDetail, page: Int) {
        withContext(Dispatchers.IO) {
            val path = detail.pagePaths.getOrNull(page) ?: throw IOException("no such page")
            val ext = path.substringAfterLast('.', "jpg").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "jpg" }
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create saved-page storage")
            val previous = dao.get(detail.id, page)
            // A saved page becomes visible through its database row, so a unique complete copy can
            // replace an older one without renaming over it or exposing a partial file on retry.
            val target = File.createTempFile("${detail.id}_${page}_", ".$ext", dir)
            try {
                val local = files.pageFile(detail.id, path)?.takeIf { it.isFile }
                if (local != null) {
                    local.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                } else {
                    val url = NhentaiCdn.page(path) ?: throw IOException("bad page path")
                    target.writeBytes(api.download(url))
                }
                dao.upsert(SavedPageEntity(detail.id, page, detail.title, target.name, System.currentTimeMillis()))
            } catch (failure: Throwable) {
                target.delete()
                throw failure
            }
            previous?.takeIf { it.fileName != target.name }?.let { fileOf(it).delete() }
        }
    }

    suspend fun remove(galleryId: Int, page: Int) {
        withContext(Dispatchers.IO) {
            val row = dao.get(galleryId, page)
            dao.delete(galleryId, page)
            row?.let { fileOf(it).delete() }
        }
    }
}
