package ru.spgsroot.peachy.translate

import android.os.Debug
import android.util.Log
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class PageTrace(
    @PublishedApi internal val clock: () -> Long = System::nanoTime,
    private val heapMb: () -> Long = ::defaultHeapMb,
    private val sink: (String) -> Unit = { Log.d("PageTrace", it); Unit }
) {
    private val stages = linkedMapOf<String, Long>()
    private val heaps = linkedMapOf<String, Long>()
    val stageMs: Map<String, Long> get() = synchronized(this) { LinkedHashMap(stages) }
    val heapAfterMb: Map<String, Long> get() = synchronized(this) { LinkedHashMap(heaps) }

    @Synchronized fun record(name: String, ms: Long) {
        stages[name] = (stages[name] ?: 0L) + ms
        heaps[name] = maxOf(heaps[name] ?: 0L, heapMb())
        sink("$name: ${stages[name]} ms, heap ${heaps[name]} MB")
    }
}

class PageTraceElement(val trace: PageTrace) : AbstractCoroutineContextElement(PageTraceElement) {
    companion object Key : CoroutineContext.Key<PageTraceElement>
}

suspend inline fun <T> traced(name: String, block: () -> T): T {
    val trace = coroutineContext[PageTraceElement]?.trace ?: return block()
    val start = trace.clock()
    return try { block() } finally { trace.record(name, (trace.clock() - start) / 1_000_000) }
}

fun defaultHeapMb(): Long =
    (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } + Debug.getNativeHeapAllocatedSize()) / (1024 * 1024)
