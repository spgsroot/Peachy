package ru.spgsroot.peachy.translate

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class EnginePageTranslatorTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun secondRequestWithAnotherTargetReusesTheCachedOcr() = runBlocking {
        val ocr = FakeOcr(blocks)
        val engine = EnginePageTranslator(TranslationClient(OkHttpClient(), { provider }, "test"), ocr, FakeDetector(), FileOcrCache(folder.root), { 800 to 1200 })
        var loads = 0
        reply("""{"lines":[{"i":0,"translation":"русский"},{"i":1,"translation":"второй"}]}""")
        val first = engine.translate(7, 0, TranslateRequest(TranslateEngine.MlKit,"ja","ru")) { loads++; jpeg }
        reply("""{"lines":[{"i":0,"translation":"English"},{"i":1,"translation":"second"}]}""")
        val second = engine.translate(7, 0, TranslateRequest(TranslateEngine.MlKit,"ja","en")) { loads++; jpeg }
        assertEquals("русский", first.lines.first().translation)
        assertEquals("English", second.lines.first().translation)
        assertEquals(1, loads); assertEquals(1, ocr.calls)
    }
    @Test fun ocrCacheHitDoesNotLoadTheImage() = runBlocking {
        val cache = FileOcrCache(folder.root)
        cache.put(7,0,"ja",PageScan(blocks, emptyList(), 1f))
        val ocr = FakeOcr(emptyList())
        val engine = EnginePageTranslator(TranslationClient(OkHttpClient(), { provider }, "test"), ocr, FakeDetector(), cache, { 800 to 1200 })
        reply("""{"lines":[{"i":0,"translation":"cached"},{"i":1,"translation":"second"}]}""")
        val result = engine.translate(7,0,TranslateRequest(TranslateEngine.MlKit,"ja","en")) { error("Must not load the image") }
        assertEquals("cached", result.lines.first().translation)
        assertEquals(0,ocr.calls)
    }
    private lateinit var server: MockWebServer
    private lateinit var provider: ProviderSettings
    private val jpeg = byteArrayOf(1, 2, 3)
    private val blocks = listOf(
        OcrBlock("最初の吹き出し", Box(0.1f, 0.2f, 0.3f, 0.4f)),
        OcrBlock("次の吹き出し", Box(0.5f, 0.6f, 0.8f, 0.9f))
    )

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
        provider = ProviderSettings(baseUrl = "http://127.0.0.1:${server.port}/v1", apiKey = "k", consented = true)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun translator(ocr: PageOcr, detector: BubbleDetector = FakeDetector(), cache: OcrCache = FileOcrCache(folder.root)): EnginePageTranslator =
        EnginePageTranslator(TranslationClient(OkHttpClient(), { provider }, "Peachy/test"), ocr, detector, cache,
            { 800 to 1200 }, fitModel = { _, response -> response })

    private fun reply(content: String) {
        server.enqueue(MockResponse().setBody(JSONObject().put(
            "choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("role", "assistant").put("content", content))
                .put("finish_reason", "stop"))
        ).toString()))
    }

    private class FakeOcr(private val result: List<OcrBlock>) : PageOcr {
        var calls = 0
        var source: String? = null
        var image: ByteArray? = null
        var extra = emptyList<OcrBlock>()
        var requestedRegions = emptyList<Box>()
        var regionCalls = 0
        var beforeRecognize: suspend () -> Unit = {}
        var beforeRegions: suspend () -> Unit = {}

        override suspend fun recognize(image: ByteArray, source: String): List<OcrBlock> {
            beforeRecognize()
            calls++
            this.source = source
            this.image = image
            return result
        }

        override suspend fun recognizeRegions(image: ByteArray, source: String, regions: List<Box>): List<OcrBlock> {
            beforeRegions()
            regionCalls++
            requestedRegions = regions
            return extra
        }
    }

    @Test
    fun modelDispatchSendsTheImageAndRequestsTightBubbleBoxesWithoutCallingOcr() = runBlocking {
        val ocr = FakeOcr(blocks)
        reply("""{"sourceLanguage":"ja","lines":[{"kind":"speech","text":"ハ？","translation":"А?","box":[100,200,300,400]}]}""")
        val result = translator(ocr).translate(7, 0, TranslateRequest(TranslateEngine.Model, "ja", "ru")) { jpeg }
        assertEquals("ja", result.sourceLanguage)
        assertEquals(listOf(TranslatedLine(LineKind.Speech, "ハ？", "А?", Box(0.1f, 0.2f, 0.3f, 0.4f))), result.lines)
        assertEquals(0, ocr.calls)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val messages = body.getJSONArray("messages")
        val parts = messages.getJSONObject(1).getJSONArray("content")
        val image = (0 until parts.length()).map { parts.getJSONObject(it) }.single { it.getString("type") == "image_url" }
        assertEquals("data:image/jpeg;base64,AQID", image.getJSONObject("image_url").getString("url"))
        val system = messages.getJSONObject(0).getString("content")
        assertTrue(system.contains("Read the whole supplied page"))
        assertTrue(system.contains("pronouns, names and forms of address consistently"))
        assertTrue(system.contains("physical line wraps"))
        assertTrue(system.contains("natural Russian syntax"))
    }

    @Test
    fun mlKitPassesSourceAndMapsReorderedIndicesToOriginalTextAndBox() = runBlocking {
        val ocr = FakeOcr(blocks)
        reply("""{"lines":[{"i":1,"kind":"thought","translation":"второй"},{"i":0,"kind":"speech","translation":"первый"}]}""")
        val result = translator(ocr).translate(7, 0, TranslateRequest(TranslateEngine.MlKit, "ja", "ru")) { jpeg }
        assertEquals("ja", result.sourceLanguage)
        assertEquals(listOf(
            TranslatedLine(LineKind.Speech, "最初の吹き出し", "первый", Box(0.1f, 0.2f, 0.3f, 0.4f)),
            TranslatedLine(LineKind.Thought, "次の吹き出し", "второй", Box(0.5f, 0.6f, 0.8f, 0.9f))
        ), result.lines)
        assertEquals(1, ocr.calls)
        assertEquals("ja", ocr.source)
        assertArrayEquals(jpeg, ocr.image)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val user = body.getJSONArray("messages").getJSONObject(1).get("content")
        assertTrue(user is String)
        assertTrue((user as String).contains("0: 最初の吹き出し"))
        assertTrue(user.contains("1: 次の吹き出し"))
        assertFalse(body.toString().contains("image_url"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun englishSceneKeepsWrappedDialogueAndEveryReplyInSourceOrder() = runBlocking {
        val opening = listOf(
            OcrBlock("If Mash returns,", Box(0.10f, 0.10f, 0.34f, 0.15f)),
            OcrBlock("tell her", Box(0.19f, 0.22f, 0.34f, 0.27f)),
            OcrBlock("I'll wait here.", Box(0.13f, 0.35f, 0.34f, 0.40f))
        )
        val answer = OcrBlock("You promised her you'd come back, Master.", Box(0.50f, 0.60f, 0.90f, 0.72f))
        val scene = listOf(
            OcrBlock("If Mash returns, tell her I'll wait here.", Box(0.10f, 0.10f, 0.34f, 0.40f)),
            answer
        )
        val detector = FakeDetector(listOf(Region(Box(0.05f, 0.05f, 0.40f, 0.50f), 0.9f)))
        val ocr = FakeOcr(opening + answer)
        // A syntactically valid reply losing the first half of the exchange cannot be success.
        reply("""{"lines":[{"i":1,"translation":"Вы обещали ей вернуться, Мастер."}]}""")
        reply("""{"lines":[
            {"i":1,"translation":"Вы обещали ей вернуться, Мастер."},
            {"i":0,"translation":"Если Маш вернётся, скажи ей, что я буду ждать здесь."}
        ]}""")
        val result = translator(ocr, detector).translate(7, 0, TranslateRequest(TranslateEngine.MlKit, "en", "ru")) { jpeg }
        assertEquals(scene.map { it.text }, result.lines.map { it.text })
        assertEquals(scene.map { it.box }, result.lines.map { it.box })
        assertEquals(listOf(
            "Если Маш вернётся, скажи ей, что я буду ждать здесь.",
            "Вы обещали ей вернуться, Мастер."
        ), result.lines.map { it.translation })
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().body.readUtf8()
        assertEquals(first, server.takeRequest().body.readUtf8())
        val messages = JSONObject(first).getJSONArray("messages")
        assertEquals(PagePrompt.blocksUser(scene), messages.getJSONObject(1).getString("content"))
        val system = messages.getJSONObject(0).getString("content")
        assertTrue(system.contains("Read all supplied blocks as one exchange"))
        assertTrue(system.contains("pronouns, names and forms of address consistently"))
        assertTrue(system.contains("Physical OCR line wraps"))
        assertTrue(system.contains("natural Russian syntax"))
        assertFalse(first.contains("image_url"))
        assertEquals(1, ocr.calls)
    }

    @Test
    fun emptyOcrReturnsChosenLanguageWithoutNetworkEvenWithoutProviderConsent() = runBlocking {
        provider = ProviderSettings()
        val ocr = FakeOcr(emptyList())
        val result = translator(ocr).translate(7, 0, TranslateRequest(TranslateEngine.MlKit, "ko", "en")) { jpeg }
        assertEquals(PageTranslation("ko", emptyList()), result)
        assertEquals("ko", ocr.source)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun emptyAutoOcrReturnsEmptyLanguageWithoutNetwork() = runBlocking {
        val ocr = FakeOcr(emptyList())
        val result = translator(ocr).translate(7, 0, TranslateRequest(TranslateEngine.MlKit, "auto", "ru")) { jpeg }
        assertEquals(PageTranslation("", emptyList()), result)
        assertEquals("auto", ocr.source)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun autoSourceWithRecognizedBlocksStillReturnsEmptyLanguage() = runBlocking {
        val ocr = FakeOcr(blocks)
        reply("""{"sourceLanguage":"ja","lines":[{"i":0,"kind":"speech","translation":"первый"},{"i":1,"translation":"второй"}]}""")
        val result = translator(ocr).translate(7, 0, TranslateRequest(TranslateEngine.MlKit, "auto", "ru")) { jpeg }
        assertEquals("", result.sourceLanguage)
        assertEquals("первый", result.lines.first().translation)
        assertEquals("auto", ocr.source)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun malformedMissingDuplicateAndOutOfRangeIndicesAreSkippedAndFirstAcceptedWins() = runBlocking {
        reply("""{"lines":[
            {"kind":"speech","translation":"missing"},
            {"i":"0","translation":"string index"},
            {"i":0.25,"translation":"fraction"},
            {"i":null,"translation":"null"},
            {"i":true,"translation":"boolean"},
            {"i":-1,"translation":"negative"},
            {"i":2,"translation":"extra"},
            {"i":99999999999999999999,"translation":"overflow"},
            {"i":0,"translation":" "},
            {"i":0,"kind":"sign","translation":"первый"},
            {"i":0,"kind":"thought","translation":"duplicate"},
            {"i":1,"kind":"narration","translation":"второй"},
            {"i":1,"translation":"duplicate again"}
        ]}""")
        val result = translator(FakeOcr(blocks)).translate(7, 0, TranslateRequest(TranslateEngine.MlKit, "ja", "ru")) { jpeg }
        assertEquals(listOf(
            TranslatedLine(LineKind.Sign, "最初の吹き出し", "первый", Box(0.1f, 0.2f, 0.3f, 0.4f)),
            TranslatedLine(LineKind.Narration, "次の吹き出し", "второй", Box(0.5f, 0.6f, 0.8f, 0.9f))
        ), result.lines)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun ocrFailurePropagatesWithoutSendingAnImageToTheProvider() {
        val ocr = object : PageOcr {
            override suspend fun recognize(image: ByteArray, source: String): List<OcrBlock> {
                throw IOException("cannot decode")
            }

            override suspend fun recognizeRegions(image: ByteArray, source: String, regions: List<Box>): List<OcrBlock> = emptyList()
        }
        try {
            runBlocking { translator(ocr).translate(7, 0, TranslateRequest(TranslateEngine.MlKit, "auto", "en")) { jpeg } }
            org.junit.Assert.fail("expected OCR failure")
        } catch (e: IOException) {
            assertEquals("cannot decode", e.message)
        }
        assertEquals(0, server.requestCount)
    }

    private class FakeDetector(var result: List<Region>? = emptyList()) : BubbleDetector {
        var calls = 0
        var beforeDetect: suspend () -> Unit = {}
        override suspend fun detect(image: ByteArray): List<Region>? {
            calls++
            beforeDetect()
            return result
        }
        override fun release() = Unit
    }

    private class MemoryCache(var scan: PageScan? = null) : OcrCache {
        var writes = 0
        override suspend fun get(galleryId: Int, page: Int, source: String): PageScan? = scan
        override suspend fun put(galleryId: Int, page: Int, source: String, scan: PageScan) {
            writes++
            this.scan = scan
        }
    }

    private suspend fun scan(engine: PageTranslator): PageTranslation =
        engine.translate(7, 0, TranslateRequest(TranslateEngine.MlKit, "en", "ru")) { jpeg }

    private fun replyUnits(count: Int) = reply(JSONObject().put("lines", JSONArray().also { lines ->
        repeat(count) { lines.put(JSONObject().put("i", it).put("translation", "translated $it")) }
    }).toString())

    @Test fun detectorNullGivesPhaseOneResultAndWritesNoCache() = runBlocking {
        val cache = MemoryCache()
        val ocr = FakeOcr(blocks)
        replyUnits(2)
        val result = scan(translator(ocr, FakeDetector(null), cache))
        assertEquals(blocks.map { it.text }, result.lines.map { it.text })
        assertEquals(blocks.map { it.box }, result.lines.map { it.box })
        assertEquals(0, cache.writes)
        assertEquals(0, ocr.regionCalls)
    }

    @Test fun detectorFailureGivesPhaseOneResult() = runBlocking {
        val cache = MemoryCache()
        val detector = FakeDetector().apply { beforeDetect = { throw IllegalStateException("runtime failed") } }
        val ocr = FakeOcr(blocks)
        replyUnits(2)
        val result = scan(translator(ocr, detector, cache))
        assertEquals(blocks.map { it.text }, result.lines.map { it.text })
        assertEquals(blocks.map { it.box }, result.lines.map { it.box })
        assertEquals(0, cache.writes)
        assertEquals(0, ocr.regionCalls)
    }

    @Test fun regionJoinsBlocksBeforeTranslation() = runBlocking {
        val a = OcrBlock("HELLO", Box(0.1f, 0.2f, 0.25f, 0.3f))
        val b = OcrBlock("WORLD", Box(0.3f, 0.2f, 0.45f, 0.3f))
        val cache = MemoryCache()
        replyUnits(1)
        val result = scan(translator(FakeOcr(listOf(a, b)), FakeDetector(listOf(Region(Box(0.05f, 0.15f, 0.5f, 0.35f), 0.9f))), cache))
        assertEquals("HELLO WORLD", result.lines.single().text)
        assertEquals(Box(0.1f, 0.2f, 0.45f, 0.3f), result.lines.single().box)
        val user = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(user.contains("0: HELLO WORLD"))
        assertFalse(user.contains("1:"))
        assertEquals(listOf(a, b), cache.scan!!.blocks) // Cache raw OCR, not a tuned merge.
        assertEquals(800f / 1200, cache.scan!!.aspect, 0f)
    }

    @Test fun silentRegionTriggersOneSecondPassWithItsBox() = runBlocking {
        val region = Region(Box(0.2f, 0.1f, 0.6f, 0.3f), 0.9f)
        val extra = OcrBlock("FOUND", Box(0.25f, 0.15f, 0.5f, 0.25f))
        val ocr = FakeOcr(emptyList()).apply { this.extra = listOf(extra) }
        val cache = MemoryCache()
        replyUnits(1)
        val result = scan(translator(ocr, FakeDetector(listOf(region)), cache))
        assertEquals(listOf(region.box), ocr.requestedRegions)
        assertEquals(1, ocr.regionCalls)
        assertEquals("FOUND", result.lines.single().text)
        assertEquals(listOf(extra), cache.scan!!.blocks)
    }

    @Test fun secondPassFailureKeepsFirstPassBlocksAndWritesNoCache() = runBlocking {
        for (failure in listOf(IOException("decode failed"), AssertionError("native failure"))) {
            val ocr = FakeOcr(blocks).apply { beforeRegions = { throw failure } }
            val cache = MemoryCache()
            val regions = listOf(
                Region(Box(0f, 0f, 1f, 0.05f), 0.9f),
                Region(Box(0f, 0.1f, 0.9f, 0.95f), 0.9f)
            )
            replyUnits(2)
            val result = scan(translator(ocr, FakeDetector(regions), cache))
            assertEquals(blocks.map { it.text }, result.lines.map { it.text })
            assertEquals(blocks.map { it.box }, result.lines.map { it.box })
            assertEquals(0, cache.writes)
        }
    }

    @Test fun secondPassCancellationPropagates() = runBlocking {
        val ocr = FakeOcr(blocks).apply { beforeRegions = { throw CancellationException("region cancelled") } }
        val cache = MemoryCache()
        try {
            scan(translator(ocr, FakeDetector(listOf(Region(Box(0f, 0f, 1f, 0.05f), 0.9f))), cache))
            org.junit.Assert.fail("Second-pass cancellation swallowed")
        } catch (cancelled: CancellationException) {
            assertEquals("region cancelled", cancelled.message)
        }
        assertEquals(0, cache.writes)
        assertEquals(0, server.requestCount)
    }

    @Test fun lowScoreRegionDoesNotTriggerSecondPass() = runBlocking {
        val ocr = FakeOcr(emptyList()).apply { extra = blocks }
        val result = scan(translator(ocr, FakeDetector(listOf(Region(Box(0.1f, 0.1f, 0.4f, 0.4f), 0.49f)))))
        assertEquals(PageTranslation("en", emptyList()), result)
        assertEquals(0, ocr.regionCalls)
        assertEquals(0, server.requestCount)
    }

    @Test fun secondPassIsCappedAtEightRegions() = runBlocking {
        val regions = (0 until 10).map { Region(Box(it / 10f, 0.1f, it / 10f + 0.05f, 0.2f), 0.5f + it * 0.04f) }
        val ocr = FakeOcr(emptyList())
        scan(translator(ocr, FakeDetector(regions)))
        assertEquals(regions.reversed().take(8).map { it.box }, ocr.requestedRegions)
        assertEquals(1, ocr.regionCalls)
    }

    @Test fun secondPassBlocksAreOrderedIntoThePage() = runBlocking {
        val bottom = OcrBlock("BOTTOM", Box(0.1f, 0.7f, 0.4f, 0.8f))
        val top = OcrBlock("TOP", Box(0.1f, 0.1f, 0.4f, 0.2f))
        val ocr = FakeOcr(listOf(bottom)).apply { extra = listOf(top) }
        replyUnits(2)
        val result = scan(translator(ocr, FakeDetector(listOf(Region(Box(0.05f, 0.05f, 0.45f, 0.25f), 0.9f)))))
        assertEquals(listOf("TOP", "BOTTOM"), result.lines.map { it.text })
    }

    @Test fun overlappingSecondPassRegionsDoNotDuplicateTheText() = runBlocking {
        val small = OcrBlock("TEXT", Box(0.2f, 0.2f, 0.4f, 0.3f))
        val large = OcrBlock("TEXT", Box(0.19f, 0.19f, 0.41f, 0.31f))
        val ocr = FakeOcr(emptyList()).apply { extra = listOf(small, large) }
        val cache = MemoryCache()
        val regions = listOf(Region(Box(0.1f, 0.1f, 0.4f, 0.4f), 0.9f), Region(Box(0.2f, 0.1f, 0.5f, 0.4f), 0.8f))
        replyUnits(2)
        val result = scan(translator(ocr, FakeDetector(regions), cache))
        assertEquals(listOf(large), cache.scan!!.blocks)
        assertEquals(listOf("TEXT"), result.lines.map { it.text })
        assertEquals(large.box, result.lines.single().box)
    }

    @Test fun cacheHitSkipsOcrDetectorAndSecondPass() = runBlocking {
        val a = OcrBlock("HELLO", Box(0.1f, 0.2f, 0.25f, 0.3f))
        val b = OcrBlock("WORLD", Box(0.3f, 0.2f, 0.45f, 0.3f))
        val cache = MemoryCache(PageScan(listOf(a, b), listOf(Region(Box(0.05f, 0.15f, 0.5f, 0.35f), 0.9f)), 0.7f))
        val ocr = FakeOcr(emptyList()).apply { beforeRecognize = { error("OCR on cache hit") } }
        val detector = FakeDetector().apply { beforeDetect = { error("Detector on cache hit") } }
        val engine = EnginePageTranslator(TranslationClient(OkHttpClient(), { provider }, "test"), ocr, detector, cache, { error("Bounds on cache hit") })
        replyUnits(1)
        val result = engine.translate(7, 0, TranslateRequest(TranslateEngine.MlKit, "en", "ru")) { error("Fetch on cache hit") }
        assertEquals("HELLO WORLD", result.lines.single().text)
        assertEquals(0, detector.calls)
        assertEquals(0, ocr.regionCalls)
        assertEquals(0, cache.writes)
    }

    @Test fun ocrAndDetectorStartBeforeEitherFinishes() = runTest {
        val ocrStarted = CompletableDeferred<Unit>()
        val detectorStarted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val ocr = FakeOcr(emptyList()).apply { beforeRecognize = { ocrStarted.complete(Unit); finish.await() } }
        val detector = FakeDetector().apply { beforeDetect = { detectorStarted.complete(Unit); finish.await() } }
        val cache = MemoryCache()
        val work = async { scan(translator(ocr, detector, cache)) }
        ocrStarted.await()
        detectorStarted.await()
        assertFalse(work.isCompleted)
        assertEquals(0, cache.writes)
        finish.complete(Unit)
        assertEquals(PageTranslation("en", emptyList()), work.await())
        assertEquals(1, cache.writes)
    }

    @Test fun cancelWhileDetectingPropagatesAndCachesNothing() = runTest {
        val detectorStarted = CompletableDeferred<Unit>()
        val ocrStarted = CompletableDeferred<Unit>()
        val ocrStopped = CompletableDeferred<Unit>()
        val detectorStopped = CompletableDeferred<Unit>()
        val ocr = FakeOcr(emptyList()).apply { beforeRecognize = {
            ocrStarted.complete(Unit)
            try { awaitCancellation() } finally { ocrStopped.complete(Unit) }
        } }
        val detector = FakeDetector().apply { beforeDetect = {
            detectorStarted.complete(Unit)
            try { awaitCancellation() } finally { detectorStopped.complete(Unit) }
        } }
        val cache = MemoryCache()
        val work = async { scan(translator(ocr, detector, cache)) }
        ocrStarted.await()
        detectorStarted.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertTrue(ocrStopped.isCompleted)
        assertTrue(detectorStopped.isCompleted)
        assertEquals(0, cache.writes)
        assertEquals(0, server.requestCount)
    }

    @Test fun detectorCancellationPropagatesAndCancelsOcr() = runTest {
        val ocrStarted = CompletableDeferred<Unit>()
        val ocrStopped = CompletableDeferred<Unit>()
        val ocr = FakeOcr(emptyList()).apply { beforeRecognize = {
            ocrStarted.complete(Unit)
            try { awaitCancellation() } finally { ocrStopped.complete(Unit) }
        } }
        val detector = FakeDetector().apply { beforeDetect = { ocrStarted.await(); throw CancellationException("detector cancelled") } }
        val cache = MemoryCache()
        try { scan(translator(ocr, detector, cache)); error("Cancellation swallowed") } catch (_: CancellationException) { }
        assertTrue(ocrStopped.isCompleted)
        assertEquals(0, cache.writes)
    }

    @Test fun ocrFailureCancelsDetectorAndWritesNothing() = runTest {
        val detectorStarted = CompletableDeferred<Unit>()
        val detectorStopped = CompletableDeferred<Unit>()
        val ocr = FakeOcr(emptyList()).apply { beforeRecognize = { detectorStarted.await(); throw IOException("OCR failed") } }
        val detector = FakeDetector().apply { beforeDetect = {
            detectorStarted.complete(Unit)
            try { awaitCancellation() } finally { detectorStopped.complete(Unit) }
        } }
        val cache = MemoryCache()
        try { scan(translator(ocr, detector, cache)); error("OCR failure swallowed") } catch (_: IOException) { }
        assertTrue(detectorStopped.isCompleted)
        assertEquals(0, cache.writes)
        assertEquals(0, server.requestCount)
    }

    @Test fun pageWithoutTextStillGivesEmptyTranslation() = runBlocking {
        val cache = MemoryCache()
        val result = scan(translator(FakeOcr(emptyList()), FakeDetector(), cache))
        assertEquals(PageTranslation("en", emptyList()), result)
        assertEquals(PageScan(emptyList(), emptyList(), 800f / 1200), cache.scan)
        assertEquals(1, cache.writes)
        assertEquals(0, server.requestCount)
    }

    @Test fun bubbleBlocksSharesCachedScanAndMergeWithWholePageTranslation() = runBlocking {
        val a = OcrBlock("HELLO", Box(0.1f, 0.2f, 0.25f, 0.3f))
        val b = OcrBlock("WORLD", Box(0.3f, 0.2f, 0.45f, 0.3f))
        val ocr = FakeOcr(listOf(a, b))
        val detector = FakeDetector(listOf(Region(Box(0.05f, 0.15f, 0.5f, 0.35f), 0.9f)))
        val engine = translator(ocr, detector)
        var loads = 0
        provider = provider.copy(consented = false)
        val selected = engine.bubbleBlocks(7, 0, "en") { loads++; jpeg }
        assertEquals(listOf(OcrBlock("HELLO WORLD", Box(0.1f, 0.2f, 0.45f, 0.3f))), selected)
        assertEquals(selected, engine.bubbleBlocks(7, 0, "en") { error("Fetch on cache hit") })
        assertEquals(0, server.requestCount)
        provider = provider.copy(consented = true)
        replyUnits(1)
        val page = engine.translate(7, 0, TranslateRequest(TranslateEngine.MlKit, "en", "ru")) { error("Fetch on cache hit") }
        assertEquals(selected.map { it.text }, page.lines.map { it.text })
        assertEquals(selected.map { it.box }, page.lines.map { it.box })
        assertEquals(1, loads)
        assertEquals(1, ocr.calls)
        assertEquals(1, detector.calls)
    }

    @Test fun bubbleBlocksCacheIsScopedToSourceLanguage() = runBlocking {
        val ocr = FakeOcr(blocks)
        val engine = translator(ocr)
        var loads = 0
        engine.bubbleBlocks(7, 0, "ja") { loads++; jpeg }
        engine.bubbleBlocks(7, 0, "ko") { loads++; jpeg }
        engine.bubbleBlocks(7, 0, "ja") { error("Fetch on cache hit") }
        assertEquals(2, loads)
        assertEquals(2, ocr.calls)
        assertEquals("ko", ocr.source)
        assertEquals(0, server.requestCount)
    }

    @Test fun mlKitBubbleSendsOnlySelectedTextAndUsesEachRequestedLanguageWithoutLoadingImage() = runBlocking {
        val ocr = FakeOcr(blocks)
        val detector = FakeDetector()
        val cache = MemoryCache()
        val engine = translator(ocr, detector, cache)
        val block = blocks[1]
        for ((target, translated) in listOf("ru" to "второй", "en" to "second")) {
            reply("""{"lines":[{"i":1,"translation":"unrequested"},{"i":0,"kind":"thought","translation":"$translated"},{"i":0,"translation":"duplicate"}]}""")
            val result = engine.translateBubble(7, 0, TranslateRequest(TranslateEngine.MlKit, "ja", target), block) {
                error("Text-only bubble translation must not fetch an image")
            }
            assertEquals(TranslatedLine(LineKind.Thought, block.text, translated, block.box), result)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            val messages = body.getJSONArray("messages")
            assertEquals(PagePrompt.blocks(target, "ja"), messages.getJSONObject(0).getString("content"))
            assertEquals(PagePrompt.blocksUser(listOf(block)), messages.getJSONObject(1).getString("content"))
            assertFalse(body.toString().contains("image_url"))
            assertFalse(body.toString().contains(blocks[0].text))
        }
        assertEquals(0, ocr.calls)
        assertEquals(0, detector.calls)
        assertEquals(0, cache.writes)
        assertEquals(2, server.requestCount)
    }

    @Test fun modelBubbleUploadsOnlyCropAndCombinesLinesInsideOriginalBlockBounds() = runBlocking {
        val cropped = byteArrayOf(9, 8, 7)
        val block = blocks[1]
        val ocr = FakeOcr(blocks)
        val cache = MemoryCache()
        var crops = 0
        var loads = 0
        val engine = EnginePageTranslator(
            TranslationClient(OkHttpClient(), { provider }, "test"), ocr, FakeDetector(), cache,
            { error("Scanning a selected bubble is not needed") },
            { bytes, box ->
                assertArrayEquals(jpeg, bytes)
                assertEquals(block.box, box)
                crops++
                cropped
            }
        )
        reply("""{"lines":[{"kind":"sign","text":"model text","translation":"first","box":[0,0,1000,1000]},{"translation":"second","box":[100,200,300,400]}]}""")
        val result = engine.translateBubble(7, 0, TranslateRequest(TranslateEngine.Model, "ja", "ru"), block) { loads++; jpeg }
        assertEquals(TranslatedLine(LineKind.Sign, block.text, "first\nsecond", block.box), result)
        val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals(PagePrompt.system("ru", "ja"), messages.getJSONObject(0).getString("content"))
        val parts = messages.getJSONObject(1).getJSONArray("content")
        val image = (0 until parts.length()).map { parts.getJSONObject(it) }.single { it.getString("type") == "image_url" }
        assertEquals("data:image/jpeg;base64,CQgH", image.getJSONObject("image_url").getString("url"))
        assertEquals(1, loads)
        assertEquals(1, crops)
        assertEquals(0, ocr.calls)
        assertEquals(0, cache.writes)
    }

    @Test fun bubbleWithoutUsableSelectedTranslationFailsInsteadOfReturningAnEmptyPlate() = runBlocking {
        val cache = MemoryCache()
        val engine = EnginePageTranslator(
            TranslationClient(OkHttpClient(), { provider }, "test"), FakeOcr(blocks), FakeDetector(), cache,
            { 800 to 1200 }, { bytes, _ -> bytes }
        )
        for (mode in TranslateEngine.entries) {
            repeat(if (mode == TranslateEngine.MlKit) 2 else 1) {
                reply(if (mode == TranslateEngine.MlKit) """{"lines":[{"i":1,"translation":"wrong block"}]}""" else """{"lines":[]}""")
            }
            try {
                engine.translateBubble(7, 0, TranslateRequest(mode, "ja", "ru"), blocks[0]) { jpeg }
                org.junit.Assert.fail("Missing bubble translation accepted")
            } catch (failure: TranslationException) {
                assertEquals(TranslationError.BadResponse, failure.error)
            }
        }
        assertEquals(0, cache.writes)
    }

    @Test fun bubbleTranslationPreservesProviderConsentAndRateLimitErrors() = runBlocking {
        val engine = EnginePageTranslator(
            TranslationClient(OkHttpClient(), { provider }, "test"), FakeOcr(blocks), FakeDetector(), MemoryCache(),
            { 800 to 1200 }, { bytes, _ -> bytes }
        )
        for (mode in TranslateEngine.entries) {
            provider = provider.copy(consented = false)
            try {
                engine.translateBubble(7, 0, TranslateRequest(mode, "auto", "en"), blocks[0]) { jpeg }
                org.junit.Assert.fail("Unconsented bubble upload accepted")
            } catch (failure: TranslationException) {
                assertEquals(TranslationError.NotConfigured, failure.error)
            }
            provider = provider.copy(consented = true)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "9"))
            try {
                engine.translateBubble(7, 0, TranslateRequest(mode, "auto", "en"), blocks[0]) { jpeg }
                org.junit.Assert.fail("Rate limit swallowed")
            } catch (failure: TranslationException) {
                assertEquals(TranslationError.RateLimit(9), failure.error)
            }
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun cancellingBubbleImageLoadPropagatesWithoutUploadOrCacheWrite() = runTest {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val cache = MemoryCache()
        val engine = translator(FakeOcr(blocks), cache = cache)
        val work = async {
            engine.translateBubble(7, 0, TranslateRequest(TranslateEngine.Model, "ja", "en"), blocks[0]) {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        started.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertTrue(stopped.isCompleted)
        assertEquals(0, server.requestCount)
        assertEquals(0, cache.writes)
    }
}
