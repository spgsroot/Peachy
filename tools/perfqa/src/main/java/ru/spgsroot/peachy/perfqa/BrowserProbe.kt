package ru.spgsroot.peachy.perfqa

import android.content.Context
import androidx.annotation.Keep
import ru.spgsroot.peachy.browser.adblock.BrowserFilter
import ru.spgsroot.peachy.browser.adblock.NativeFilter
import java.io.File
import java.util.Random
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

@Keep
internal object BrowserProbe {
    external fun split(handle: Long, url: String, source: String, kind: String, method: String, phases: LongArray)
    external fun checked(handle: Long, url: String, source: String, kind: String, method: String): String?
    private const val URL = "https://blocked.qa.invalid/object"
    private const val SOURCE = "https://page.qa.invalid/chapter/1"
    private fun field(engine: BrowserFilter, name: String): Any = BrowserFilter::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(engine)!!
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate()) { check(System.nanoTime() < deadline) { "Lock barrier timed out" }; Thread.yield() }
    }

    fun generations(ctx: Context) {
        val engine = BrowserFilter()
        fun candidate(rules: String) = engine.prepare(emptyList(), ctx.cacheDir, "[]", false, rules)
        engine.install(candidate("||blocked.qa.invalid^"))
        val lock = field(engine, "lock") as ReentrantReadWriteLock
        val cache = field(engine, "decisions") as Map<*, *>
        fun decide(session: Long = engine.sessionEpoch) = engine.decide(URL, SOURCE, "script", "get", session)
        fun race(expectBlock: Boolean, writer: () -> Unit) {
            engine.clearDecisions()
            val work = List(2) { FutureTask { decide() } }
            val write = FutureTask { writer() }
            synchronized(cache) {
                val readers = work.map { Thread(it).apply { start() } }
                await { lock.readLockCount == readers.size && readers.all { it.state == Thread.State.BLOCKED } }
                val worker = Thread(write).apply { start() }
                await { lock.hasQueuedThread(worker) }
            }
            work.forEach { check((it.get(10, TimeUnit.SECONDS) != null) == expectBlock) }
            write.get(10, TimeUnit.SECONDS)
            check(cache.isEmpty()) { "Completed readers repopulated a cleared generation" }
        }
        // Disjoint native generations return opposite answers for the identical cache key.
        repeat(8) { i ->
            val before = engine.generation
            val oldBlocks = i % 2 == 0
            val next = candidate(if (oldBlocks) "@@||blocked.qa.invalid^" else "||blocked.qa.invalid^")
            race(oldBlocks) { engine.install(next) }
            check(engine.generation == before + 1 && (decide() != null) != oldBlocks)
        }
        val oldEpoch = engine.sessionEpoch
        race(true) { engine.endSession() }
        check(engine.sessionEpoch == oldEpoch + 1 && decide(oldEpoch) != null && cache.isEmpty())
        check(decide() != null && cache.size == 1)
        race(true) { engine.clearDecisions() }
        check(engine.sessionEpoch == oldEpoch + 1 && decide() != null && cache.size == 1)
    }

    private class Request(id: Int) {
        val url = "https://${if (id % 5 == 0) "blocked" else "cdn"}.qa.invalid/assets/$id?variant=${id % 9}"
        val source = "https://page.qa.invalid/chapter/${id % 13}"
        val kind = when (id % 3) { 0 -> "script"; 1 -> "image"; else -> "xmlhttprequest" }
        val method = if (id % 11 == 0) "post" else "get"
        fun call(engine: BrowserFilter) = engine.decide(url, source, kind, method)
    }

    fun measure(ctx: Context, emit: (String) -> Unit) {
        val sources = listOf("easylist.txt", "easyprivacy.txt", "adult.txt").map { name ->
            File(ctx.cacheDir, name).also { file -> ctx.assets.open("filters/$name").use { input -> file.outputStream().use { input.copyTo(it) } } }
        }
        val resources = ctx.assets.open("adblock/resources.json").bufferedReader().use { it.readText() }
        val engine = BrowserFilter()
        fun candidate() = engine.prepare(sources, ctx.cacheDir, resources, false, "||blocked.qa.invalid^")
        engine.install(candidate())
        val cache = field(engine, "decisions") as Map<*, *>
        val constructor = Class.forName("ru.spgsroot.peachy.browser.adblock.BrowserFilter\$Request").declaredConstructors.single().apply { isAccessible = true }
        val random = Random(12345L)
        var unique = 0
        val requests = List(12000) {
            val id = when {
                unique == 0 || random.nextInt(100) >= 60 -> unique++
                random.nextBoolean() -> unique - 1 - random.nextInt(minOf(64, unique))
                else -> random.nextInt(unique)
            }
            Request(id)
        }
        // Untimed replay interrogates the actual access-order LRU, not a simulated cache or the repeat selector.
        val hit = BooleanArray(requests.size) { i ->
            val r = requests[i]
            cache.containsKey(constructor.newInstance(r.url, r.source, r.kind, r.method)).also { r.call(engine) }
        }
        check(cache.size == 512)
        val hits = hit.count { it }
        emit("browser total=${requests.size} drawnRepeats=${requests.size - unique} hits=$hits misses=${requests.size - hits}")
        repeat(6) { pass ->
            engine.clearDecisions()
            val h = LongArray(hits); val m = LongArray(requests.size - hits)
            var nh = 0; var nm = 0; var blocked = 0
            requests.forEachIndexed { i, r ->
                val start = System.nanoTime()
                val value = r.call(engine)
                val ns = System.nanoTime() - start
                if (value != null) blocked++
                if (hit[i]) h[nh++] = ns else m[nm++] = ns
            }
            if (pass > 0) emit("browser pass=$pass blocked=$blocked hit_p95_ns=${p95(h)} miss_p95_ns=${p95(m)}")
        }
        // The split probe borrows only a separate QA-owned candidate; never inspect or close an installed handle.
        val handle = candidate()
        try {
            val missed = requests.filterIndexed { i, _ -> !hit[i] }
            val phases = LongArray(3)
            repeat(4) { pass ->
                val samples = Array(3) { LongArray(missed.size) }
                missed.forEachIndexed { i, r ->
                    split(handle, r.url, r.source, r.kind, r.method, phases)
                    for (p in phases.indices) samples[p][i] = phases[p]
                }
                if (pass > 0) emit("native pass=$pass miss_only_p95_ns strings,match,return=${samples.joinToString(",") { p95(it).toString() }}")
            }
            for (host in listOf("blocked", "cdn")) {
                for (path in listOf("日本語", "\uD842\uDFB7", "a\u0000b", "%F0%9F%98%80")) {
                    val url = "https://$host.qa.invalid/$path"
                    check(engine.decide(url, "$SOURCE/$path", "image", "get") ==
                        checked(handle, url, "$SOURCE/$path", "image", "get"))
                }
            }
            emit("PASS checked JNI oracle: 8 allow/block comparisons with non-ASCII, surrogate pairs, NUL and percent encoding")
        } finally { NativeFilter.close(handle) }
    }

    private fun p95(values: LongArray): Long = values.sortedArray()[(values.size * 95 + 99) / 100 - 1]
}
