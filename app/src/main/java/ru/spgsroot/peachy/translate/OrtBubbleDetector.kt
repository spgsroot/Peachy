package ru.spgsroot.peachy.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val DETECTOR_MODEL_BYTES = 10_513_152L

/** One lazy CPU session and its reusable buffers; every access is protected by [lock]. */
@Singleton
class OrtBubbleDetector @Inject constructor(@ApplicationContext private val context: Context) : BubbleDetector {
    private val lock = Mutex()
    private var unavailable = !Process.is64Bit() || "arm64-v8a" !in Build.SUPPORTED_ABIS
    private var loaded: LoadedDetector? = null

    override suspend fun detect(image: ByteArray): List<Region>? = withContext(Dispatchers.Default) {
        lock.withLock {
            coroutineContext.ensureActive()
            if (unavailable) return@withLock null
            try {
                val (width, height) = PageDecode.size(image)
                val detector = loaded ?: load().also { loaded = it }
                val regions = mutableListOf<Region>()
                for (tile in PageTiles.plan(width, height)) {
                    coroutineContext.ensureActive()
                    val bitmap = traced("detect.decode") { PageDecode.decodeTile(image, tile, DETECTOR_SIDE) }
                    try {
                        traced("detect.preprocess") { detector.preprocess.prepare(bitmap, width, tile.bottom - tile.top) }
                    } finally { bitmap.recycle() }
                    coroutineContext.ensureActive()
                    // run() is synchronous and non-cancellable: this coroutine retains the mutex until
                    // it returns. Only then can cancellation unwind use{} and the outer withLock.
                    traced("detect.infer") { detector.session.run(detector.inputs) }.use { result ->
                        coroutineContext.ensureActive()
                        traced("detect.post") {
                            (result[0] as OnnxTensor).floatBuffer.get(detector.rows)
                            val prep = detector.preprocess
                            for (region in DetectorDecode.yolo(detector.rows, prep.padX, prep.padY, prep.scale, width, tile.bottom - tile.top)) {
                                regions += region.copy(box = tile.toPage(region.box, height))
                            }
                        }
                    }
                }
                PageTiles.dedupeSeams(regions, { it.box }) { a, b ->
                    Region(Box(minOf(a.box.left, b.box.left), minOf(a.box.top, b.box.top),
                        maxOf(a.box.right, b.box.right), maxOf(a.box.bottom, b.box.bottom)), maxOf(a.score, b.score))
                }.sortedWith(compareBy({ it.box.top }, { it.box.left }))
            } catch (_: LinkageError) {
                unavailable = true
                closeLoaded()
                null
            } catch (_: OrtException) {
                null
            } catch (_: OutOfMemoryError) {
                closeLoaded()
                null
            } catch (_: IOException) {
                null
            }
        }
    }

    private fun load(): LoadedDetector {
        val directory = File(context.filesDir, "detector")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create detector directory")
        val model = File(directory, "yolo26n-$DETECTOR_MODEL_TAG.ort")
        if (model.length() != DETECTOR_MODEL_BYTES) {
            try {
                val source = try { context.assets.open("detector/yolo26n.ort") } catch (failure: FileNotFoundException) {
                    unavailable = true
                    throw failure
                }
                source.use { input -> model.outputStream().use { input.copyTo(it) } }
                if (model.length() != DETECTOR_MODEL_BYTES) throw IOException("Incomplete detector model")
            } catch (failure: Throwable) {
                model.delete()
                throw failure
            }
        }
        val environment = OrtEnvironment.getEnvironment()
        val session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4)
            options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(false)
            environment.createSession(model.absolutePath, options)
        }
        var preprocess: DetectorPreprocess? = null
        var tensor: OnnxTensor? = null
        try {
            preprocess = DetectorPreprocess()
            tensor = OnnxTensor.createTensor(environment, preprocess.tensor, longArrayOf(1, 3, DETECTOR_SIDE.toLong(), DETECTOR_SIDE.toLong()))
            return LoadedDetector(session, preprocess, tensor)
        } catch (failure: Throwable) {
            try { tensor?.close() } finally {
                try { session.close() } finally { preprocess?.close() }
            }
            throw failure
        }
    }

    override fun release() {
        if (!lock.tryLock()) return
        try { closeLoaded() } finally { lock.unlock() }
    }

    private fun closeLoaded() {
        val old = loaded
        loaded = null
        try { old?.close() } catch (_: OrtException) { /* Resources are already detached; next call loads afresh. */ }
    }
}

/** The environment is process-owned; the session, tensor and all staging storage are detector-owned. */
private class LoadedDetector(
    val session: OrtSession,
    val preprocess: DetectorPreprocess,
    private val tensor: OnnxTensor
) : AutoCloseable {
    val inputs = mapOf("images" to tensor)
    val rows = FloatArray(300 * 6)

    override fun close() {
        try { tensor.close() } finally {
            try { session.close() } finally { preprocess.close() }
        }
    }
}
