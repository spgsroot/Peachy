package ru.spgsroot.peachy.data.browser

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import ru.spgsroot.peachy.browser.BrowserPolicy
import ru.spgsroot.peachy.browser.BrowserUrls

/**
 * Browser session across process death: tab identities, addresses, titles, scroll offsets and the
 * per-tab page history.
 *
 * Deliberately not a `WebView.saveState` snapshot. That bundle restores page state, which includes
 * values typed into forms (potentially an untrusted page's credentials) and is deserialized by the
 * WebView itself on load; this store keeps only what the browser can recreate safely and cheaply.
 * Every restored address is fetched again with a plain GET, never with a replayed form submission.
 *
 * Version 1 and 2 plaintext files are accepted for migration. The app-owned store writes the same
 * bounded version 2 payload sealed with AES-GCM and an Android Keystore key, then atomically
 * replaces the old file. Local tests can use the plaintext constructor to exercise the wire format.
 */
class BrowserSessionStore internal constructor(
    private val file: File,
    private val cipher: BrowserSessionCipher?
) {
    constructor(file: File) : this(file, null)

    data class Tab(
        val id: Int,
        val url: String,
        val title: String,
        val scrollY: Int,
        /** Addresses this tab reached with GET, oldest first; only pages the browser may load again. */
        val history: List<String> = emptyList(),
        /** Address on screen in [history], or -1 for the tab's start page. */
        val historyIndex: Int = -1
    )

    data class Snapshot(val tabs: List<Tab>, val activeId: Int)

    /** Session written by a previous process, or null when there is none or it is unreadable. */
    fun load(): Snapshot? {
        if (!file.isFile) return null
        if (file.length() !in 1L..(MAX_FILE_BYTES + AesGcmBrowserSessionCipher.MAX_OVERHEAD)) return discard()
        return try {
            val stored = file.readBytes()
            val plaintext = ByteBuffer.wrap(stored, 0, minOf(stored.size, 4)).let {
                stored.size >= 4 && it.int == MAGIC
            }
            val bytes = if (cipher != null && !plaintext) cipher.open(stored) else stored
            if (bytes.size.toLong() !in 1L..MAX_FILE_BYTES) return discard()
            val snapshot = decode(bytes) ?: return discard()
            // Migrate readable legacy plaintext immediately. A failed rewrite leaves the valid old
            // file in place and is retried by the next save rather than destroying the session.
            if (cipher != null && plaintext) save(snapshot)
            snapshot
        } catch (_: Exception) {
            // Truncation, authentication failure, an oversized length prefix, or an unknown format.
            discard()
        }
    }

    /** Replaces the stored session, retaining every valid tab that fits the global byte budget. */
    fun save(snapshot: Snapshot) {
        val tabs = snapshot.tabs.asSequence()
            .filter { it.id > 0 && (it.url.isEmpty() || BrowserUrls.isWebUrl(it.url)) }
            .mapNotNull(::normalized)
            .distinctBy { it.id }
            .take(MAX_TABS)
            .toMutableList()
        if (tabs.isEmpty()) {
            file.delete()
            return
        }

        try {
            var bytes = encode(tabs, snapshot.activeId)
            while (bytes.size > MAX_FILE_BYTES && tabs.isNotEmpty()) {
                // Per-tab limits normally make this unnecessary. The global ceiling is still
                // enforced by dropping trailing tabs, never by blanking the entire session.
                tabs.removeAt(tabs.lastIndex)
                if (tabs.isNotEmpty()) bytes = encode(tabs, snapshot.activeId)
            }
            if (tabs.isEmpty() || bytes.size > MAX_FILE_BYTES) return
            writeAtomically(if (cipher != null) cipher.seal(bytes) else bytes)
        } catch (_: Exception) {
            // Keep the previous authenticated snapshot if encoding or replacement fails.
        }
    }

    private fun decode(bytes: ByteArray): Snapshot? {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != MAGIC) return null
        val version = input.readInt()
        if (version != VERSION && version != LEGACY_VERSION) return null
        val count = input.readInt()
        if (count !in 1..MAX_TABS) return null
        val tabs = ArrayList<Tab>(count)
        repeat(count) {
            val id = input.readInt()
            val url = input.readUTF()
            val title = input.readUTF()
            val scrollY = input.readInt()
            // Identity and framing errors invalidate the file; one bad address invalidates only
            // its tab, after its complete record has been consumed, so unrelated tabs survive.
            if (id <= 0 || tabs.any { it.id == id }) return null
            var usable = url.length <= MAX_URL_LENGTH && title.length <= MAX_TITLE_LENGTH
            if (url.isNotEmpty() && !BrowserUrls.isWebUrl(url)) usable = false
            val secureUrl = if (usable) BrowserPolicy.httpsOnly(url) else ""

            val (history, index) = if (version == LEGACY_VERSION) {
                if (url.isEmpty()) emptyList<String>() to -1 else listOf(secureUrl) to 0
            } else {
                val size = input.readInt()
                if (size !in 0..MAX_HISTORY) return null
                val entries = ArrayList<String>(size)
                repeat(size) {
                    val entry = input.readUTF()
                    if (entry.length !in 1..MAX_URL_LENGTH || !BrowserUrls.isWebUrl(entry)) {
                        usable = false
                    } else {
                        entries += BrowserPolicy.httpsOnly(entry)
                    }
                }
                val current = input.readInt()
                if (entries.isEmpty()) {
                    if (current != -1) return null
                } else if (current !in entries.indices) {
                    return null
                }
                entries to current
            }
            if (url.isEmpty() && history.isNotEmpty()) usable = false
            if (usable) tabs += Tab(id, secureUrl, title, scrollY.coerceAtLeast(0), history, index)
        }
        if (tabs.isEmpty()) return null
        val activeId = input.readInt()
        return Snapshot(tabs, if (tabs.any { it.id == activeId }) activeId else tabs.first().id)
    }

    private fun encode(tabs: List<Tab>, requestedActiveId: Int): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(tabs.size)
                tabs.forEach { tab ->
                    out.writeInt(tab.id)
                    out.writeUTF(tab.url)
                    out.writeUTF(tab.title)
                    out.writeInt(tab.scrollY.coerceAtLeast(0))
                    out.writeInt(tab.history.size)
                    tab.history.forEach { out.writeUTF(it) }
                    out.writeInt(tab.historyIndex)
                }
                out.writeInt(if (tabs.any { it.id == requestedActiveId }) requestedActiveId else tabs.first().id)
            }
        }.toByteArray()

    /** Normalizes one tab without allowing its bad or oversized address to discard other tabs. */
    private fun normalized(tab: Tab): Tab? {
        if (tab.url.isEmpty()) return tab.copy(title = tab.title.take(MAX_TITLE_LENGTH), history = emptyList(), historyIndex = -1)
        val url = tab.url.trim()
        if (url.length > MAX_URL_LENGTH || !BrowserUrls.isWebUrl(url)) return null
        val secureUrl = BrowserPolicy.httpsOnly(url)
        val entries = ArrayList<String>(tab.history.size)
        var mapped = -1
        tab.history.forEachIndexed { at, entry ->
            if (entry.length !in 1..MAX_URL_LENGTH || !BrowserUrls.isWebUrl(entry)) return@forEachIndexed
            if (at == tab.historyIndex) mapped = entries.size
            entries += BrowserPolicy.httpsOnly(entry)
        }
        if (entries.isEmpty()) {
            return tab.copy(url = secureUrl, title = tab.title.take(MAX_TITLE_LENGTH), history = listOf(secureUrl), historyIndex = 0)
        }
        if (entries.size > MAX_HISTORY) {
            // Keep a window that still contains the page on screen: the newest entries normally,
            // but the current one stays inside even if the user walked far back before the kill.
            val start = if (mapped in 0 until entries.size - MAX_HISTORY) mapped else entries.size - MAX_HISTORY
            val window = entries.subList(start, start + MAX_HISTORY).toList()
            mapped -= start
            entries.clear()
            entries.addAll(window)
        }
        val index = if (mapped in entries.indices) mapped else entries.lastIndex
        return tab.copy(
            url = secureUrl,
            title = tab.title.take(MAX_TITLE_LENGTH),
            history = entries,
            historyIndex = index
        )
    }

    private fun writeAtomically(bytes: ByteArray) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeBytes(bytes)
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun discard(): Snapshot? {
        file.delete()
        return null
    }

    companion object {
        /** Same ceiling as [ru.spgsroot.peachy.ui.webview.BrowserController.MAX_TABS]. */
        const val MAX_TABS = 10

        /** Addresses kept per tab: enough to go back across a restart, still bounded. */
        const val MAX_HISTORY = 16

        internal const val VERSION = 2
        internal const val LEGACY_VERSION = 1
        internal const val MAX_URL_LENGTH = 1024
        internal const val MAX_TITLE_LENGTH = 256

        /** "PCHB": Peachy browser session. */
        internal const val MAGIC = 0x50434842

        /** Ten tabs × (16 history entries + address + title) at the caps, with room to spare. */
        internal const val MAX_FILE_BYTES = 768L * 1024L

        fun forApp(context: Context) = BrowserSessionStore(
            File(context.noBackupFilesDir, "browser_session.bin"),
            AesGcmBrowserSessionCipher.forApp(context)
        )
    }
}
