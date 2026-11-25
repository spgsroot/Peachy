package ru.spgsroot.peachy.data.vault

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.spgsroot.peachy.data.db.AllowlistDao
import ru.spgsroot.peachy.data.db.AllowlistEntity
import ru.spgsroot.peachy.data.db.AppDatabase
import ru.spgsroot.peachy.data.db.BookmarkDao
import ru.spgsroot.peachy.data.db.BrowserHistoryDao
import ru.spgsroot.peachy.data.db.LibraryGalleryEntity
import ru.spgsroot.peachy.data.db.GalleryMemoryDao
import ru.spgsroot.peachy.data.db.LibraryGalleryDao
import ru.spgsroot.peachy.data.db.MangaReadMarkEntity
import ru.spgsroot.peachy.data.db.PageTranslationDao
import ru.spgsroot.peachy.data.db.PageTranslationEntity
import ru.spgsroot.peachy.data.db.PhotoEntity
import ru.spgsroot.peachy.data.db.ReadHistoryDao
import ru.spgsroot.peachy.data.db.SearchHistoryDao
import ru.spgsroot.peachy.data.db.TagBlacklistDao
import ru.spgsroot.peachy.data.db.VideoDao
import ru.spgsroot.peachy.data.db.VideoEntity
import ru.spgsroot.peachy.data.manga.MangaFiles
import ru.spgsroot.peachy.data.manga.TagPreferences
import ru.spgsroot.peachy.data.notes.NoteDao
import ru.spgsroot.peachy.data.notes.NoteKind
import ru.spgsroot.peachy.data.notes.NoteRepository
import ru.spgsroot.peachy.data.storage.InternalStorageManager
import ru.spgsroot.peachy.data.storage.PhotoStorageManager
import ru.spgsroot.peachy.manga.TagLibrary
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

data class VaultExportResult(val videos: Int, val galleries: Int, val notes: Int, val photos: Int = 0)

data class VaultImportResult(
    val videosAdded: Int,
    val videosSkipped: Int,
    val galleries: Int,
    val notes: Int,
    val photosAdded: Int = 0,
    val photosSkipped: Int = 0
)

/**
 * Writes the user's whole library to a password-protected `.peachy` file and merges one back in:
 * videos and photos with thumbnails, saved galleries with their pages, read and search history, tag lists and
 * blacklist, notes, bookmarks, browser history and blocker exceptions. The PIN, the device address
 * and playback settings stay on the device.
 */
