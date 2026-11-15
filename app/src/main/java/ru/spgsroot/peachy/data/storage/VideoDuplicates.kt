package ru.spgsroot.peachy.data.storage

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Content identity of a file: the same bytes always give the same value, whatever the name. */
object FileHash {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** The import was refused because the same file is already in the gallery; the message is for the user. */
class DuplicateVideoException(message: String, val existingTitle: String) : IOException(message)

/**
 * A downloaded file held back because the gallery already has it. It stays in [file] until the user
 * either adds it anyway or discards it; the list is memory-only, so it is gone with the process.
 */
data class PendingDuplicate(val id: Long, val title: String, val existingTitle: String, val file: File)

@Singleton
class PendingDuplicates @Inject constructor() {
    private val items = MutableStateFlow<List<PendingDuplicate>>(emptyList())
    val pending: StateFlow<List<PendingDuplicate>> = items.asStateFlow()

    fun add(duplicate: PendingDuplicate) = items.update { it + duplicate }

    /** Removes and returns the entry, or null when it was already resolved. */
    fun take(id: Long): PendingDuplicate? {
        var taken: PendingDuplicate? = null
        items.update { list -> list.also { taken = it.firstOrNull { item -> item.id == id } }.filter { it.id != id } }
        return taken
    }
}
