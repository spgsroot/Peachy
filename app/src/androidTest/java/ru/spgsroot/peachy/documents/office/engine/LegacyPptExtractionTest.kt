package ru.spgsroot.peachy.documents.office.engine

import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.documents.DocumentFormat

/**
 * Real-LOK extraction harness for the frozen x86_64 legacy `.ppt` candidate.
 *
 * This is the bridge from the staged native candidate to `accept_host.py`:
 * - it loads the two frozen synthetic `.ppt` fixtures through the public [OfficeEngine], which
 *   keeps the proven [OfficeUnpack] extraction and [OfficeEnvironmentPolicy] process environment;
 * - it calls the pinned `org.libreoffice.kit.Document.getCommandValues(String)` native method on
 *   the real document handle, so the JSON is produced by `doc_getCommandValues`, not by a host
 *   converter or a synthetic JSON writer;
 * - it captures the plain `filter=slides` response and the opt-in
 *   `filter=slides&legacyText=1` response for each fixture, and checks only the opt-in response
 *   has `Hidden`, `Notes`, or table metadata;
 * - it asks the production `OfficeEngine.extractLegacyPresentation()` for the typed exchange on
 *   the same open document and checks titles, hidden state, blocks, tables and notes;
 * - it leaves the four raw JSON files under the app-private token directory for the host
 *   `accept_host.py` checks.
 *
 * The load path deliberately remains `Office.documentLoad(String)`. Pinned source falls through
 * `desktop/source/lib/lokandroid.cxx:128-134` → `lo_documentLoad` in
 * `desktop/source/lib/init.cxx:2799-2802` → `lo_documentLoadWithOptions(..., nullptr)`. With
 * `HAVE_FEATURE_ANDROID_LOK 0` (`config_host/config_features.h:134`) and no
 * `EnableMacrosExecution`, the `#else` branch at `init.cxx:2937-2946` selects
 * `document::MacroExecMode::NEVER_EXECUTE`. The test never calls the Office-level
 * `extractDocumentStructureRequest` shortcut and never passes an explicit options string.
 *
 * Fail-closed, opt-in only. Required arguments:
 * `-e officeLegacyPpt 1 -e officeLegacyPptRunId <fresh token> \
 *  -e officeLegacyPptIsolationAck <same token> -e officeLegacyPptKeepArtifacts 1 \
 *  -e officeApkSha256 <frozen app apk sha> -e officeTestApkSha256 <frozen test apk sha>`.
 * The ack is an operator declaration that the disposable x86_64 emulator is externally egress
 * isolated; this process sends no traffic and cannot prove host isolation.
 *
 * Host retrieval (after the instrumentation run):
 * `adb -s "$SERIAL" exec-out run-as ru.spgsroot.peachy cat \
 *   files/office-legacy-ppt-artifacts/<token>/default-plain.json > default-plain.json`
 * and likewise for `default-legacy.json`, `hidden-plain.json`, `hidden-legacy.json`.
 */
