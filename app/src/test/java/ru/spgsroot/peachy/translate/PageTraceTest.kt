package ru.spgsroot.peachy.translate

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTraceTest {
    @Test fun tracedRecordsElapsedMillisUnderItsName() = runBlocking {
        var now = 0L
        val trace = PageTrace({ now }, { 12L }, {})
        withContext(PageTraceElement(trace)) { traced("decode") { now += 5_000_000 } }
        assertEquals(5L, trace.stageMs["decode"])
        assertEquals(12L, trace.heapAfterMb["decode"])
    }

    @Test fun sameStageNameAccumulates() = runBlocking {
        var now = 0L
        val trace = PageTrace({ now }, { 0L }, {})
        withContext(PageTraceElement(trace)) {
            traced("ocr") { now += 3_000_000 }
            traced("ocr") { now += 4_000_000 }
        }
        assertEquals(7L, trace.stageMs["ocr"])
    }

    @Test fun withoutATraceInContextTracedJustRunsTheBlock() = runBlocking {
        assertEquals(42, traced("unused") { 42 })
    }

    @Test fun tracedRecordsEvenWhenTheBlockThrows() = runBlocking {
        var now = 0L
        val trace = PageTrace({ now }, { 0L }, {})
        var thrown = false
        try {
            withContext(PageTraceElement(trace)) {
                traced("fetch") { now += 2_000_000; throw IOException("unavailable") }
            }
        } catch (_: IOException) { thrown = true }
        assertTrue(thrown)
        assertEquals(2L, trace.stageMs["fetch"])
    }
}
