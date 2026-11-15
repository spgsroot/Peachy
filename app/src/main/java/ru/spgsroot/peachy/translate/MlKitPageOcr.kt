package ru.spgsroot.peachy.translate

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

internal const val OCR_WORK_SIDE = 2000

/** Bundled models stay alive for the application lifetime; each heavy recognizer is created once. */
@Singleton
class MlKitPageOcr @Inject constructor() : PageOcr {
    private val recognitionLock = Mutex()
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val japanese by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    override suspend fun recognize(image: ByteArray, source: String): List<OcrBlock> = withContext(Dispatchers.Default) {
        val (width, height) = PageDecode.size(image)
        if (height <= width * PageTiles.STRIP_ASPECT) {
            val read = readBitmap(source) { PageDecode.decode(image, OCR_WORK_SIDE) }
            return@withContext traced("group") { groupOcrBlocks(read.blocks, read.width, read.height, source) }
        }
        val blocks = mutableListOf<RawOcrBlock>()
        for (tile in PageTiles.plan(width, height)) {
            val read = readBitmap(source) { PageDecode.decodeTile(image, tile, OCR_WORK_SIDE) }
            blocks += read.onPage(PixelRect(0, tile.top, width, tile.bottom))
        }
        traced("group") {
            val unique = PageTiles.dedupeSeams(blocks, { it.rect.box(width, height) }) { a, b ->
                if (a.rect.area() >= b.rect.area()) a else b
            }
            groupOcrBlocks(unique, width, height, source)
        }
    }

    override suspend fun recognizeRegions(image: ByteArray, source: String, regions: List<Box>): List<OcrBlock> {
        if (regions.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            val (width, height) = PageDecode.size(image)
            val blocks = mutableListOf<RawOcrBlock>()
            for (region in regions) {
                val padX = (region.right - region.left) * 0.1f
                val padY = (region.bottom - region.top) * 0.1f
                val rect = PixelRect(
                    floor((region.left - padX) * width).toInt().coerceIn(0, width),
                    floor((region.top - padY) * height).toInt().coerceIn(0, height),
                    ceil((region.right + padX) * width).toInt().coerceIn(0, width),
                    ceil((region.bottom + padY) * height).toInt().coerceIn(0, height)
                )
                if (rect.left >= rect.right || rect.top >= rect.bottom) continue
                val read = readBitmap(source) {
                    val tile = PageDecode.decodeTile(image, PageTile(rect.top, rect.bottom), OCR_WORK_SIDE)
                    try {
                        val left = (rect.left.toLong() * tile.width / width).toInt()
                        val right = ((rect.right.toLong() * tile.width + width - 1) / width).toInt()
                        Bitmap.createBitmap(tile, left, 0, right - left, tile.height).also { crop ->
                            if (crop !== tile) tile.recycle()
                        }
                    } catch (failure: Throwable) {
                        tile.recycle()
                        throw failure
                    }
                }
                blocks += read.onPage(rect)
            }
            traced("group") { groupOcrBlocks(blocks, width, height, source) }
        }
    }

    private suspend fun readBitmap(source: String, decode: () -> Bitmap): BitmapRead {
        coroutineContext.ensureActive()
        recognitionLock.lock()
        val bitmap = try {
            traced("decode") { decode() }
        } catch (failure: Throwable) {
            recognitionLock.unlock()
            throw failure
        }
        val width = bitmap.width
        val height = bitmap.height
        val task = try {
            coroutineContext.ensureActive()
            val recognizer = when (source) {
                "ja", "auto" -> japanese
                "zh" -> chinese
                "ko" -> korean
                else -> latin
            }
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (failure: Throwable) {
            bitmap.recycle()
            recognitionLock.unlock()
            throw failure
        }
        // Ownership passes to the task listener, including when the waiter is already cancelled.
        // One tile/crop owns the lock; the next must acquire it again so other pages can interleave.
        // Never unlock in coroutine finally: the native task can still be reading this bitmap.
        val text = traced("recognize") {
            awaitOcrTask(task) {
                bitmap.recycle()
                recognitionLock.unlock()
            }
        }
        val blocks = text.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            RawOcrBlock(block.lines.mapNotNull { line ->
                val lineBox = line.boundingBox ?: return@mapNotNull null
                RawOcrLine(line.text, PixelRect(lineBox.left, lineBox.top, lineBox.right, lineBox.bottom), line.confidence)
            }, PixelRect(box.left, box.top, box.right, box.bottom))
        }
        return BitmapRead(blocks, width, height)
    }
}

private data class BitmapRead(val blocks: List<RawOcrBlock>, val width: Int, val height: Int) {
    fun onPage(rect: PixelRect): List<RawOcrBlock> {
        fun map(box: PixelRect) = PixelRect(
            rect.left + (box.left.toDouble() * (rect.right - rect.left) / width).roundToInt(),
            rect.top + (box.top.toDouble() * (rect.bottom - rect.top) / height).roundToInt(),
            rect.left + (box.right.toDouble() * (rect.right - rect.left) / width).roundToInt(),
            rect.top + (box.bottom.toDouble() * (rect.bottom - rect.top) / height).roundToInt()
        )
        return blocks.map { block ->
            RawOcrBlock(block.lines.map { it.copy(rect = map(it.rect)) }, map(block.rect))
        }
    }
}

private fun PixelRect.area(): Long = (right - left).toLong() * (bottom - top)

private fun PixelRect.box(width: Int, height: Int) =
    Box(left.toFloat() / width, top.toFloat() / height, right.toFloat() / width, bottom.toFloat() / height)

private val directOcrExecutor = Executor { it.run() }

/** Releases native input only once the task is finished, not when its waiter is cancelled. */
internal suspend fun <T> awaitOcrTask(task: Task<T>, release: () -> Unit): T =
    suspendCancellableCoroutine { continuation ->
        task.addOnCompleteListener(directOcrExecutor) { completed ->
            release()
            when {
                completed.isCanceled -> continuation.cancel()
                completed.isSuccessful -> continuation.resume(completed.result)
                else -> continuation.resumeWithException(completed.exception ?: IOException("Text recognition failed"))
            }
        }
    }
