package ru.spgsroot.peachy.data.manga

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaFiles @Inject constructor(
    @ApplicationContext context: Context
) {
    private val files = MangaFileStore(File(context.filesDir, "manga"))

    fun pageFile(galleryId: Int, remotePath: String): File? = files.pageFile(galleryId, remotePath)

    fun save(galleryId: Int, remotePath: String, bytes: ByteArray): File? = files.save(galleryId, remotePath, bytes)

    /** Every file saved for the gallery, for the vault export. */
    fun listFiles(galleryId: Int): List<File> = files.listFiles(galleryId)

    /** Writes one page from [input] unless the file is already there; null when it is, or [name] is not a plain file name. */
    fun restore(galleryId: Int, name: String, input: InputStream): File? = files.restore(galleryId, name, input)

    fun deleteGallery(galleryId: Int) = files.deleteGallery(galleryId)
}

/** Filesystem-only manga storage, with no partially written page visible at its final path. */
internal class MangaFileStore(private val root: File) {
    fun pageFile(galleryId: Int, remotePath: String): File? {
        val name = remotePath.trim().substringAfterLast('/')
        if (name.isBlank() || name.contains("..") || name.contains('/') || name.contains('\\')) return null
        return File(File(root, galleryId.toString()), name)
    }

    fun save(galleryId: Int, remotePath: String, bytes: ByteArray): File? {
        val file = pageFile(galleryId, remotePath) ?: return null
        return writeAtomically(file) { it.writeBytes(bytes) }
    }

    fun listFiles(galleryId: Int): List<File> =
        File(root, galleryId.toString()).listFiles()?.filter { it.isFile }.orEmpty()

    fun restore(galleryId: Int, name: String, input: InputStream): File? {
        val file = pageFile(galleryId, name) ?: return null
        if (file.exists()) return null
        return writeAtomically(file) { part -> part.outputStream().use { input.copyTo(it) } }
    }

    fun deleteGallery(galleryId: Int) {
        File(root, galleryId.toString()).deleteRecursively()
    }

    private fun writeAtomically(file: File, write: (File) -> Unit): File {
        val parent = file.parentFile ?: throw IOException("Manga page has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create manga storage")
        val part = File.createTempFile(".${file.name}.", ".part", parent)
        try {
            write(part)
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return file
        } catch (failure: Throwable) {
            part.delete()
            throw failure
        }
    }
}
