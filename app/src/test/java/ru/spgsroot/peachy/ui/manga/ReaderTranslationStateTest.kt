package ru.spgsroot.peachy.ui.manga

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.spgsroot.peachy.translate.BatchState
import ru.spgsroot.peachy.translate.PageSource
import ru.spgsroot.peachy.translate.PageState
import ru.spgsroot.peachy.translate.PageTranslation
import ru.spgsroot.peachy.translate.PageTranslator
import ru.spgsroot.peachy.translate.TargetLanguage
import ru.spgsroot.peachy.translate.TranslateEngine
import ru.spgsroot.peachy.translate.TranslateRequest
import ru.spgsroot.peachy.translate.TranslationCache
import ru.spgsroot.peachy.translate.TranslationRunner

class ReaderTranslationStateTest {
    private val choice = TranslationChoice(10, TranslateRequest(TranslateEngine.MlKit, "ja", "ru"))
    private val cached = PageTranslation("ja", emptyList())

    private fun otherChoices() = listOf(
        choice.copy(galleryId = 11),
        choice.copy(request = choice.request.copy(target = "en")),
        choice.copy(request = choice.request.copy(source = "auto")),
        choice.copy(request = choice.request.copy(engine = TranslateEngine.Model))
    )

    @Test fun oldPageBatchAndConsentAreUnavailableBeforeTheNewChoiceEffectRuns() {
        val old = ReaderTranslationState(choice, mapOf(0 to PageState.Done(cached)), BatchState(1, 10), true)

        for (next in otherChoices()) {
            // stateIn and Compose may both still hold this exact old emission during composition.
            assertNull(old.forChoice(next))
        }
        assertSame(old, old.forChoice(choice))
        assertEquals(cached, (old.forChoice(choice)!!.pages[0] as PageState.Done).translation)
        assertEquals(BatchState(1, 10), old.forChoice(choice)!!.batch)
        assertTrue(old.forChoice(choice)!!.needsConsent)
    }

    @Test fun appTargetIdentityUsesTheResolvedLocaleRatherThanThePreference() {
        val russian = choice.copy(request = choice.request.copy(target = TargetLanguage.resolve("ru", "en", "app")))
        val english = choice.copy(request = choice.request.copy(target = TargetLanguage.resolve("en", "en", "app")))
        val old = ReaderTranslationState(russian, mapOf(0 to PageState.Done(cached)), null, false)

        assertEquals("ru", russian.request.target)
        assertEquals("en", english.request.target)
        assertNull(old.forChoice(english))
    }

    private class Cache : TranslationCache {
        var reads = 0
        var read: suspend () -> PageTranslation? = { null }
        override suspend fun get(galleryId: Int, page: Int, lang: String): PageTranslation? {
            reads++
            return read()
        }
        override suspend fun put(galleryId: Int, page: Int, lang: String, model: String, translation: PageTranslation) = Unit
        override suspend fun cachedPages(galleryId: Int, lang: String): Set<Int> = emptySet()
    }

    private fun session(scope: CoroutineScope, cache: Cache, selected: TranslationChoice = choice): ReaderTranslationSession {
        val source = object : PageSource {
            override suspend fun raw(galleryId: Int, page: Int): ByteArray = error("Cache checks must not fetch pages")
            override suspend fun jpeg(galleryId: Int, page: Int): ByteArray = error("Cache checks must not fetch pages")
        }
        val translator = object : PageTranslator {
            override suspend fun translate(galleryId: Int, page: Int, request: TranslateRequest, image: suspend () -> ByteArray): PageTranslation =
                error("Cache checks must not translate pages")
        }
        return ReaderTranslationSession(selected, TranslationRunner(source, translator, cache, { "test" }, scope))
    }

    @Test fun cacheMissForEveryRetiredChoiceCannotAskForConsent() = runTest {
        for (next in otherChoices()) {
            val cacheResult = CompletableDeferred<PageTranslation?>()
            val cache = Cache().apply { read = { cacheResult.await() } }
            val old = session(this, cache)
            var consentRequests = 0
            val check = async(start = CoroutineStart.UNDISPATCHED) {
                old.afterCacheMiss(0, force = false) { consentRequests++ }
            }
            assertEquals(1, cache.reads)
            old.close()
            val current = session(this, Cache(), next)
            cacheResult.complete(null)
            check.await()

            assertFalse(old.active)
            assertTrue(current.active)
            assertEquals(0, consentRequests)
        }
    }

    @Test fun aLateCacheHitStaysOnTheOldRunnerAndIsNotOfferedToTheNewChoice() = runTest {
        val cacheResult = CompletableDeferred<PageTranslation?>()
        val cache = Cache().apply { read = { cacheResult.await() } }
        val old = session(this, cache)
        var requested = false
        val check = async(start = CoroutineStart.UNDISPATCHED) {
            old.afterCacheMiss(0, force = false) { requested = true }
        }
        old.close()
        cacheResult.complete(cached)
        check.await()

        assertFalse(requested)
        assertEquals(PageState.Done(cached), old.runner.pages.value[0])
        val oldState = ReaderTranslationState(old.choice, old.runner.pages.value, null, false)
        assertNull(oldState.forChoice(otherChoices().first()))
    }

    @Test fun currentCacheHitPublishesThePageWithoutConsentOrTranslation() = runTest {
        val cache = Cache().apply { read = { cached } }
        val current = session(this, cache)
        var requested = false

        current.afterCacheMiss(0, force = false) { requested = true }

        assertFalse(requested)
        assertEquals(PageState.Done(cached), current.runner.pages.value[0])
    }

    @Test fun currentCacheMissStillRequestsTranslationAndForceStillSkipsTheCache() = runTest {
        val cache = Cache()
        val current = session(this, cache)
        var requests = 0

        current.afterCacheMiss(0, force = false) { requests++ }
        current.afterCacheMiss(0, force = true) { requests++ }

        assertEquals(2, requests)
        assertEquals(1, cache.reads)
        current.close()
        current.afterCacheMiss(0, force = true) { requests++ }
        assertEquals(2, requests)
    }
}
