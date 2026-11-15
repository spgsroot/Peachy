package ru.spgsroot.peachy.translate

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

const val SECOND_PASS_SCORE = 0.5f
const val SECOND_PASS_LIMIT = 8

/** Selects provider image translation or concurrent local OCR/detection with text-only translation. */
class EnginePageTranslator internal constructor(
    private val client: TranslationClient,
    private val ocr: PageOcr,
    private val detector: BubbleDetector,
    private val ocrCache: OcrCache,
    private val pageSize: (ByteArray) -> Pair<Int, Int>
) : PageTranslator {
    @Inject constructor(client: TranslationClient, ocr: PageOcr, detector: BubbleDetector, ocrCache: OcrCache) :
        this(client, ocr, detector, ocrCache, PageDecode::size)

    override suspend fun translate(galleryId: Int, page: Int, request: TranslateRequest, image: suspend () -> ByteArray): PageTranslation =
        when (request.engine) {
            TranslateEngine.Model -> traced("translate.net") { client.translate(galleryId, page, request, image) }
            TranslateEngine.MlKit -> {
                val scan = ocrCache.get(galleryId, page, request.source)
                    ?: scanPage(galleryId, page, image(), request.source)
                val blocks = mergeBlocks(scan)
                val source = if (request.source == "auto") "" else request.source
                if (blocks.isEmpty()) {
                    PageTranslation(source, emptyList())
                } else {
                    val translations = traced("translate.net") {
                        client.translateBlocks(galleryId, blocks, request.source, request.target)
                    }
                    val accepted = HashSet<Int>()
                    val lines = buildList {
                        for (translation in translations) {
                            val block = blocks.getOrNull(translation.index) ?: continue
                            if (!accepted.add(translation.index)) continue
                            add(TranslatedLine(translation.kind, block.text, translation.translation, block.box))
                        }
                    }
                    PageTranslation(source, lines)
                }
            }
        }

    private suspend fun scanPage(galleryId: Int, page: Int, image: ByteArray, source: String): PageScan = coroutineScope {
        val recognition = async { ocr.recognize(image, source) }
        val detection = async {
            traced("detect") {
                try { detector.detect(image) } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Detector failure must not take away text that ML Kit can still read.
                    null
                }
            }
        }
        val (width, height) = pageSize(image)
        val aspect = width.toFloat() / height
        // Await both together: even a child's own CancellationException must cancel its sibling,
        // not leave the parent waiting indefinitely for the other child.
        awaitAll(recognition, detection)
        val blocks = recognition.await()
        val regions = detection.await() ?: return@coroutineScope PageScan(blocks, emptyList(), aspect)
        val silent = regions.filter { region ->
            region.score >= SECOND_PASS_SCORE && blocks.none { blockShare(it.box, region.box) >= ASSIGN_SHARE }
        }.sortedByDescending { it.score }.take(SECOND_PASS_LIMIT).map { it.box }
        val extra = try {
            if (silent.isEmpty()) emptyList() else traced("ocr2") {
                PageTiles.dedupeSeams(ocr.recognizeRegions(image, source, silent), { it.box }) { a, b ->
                    val aArea = (a.box.right - a.box.left) * (a.box.bottom - a.box.top)
                    val bArea = (b.box.right - b.box.left) * (b.box.bottom - b.box.top)
                    if (aArea >= bArea) a else b
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Do not cache an incomplete scan: a retry must be able to recover the second pass.
            return@coroutineScope PageScan(blocks, emptyList(), aspect)
        }
        val combined = if (extra.isEmpty()) blocks else orderPage(blocks + extra, aspect)
        PageScan(combined, regions, aspect).also { ocrCache.put(galleryId, page, source, it) }
    }
}