@RunWith(AndroidJUnit4::class)
class LegacyPptExtractionTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    private lateinit var sessionDir: File
    private lateinit var artifactDir: File
    private var keepArtifacts = false
    private var runId: String = ""

    @Test(timeout = 360_000)
    fun extractsPlainAndOptInJsonFromRealDocumentCommandValues() {
        requireHarnessMode()
        requireHarnessTarget()
        requireLocalRadiosOff()

        val engine = OfficeEngine.create(target)
        try {
            val availability = runBlocking { engine.availability() }
            val available = availability as? OfficeAvailability.Available
                ?: throw AssertionError(
                    "legacy ppt extraction refused: engine unavailable: " +
                        ((availability as? OfficeAvailability.Unavailable)?.reason?.name ?: "unknown"),
                )
            assertEquals(
                "legacy ppt extraction refused: unexpected pinned build",
                OfficeBuildManifest.PINNED_COMMIT.lowercase(),
                available.manifest.commit.lowercase(),
            )

            prepareSession()
            val default = extractFixture(engine, DEFAULT_FIXTURE)
            val hidden = extractFixture(engine, HIDDEN_FIXTURE)
            verifyExtraction(DEFAULT_FIXTURE, default)
            verifyExtraction(HIDDEN_FIXTURE, hidden)
        } finally {
            runBlocking { engine.shutdown() }
        }
    }

    @After
    fun removeOwnedSession() {
        if (this::sessionDir.isInitialized) {
            sessionDir.deleteRecursively()
            assertFalse("legacy ppt session cleanup failed", sessionDir.exists())
        }
        // Raw JSON is intentionally retained for the host checker; the runbook removes it after
        // the host validation. A failed run therefore leaves diagnostics behind, never a green
        // result.
        if (this::artifactDir.isInitialized && !keepArtifacts) {
            artifactDir.deleteRecursively()
            assertFalse("legacy ppt artifact cleanup failed", artifactDir.exists())
        }
    }

    // ---- gates ---------------------------------------------------------------------------

    private fun requireHarnessMode() {
        val mode = arguments.getString(HARNESS_ARG)
        if (mode != "1") {
            throw AssertionError(
                "legacy ppt extraction refused: pass -e $HARNESS_ARG 1 (got ${mode ?: "nothing"})",
            )
        }
        val token = arguments.getString(RUN_ID_ARG).orEmpty()
        if (!RUN_ID_PATTERN.matches(token)) {
            throw AssertionError(
                "legacy ppt extraction refused: pass -e $RUN_ID_ARG <fresh token matching " +
                    RUN_ID_PATTERN.pattern + ">",
            )
        }
        if (arguments.getString(ISOLATION_ARG) != token) {
            throw AssertionError(
                "legacy ppt extraction refused: pass -e $ISOLATION_ARG $token only after the " +
                    "disposable AVD's host egress isolation is established and recorded",
            )
        }
        if (arguments.getString(KEEP_ARTIFACTS_ARG) != "1") {
            throw AssertionError(
                "legacy ppt extraction refused: pass -e $KEEP_ARTIFACTS_ARG 1 so the raw JSON " +
                    "can be retrieved for accept_host.py",
            )
        }
        keepArtifacts = true
        runId = token
    }

    private fun requireHarnessTarget() {
        val app = target.applicationInfo
        assertTrue(
            "legacy ppt extraction refused: debuggable target required (debug APK only)",
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        assertTrue(
            "legacy ppt extraction refused: physical device (fingerprint=${Build.FINGERPRINT})",
            isEmulator(),
        )
        assertEquals(
            "legacy ppt extraction refused: x86_64 runtime required",
            "x86_64",
            Build.SUPPORTED_ABIS.firstOrNull(),
        )

        val appApk = File(app.sourceDir)
        val expectedApp = arguments.getString(APP_SHA_ARG)
            .orEmpty()
            .lowercase()
            .ifBlank { FROZEN_APP_APK_SHA256 }
        assertEquals(
            "legacy ppt extraction refused: installed app APK is not the frozen artifact",
            expectedApp,
            sha256(appApk),
        )

        val expectedTest = arguments.getString(TEST_APK_SHA_ARG).orEmpty().lowercase()
        assertTrue(
            "legacy ppt extraction refused: pass -e $TEST_APK_SHA_ARG <frozen test APK SHA-256>",
            SHA256_PATTERN.matches(expectedTest),
        )
        assertEquals(
            "legacy ppt extraction refused: installed test APK is not the frozen artifact",
            expectedTest,
            sha256(File(instrumentation.context.applicationInfo.sourceDir)),
        )

        val expectedEngine = arguments.getString(ENGINE_SHA_ARG)
            .orEmpty()
            .lowercase()
            .ifBlank { FROZEN_ENGINE_SHA256 }
        // This APK maps native libraries directly from the archive, without extracting them.
        val engineSha = ZipFile(appApk).use { apk ->
            val entry = apk.getEntry(ENGINE_LIBRARY_ENTRY)
                ?: throw AssertionError("legacy ppt extraction refused: missing $ENGINE_LIBRARY_ENTRY in APK")
            apk.getInputStream(entry).use(::sha256)
        }
        assertEquals(
            "legacy ppt extraction refused: native engine is not the frozen candidate",
            expectedEngine,
            engineSha,
        )
    }

    /**
     * Local radio controls only: no packet is sent and no external host is contacted. Host-level
     * egress isolation is an operator declaration (`officeLegacyPptIsolationAck`); the harness
     * repeats the readable local gates before touching the engine.
     */
    private fun requireLocalRadiosOff() {
        assertEquals(
            "cannot show isolation: airplane_mode_on must be 1",
            1,
            readGlobalInt(Settings.Global.AIRPLANE_MODE_ON, "airplane_mode_on"),
        )
        assertEquals(
            "cannot show isolation: wifi_on must be 0",
            0,
            readGlobalInt(Settings.Global.WIFI_ON, "wifi_on"),
        )
        assertEquals(
            "cannot show isolation: mobile_data must be 0",
            0,
            readGlobalInt(MOBILE_DATA_KEY, "mobile_data"),
        )
    }

    private fun readGlobalInt(key: String, label: String): Int = try {
        Settings.Global.getInt(target.contentResolver, key)
    } catch (missing: Settings.SettingNotFoundException) {
        throw AssertionError("cannot show isolation: $label is absent")
    } catch (denied: SecurityException) {
        throw AssertionError("cannot show isolation: $label is unreadable (${denied.javaClass.simpleName})")
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

    // ---- session -------------------------------------------------------------------------

    private fun prepareSession() {
        val documents = File(target.filesDir, OfficeEngine.DOCUMENTS_DIRECTORY)
        assertTrue(
            "legacy ppt extraction refused: documents dir",
            documents.isDirectory || documents.mkdirs(),
        )
        val session = File(documents, "office-legacy-ppt-$runId")
        assertFalse(
            "legacy ppt extraction refused: run id already used; choose a fresh token",
            session.exists(),
        )
        assertTrue("legacy ppt extraction refused: session dir", session.mkdirs())
        sessionDir = session

        val artifactRoot = File(target.filesDir, ARTIFACT_ROOT)
        assertTrue(
            "legacy ppt extraction refused: artifact root",
            artifactRoot.isDirectory || artifactRoot.mkdirs(),
        )
        val artifacts = File(artifactRoot, runId)
        assertFalse("legacy ppt extraction refused: artifact token already used", artifacts.exists())
        assertTrue("legacy ppt extraction refused: artifact dir", artifacts.mkdirs())
        artifactDir = artifacts
    }

    // ---- extraction ----------------------------------------------------------------------

    private fun extractFixture(engine: OfficeEngine, fixture: Fixture): Extraction {
        val bytes = instrumentation.context.assets.open(fixture.asset).use { it.readBytes() }
        assertEquals(
            "${fixture.id}: fixture bytes differ from the frozen corpus",
            fixture.sha256,
            sha256(bytes),
        )

        val workingDir = File(sessionDir, fixture.id)
        assertTrue("${fixture.id}: working dir", workingDir.mkdirs())
        val working = File(workingDir, WORKING_COPY)
        working.writeBytes(bytes)
        assertEquals("${fixture.id}: working copy differs", fixture.sha256, sha256(working))

        val info = runBlocking { engine.open(working, DocumentFormat.PPT) }.getOrElse {
            throw AssertionError("${fixture.id}: open failed: $it")
        }
        assertEquals("${fixture.id}: family", OfficeFamily.PRESENTATION, info.family)
        assertEquals("${fixture.id}: format", DocumentFormat.PPT, info.format)
        assertTrue("${fixture.id}: presentation must be read-only", info.readOnly)
        assertEquals("${fixture.id}: slide count", 4, info.partCount)

        // Raw JSON capture stays reflective so this test keeps the original unfiltered payload
        // checks; the production bridge exposes only the typed result. Every JNI call and every
        // read of OfficeEngine's document handle still runs on its private dispatcher.
        val dispatcher = engineField<CoroutineDispatcher>(engine, "dispatcher")
        val extraction = runBlocking {
            withContext(dispatcher) {
                val document = engineField<Any?>(engine, "document")
                assertNotNull("${fixture.id}: engine has no real Document handle", document)
                val getCommandValues = document!!.javaClass.getMethod(
                    "getCommandValues",
                    String::class.java,
                )
                val plain = commandValues(
                    document,
                    getCommandValues,
                    "${fixture.id}/plain",
                    ".uno:ExtractDocumentStructure?filter=slides",
                )
                val optIn = commandValues(
                    document,
                    getCommandValues,
                    "${fixture.id}/legacyText",
                    ".uno:ExtractDocumentStructure?filter=slides&legacyText=1",
                )
                plain to optIn
            }
        }

        val (plain, optIn) = extraction
        writeArtifact(fixture, "plain", plain)
        writeArtifact(fixture, "legacy", optIn)

        // The production bridge must derive the same content from the document that is still open
        // here: one engine-thread `getCommandValues` call, no reopen and no export/render fallback.
        val slides = runBlocking { engine.extractLegacyPresentation() }.getOrElse {
            throw AssertionError("${fixture.id}: typed legacy extraction failed: $it")
        }
        assertTrue(
            "${fixture.id}: typed extraction must keep the document open",
            engine.state.value is OfficeEngineState.Open,
        )

        assertEquals(
            "${fixture.id}: source bytes changed during extraction",
            fixture.sha256,
            sha256(working),
        )
        val closed = runBlocking { engine.close() }
        assertTrue("${fixture.id}: close failed: ${closed.exceptionOrNull()}", closed.isSuccess)
        assertTrue(
            "${fixture.id}: close must publish Idle",
            engine.state.value is OfficeEngineState.Idle,
        )
        assertTrue("${fixture.id}: working dir cleanup", workingDir.deleteRecursively())
        return Extraction(plain = plain, optIn = optIn, slides = slides)
    }

    private fun verifyExtraction(fixture: Fixture, extraction: Extraction) {
        assertPlainHasNoOptIn(JSONObject(extraction.plain), fixture.id)
        assertOptInShape(JSONObject(extraction.optIn), fixture)
        assertEquals(
            "${fixture.id}: typed legacy interchange",
            expectedSlides(fixture.hiddenSlideIndex),
            extraction.slides,
        )
    }

    private fun commandValues(
        document: Any,
        method: Method,
        label: String,
        command: String,
    ): String {
        val result = try {
            method.invoke(document, command)
        } catch (failure: InvocationTargetException) {
            throw AssertionError("$label: getCommandValues threw: ${failure.targetException}")
        } catch (failure: Exception) {
            throw AssertionError("$label: getCommandValues failed: ${failure.javaClass.simpleName}")
        }
        assertNotNull("$label: getCommandValues returned null", result)
        val text = result as? String
            ?: throw AssertionError(
                "$label: getCommandValues returned ${result?.javaClass?.name ?: "null"}",
            )
        assertTrue("$label: getCommandValues returned empty JSON", text.isNotBlank())
        return text
    }

    private fun assertPlainHasNoOptIn(json: JSONObject, label: String) {
        val structure = json.getJSONObject("DocStructure")
        assertEquals("$label/plain: SlideCount", 4, structure.getInt("SlideCount"))
        val slides = structure.getJSONObject("Slides")
        assertEquals("$label/plain: slide entries", 4, slides.length())
        for (index in 0 until 4) {
            val slide = slides.getJSONObject("Slide $index")
            for (key in SLIDE_OPT_IN_KEYS) {
                assertFalse("$label/plain: Slide $index unexpectedly has $key", slide.has(key))
            }
            val objects = slide.optJSONObject("Objects") ?: continue
            objects.keys().forEach { objectKey ->
                val obj = objects.getJSONObject(objectKey)
                for (key in OBJECT_OPT_IN_KEYS) {
                    assertFalse(
                        "$label/plain: Slide $index $objectKey unexpectedly has $key",
                        obj.has(key),
                    )
                }
            }
        }
    }

    private fun assertOptInShape(json: JSONObject, fixture: Fixture) {
        val structure = json.getJSONObject("DocStructure")
        assertEquals("${fixture.id}/legacyText: SlideCount", 4, structure.getInt("SlideCount"))
        val slides = structure.getJSONObject("Slides")
        assertEquals("${fixture.id}/legacyText: slide entries", 4, slides.length())

        for (index in 0 until 4) {
            val slide = slides.getJSONObject("Slide $index")
            assertEquals(
                "${fixture.id}/legacyText: Slide $index Hidden",
                index == fixture.hiddenSlideIndex,
                slide.getBoolean("Hidden"),
            )
        }

        val notesSlide = slides.getJSONObject("Slide 2")
        assertEquals("${fixture.id}/legacyText: NotesParaCount", 1, notesSlide.getInt("NotesParaCount"))
        val notes = notesSlide.getJSONArray("Notes")
        assertEquals("${fixture.id}/legacyText: Notes length", 1, notes.length())
        assertEquals("${fixture.id}/legacyText: notes text", NOTES_TEXT, notes.getString(0))
        for (index in listOf(0, 1, 3)) {
            assertFalse(
                "${fixture.id}/legacyText: Slide $index unexpectedly has notes",
                slides.getJSONObject("Slide $index").has("Notes"),
            )
        }

        val tableSlide = slides.getJSONObject("Slide 1")
        val table = tableObject(tableSlide)
            ?: throw AssertionError("${fixture.id}/legacyText: table object absent")
        assertEquals("${fixture.id}/legacyText: table rows", 2, table.getInt("Rows"))
        assertEquals("${fixture.id}/legacyText: table columns", 2, table.getInt("Columns"))
        val texts = table.getJSONObject("Texts")
        val cells = (0 until 4).map { index ->
            texts.getJSONObject("Text $index").getJSONArray("Paragraphs").getString(0)
        }
        assertEquals("${fixture.id}/legacyText: table cells", TABLE_CELLS, cells)
    }

    private fun tableObject(slide: JSONObject): JSONObject? {
        val objects = slide.optJSONObject("Objects") ?: return null
        return objects.keys().asSequence()
            .map { objects.getJSONObject(it) }
            .firstOrNull { it.optBoolean("IsTable") }
    }

    private fun writeArtifact(fixture: Fixture, kind: String, json: String) {
        val file = File(artifactDir, "${fixture.id}-$kind.json")
        file.writeText(json, Charsets.UTF_8)
        assertTrue("${fixture.id}/$kind: raw JSON is empty", file.isFile && file.length() > 0L)
        assertEquals("${fixture.id}/$kind: raw JSON changed on write", json, file.readText(Charsets.UTF_8))
    }

    // ---- helpers -------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun <T> engineField(engine: OfficeEngine, name: String): T {
        val field = OfficeEngine::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(engine) as T
    }

    private fun sha256(file: File): String = file.inputStream().use(::sha256)

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun expectedSlides(hiddenSlideIndex: Int?): List<OfficeLegacySlide> = listOf(
        OfficeLegacySlide(
            title = "Слайд 1 — Начало",
            hidden = false,
            blocks = listOf(OfficeLegacyBlock.Paragraph("Кириллица: привет, мир")),
            notes = emptyList(),
        ),
        OfficeLegacySlide(
            title = "Слайд 2 — Таблица",
            hidden = false,
            blocks = listOf(
                OfficeLegacyBlock.Paragraph("Таблица: проверка ячеек"),
                OfficeLegacyBlock.Table(TABLE_CELLS.chunked(2)),
            ),
            notes = emptyList(),
        ),
        OfficeLegacySlide(
            title = "Слайд 3 — Заметки",
            hidden = hiddenSlideIndex == 2,
            blocks = listOf(OfficeLegacyBlock.Paragraph("Текст третьего слайда")),
            notes = listOf(NOTES_TEXT),
        ),
        OfficeLegacySlide(
            title = "Слайд 4 — Конец",
            hidden = false,
            blocks = listOf(OfficeLegacyBlock.Paragraph("Последний слайд")),
            notes = emptyList(),
        ),
    )

    private data class Extraction(
        val plain: String,
        val optIn: String,
        val slides: List<OfficeLegacySlide>,
    )

    private data class Fixture(
        val id: String,
        val asset: String,
        val sha256: String,
        val hiddenSlideIndex: Int?,
    )

    private companion object {
        const val HARNESS_ARG = "officeLegacyPpt"
        const val RUN_ID_ARG = "officeLegacyPptRunId"
        const val ISOLATION_ARG = "officeLegacyPptIsolationAck"
        const val KEEP_ARTIFACTS_ARG = "officeLegacyPptKeepArtifacts"
        const val APP_SHA_ARG = "officeApkSha256"
        const val TEST_APK_SHA_ARG = "officeTestApkSha256"
        const val ENGINE_SHA_ARG = "officeEngineLibSha256"

        const val ARTIFACT_ROOT = "office-legacy-ppt-artifacts"
        const val WORKING_COPY = "working.ppt"
        const val ENGINE_LIBRARY_ENTRY = "lib/x86_64/liblo-native-code.so"
        const val MOBILE_DATA_KEY = "mobile_data"

        const val FROZEN_APP_APK_SHA256 =
            "83edd788e668487dd78bea7fa6d030d0d65345c41a82080593ceabbdcf1c48ff"
        const val FROZEN_ENGINE_SHA256 =
            "0a6c5fea7ab6b905755cd9c46e6c69fccafeedfb8b7a4ceff4f97905b1cf5c0b"

        const val NOTES_TEXT = "Заметки докладчика: проверка кириллицы."
        val TABLE_CELLS = listOf("Ключ", "Значение", "альфа", "42")
        val SLIDE_OPT_IN_KEYS = listOf("Hidden", "Notes", "NotesParaCount")
        val OBJECT_OPT_IN_KEYS = listOf("IsTable", "Rows", "Columns")

        val RUN_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{7,63}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        val DEFAULT_FIXTURE = Fixture(
            id = "default",
            asset = "legacy-ppt/default.ppt",
            sha256 = "a348b088b77904c08159c3986404a7d00e10f49608088c2795d7abd6a6ca4df0",
            hiddenSlideIndex = null,
        )
        val HIDDEN_FIXTURE = Fixture(
            id = "hidden",
            asset = "legacy-ppt/hidden-slide.ppt",
            sha256 = "302b7d60aa71157bafe8ca933158b63e322044a6d3fec7c37e3dadbdbe4684ff",
            hiddenSlideIndex = 2,
        )
    }
}
