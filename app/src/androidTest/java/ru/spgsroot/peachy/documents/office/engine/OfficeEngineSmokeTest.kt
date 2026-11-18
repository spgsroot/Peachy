package ru.spgsroot.peachy.documents.office.engine

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.documents.DocumentFormat

/**
 * Offline smoke for the frozen x86_64 debug APK.
 *
 * Fail-closed, opt-in only: every run needs `-e officeSmoke 1 -e officeRunId <fresh token>`. A bare
 * run throws before any write instead of skipping to a green suite, matching the repo's device
 * fixtures. The test writes nothing before it has rejected a non-debuggable target, a physical
 * device, a non-x86_64 runtime, an APK whose SHA-256 differs from the frozen artifact (or from an
 * explicit `-e officeApkSha256 <sha>` re-freeze), a reused run token and a target whose local radio
 * settings cannot be read as offline. It sends no network traffic; host-level egress isolation for
 * the dedicated AVD is an external, recorded precondition (report §5).
 *
 * It exercises the public bridge only: `availability`, `open` on a synthetic DOCX in a unique
 * app-private session directory, one `renderTile` over the page, per-swatch colour assertions,
 * `close`, and byte-identical source verification. It never enables the editor route.
 *
 * Host-side timeout is mandatory because a native hang cannot be interrupted in-process; see
 * `.impeccable/office-instrumentation-report.md` for the run, cleanup and exit-status rules. The
 * local radio check and the clean first-init assertion prove nothing about LOK's macro or
 * external-reference policy.
 */
