package ru.spgsroot.peachy.browser.adblock

import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.json.JSONArray

@Keep
internal object NativeFilter {
    init { System.loadLibrary("peachy_adblock") }
    external fun open(paths: String, directory: String, resources: String, restore: Boolean, privateRules: String): Long
    external fun close(handle: Long)
    external fun decide(handle: Long, url: String, source: String, kind: String, method: String): String?
    external fun page(handle: Long, url: String): String
    external fun generic(handle: Long, keys: String, exceptions: String): String
}

/** Stable across updates. A request holds its read lock until the native call returns. */
class BrowserFilter {
    private val lock = ReentrantReadWriteLock()
    private var handle = 0L
    @Volatile var generation = 0L
        private set
    private data class Request(val url: String, val source: String, val kind: String, val method: String)
    private val decisions = object : LinkedHashMap<Request, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Request, String>?) = size > 512
    }
    private val allowed = String(charArrayOf()) // identity distinguishes an allow from the empty block response
    @Volatile var sessionEpoch = 0L
        private set

    internal fun prepare(sources: List<File>, directory: File, resources: String, restore: Boolean, privateRules: String = "") =
        NativeFilter.open(JSONArray(sources.map { it.absolutePath }).toString(), directory.absolutePath, resources, restore, privateRules)

    internal fun install(candidate: Long) = lock.write {
        require(candidate != 0L)
        val previous = handle
        handle = candidate
        generation++
        decisions.clear()
        NativeFilter.close(previous)
    }

    fun clearDecisions() = lock.write { decisions.clear() }
    fun endSession() = lock.write { sessionEpoch++; decisions.clear() }

    fun decide(url: String, source: String, kind: String, method: String, session: Long = sessionEpoch): String? = lock.read {
        check(handle != 0L)
        // A finishing callback from a destroyed profile must not put its URL back into the LRU.
        if (session != sessionEpoch) return@read NativeFilter.decide(handle, url, source, kind, method)
        val key = Request(url, source, kind, method)
        synchronized(decisions) { decisions[key] }?.let { return@read if (it === allowed) null else it }
        val value = NativeFilter.decide(handle, url, source, kind, method)
        synchronized(decisions) { decisions[key] = value ?: allowed }
        value
    }

    fun page(url: String): String = lock.read { NativeFilter.page(handle, url) }
    fun generic(keys: String, exceptions: String): String = lock.read { NativeFilter.generic(handle, keys, exceptions) }
}
