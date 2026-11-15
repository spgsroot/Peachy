package ru.spgsroot.peachy.translate

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
import kotlinx.coroutines.Dispatchers
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
        recognitionLock.lock()
        val bitmap = try {
            traced("decode") {
                PageDecode.decode(image, OCR_WORK_SIDE)
            }
        } catch (failure: Throwable) {
            recognitionLock.unlock()
            throw failure
        }
        val width = bitmap.width
        val height = bitmap.height
        val task = try {
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
        // Never unlock in coroutine finally: the native task can still be reading this bitmap.
        val text = traced("recognize") {
            awaitOcrTask(task) {
                bitmap.recycle()
                recognitionLock.unlock()
            }
        }
        traced("group") {
            val blocks = text.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                RawOcrBlock(block.lines.mapNotNull { line ->
                    val lineBox = line.boundingBox ?: return@mapNotNull null
                    RawOcrLine(line.text, PixelRect(lineBox.left, lineBox.top, lineBox.right, lineBox.bottom), line.confidence)
                }, PixelRect(box.left, box.top, box.right, box.bottom))
            }
            groupOcrBlocks(blocks, width, height, source)
        }
    }
}

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
