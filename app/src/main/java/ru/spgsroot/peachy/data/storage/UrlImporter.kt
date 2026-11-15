package ru.spgsroot.peachy.data.storage

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import ru.spgsroot.peachy.data.downloader.ExternalDownloader
import ru.spgsroot.peachy.data.downloader.completeDownloadedVideo
import ru.spgsroot.peachy.data.hosts.HostEntry
import ru.spgsroot.peachy.data.hosts.HostImporter

/**
 * Turns a link into library videos: a direct file link is downloaded as it is, a link of a supported
 * file host is expanded to every video it points to. Shared by the player dialogs and by links that
 * other apps hand to Peachy.
 */
@Singleton
class UrlImporter @Inject constructor(
    private val hostImporter: HostImporter,
    private val externalDownloader: ExternalDownloader,
    private val videoImporter: VideoImporter
) {
    class Item(val label: String, val run: suspend () -> Result<Int>)

    /** Never throws: a host link that cannot be read becomes one failing item, so batches carry on. */
    fun itemsFor(url: String, copy: ((File) -> Unit)? = null): List<Item> {
        if (!hostImporter.recognizes(url)) {
            return listOf(Item(url) { videoImporter.importUrlWithCopy(url, copy = copy) })
        }
        val entries = try {
            hostImporter.expand(url)
        } catch (e: Exception) {
            return listOf(Item(url) { Result.failure(e) })
        }
        return entries.map { entry -> Item(entry.label) { importHostEntry(entry, copy) } }
    }

    /** Success when at least one video was saved; otherwise the first failure, so a duplicate stays recognisable. */
    suspend fun importLink(url: String): Result<Unit> {
        val results = itemsFor(url).map { it.run() }
        return results.firstOrNull { it.isSuccess }?.map { }
            ?: Result.failure(results.firstOrNull()?.exceptionOrNull() ?: IllegalStateException("Nothing to import"))
    }

    private suspend fun importHostEntry(entry: HostEntry, copy: ((File) -> Unit)?): Result<Int> {
        val link = try {
            hostImporter.resolve(entry)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return externalDownloader.download(
            url = link.url,
            fileName = "${System.currentTimeMillis()}.${link.name.substringAfterLast('.', "mp4").lowercase()}",
            headers = link.headers
        ).fold(
            onSuccess = { file ->
                completeDownloadedVideo(file,
                    save = { videoImporter.persist(file, title = link.name.substringBeforeLast('.')) },
                    copy = copy)
            },
            onFailure = { Result.failure(it) }
        )
    }
}
