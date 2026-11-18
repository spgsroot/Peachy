package ru.spgsroot.peachy.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.data.storage.InternalStorageManager
import ru.spgsroot.peachy.data.storage.PhotoImporter
import ru.spgsroot.peachy.data.storage.LinkDownloader
import ru.spgsroot.peachy.data.storage.VideoImporter

/** Activity-scoped: shares are handled after authentication, regardless of the visible destination. */
@HiltViewModel
class ShareImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedState: SavedStateHandle,
    private val photoImporter: PhotoImporter,
    private val videoImporter: VideoImporter,
    private val videoStorage: InternalStorageManager,
    private val linkDownloader: LinkDownloader
) : ViewModel() {
    private val queue = ShareImportQueue(
        initial = savedState.get<ArrayList<String>>("shareUris").orEmpty().mapIndexed { index, uri ->
            SharedStream(uri, savedState.get<ArrayList<String>>("shareTypes")?.getOrNull(index)?.ifEmpty { null })
        },
        initialRejected = savedState.get<Int>("shareRejected") ?: 0
    ) { streams, rejected ->
        savedState["shareUris"] = ArrayList(streams.map { it.uri })
        savedState["shareTypes"] = ArrayList(streams.map { it.type.orEmpty() })
        savedState["shareRejected"] = rejected
    }
    val hasPending = queue.hasPending
    val results = queue.results
    val linkResults = linkDownloader.results

    fun enqueue(intent: Intent?) {
        val streams = ShareIntents.streams(intent).map { SharedStream(it.toString(), intent?.type) }
        val links = ShareIntents.links(intent).map { SharedStream(it, null) }
        queue.enqueue(streams + links, ShareIntents.rejected(intent))
    }

    /** The caller must remain authenticated; cancelling this call keeps the unprocessed queue. */
    suspend fun importPending() = queue.drain { stream ->
        // A link is handed to the app-scoped downloader and leaves the queue at once, so locking
        // the app or leaving it neither cancels the download nor repeats it on the next unlock.
        if (ShareUriPolicy.isLink(stream.uri)) {
            linkDownloader.start(stream.uri)
            return@drain SharedMediaKind.LINK
        }
        val uri = Uri.parse(stream.uri)
        val kind = withContext(Dispatchers.IO) {
            val providerType = try { context.contentResolver.getType(uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            ShareUriPolicy.mediaKind(providerType, stream.type)
        }
        dispatchSharedImport(
            kind,
            { photoImporter.importUri(uri).getOrThrow() },
            { importVideo(uri).getOrThrow() }
        )
    }

    private suspend fun importVideo(uri: Uri): Result<Unit> {
        var saved: File? = null
        var persisted = false
        return try {
            withContext(Dispatchers.IO) {
                val jobContext = currentCoroutineContext()
                var title: String? = null
                val declaredSize = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use -1L
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        title = cursor.getString(nameIndex).substringBeforeLast('.').trim()
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                } ?: -1L
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open shared video")
                val source = object : FilterInputStream(input) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        jobContext.ensureActive()
                        return super.read(bytes, offset, length)
                    }
                }
                val file = source.use { videoStorage.save(it, "${UUID.randomUUID()}.mp4", totalBytes = declaredSize) }
                saved = file
                jobContext.ensureActive()
                withContext(NonCancellable) {
                    videoImporter.persist(file, title = title).also { persisted = it.isSuccess }
                }
            }.also { if (it.isFailure) saved?.let { file -> videoStorage.deleteFile(file.absolutePath) } }
        } catch (cancelled: CancellationException) {
            if (persisted) {
                // Persistence is committed. Finish consuming this item without suspending, rather
                // than deleting a file still referenced by its row or re-importing it after unlock.
                Result.success(Unit)
            } else {
                saved?.let { videoStorage.deleteFile(it.absolutePath) }
                throw cancelled
            }
        } catch (failure: Exception) {
            saved?.let { videoStorage.deleteFile(it.absolutePath) }
            Result.failure(failure)
        }
    }
}
