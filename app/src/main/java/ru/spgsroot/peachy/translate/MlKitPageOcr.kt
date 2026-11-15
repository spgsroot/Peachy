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
import kotlinx.coroutines.CancellationException
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
            if (width < MIN_OCR_SIDE || height < MIN_OCR_SIDE) return@withContext emptyList()
            val blocks = mutableListOf<RawOcrBlock>()
            for (region in regions) {
                coroutineContext.ensureActive()
                try {
                    val padX = (region.right - region.left) * 0.1f
                    val padY = (region.bottom - region.top) * 0.1f
                    val left = floor((region.left - padX) * width).toInt().coerceIn(0, width)
                    val top = floor((region.top - padY) * height).toInt().coerceIn(0, height)
                    val right = ceil((region.right + padX) * width).toInt().coerceIn(0, width)
                    val bottom = ceil((region.bottom + padY) * height).toInt().coerceIn(0, height)
                    if (left >= right || top >= bottom) continue
                    val cropWidth = maxOf(MIN_OCR_SIDE, right - left)
                    val cropHeight = maxOf(MIN_OCR_SIDE, bottom - top)
                    val cropLeft = (left - (cropWidth - (right - left)) / 2).coerceIn(0, width - cropWidth)
                    val cropTop = (top - (cropHeight - (bottom - top)) / 2).coerceIn(0, height - cropHeight)
                    val rect = PixelRect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)
                    var sample = PageImage.sampleSize(width, cropHeight, OCR_WORK_SIDE)
                    while (sample > 1 && (cropWidth / sample < MIN_OCR_SIDE || cropHeight / sample < MIN_OCR_SIDE)) sample /= 2
                    val maxSide = maxOf(OCR_WORK_SIDE, maxOf(width, cropHeight) / sample)
                    val read = readBitmap(source) {
                        val tile = PageDecode.decodeTile(image, PageTile(rect.top, rect.bottom), maxSide)
                        try {
                            val x = (rect.left.toLong() * tile.width / width).toInt()
                            val end = ((rect.right.toLong() * tile.width + width - 1) / width).toInt()
                            Bitmap.createBitmap(tile, x, 0, end - x, tile.height).also { crop ->
                                if (crop !== tile) tile.recycle()
                            }
                        } catch (failure: Throwable) {
                            tile.recycle()
                            throw failure
                        }
                    }
                    blocks += read.onPage(rect)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // One unreadable region must not discard other regions or first-pass text.
                }
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
        if (width < MIN_OCR_SIDE || height < MIN_OCR_SIDE) {
            bitmap.recycle()
            recognitionLock.unlock()
            throw IOException("OCR region is smaller than $MIN_OCR_SIDE pixels")
        }
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

private const val MIN_OCR_SIDE = 32

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
