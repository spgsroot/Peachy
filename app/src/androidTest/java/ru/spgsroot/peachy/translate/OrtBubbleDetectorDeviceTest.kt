package ru.spgsroot.peachy.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrtBubbleDetectorDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun detector() = OrtBubbleDetector(context)

    @Before fun prepareFixtureDirectory() {
        // App-created directories are required by scoped storage, even for adb-pushed fixtures.
        File(context.getExternalFilesDir("eval"), "pages").mkdirs()
    }

    @Test fun detectsRegionsOnAPage() = runBlocking<Unit> {
        val name = InstrumentationRegistry.getArguments().getString("page") ?: "1.webp"
        val page = File(context.getExternalFilesDir("eval"), "pages/$name")
        assertTrue("Push fixture $page", page.isFile)
        val detector = detector()
        try {
            val trace = PageTrace()
            val regions = withContext(PageTraceElement(trace)) { detector.detect(page.readBytes()) }
            assertNotNull(regions)
            assertTrue(regions!!.isNotEmpty())
            regions.forEach(::assertOnPage)
            Log.i("DetectorDeviceTest", "$name stages=${trace.stageMs} regions=${regions.size}")
        } finally { detector.release() }
    }

    @Test fun blankPageGivesEmptyNotNull() = runBlocking {
        val detector = detector()
        try { assertEquals(emptyList<Region>(), detector.detect(image())) } finally { detector.release() }
    }

    @Test fun detectAfterReleaseWorksAgain() = runBlocking {
        val detector = detector()
        try {
            val bytes = image()
            assertEquals(emptyList<Region>(), detector.detect(bytes))
            detector.release()
            assertEquals(emptyList<Region>(), detector.detect(bytes))
        } finally { detector.release() }
    }

    @Test fun cancelledDetectionLeavesTheLockFree() = runBlocking {
        val detector = detector()
        val bytes = image(height = 6000, text = true)
        val enteredInference = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val ticks = AtomicInteger()
        val decodedTiles = AtomicInteger()
        // decode and preprocess each read the trace clock twice; the fifth read enters infer.
        val trace = PageTrace(clock = {
            if (ticks.incrementAndGet() == 5) {
                enteredInference.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
            }
            System.nanoTime()
        }, sink = { if (it.startsWith("detect.decode:")) decodedTiles.incrementAndGet() })
        val first = launch(Dispatchers.Default + PageTraceElement(trace)) { detector.detect(bytes) }
        try {
            assertTrue(withContext(Dispatchers.IO) { enteredInference.await(10, TimeUnit.SECONDS) })
            first.cancel()
            assertFalse("Synchronous inference still owns the lock", first.isCompleted)
            detector.release() // Must be non-blocking and must not close the active session/buffers.
            proceed.countDown()
            withTimeout(10_000) { first.join() }
            assertTrue(first.isCancelled)
            assertTrue(trace.stageMs.containsKey("detect.infer"))
            // No second tile is prepared after cancellation of the first inference.
            assertEquals(1, decodedTiles.get())
            assertEquals(emptyList<Region>(), withTimeout(10_000) { detector.detect(image()) })
        } finally { proceed.countDown(); first.join(); detector.release() }
    }

    @Test fun releaseDuringDetectionDoesNotCloseActiveSession() = runBlocking {
        val detector = detector()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val ticks = AtomicInteger()
        val trace = PageTrace(clock = {
            if (ticks.incrementAndGet() == 5) {
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
            }
            System.nanoTime()
        })
        val running = async(Dispatchers.Default + PageTraceElement(trace)) { detector.detect(image()) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            detector.release()
            proceed.countDown()
            assertEquals(emptyList<Region>(), withTimeout(10_000) { running.await() })
        } finally { proceed.countDown(); running.join(); detector.release() }
    }

    @Test fun twoConcurrentDetectionsBothComplete() = runBlocking {
        val detector = detector()
        val bytes = image(text = true)
        try {
            val first = async(Dispatchers.Default) { detector.detect(bytes) }
            val second = async(Dispatchers.Default) { detector.detect(bytes) }
            withTimeout(10_000) {
                val a = first.await()
                assertNotNull(a)
                assertTrue(a!!.isNotEmpty())
                assertEquals(a, second.await())
            }
        } finally { detector.release() }
    }

    @Test fun corruptBytesReturnNull() = runBlocking {
        val detector = detector()
        try {
            assertNull(detector.detect(byteArrayOf(1, 2, 3)))
            assertEquals(emptyList<Region>(), detector.detect(image()))
        } finally { detector.release() }
    }

    @Test fun stripIsTiledAndRegionsStayOnThePage() = runBlocking {
        val detector = detector()
        try {
            val regions = detector.detect(image(height = 6000, text = true))
            assertNotNull(regions)
            val boxes = regions!!.map { it.box }
            regions.forEach(::assertOnPage)
            assertEquals(boxes.map { it.top }.sorted(), boxes.map { it.top })
            for ((low, high) in listOf(0f to 0.2f, 0.4f to 0.6f, 0.8f to 1f)) {
                assertTrue("Missing strip band $low..$high: $boxes", boxes.any { (it.top + it.bottom) / 2 in low..high })
            }
        } finally { detector.release() }
    }

    private fun assertOnPage(region: Region) {
        val b = region.box
        assertTrue(b.left >= 0 && b.top >= 0 && b.right <= 1 && b.bottom <= 1 && b.left < b.right && b.top < b.bottom)
    }

    private fun image(height: Int = 1200, text: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(800, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            if (text) {
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
                val ys = if (height > 1200) listOf(300, 1500, 2900, 4300, 5700) else listOf(400)
                for (y in ys) {
                    canvas.drawText("HELLO", 100f, y.toFloat(), paint)
                    canvas.drawText("WORLD", 100f, y + 110f, paint)
                }
            }
            return ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
}
