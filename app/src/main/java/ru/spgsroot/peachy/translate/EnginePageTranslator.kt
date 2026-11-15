package ru.spgsroot.peachy.translate

import javax.inject.Inject

/** Selects image recognition by the provider or on-device OCR followed by text-only translation. */
class EnginePageTranslator @Inject constructor(
    private val client: TranslationClient,
    private val ocr: PageOcr,
    private val ocrCache: OcrCache
) : PageTranslator {
    override suspend fun translate(galleryId: Int, page: Int, request: TranslateRequest, image: suspend () -> ByteArray): PageTranslation =
        when (request.engine) {
            TranslateEngine.Model -> traced("translate.net") { client.translate(galleryId, page, request, image) }
            TranslateEngine.MlKit -> {
                val blocks = ocrCache.get(galleryId, page, request.source) ?: ocr.recognize(image(), request.source).also {
                    ocrCache.put(galleryId, page, request.source, it)
                }
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
}
