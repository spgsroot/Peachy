package ru.spgsroot.peachy.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrtExceptionRecoveryDeviceTest {
    @Test fun malformedModelsThrowAndValidInferenceStillWorksInTheSameProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "ort-recovery-${System.nanoTime()}").apply { mkdirs() }
        try {
            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(4)
                options.setInterOpNumThreads(1)
                val truncated = File(directory, "truncated.ort")
                context.assets.open("detector/yolo26n.ort").use { source ->
                    val bytes = ByteArray(1 shl 20)
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = source.read(bytes, offset, bytes.size - offset)
                        check(count > 0)
                        offset += count
                    }
                    truncated.writeBytes(bytes)
                }
                expectOrtException("truncated") { environment.createSession(truncated.path, options).close() }
                val valid = File(directory, "valid.ort")
                context.assets.open("detector/yolo26n.ort").use { source -> valid.outputStream().use { source.copyTo(it) } }
                environment.createSession(valid.path, options).use { session ->
                    val blank = ByteBuffer.allocateDirect(3 * 960 * 960 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    OnnxTensor.createTensor(environment, blank, longArrayOf(1, 3, 960, 960)).use { tensor ->
                        session.run(mapOf("images" to tensor)).use { result ->
                            val output = result[0] as OnnxTensor
                            assertArrayEquals(longArrayOf(1, 300, 6), output.info.shape)
                            Log.i("OrtRecovery", "valid inference after truncated model succeeded: [1,300,6]")
                        }
                    }
                }
                val random = File(directory, "random.ort")
                random.writeBytes(ByteArray(4096).also { Random(0).nextBytes(it) })
                expectOrtException("random") { environment.createSession(random.path, options).close() }
                Log.i("OrtRecovery", "process survived both malformed models and valid inference")
            }
        } finally { directory.deleteRecursively() }
    }

    private fun expectOrtException(label: String, block: () -> Unit) {
        try {
            block()
            fail("$label model was accepted")
        } catch (expected: OrtException) {
            Log.i("OrtRecovery", "$label model threw ${expected.javaClass.simpleName}: ${expected.message}")
        }
    }
}