@Singleton
class VaultManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val videoDao: VideoDao,
    private val libraryDao: LibraryGalleryDao,
    private val readHistoryDao: ReadHistoryDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val tagBlacklistDao: TagBlacklistDao,
    private val memoryDao: GalleryMemoryDao,
    private val noteDao: NoteDao,
    private val bookmarkDao: BookmarkDao,
    private val browserHistoryDao: BrowserHistoryDao,
    private val allowlistDao: AllowlistDao,
    private val tagPreferences: TagPreferences,
    private val pageTranslationDao: PageTranslationDao,
    private val storage: InternalStorageManager,
    private val mangaFiles: MangaFiles,
    private val photoStorage: PhotoStorageManager
) {
    /**
     * Writes the vault to [target], a document the user created. [onProgress] gets (done, total)
     * file entries. A failed SAF write may remain incomplete, but is never mistaken for a valid import.
     */
    suspend fun export(
        target: Uri,
        password: CharArray,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): VaultExportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val operationContext = currentCoroutineContext()
        try {
            val (videoEntities, photoEntities, albums) = database.withTransaction {
                Triple(videoDao.getAllNow(), database.photoDao().getAllNow(), database.albumDao().getAllNow())
            }
            val videos = videoEntities
                .mapNotNull { entity -> File(entity.filePath).takeIf { it.isFile }?.let { entity to it } }
            val thumbnails = videos.associate { (entity, _) ->
                entity.id to entity.thumbnailPath?.let(::File)?.takeIf { it.isFile }
            }
            val photos = photoEntities
                .mapNotNull { entity -> File(entity.filePath).takeIf { it.isFile }?.let { entity to it } }
            val photoThumbnails = photos.associate { (entity, _) ->
                entity.id to entity.thumbnailPath?.let(::File)?.takeIf { it.isFile }
            }
            val galleries = libraryDao.getAll()
            val pages = galleries.associate { gallery ->
                gallery.id to mangaEntryNames(gallery).map { entryName ->
                    val file = mangaFiles.pageFile(gallery.id, entryName.substringAfterLast('/'))
                        ?.takeIf { it.isFile }
                        ?: throw IOException("Saved manga entry is missing: $entryName")
                    entryName to file
                }
            }
            val notes = noteDao.getAll()
            val entryCount = videos.size + thumbnails.values.count { it != null } + pages.values.sumOf { it.size } +
                photos.size + photoThumbnails.values.count { it != null }

            val identityDao = database.mangaIdentityDao()
            val mangaIdentity = VaultMangaIdentity(
                works = identityDao.getAllWorks(),
                units = identityDao.getAllUnits(),
                links = identityDao.getAllLinks(),
                rejections = identityDao.getAllRejections(),
                readMarks = identityDao.getAllReadMarks()
            )
            val data = VaultData(
                entryCount = entryCount,
                videos = videos.map { (entity, file) ->
                    VaultVideo(
                        entity.id, entity.title, entity.duration, entity.addedAt, file.length(), entity.isCorrupted,
                        extensionOf(file), thumbnails[entity.id] != null, entity.albumId
                    )
                },
                galleries = galleries,
                readHistory = readHistoryDao.getAll(),
                searches = searchHistoryDao.getAll(),
                blacklist = tagBlacklistDao.getAll(),
                memory = memoryDao.getAll(),
                notes = notes,
                bookmarks = bookmarkDao.getAll(),
                browserHistory = browserHistoryDao.getAll(),
                allowlist = allowlistDao.getHosts(),
                tagLibrary = tagPreferences.snapshot().toJson(),
                translations = pageTranslationDao.getAll().map {
                    VaultTranslation(it.galleryId, it.page, it.lang, it.model, it.json, it.createdAt)
                },
                photos = photos.map { (entity, file) ->
                    VaultPhoto(
                        entity.id, entity.title, entity.addedAt, file.length(), entity.width, entity.height,
                        photoExtensionOf(file), photoThumbnails[entity.id] != null, entity.albumId
                    )
                },
                albums = albums,
                mangaIdentity = mangaIdentity
            )

            val raw = resolver.openOutputStream(target, "wt") ?: throw IOException("Could not open the file")
            VaultArchiveWriter(VaultCrypto.encrypt(BufferedOutputStream(raw), password)).use { writer ->
                writer.add(DATA_ENTRY, data.toJson().toByteArray(Charsets.UTF_8))
                var done = 0
                fun step() {
                    onProgress(++done, entryCount)
                }
                for ((index, pair) in videos.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val (_, file) = pair
                    val video = data.videos[index]
                    file.inputStream().use { writer.add(video.entryName, video.fileSize, it) }
                    step()
                    thumbnails[video.id]?.let { thumb ->
                        thumb.inputStream().use { writer.add(video.thumbnailEntryName, thumb.length(), it) }
                        step()
                    }
                }
                for ((index, pair) in photos.withIndex()) {
                    operationContext.ensureActive()
                    val (_, file) = pair
                    val photo = data.photos[index]
                    file.inputStream().use {
                        writer.add(photo.entryName, photo.fileSize, cancellableInput(it, operationContext))
                    }
                    step()
                    photoThumbnails[photo.id]?.let { thumb ->
                        thumb.inputStream().use {
                            writer.add(photo.thumbnailEntryName, thumb.length(), cancellableInput(it, operationContext))
                        }
                        step()
                    }
                }
                for ((_, files) in pages) {
                    for ((entryName, file) in files) {
                        currentCoroutineContext().ensureActive()
                        file.inputStream().use { writer.add(entryName, file.length(), it) }
                        step()
                    }
                }
                operationContext.ensureActive()
            }
            VaultExportResult(videos.size, galleries.size, notes.size, photos = photos.size)
        } catch (e: Throwable) {
            // CreateDocument can return a pre-existing target. SAF has no general atomic replacement
            // or safe ownership check, so a failed export must not delete that user's document.
            throw e
        }
    }

    /**
     * Merges the vault at [source] into the library. Nothing local is overwritten or removed; a
     * video already present (same title, length and size) is skipped, as is a photo with the same
     * title, width, height and size. Authentication, failure or cancellation rolls back added media.
     */
    suspend fun import(
        source: Uri,
        password: CharArray,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): VaultImportResult = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(source) ?: throw IOException("Could not open the file")
        val createdFiles = mutableListOf<File>()
        val photoDao = database.photoDao()
        val createdPhotoFiles = mutableListOf<String>()
        val createdPhotoThumbnails = mutableListOf<String>()
        var committed = false
        var archivedTags: String? = null
        val operationContext = currentCoroutineContext()
        try {
            // Room rolls back rows together. The caller's Job still controls cancellation until commit.
            val result = withContext(NonCancellable) { database.withTransaction {
                val reader = VaultArchiveReader(VaultCrypto.decrypt(BufferedInputStream(raw), password))
                val first = reader.next()
                if (first == null || first.name != DATA_ENTRY) throw VaultException(VaultError.CORRUPT, "Vault has no data document")
                val data = VaultData.fromJson(String(first.readBytes(MAX_DATA_BYTES), Charsets.UTF_8))

                val videoMeta = data.videos.associateBy { it.id }
                val known = videoDao.getAllNow().associateBy { VaultMerge.videoKey(it.title, it.duration, it.fileSize) }
                val idMap = HashMap<Long, Long>()
                val added = HashMap<Long, VideoEntity>()
                var skipped = 0
                var done = 0
                val remainingPages = data.galleries.flatMap(::mangaEntryNames).toMutableSet()
                val seenEntries = HashSet<String>()
                val seenVideos = HashSet<Long>()
                val seenVideoThumbnails = HashSet<Long>()
                val photoMeta = data.photos.associateBy { it.id }
                val knownPhotos = photoDao.getAllNow().associateBy {
                    VaultMerge.photoKey(it.title, it.width, it.height, it.fileSize)
                }.toMutableMap()
                // Archived IDs only identify entries; new database IDs identify restored thumbnails.
                val addedPhotos = HashMap<Long, PhotoEntity>()
                val seenPhotos = HashSet<Long>()
                val seenPhotoThumbnails = HashSet<Long>()
                var photosSkipped = 0

                while (true) {
                    operationContext.ensureActive()
                    val entry = reader.next() ?: break
                    val name = entry.name
                    if (!seenEntries.add(name)) throw VaultException(VaultError.CORRUPT, "Duplicate vault entry")
                    when {
                        name.startsWith("video/") -> {
                            val meta = videoMeta[videoIdOf(name)]
                            if (meta == null || name != meta.entryName || entry.size != meta.fileSize ||
                                !seenVideos.add(meta.id)
                            ) throw VaultException(VaultError.CORRUPT, "Invalid video entry")
                            val existing = known[VaultMerge.videoKey(meta.title, meta.duration, meta.fileSize)]
                            if (existing != null) {
                                idMap[meta.id] = existing.id
                                skipped++
                            } else {
                                val file = storage.save(
                                    cancellableInput(entry.stream, operationContext),
                                    "vault_${System.nanoTime()}.${meta.extension}",
                                    totalBytes = entry.size,
                                    maxBytes = Long.MAX_VALUE
                                )
                                createdFiles += file
                                val entity = VideoEntity(
                                    title = meta.title, filePath = file.absolutePath, duration = meta.duration,
                                    thumbnailPath = null, addedAt = meta.addedAt, fileSize = file.length(),
                                    isCorrupted = meta.isCorrupted
                                )
                                val id = videoDao.insert(entity)
                                idMap[meta.id] = id
                                added[meta.id] = entity.copy(id = id)
                            }
                        }
                        name.startsWith("thumb/") -> {
                            val oldId = name.removePrefix("thumb/").toLongOrNull()
                            val meta = videoMeta[oldId]
                            if (meta == null || !meta.hasThumbnail || entry.size <= 0 ||
                                name != meta.thumbnailEntryName || meta.id !in seenVideos ||
                                !seenVideoThumbnails.add(meta.id)
                            ) throw VaultException(VaultError.CORRUPT, "Invalid video thumbnail entry")
                            val entity = added[meta.id]
                            if (entity != null) {
                                val path = storage.saveThumbnail(cancellableInput(entry.stream, operationContext), entity.id)
                                createdFiles += File(path)
                                val updated = entity.copy(thumbnailPath = path)
                                videoDao.update(updated)
                                added[meta.id] = updated
                            }
                        }
                        name.startsWith("photo/") -> {
                            val oldId = name.removePrefix("photo/").substringBefore('.').toLongOrNull()
                            val meta = photoMeta[oldId]
                            if (meta == null || name != meta.entryName || !seenPhotos.add(meta.id) ||
                                entry.size != meta.fileSize
                            ) throw VaultException(VaultError.CORRUPT, "Invalid photo entry")
                            val key = VaultMerge.photoKey(meta.title, meta.width, meta.height, meta.fileSize)
                            if (key in knownPhotos) {
                                photosSkipped++
                            } else {
                                val file = photoStorage.save(
                                    cancellableInput(entry.stream, operationContext), "vault.${meta.extension}",
                                    totalBytes = entry.size
                                )
                                createdPhotoFiles += file.absolutePath
                                val entity = PhotoEntity(
                                    title = meta.title, filePath = file.absolutePath, thumbnailPath = null,
                                    addedAt = meta.addedAt, fileSize = file.length(), width = meta.width, height = meta.height
                                )
                                val restored = entity.copy(id = photoDao.insert(entity))
                                addedPhotos[meta.id] = restored
                                knownPhotos[key] = restored
                                operationContext.ensureActive()
                            }
                        }
                        name.startsWith("photo_thumb/") -> {
                            val oldId = name.removePrefix("photo_thumb/").toLongOrNull()
                            val meta = photoMeta[oldId]
                            if (meta == null || name != meta.thumbnailEntryName || !meta.hasThumbnail ||
                                meta.id !in seenPhotos || !seenPhotoThumbnails.add(meta.id)
                                || entry.size <= 0
                            ) throw VaultException(VaultError.CORRUPT, "Invalid photo thumbnail entry")
                            // A skipped duplicate keeps its existing thumbnail, including a missing one.
                            val entity = addedPhotos[meta.id]
                            if (entity != null) {
                                val path = photoStorage.saveThumbnail(
                                    cancellableInput(entry.stream, operationContext), entity.id
                                )
                                createdPhotoThumbnails += path
                                val updated = entity.copy(thumbnailPath = path)
                                photoDao.update(updated)
                                addedPhotos[meta.id] = updated
                            }
                        }
                        name.startsWith("manga/") -> {
                            if (remainingPages.remove(name)) {
                                if (entry.size <= 0) throw VaultException(VaultError.CORRUPT, "Empty manga page")
                                val galleryId = name.substringAfter('/').substringBefore('/').toInt()
                                mangaFiles.restore(
                                    galleryId, name.substringAfterLast('/'), cancellableInput(entry.stream, operationContext)
                                )?.let { createdFiles += it }
                            }
                        }
                    }
                    onProgress(++done, data.entryCount)
                }
                operationContext.ensureActive()
                if (remainingPages.isNotEmpty() ||
                    seenVideos.size != videoMeta.size || seenVideoThumbnails.size != data.videos.count { it.hasThumbnail } ||
                    seenPhotos.size != photoMeta.size || seenPhotoThumbnails.size != data.photos.count { it.hasThumbnail }
                ) throw VaultException(VaultError.CORRUPT, "Vault entries do not match its metadata")

                // The stream has been authenticated to its end; only now touch the rest of the database.
                applyData(data, idMap, added.mapValues { it.value.id }, addedPhotos.mapValues { it.value.id })
                archivedTags = data.tagLibrary
                operationContext.ensureActive()
                VaultImportResult(
                    videosAdded = added.size,
                    videosSkipped = skipped,
                    galleries = data.galleries.size,
                    notes = data.notes.size,
                    photosAdded = addedPhotos.size,
                    photosSkipped = photosSkipped
                )
            }.also { committed = true } }
            archivedTags?.let { rawTags ->
                val incoming = TagLibrary.fromJson(rawTags)
                tagPreferences.update { VaultMerge.tagLibrary(it, incoming) }
            }
            result
        } catch (e: Throwable) {
            if (committed) throw e
            withContext(NonCancellable) {
                createdPhotoThumbnails.forEach { path ->
                    runCatching {
                        if (!photoStorage.deleteThumbnail(path)) throw IOException("Could not roll back photo thumbnail")
                    }.exceptionOrNull()?.let(e::addSuppressed)
                }
                createdPhotoFiles.forEach { path ->
                    runCatching {
                        if (!photoStorage.deleteFile(path)) throw IOException("Could not roll back photo")
                    }.exceptionOrNull()?.let(e::addSuppressed)
                }
                createdFiles.forEach { file ->
                    runCatching {
                        if (!file.delete() && file.exists()) throw IOException("Could not roll back vault file")
                    }.exceptionOrNull()?.let(e::addSuppressed)
                }
            }
            throw e
        }
        finally { raw.close() }
    }

    private suspend fun applyData(data: VaultData, idMap: Map<Long, Long>, newVideos: Map<Long, Long>, newPhotos: Map<Long, Long>) {
        val notes = data.notes
            .filter { it.kind in NoteKind.ALL }
            .map { it.copy(text = it.text.take(NoteRepository.MAX_LENGTH)) }
            .mapNotNull { note ->
                if (note.kind != NoteKind.VIDEO) return@mapNotNull note
                val newId = note.ref.toLongOrNull()?.let(idMap::get) ?: return@mapNotNull null
                note.copy(ref = newId.toString())
            }

        database.withTransaction {
            val albumDao = database.albumDao()
            val localAlbums = albumDao.getAllNow().groupBy { it.name to it.createdAt }
                .mapValues { (_, albums) -> ArrayDeque(albums) }
            val albumIds = data.albums.associate { incoming ->
                val local = localAlbums[incoming.name to incoming.createdAt]?.removeFirstOrNull()
                    ?: incoming.copy(id = albumDao.insert(incoming.copy(id = 0)))
                incoming.id to local.id
            }
            for (video in data.videos) {
                val newId = newVideos[video.id] ?: continue
                video.albumId?.let { albumDao.moveVideos(listOf(newId), albumIds.getValue(it)) }
            }
            for (photo in data.photos) {
                val newId = newPhotos[photo.id] ?: continue
                photo.albumId?.let { albumDao.movePhotos(listOf(newId), albumIds.getValue(it)) }
            }
            val haveGalleries = libraryDao.getAll().associateBy { it.id }
            for (gallery in data.galleries) {
                val have = haveGalleries[gallery.id]
                if (have == null) libraryDao.upsert(gallery)
                else if (gallery.favorite && !have.favorite) libraryDao.upsert(have.copy(favorite = true))
            }
            VaultMerge.readHistoryToWrite(readHistoryDao.getAll(), data.readHistory).forEach { readHistoryDao.upsert(it) }
            val haveMemory = memoryDao.getAll().associateBy { it.galleryId }
            for (row in data.memory) {
                val existing = haveMemory[row.galleryId]
                if (existing == null) {
                    memoryDao.upsert(row)
                } else if (existing.japaneseTitle.isNullOrBlank() && !row.japaneseTitle.isNullOrBlank()) {
                    memoryDao.upsert(existing.copy(japaneseTitle = row.japaneseTitle))
                }
            }

            val searches = VaultMerge.searches(searchHistoryDao.getAll(), data.searches)
            searchHistoryDao.clear()
            searchHistoryDao.insertAll(searches)
            data.blacklist.forEach { tagBlacklistDao.upsert(it) }

            val current = noteDao.getAll()
            val merged = VaultMerge.notes(current, notes)
            val changed = merged.filter { it !in current }
            if (changed.isNotEmpty()) noteDao.upsertAll(changed)

            val haveBookmarks = bookmarkDao.getAll().map { it.url }.toSet()
            bookmarkDao.upsertAll(data.bookmarks.filter { it.url !in haveBookmarks })
            val haveVisits = browserHistoryDao.getAll().map { it.url }.toSet()
            browserHistoryDao.upsertAll(data.browserHistory.filter { it.url !in haveVisits })
            browserHistoryDao.trim(BrowserHistoryDao.MAX_ROWS)
            data.allowlist.forEach { allowlistDao.add(AllowlistEntity(it)) }

            val haveTranslations = pageTranslationDao.getAll()
            val incoming = data.translations.map { PageTranslationEntity(it.galleryId, it.page, it.lang, it.model, it.json, it.createdAt) }
            val toWrite = VaultMerge.translations(haveTranslations, incoming)
            if (toWrite.isNotEmpty()) pageTranslationDao.upsertAll(toWrite)

            val identityDao = database.mangaIdentityDao()
            val currentIdentity = VaultMangaIdentity(
                works = identityDao.getAllWorks(),
                units = identityDao.getAllUnits(),
                links = identityDao.getAllLinks(),
                rejections = identityDao.getAllRejections(),
                readMarks = identityDao.getAllReadMarks()
            )
            val incomingIdentity = if (data.sourceFormat < 4 && data.mangaIdentity.readMarks.isEmpty()) {
                val legacyMarks = data.readHistory
                    .filter { it.page == -1 && it.pageCount == 0 }
                    .map { MangaReadMarkEntity(it.galleryId, it.readAt) }
                data.mangaIdentity.copy(readMarks = legacyMarks)
            } else {
                data.mangaIdentity
            }
            val identityToWrite = VaultMerge.mangaIdentityToWrite(currentIdentity, incomingIdentity)
            if (identityToWrite.works.isNotEmpty()) identityDao.upsertWorks(identityToWrite.works)
            if (identityToWrite.units.isNotEmpty()) identityDao.upsertUnits(identityToWrite.units)
            if (identityToWrite.links.isNotEmpty()) identityDao.upsertLinks(identityToWrite.links)
            if (identityToWrite.rejections.isNotEmpty()) identityDao.upsertRejections(identityToWrite.rejections)
            if (identityToWrite.readMarks.isNotEmpty()) identityDao.upsertReadMarks(identityToWrite.readMarks)
        }
    }

    private fun mangaEntryNames(gallery: LibraryGalleryEntity): List<String> =
        gallery.pagePaths.lineSequence().filter(String::isNotBlank).map { path ->
            val file = mangaFiles.pageFile(gallery.id, path)
                ?: throw VaultException(VaultError.CORRUPT, "Invalid manga page name")
            "manga/${gallery.id}/${file.name}"
        }.distinct().toList()

    private fun videoIdOf(entryName: String): Long? =
        entryName.removePrefix("video/").substringBefore('.').toLongOrNull()

    private fun extensionOf(file: File): String =
        file.extension.lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "mp4" }

    private fun photoExtensionOf(file: File): String =
        file.extension.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(10).ifEmpty { "img" }

    /** A synchronous storage copy must observe coroutine cancellation between reads, not just entries. */
    private fun cancellableInput(source: InputStream, operationContext: CoroutineContext): InputStream =
        object : FilterInputStream(source) {
            override fun read(): Int {
                operationContext.ensureActive()
                return `in`.read()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                operationContext.ensureActive()
                return `in`.read(buffer, offset, length)
            }
        }

    companion object {
        const val DATA_ENTRY = "data.json"
        const val FILE_EXTENSION = "peachy"
        private const val MAX_DATA_BYTES = 64 * 1024 * 1024
    }
}
