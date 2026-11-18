package ru.spgsroot.peachy.ui.manga

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.spgsroot.peachy.manga.CatalogQuery
import ru.spgsroot.peachy.manga.GallerySummary

/**
 * Catalog responses are ordered by the test, not by the network. Each request parks on a plain
 * [suspendCoroutine], whose completion is not cancellable: completing a superseded request really runs
 * the production code after the call, so the generation checks are exercised instead of cancellation.
 * [PendingFetches.returned] and [PendingFilters.returned] record each body that returned together with
 * the test phase it observed, proving the old completion happened after the newer search began.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSearchTest {
    private class PendingFetches(private val phase: () -> Int) {
        class Call(val query: CatalogQuery, val page: Int) {
            lateinit var respond: (Result<List<GallerySummary>>) -> Unit
        }

        val calls = mutableListOf<Call>()
        val returned = mutableListOf<String>()

        suspend fun fetch(query: CatalogQuery, page: Int): List<GallerySummary> {
            val call = Call(query, page)
            val result = suspendCoroutine<Result<List<GallerySummary>>> { continuation ->
                call.respond = { value -> continuation.resume(value) }
                calls += call
            }
            returned += "${query.text}#$page@${phase()}"
            return result.getOrThrow()
        }
    }

    private class PendingFilters(private val phase: () -> Int) {
        class Gate {
            lateinit var open: () -> Unit
        }

        /** Parks every pass while true, and the next pass once when set. */
        var gateAll = false
        var gateNext = false
        val gates = mutableListOf<Gate>()
        val returned = mutableListOf<String>()

        suspend fun filter(source: List<GallerySummary>): List<GallerySummary> {
            if (gateAll || gateNext) {
                gateNext = false
                val gate = Gate()
                suspendCoroutine<Unit> { continuation ->
                    gate.open = { continuation.resume(Unit) }
                    gates += gate
                }
            }
            returned += "filter@${phase()}"
            return source
        }
    }

    private fun gallery(id: Int) = GallerySummary(id, "gallery $id", "thumb $id", 1)

    private fun ids(state: CatalogState) = state.results.map { it.id }

    @Test
    fun supersededSuccessCannotReplaceTheNewerSearchResults() = runTest {
        var phase = 0
        val fetches = PendingFetches { phase }
        val search = CatalogSearch(this, { query, page -> fetches.fetch(query, page) }, {}, { it })

        search.search(CatalogQuery(text = "old"))
        advanceUntilIdle()
        phase = 1
        search.search(CatalogQuery(text = "new"))
        advanceUntilIdle()
        assertEquals("new", search.state.value.query.text)
        assertTrue(search.state.value.loading)

        fetches.calls[0].respond(Result.success(listOf(gallery(1))))
        advanceUntilIdle()
        assertEquals(listOf("old#1@1"), fetches.returned)
        assertEquals("new", search.state.value.query.text)
        assertTrue(search.state.value.results.isEmpty())
        assertTrue(search.state.value.loading)

        fetches.calls[1].respond(Result.success(listOf(gallery(2))))
        advanceUntilIdle()
        assertEquals(listOf(2), ids(search.state.value))
        assertEquals("new", search.state.value.query.text)
        assertFalse(search.state.value.loading)
        assertFalse(search.state.value.failed)
        assertTrue(search.state.value.hasNext)
    }

    @Test
    fun supersededFailureCannotMarkTheNewerSearchFailed() = runTest {
        var phase = 0
        val fetches = PendingFetches { phase }
        val search = CatalogSearch(this, { query, page -> fetches.fetch(query, page) }, {}, { it })

        search.search(CatalogQuery(text = "old"))
        advanceUntilIdle()
        phase = 1
        search.search(CatalogQuery(text = "new"))
        advanceUntilIdle()

        fetches.calls[0].respond(Result.failure(IOException("old request failed")))
        advanceUntilIdle()
        assertEquals(listOf("old#1@1"), fetches.returned)
        assertFalse(search.state.value.failed)
        assertTrue(search.state.value.loading)

        fetches.calls[1].respond(Result.success(emptyList<GallerySummary>()))
        advanceUntilIdle()
        assertFalse(search.state.value.failed)
        assertFalse(search.state.value.loading)
        assertTrue(search.state.value.searched)
        assertTrue(search.state.value.results.isEmpty())
        assertFalse(search.state.value.hasNext)
    }

    @Test
    fun supersededPageAppendCannotChangeTheNewestSearch() = runTest {
        var phase = 0
        val fetches = PendingFetches { phase }
        val search = CatalogSearch(this, { query, page -> fetches.fetch(query, page) }, {}, { it })

        search.search(CatalogQuery(text = "first"))
        advanceUntilIdle()
        fetches.calls[0].respond(Result.success(listOf(gallery(1))))
        advanceUntilIdle()
        assertEquals(listOf(1), ids(search.state.value))
        assertTrue(search.state.value.hasNext)

        phase = 1
        search.loadMore()
        advanceUntilIdle()
        assertEquals(2, fetches.calls[1].page)
        assertTrue(search.state.value.loadingMore)

        phase = 2
        search.search(CatalogQuery(text = "second"))
        advanceUntilIdle()
        fetches.calls[1].respond(Result.success(listOf(gallery(3))))
        advanceUntilIdle()
        assertEquals(listOf("first#1@0", "first#2@2"), fetches.returned)
        assertEquals("second", search.state.value.query.text)
        assertEquals(1, search.state.value.page)
        assertTrue(search.state.value.loading)
        assertFalse(search.state.value.loadingMore)
        assertFalse(search.state.value.moreFailed)
        assertTrue(search.state.value.results.isEmpty())

        fetches.calls[2].respond(Result.success(listOf(gallery(2))))
        advanceUntilIdle()
        assertEquals(listOf("first#1@0", "first#2@2", "second#1@2"), fetches.returned)
        assertEquals("second", search.state.value.query.text)
        assertEquals(1, search.state.value.page)
        assertEquals(listOf(2), ids(search.state.value))
        assertFalse(search.state.value.loading)
        assertTrue(search.state.value.hasNext)
    }

    @Test
    fun filterPassStartedBeforeANewSearchCannotReplaceItsResults() = runTest {
        var phase = 0
        val fetches = PendingFetches { phase }
        val filters = PendingFilters { phase }
        val search = CatalogSearch(
            scope = this,
            fetch = { query, page -> fetches.fetch(query, page) },
            rememberQuery = {},
            filter = { source -> filters.filter(source) }
        )

        search.search(CatalogQuery(text = "first"))
        advanceUntilIdle()
        fetches.calls[0].respond(Result.success(listOf(gallery(1))))
        advanceUntilIdle()
        assertEquals(listOf(1), ids(search.state.value))

        filters.gateAll = true
        val stalePass = launch { search.refreshVisible() }
        advanceUntilIdle()
        assertEquals(1, filters.gates.size)

        phase = 1
        search.search(CatalogQuery(text = "second"))
        advanceUntilIdle()
        fetches.calls[1].respond(Result.success(listOf(gallery(2))))
        advanceUntilIdle()
        assertEquals(2, filters.gates.size)
        assertTrue(search.state.value.loading)
        assertTrue(search.state.value.results.isEmpty())

        filters.gates[0].open()
        advanceUntilIdle()
        assertEquals(listOf("filter@0", "filter@1"), filters.returned)
        assertTrue(search.state.value.results.isEmpty())
        assertTrue(search.state.value.loading)

        filters.gates[1].open()
        advanceUntilIdle()
        assertEquals(listOf(2), ids(search.state.value))
        assertFalse(search.state.value.loading)
        stalePass.join()
    }

    @Test
    fun initialFilterOfASupersededSearchCannotEndTheNewSearchLoading() = runTest {
        var phase = 0
        val fetches = PendingFetches { phase }
        val filters = PendingFilters { phase }
        val search = CatalogSearch(
            scope = this,
            fetch = { query, page -> fetches.fetch(query, page) },
            rememberQuery = {},
            filter = { source -> filters.filter(source) }
        )

        filters.gateNext = true
        search.search(CatalogQuery(text = "old"))
        advanceUntilIdle()
        fetches.calls[0].respond(Result.success(listOf(gallery(1))))
        advanceUntilIdle()
        assertEquals(1, filters.gates.size)
        assertTrue(search.state.value.loading)

        phase = 1
        search.search(CatalogQuery(text = "new"))
        advanceUntilIdle()
        assertEquals("new", search.state.value.query.text)
        assertTrue(search.state.value.loading)

        filters.gates[0].open()
        advanceUntilIdle()
        assertEquals(listOf("filter@1"), filters.returned)
        assertEquals("new", search.state.value.query.text)
        assertTrue(search.state.value.loading)
        assertFalse(search.state.value.hasNext)
        assertTrue(search.state.value.results.isEmpty())

        fetches.calls[1].respond(Result.success(emptyList<GallerySummary>()))
        advanceUntilIdle()
        assertFalse(search.state.value.loading)
        assertFalse(search.state.value.hasNext)
        assertTrue(search.state.value.results.isEmpty())
    }
}