@RunWith(AndroidJUnit4::class)
class OfficeEngineSmokeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    private lateinit var sessionDir: File
    private lateinit var runId: String

    @Test(timeout = 240_000)
    fun firstInitOpensRendersSwatchesAndKeepsSourceBytes() {
        requireHarnessMode()
        requireHarnessTarget()
        requireCleanFirstInit()

        val fixture = instrumentation.context.assets.open(FIXTURE).use { it.readBytes() }
        assertTrue("fixture is empty", fixture.isNotEmpty())

        val documents = File(target.filesDir, DOCUMENTS_DIRECTORY)
        assertTrue("documents dir", documents.isDirectory || documents.mkdirs())
        val candidate = File(documents, "office-smoke-$runId")
        assertFalse("run id already used; choose a fresh token", candidate.exists())
        assertTrue("session dir", candidate.mkdirs())
        // Only now is the path proven to be created by this run, so only now is it cleanable.
        sessionDir = candidate
        val working = File(sessionDir, WORKING_COPY)
        working.writeBytes(fixture)

        val sourceDigest = sha256(working)
        assertEquals("fixture copy differs", sha256(fixture), sourceDigest)

        val engine = OfficeEngine.create(target)
        try {
            val availability = runBlocking { engine.availability() }
            assertTrue(
                "engine unavailable: " +
                    (availability as? OfficeAvailability.Unavailable)?.reason?.name,
                availability is OfficeAvailability.Available,
            )
            val available = availability as OfficeAvailability.Available
            assertEquals(
                OfficeBuildManifest.PINNED_COMMIT,
                available.manifest.commit.lowercase(),
            )

            val info = runBlocking { engine.open(working, DocumentFormat.DOCX) }
                .getOrElse { throw AssertionError("open failed: $it") }
            assertEquals(OfficeFamily.TEXT, info.family)
            assertEquals(DocumentFormat.DOCX, info.format)
            assertFalse("Writer session must be editable", info.readOnly)
            assertTrue("page width", info.widthTwips > 0)
            assertTrue("page height", info.heightTwips > 0)
            assertEquals(1, info.partCount)
            assertTrue(
                "open must publish OfficeEngineState.Open",
                engine.state.value is OfficeEngineState.Open,
            )

            assertFirstInit()

            val width = CANVAS_WIDTH
            val height = ((width.toLong() * info.heightTwips) / info.widthTwips)
                .toInt()
                .coerceIn(16, 4096)
            assertTrue("canvas pixels", width.toLong() * height <= 4L * 1024 * 1024)
            val request = OfficeTileRequest(
                canvasWidth = width,
                canvasHeight = height,
                tileXTwips = 0,
                tileYTwips = 0,
                tileWidthTwips = info.widthTwips.toInt(),
                tileHeightTwips = info.heightTwips.toInt(),
            )
            val tile = runBlocking { engine.renderTile(request) }
                .getOrElse { throw AssertionError("render failed: $it") }
            val bitmap = tile.bitmap
            try {
                // Fixture: one row of two shaded cells, 6000 twips tall, page margins 720 twips.
                val red = bitmap.getPixel(width / 4, height / 5)
                val blue = bitmap.getPixel(3 * width / 4, height / 5)
                val paper = bitmap.getPixel(width / 2, (height * 9) / 10)
                assertDominantSwatch("red cell", red, Color.RED)
                assertDominantSwatch("blue cell", blue, Color.BLUE)
                assertTrue("page background alpha", Color.alpha(paper) == 0xFF)
                assertTrue("page background is light", Color.red(paper) > 0xC0)
                assertTrue("page background is light", Color.green(paper) > 0xC0)
                assertTrue("page background is light", Color.blue(paper) > 0xC0)
            } finally {
                bitmap.recycle()
            }

            val closed = runBlocking { engine.close() }
            assertTrue("close failed: ${closed.exceptionOrNull()}", closed.isSuccess)
            assertTrue("close must publish Idle", engine.state.value is OfficeEngineState.Idle)
            val afterClose = runBlocking { engine.renderTile(request) }
            assertEquals(OfficeFailure.NotOpen, afterClose.exceptionOrNull())
        } finally {
            runBlocking { engine.shutdown() }
        }

        assertEquals("working copy was modified", sourceDigest, sha256(working))
        assertEquals("working copy size changed", fixture.size.toLong(), working.length())
        assertTrue("working copy renamed", working.isFile && working.name == WORKING_COPY)
    }

    @After
    fun removeSession() {
        if (this::sessionDir.isInitialized) {
            sessionDir.deleteRecursively()
            assertFalse("test session cleanup failed", sessionDir.exists())
        }
    }

    /** Fail-closed gate: a bare suite run must fail, not skip to a green run. */
    private fun requireHarnessMode() {
        val mode = arguments.getString(SMOKE_ARG)
        if (mode != "1") {
            throw AssertionError("office smoke refused: pass -e $SMOKE_ARG 1 (got ${mode ?: "nothing"})")
        }
        val token = arguments.getString(RUN_ID_ARG).orEmpty()
        if (!RUN_ID_PATTERN.matches(token)) {
            throw AssertionError(
                "office smoke refused: pass -e $RUN_ID_ARG <fresh token matching ${RUN_ID_PATTERN.pattern}>",
            )
        }
        runId = token
    }

    /** Refuses anything but the frozen x86_64 debuggable emulator target, before any write. */
    private fun requireHarnessTarget() {
        val app = target.applicationInfo
        assertTrue(
            "debuggable target required (debug APK only)",
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        assertTrue(
            "physical device refused: fingerprint=${Build.FINGERPRINT}",
            isEmulator(),
        )
        assertEquals(
            "x86_64 runtime required",
            "x86_64",
            Build.SUPPORTED_ABIS.firstOrNull(),
        )
        val expected = arguments.getString("officeApkSha256") ?: FROZEN_APK_SHA256
        val actual = sha256(File(app.sourceDir))
        assertEquals("installed APK is not the frozen artifact", expected.lowercase(), actual)
        requireLocalRadioOff()
    }

    /**
     * Local radio controls only: no packet is sent and no external host is contacted. Host-level
     * egress isolation for the dedicated AVD is recorded by the operator (report §5); this gate
     * refuses a target whose airplane/Wi-Fi state cannot be read as offline.
     */
    private fun requireLocalRadioOff() {
        assertEquals(
            "cannot show isolation: airplane mode must be on",
            1,
            readGlobalInt(Settings.Global.AIRPLANE_MODE_ON, "airplane_mode_on"),
        )
        assertEquals(
            "cannot show isolation: Wi-Fi must be off",
            0,
            readGlobalInt(Settings.Global.WIFI_ON, "wifi_on"),
        )
    }

    private fun readGlobalInt(key: String, label: String): Int = try {
        Settings.Global.getInt(target.contentResolver, key)
    } catch (missing: Settings.SettingNotFoundException) {
        throw AssertionError("cannot show isolation: $label is absent")
    } catch (denied: SecurityException) {
        throw AssertionError("cannot show isolation: $label is unreadable (${denied.javaClass.simpleName})")
    }

    /**
     * Refused before the session copy and before the engine is created: an existing completed tree
     * means the profile was reused, so first-init evidence cannot be produced. Staging leftovers
     * (`*.tmp`, no marker) are pruned by the engine and do not count.
     */
    private fun requireCleanFirstInit() {
        val existing = completedUnpackTrees()
        assertTrue(
            "clean first-init required: ${existing.size} completed unpack tree(s) (marker present) " +
                "already exist; use a separately authorized clean AVD/profile, do not reuse this one",
            existing.isEmpty(),
        )
    }

    private fun assertFirstInit() {
        val after = completedUnpackTrees()
        assertTrue("first init did not materialise the unpack tree", after.isNotEmpty())
        assertEquals("first init must produce exactly one unpack tree", 1, after.size)
        val root = after.single()
        val markerFile = File(root, OfficeUnpackLayout.MARKER_NAME)
        assertTrue("missing completion marker", markerFile.isFile)
        val marker = OfficeUnpackMarker.parse(markerFile.readText())
        assertNotNull("unparsable completion marker", marker)
        assertEquals(
            "marker commit",
            OfficeBuildManifest.PINNED_COMMIT.lowercase(),
            marker!!.commit,
        )
        for (rdb in RDB_FILES) {
            assertTrue("missing program/$rdb", File(root, "program/$rdb").isFile)
        }
        assertTrue("missing fonts.conf", File(root, "etc/fonts/fonts.conf").isFile)
        assertTrue("missing HOME dir", File(root, OfficeUnpackLayout.CACHE_DIRECTORY).isDirectory)
        val fonts = File(root, "user/fonts")
        assertTrue("missing bundled fonts", fonts.isDirectory && fonts.listFiles()?.isNotEmpty() == true)
    }

    /**
     * Completed extraction roots only. A host-timeout kill can leave a `*.tmp` staging directory,
     * which the engine prunes on the next extraction; it must not be mistaken for a completed tree
     * and force a false "reuse" verdict.
     */
    private fun completedUnpackTrees(): List<File> {
        val owned = File(target.filesDir, OfficeUnpackLayout.OWNED_ROOT)
        return owned.listFiles()?.filter { candidate ->
            candidate.isDirectory && File(candidate, OfficeUnpackLayout.MARKER_NAME).isFile
        } ?: emptyList()
    }

    private fun assertDominantSwatch(label: String, pixel: Int, expected: Int) {
        assertEquals("$label alpha", 0xFF, Color.alpha(pixel))
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)
        if (expected == Color.RED) {
            assertTrue("$label is not red: rgb($red,$green,$blue)", red > 0xB0 && green < 0x60 && blue < 0x60)
            assertTrue("$label channels swapped", red - blue > 0x60)
        } else {
            assertTrue("$label is not blue: rgb($red,$green,$blue)", blue > 0xB0 && green < 0x60 && red < 0x60)
            assertTrue("$label channels swapped", blue - red > 0x60)
        }
    }

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        val product = Build.PRODUCT.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            hardware.contains("cuttlefish") ||
            hardware.contains("cutf") ||
            product.contains("sdk_gphone") ||
            product.contains("aosp_") ||
            product.contains("sdk_phone") ||
            model.contains("sdk_gphone") ||
            model.contains("android sdk built for")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FIXTURE = "office/swatches.docx"
        const val DOCUMENTS_DIRECTORY = "documents"
        const val WORKING_COPY = "working.docx"
        const val CANVAS_WIDTH = 256
        const val SMOKE_ARG = "officeSmoke"
        const val RUN_ID_ARG = "officeRunId"

        /** Path-safe, fresh token per run; it names the only directory this test may write. */
        val RUN_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{7,63}")

        /** The three `program` rdb files (`udkapi`, `offapi`, `oovbaapi`) the bootstrap needs. */
        val RDB_FILES = listOf("udkapi.rdb", "offapi.rdb", "oovbaapi.rdb")

        /** SHA-256 of the frozen x86_64 debug APK; a rebuilt APK must be re-frozen explicitly. */
        const val FROZEN_APK_SHA256 =
            "af8aa3a646555ec8a43033cb40bca128957008a22628a0dc7072b453220f99fc"
    }
}
