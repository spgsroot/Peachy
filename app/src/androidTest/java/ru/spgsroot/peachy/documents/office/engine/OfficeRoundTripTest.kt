package ru.spgsroot.peachy.documents.office.engine

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Document
import org.w3c.dom.Element
import ru.spgsroot.peachy.documents.DocumentFormat

/**
 * Native Office acceptance harness for the uncertain import/edit/export contracts.
 *
 * Scope (only the public bridge, no mocks, no fake engine):
 * - Word: eight inputs (`.docx .docm .dotx .doc .odt .rtf .md .txt`) exported to every Text
 *   target the pinned matrix exposes — `.docx .odt .rtf .html .md .txt` and PDF (7 targets; the
 *   matrix defines seven, there is no eighth Text export and no macro-enabled target).
 * - Excel: seven inputs (`.xlsx .xlsm .xlsb .xls .ods .csv .tsv`) exported to `.xlsx`, `.csv` and PDF.
 * - PowerPoint: modern `.pptx .ppsx .potx` exported to `.pptx` and PDF. Legacy `.ppt` is
 *   deliberately not a case and not a substitute: plan §4 keeps it an explicitly refused gap.
 *
 * Assertions are consumer-visible, not `Result.isSuccess`/non-empty:
 * - every export is reopened by an independent reader in this test (ZIP+DOM for OOXML/ODF,
 *   an RTF tokeniser, HTML tag stripping, UTF-8 text, and the platform `PdfRenderer` for PDF) and
 *   must contain the corpus text of its source;
 * - the edited `.docx` must contain the inserted Cyrillic paragraph with a real bold run;
 * - the edited `.xlsx` must contain a changed numeric cell, a formula with its recalculated cached
 *   value, bold cell formatting, and the row/column insertion shifts;
 * - the working copy bytes must equal the fixture bytes before and after every case.
 *
 * Fail-closed, opt-in only. Required arguments:
 * `-e officeRoundTrip 1 -e officeRoundTripRunId <fresh token> -e officeIsolationAck <same token>`.
 * The ack is an operator declaration that a disposable x86_64 emulator is externally egress
 * isolated; this process cannot prove host isolation from inside and sends no traffic to try.
 * The target must be a debuggable `x86_64` emulator running the frozen Office APK
 * (`af8aa3a646555ec8a43033cb40bca128957008a22628a0dc7072b453220f99fc` unless
 * `-e officeApkSha256 <sha>` deliberately re-freezes it) with local radios
 * `airplane_mode_on=1`, `wifi_on=0`, `mobile_data=0`. On the Play Store image `svc data disable`
 * can leave the `mobile_data` preference at 1 while the radio is unregistered. Before the test
 * process is launched the operator must run, in this order, and then read all three keys back:
 * `cmd connectivity airplane-mode enable`, `svc wifi disable`, `svc data disable`,
 * `settings put global mobile_data 0`; the read-back must be exactly `1/0/0` and
 * `dumpsys telephony.registry` must show voice and data `OUT_OF_SERVICE`. The harness repeats the
 * three-key gate before touching the engine and refuses on any mismatch, so a stale preference
 * cannot be accepted as a deviation.
 *
 * Run (parent-owned build; host timeout must bound a native hang):
 * `adb -s "$SERIAL" shell am instrument -w -r -e officeRoundTrip 1 \
 *  -e officeRoundTripRunId <token> -e officeIsolationAck <token> \
 *  -e class ru.spgsroot.peachy.documents.office.engine.OfficeRoundTripTest \
 *  ru.spgsroot.peachy.test/androidx.test.runner.AndroidJUnitRunner`
 * wrapped in `timeout --signal=KILL 1500`.
 *
 * Exports stay inside `files/documents/office-roundtrip-<token>/`, which `@After` deletes
 * together with the working copies. `-e officeKeepArtifacts 1` copies only synthetic exports
 * to `files/office-roundtrip-artifacts/<token>/<case>/`: `office-<target>.<ext>` for the base
 * matrix and `edited.docx` or `edited.xlsx` for the additional edit checks (never session inputs).
 * Safe extraction of that token directory, then cleanup:
 * `adb -s "$SERIAL" exec-out run-as ru.spgsroot.peachy tar -C files -cf - \
 *    office-roundtrip-artifacts/<token> > roundtrip-<token>.tar`
 * `python .impeccable/fixtures/office/verify_roundtrip_exports.py <extracted-dir>`
 * `adb -s "$SERIAL" shell run-as ru.spgsroot.peachy rm -rf files/office-roundtrip-artifacts/<token>`
 * With the default (`officeKeepArtifacts` unset/0) no artifact directory remains at all.
 *
 * Every case is attempted; individual errors are collected and reported together, and the test
 * fails if any required case fails. There is no skip or assumption path.
 */
@RunWith(AndroidJUnit4::class)
class OfficeRoundTripTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    private lateinit var sessionDir: File
    private var artifactDir: File? = null
    private var keepArtifacts = false
    private var runId: String = ""
    private val failures = ArrayList<String>()

    @Test(timeout = 1_200_000)
    fun roundTripContractsAreConsumerVisible() {
        requireHarnessMode()
        requireHarnessTarget()
        requireLocalRadiosOff()
        val allCases = WORD_CASES + EXCEL_CASES + DECK_CASES
        val requestedCase = arguments.getString(CASE_ARG)
        val cases = if (requestedCase == null) {
            allCases
        } else {
            val case = allCases.firstOrNull { it.id == requestedCase }
                ?: throw AssertionError("office round-trip refused: unknown case '$requestedCase'")
            listOf(case)
        }

        val engine = OfficeEngine.create(target)
        try {
            val availability = runBlocking { engine.availability() }
            val available = availability as? OfficeAvailability.Available
                ?: throw AssertionError(
                    "office round-trip refused: engine unavailable: " +
                        ((availability as? OfficeAvailability.Unavailable)?.reason?.name ?: "unknown"),
                )
            assertEquals(
                "office round-trip refused: unexpected pinned build",
                OfficeBuildManifest.PINNED_COMMIT.lowercase(),
                available.manifest.commit.lowercase(),
            )

            prepareSession()

            for (case in cases) execute(engine, case)

            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "office round-trip failures (${failures.size}):\n" + failures.joinToString("\n"),
                )
            }
        } finally {
            runBlocking { engine.shutdown() }
        }
    }

    @After
    fun removeSession() {
        if (this::sessionDir.isInitialized) {
            sessionDir.deleteRecursively()
            assertFalse("office round-trip session cleanup failed", sessionDir.exists())
        }
        if (!keepArtifacts) {
            artifactDir?.let { dir ->
                dir.deleteRecursively()
                assertFalse("office round-trip artifact cleanup failed", dir.exists())
            }
        }
    }

    // ---- gates ---------------------------------------------------------------------------

    private fun requireHarnessMode() {
        val mode = arguments.getString(SMOKE_ARG)
        if (mode != "1") {
            throw AssertionError(
                "office round-trip refused: pass -e $SMOKE_ARG 1 (got ${mode ?: "nothing"})",
            )
        }
        val token = arguments.getString(RUN_ID_ARG).orEmpty()
        if (!RUN_ID_PATTERN.matches(token)) {
            throw AssertionError(
                "office round-trip refused: pass -e $RUN_ID_ARG <fresh token matching " +
                    RUN_ID_PATTERN.pattern + ">",
            )
        }
        val ack = arguments.getString(ISOLATION_ARG).orEmpty()
        if (ack != token) {
            throw AssertionError(
                "office round-trip refused: pass -e $ISOLATION_ARG $token only after the " +
                    "disposable AVD's host egress isolation is established and recorded",
            )
        }
        runId = token
        keepArtifacts = arguments.getString(KEEP_ARTIFACTS_ARG) == "1"
    }

    private fun requireHarnessTarget() {
        val app = target.applicationInfo
        assertTrue(
            "office round-trip refused: debuggable target required (debug APK only)",
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        assertTrue(
            "office round-trip refused: physical device (fingerprint=${Build.FINGERPRINT})",
            isEmulator(),
        )
        assertEquals(
            "office round-trip refused: x86_64 runtime required",
            "x86_64",
            Build.SUPPORTED_ABIS.firstOrNull(),
        )
        val expected = (arguments.getString(APK_SHA_ARG) ?: FROZEN_APK_SHA256).lowercase()
        val actual = sha256(File(app.sourceDir))
        assertEquals(
            "office round-trip refused: installed APK is not the frozen Office artifact",
            expected,
            actual,
        )
    }

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
            "cannot show isolation: mobile_data must be 0 (record and disable it before the run)",
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
        assertTrue("office round-trip refused: documents dir", documents.isDirectory || documents.mkdirs())
        val candidate = File(documents, "office-roundtrip-$runId")
        assertFalse("office round-trip refused: run id already used; choose a fresh token", candidate.exists())
        assertTrue("office round-trip refused: session dir", candidate.mkdirs())
        sessionDir = candidate

        if (keepArtifacts) {
            val root = File(target.filesDir, ARTIFACT_ROOT)
            assertTrue("office round-trip refused: artifact root", root.isDirectory || root.mkdirs())
            val dir = File(root, runId)
            assertFalse("office round-trip refused: artifact token already used", dir.exists())
            assertTrue("office round-trip refused: artifact dir", dir.mkdirs())
            artifactDir = dir
        }
    }

    // ---- cases ---------------------------------------------------------------------------

    private fun execute(engine: OfficeEngine, case: Case) {
        val working = File(sessionDir, "working.${case.format.extension}")
        try {
            val bytes = fixtureBytes(case)
            working.writeBytes(bytes)
            val digest = sha256(working)

            reportProgress(case, "open")
            val info = runBlocking { engine.open(working, case.format) }.getOrElse {
                throw AssertionError("open failed: ${failureText(it)}")
            }
            assertEquals("${case.id}: family", case.family, info.family)
            assertEquals("${case.id}: format", case.format, info.format)
            assertEquals("${case.id}: read-only policy", case.readOnly, info.readOnly)
            if (case.family == OfficeFamily.PRESENTATION) {
                assertEquals("${case.id}: slide count", 3, info.partCount)
            }

            when (case.id) {
                WORD_EDIT_CASE -> {
                    reportProgress(case, "edit")
                    editWord(engine)
                }
                SHEET_EDIT_CASE -> {
                    reportProgress(case, "edit")
                    editSheet(engine)
                }
                XLSB_EDIT_CASE -> {
                    reportProgress(case, "edit")
                    editXlsb(engine)
                }
            }

            for (exportTarget in case.targets) {
                try {
                    val exportDir = File(sessionDir, "exports/${case.id}")
                    reportProgress(case, "export ${exportTarget.name}")
                    val plan = runBlocking { engine.export(exportTarget, exportDir) }.getOrElse {
                        throw AssertionError("export failed: ${failureText(it)}")
                    }
                    assertEquals("export target", exportTarget, plan.target)
                    assertTrue(
                        "staged file missing",
                        plan.stagedFile.isFile && plan.stagedFile.length() > 0L,
                    )
                    // Keep the export for the independent host reader even if this reader rejects it.
                    if (keepArtifacts) copyArtifact(case, exportTarget, plan.stagedFile)
                    reportProgress(case, "verify ${exportTarget.name}")
                    verifyExport(case, exportTarget, plan.stagedFile)
                } catch (failure: AssertionError) {
                    reportFailure(case, "${exportTarget.name}: ${failure.message ?: "assertion"}")
                } catch (interrupted: InterruptedException) {
                    throw interrupted
                } catch (failure: Exception) {
                    reportFailure(
                        case,
                        "${exportTarget.name}: ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
                    )
                }
            }

            if (case.family == OfficeFamily.TEXT && case.id != WORD_EDIT_CASE) {
                val anchor = if (case.id == "word-md") {
                    "Plain paragraph with bold and italic text — Печать документов."
                } else {
                    WORD_PLAIN_PARAGRAPH
                }
                reportProgress(case, "additional writer edit")
                editWord(engine, anchor, selectAfterInsert = case.id == "word-doc")
                val changed = exportEditedCase(engine, case, OfficeExportTarget.DOCX)
                val paragraphs = parseDocx(changed).paragraphs
                val index = paragraphs.indexOfFirst { it.text == anchor }
                assertTrue("${case.id}: original paragraph missing", index >= 0)
                val paragraph = paragraphs.getOrNull(index + 1)
                assertEquals("${case.id}: adjacent insertion", WORD_INSERTED_TEXT, paragraph?.text)
                assertTrue("${case.id}: insertion is not entirely bold", paragraph!!.runs.all {
                    it.text.isEmpty() || it.bold
                })
            }
            if (case.family == OfficeFamily.SPREADSHEET && case.id != SHEET_EDIT_CASE && case.id != XLSB_EDIT_CASE) {
                reportProgress(case, "additional calc edit")
                editSheet(engine)
                val changed = exportEditedCase(engine, case, OfficeExportTarget.XLSX)
                val sheet = parseXlsx(changed).sheets.first()
                assertCellNumber(sheet, "A1", 150.0, case.id)
                assertCellBold(sheet, "A2", case.id)
                assertCellFormula(sheet, "A2", "A1*2", case.id)
                assertCellCached(sheet, "A2", 300.0, case.id)
                assertCellBlank(sheet, "A3", case.id)
                assertCellText(sheet, "A4", "Гайка", case.id)
                assertCellBlank(sheet, "B1", case.id)
                assertCellText(sheet, "C1", "Qty", case.id)
            }

            assertEquals("${case.id}: working copy digest changed", digest, sha256(working))
            assertEquals("${case.id}: working copy size changed", bytes.size.toLong(), working.length())
            assertEquals(
                "${case.id}: working copy renamed",
                "working.${case.format.extension}",
                working.name,
            )
        } catch (failure: AssertionError) {
            reportFailure(case, failure.message ?: "assertion")
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (failure: Exception) {
            reportFailure(case, "${failure.javaClass.simpleName}: ${failure.message ?: ""}")
        } finally {
            reportProgress(case, "close")
            val closed = runBlocking { engine.close() }
            if (closed.isFailure) {
                reportFailure(case, "close failed")
            } else if (engine.state.value !is OfficeEngineState.Idle) {
                reportFailure(case, "close did not publish Idle")
            }
            working.delete()
        }
    }

    // Send a raw instrumentation checkpoint before any JNI call can block the test thread.
    private fun reportProgress(case: Case, stage: String) {
        instrumentation.sendStatus(2, Bundle().apply {
            putString("officeCase", case.id)
            putString("officeStage", stage)
        })
    }

    private fun reportFailure(case: Case, reason: String) {
        failures.add("${case.id}: $reason")
        reportProgress(case, "failure: $reason")
    }

    private fun fixtureBytes(case: Case): ByteArray {
        val bytes = instrumentation.context.assets.open(case.asset).use { it.readBytes() }
        assertEquals("${case.id}: fixture bytes differ from the frozen corpus", case.sha256, sha256(bytes))
        return bytes
    }

    private fun editWord(
        engine: OfficeEngine,
        anchor: String = WORD_PLAIN_PARAGRAPH,
        selectAfterInsert: Boolean = false,
    ) = withLokDocument(engine) { document ->
        val getSelection = document.javaClass.getMethod("getTextSelection", String::class.java)
        suspend fun awaitSelection(expected: String, step: String) {
            var last = ""
            val selecting = withTimeoutOrNull(5_000) {
                do {
                    last = (getSelection.invoke(document, "text/plain;charset=utf-8") as? String).orEmpty()
                    if (last.trimStart('\r', '\n') == expected) break
                    delay(25)
                } while (true)
                true
            }
            assertTrue("word edit: $step (selected '$last')", selecting == true)
        }

        // The Android registry omits Ctrl+Down. Navigate by fixed UNO slots and
        // confirm that the selection is the plain paragraph, not Heading 1.
        val postCommand = document.javaClass.getMethod(
            "postUnoCommand", String::class.java, String::class.java, java.lang.Boolean.TYPE,
        )
        postCommand.invoke(document, ".uno:GoToNextPara", null, false)
        postCommand.invoke(document, ".uno:EndOfParaSel", null, false)
        awaitSelection(anchor, "plain paragraph not selected")
        document.javaClass.getMethod("resetSelection").invoke(document)
        awaitSelection("", "plain paragraph still selected before insertion")
        // EndOfParaSel left the point at the end of the plain paragraph. A second
        // end-of-paragraph move from there advances to the next paragraph.
        engine.pressKey(OfficeKey.RETURN).getOrElse { throw AssertionError("word edit: new paragraph", it) }
        if (!selectAfterInsert) engine.apply(OfficeCommand.BOLD).getOrThrow()
        engine.insertText(WORD_INSERTED_TEXT).getOrThrow()
        if (selectAfterInsert) {
            // The .doc fixture's paragraph mark is bold, so its new paragraph starts bold.
            verifyWordInsertionWeight(engine, "inherited", bold = true)
            postCommand.invoke(document, ".uno:GoToStartOfPara", null, false)
            postCommand.invoke(document, ".uno:EndOfParaSel", null, false)
            awaitSelection(WORD_INSERTED_TEXT, "inserted paragraph not selected")
            engine.apply(OfficeCommand.BOLD).getOrThrow()
            verifyWordInsertionWeight(engine, "plain", bold = false)
            awaitSelection(WORD_INSERTED_TEXT, "formatting cleared the selection")
            engine.apply(OfficeCommand.BOLD).getOrThrow()
        }
    }

    private suspend fun verifyWordInsertionWeight(engine: OfficeEngine, snapshot: String, bold: Boolean) {
        val file = engine.export(OfficeExportTarget.DOCX, File(sessionDir, snapshot)).getOrThrow().stagedFile
        val paragraph = parseDocx(file).paragraphs.single { it.text == WORD_INSERTED_TEXT }
        assertTrue("word edit: $snapshot insertion must have bold=$bold",
            paragraph.runs.all { it.text.isEmpty() || it.bold == bold })
    }

    /**
     * Deterministic Calc sequence from the default A1 selection: numeric entry, formula entry, cell
     * formatting, then a row and a column insertion. The exported workbook is parsed afterwards;
     * these are expectations about the document, never about return values.
     */
    private fun editSheet(engine: OfficeEngine) = withLokDocument(engine) { document ->
        awaitCell(document, 0, 0, "A1 on open")
        engine.insertText("100").getOrThrow()
        engine.pressKey(OfficeKey.RETURN).getOrThrow()
        awaitCell(document, 0, 1, "A2 after entering A1")
        engine.insertText("=A1*2").getOrThrow()
        engine.pressKey(OfficeKey.RETURN).getOrThrow()
        awaitCell(document, 0, 2, "A3 after entering the formula")
        engine.pressKey(OfficeKey.UP).getOrThrow()
        awaitCell(document, 0, 1, "A2 after moving up")
        engine.pressKey(OfficeKey.UP).getOrThrow()
        awaitCell(document, 0, 0, "A1 after moving up")
        engine.insertText("150").getOrThrow()
        engine.pressKey(OfficeKey.RETURN).getOrThrow()
        awaitCell(document, 0, 1, "A2 after changing A1")
        engine.apply(OfficeCommand.BOLD).getOrThrow()
        engine.apply(OfficeCommand.INSERT_ROWS_AFTER).getOrThrow()
        engine.apply(OfficeCommand.INSERT_COLUMNS_AFTER).getOrThrow()
    }

    private fun editXlsb(engine: OfficeEngine) = withLokDocument(engine) { document ->
        awaitCell(document, 0, 0, "A1")
        for (column in 1..2) {
            engine.pressKey(OfficeKey.RIGHT).getOrElse { throw AssertionError("XLSB edit: move right", it) }
            awaitCell(document, column, 0, if (column == 1) "B1" else "C1")
        }
        engine.insertText("=B1*2").getOrElse { throw AssertionError("XLSB edit: formula", it) }
        engine.pressKey(OfficeKey.RETURN).getOrElse { throw AssertionError("XLSB edit: commit formula", it) }
        awaitCell(document, 2, 1, "C2 after Return")
    }

    private suspend fun awaitCell(document: Any, column: Int, row: Int, label: String) {
        // A posted key is not an input acknowledgment; read the real grid cursor.
        val commandValues = document.javaClass.getMethod("getCommandValues", String::class.java)
        var last = ""
        val reached = withTimeoutOrNull(5_000) {
            while (true) {
                val response = commandValues.invoke(document, ".uno:CellCursor") as? String
                last = response?.let { JSONObject(it).optString("commandValues") }.orEmpty()
                val fields = last.split(',')
                if (fields.size == 6 &&
                    fields[4].trim().toIntOrNull() == column &&
                    fields[5].trim().toIntOrNull() == row
                ) break
                delay(25)
            }
            true
        }
        assertTrue("Calc edit: cursor did not reach $label (last: $last)", reached == true)
    }

    private fun withLokDocument(engine: OfficeEngine, action: suspend (Any) -> Unit) = runBlocking {
        val dispatcher = OfficeEngine::class.java.getDeclaredField("dispatcher").let {
            it.isAccessible = true
            it.get(engine) as CoroutineDispatcher
        }
        withContext(dispatcher) {
            val document = OfficeEngine::class.java.getDeclaredField("document").let {
                it.isAccessible = true
                checkNotNull(it.get(engine))
            }
            action(document)
        }
    }

    private fun exportEditedCase(engine: OfficeEngine, case: Case, target: OfficeExportTarget): File {
        val dir = File(sessionDir, "edited/${case.id}")
        val staged = runBlocking { engine.export(target, dir) }.getOrThrow().stagedFile
        if (keepArtifacts) {
            val root = checkNotNull(artifactDir)
            staged.copyTo(File(root, "${case.id}/edited.${target.extension}"))
        }
        return staged
    }

    private fun copyArtifact(case: Case, exportTarget: OfficeExportTarget, staged: File) {
        val root = artifactDir ?: return
        val caseDir = File(root, case.id)
        assertTrue("${case.id}: artifact case dir", caseDir.isDirectory || caseDir.mkdirs())
        val destination = File(caseDir, artifactName(exportTarget))
        staged.copyTo(destination, overwrite = true)
        assertTrue(
            "${case.id}/${exportTarget.name}: artifact copy differs",
            destination.isFile && destination.length() == staged.length(),
        )
    }

    private fun artifactName(exportTarget: OfficeExportTarget): String =
        "office-${exportTarget.name.lowercase()}.${exportTarget.extension}"

    // ---- word verification ---------------------------------------------------------------

    private fun verifyWordExport(case: Case, exportTarget: OfficeExportTarget, file: File) {
        val label = "${case.id}/${exportTarget.name}"
        when (exportTarget) {
            OfficeExportTarget.DOCX -> {
                val doc = parseDocx(file)
                assertContainsAll(label, doc.paragraphs.joinToString("\n") { it.text }, case.needles)
                assertFalse("$label: export re-emitted a macro part", doc.hasVbaPart)
                assertTrue(
                    "$label: export is not a plain WordprocessingML document",
                    doc.contentTypes.contains("wordprocessingml.document.main") &&
                        !doc.contentTypes.contains("macroEnabled") &&
                        !doc.contentTypes.contains("template.main"),
                )
                if (case.id == WORD_EDIT_CASE) {
                    val plainIndex = doc.paragraphs.indexOfFirst { it.text == WORD_PLAIN_PARAGRAPH }
                    assertTrue("$label: original plain paragraph absent", plainIndex >= 0)
                    val paragraph = doc.paragraphs.getOrNull(plainIndex + 1)
                    assertEquals("$label: paragraph immediately after original", WORD_INSERTED_TEXT, paragraph?.text)
                    assertTrue(
                        "$label: inserted text is not entirely bold (runs may be split)",
                        paragraph!!.runs.all { it.text.isEmpty() || it.bold },
                    )
                }
                if (case.id in FORMATTED_WORD_CASES) {
                    val runs = doc.paragraphs.flatMap { it.runs }
                    assertTrue("$label: bold run absent", runs.any { it.bold && it.text.contains("Bold") })
                    assertTrue("$label: italic run absent", runs.any { it.italic && it.text.contains("italic") })
                    assertTrue(
                        "$label: underline run absent",
                        runs.any { it.underline && it.text.contains("underline") },
                    )
                }
            }

            OfficeExportTarget.ODT -> {
                val doc = parseOdt(file)
                assertEquals(
                    "$label: ODF mimetype",
                    "application/vnd.oasis.opendocument.text",
                    doc.mimetype,
                )
                assertContainsAll(label, doc.text, case.needles)
            }

            OfficeExportTarget.RTF -> {
                val bytes = file.readBytes()
                val head = String(bytes, 0, minOf(bytes.size, 12), Charsets.ISO_8859_1)
                assertTrue("$label: RTF header absent", head.contains("{\\rtf"))
                assertContainsAll(label, rtfText(bytes), case.needles)
            }

            OfficeExportTarget.HTML -> {
                assertContainsAll(label, htmlText(String(file.readBytes(), Charsets.UTF_8)), case.needles)
            }

            OfficeExportTarget.MARKDOWN -> {
                assertContainsAll(label, markdownText(String(file.readBytes(), Charsets.UTF_8)), case.needles)
            }

            OfficeExportTarget.TXT -> {
                assertContainsAll(label, String(file.readBytes(), Charsets.UTF_8), case.needles)
            }

            OfficeExportTarget.PDF_TEXT -> assertPdf(file, label, minimumPages = 1, exactPages = null)

            else -> throw AssertionError("$label: unexpected Word target")
        }
    }

    // ---- sheet verification --------------------------------------------------------------

    private fun verifySheetExport(case: Case, exportTarget: OfficeExportTarget, file: File) {
        val label = "${case.id}/${exportTarget.name}"
        when (exportTarget) {
            OfficeExportTarget.XLSX -> {
                val book = parseXlsx(file)
                assertTrue("$label: workbook has no sheets", book.sheets.isNotEmpty())
                assertFalse("$label: export re-emitted a macro part", book.hasVbaPart)
                assertFalse("$label: macro-enabled content type", book.contentTypes.contains("macroEnabled"))
                when (case.id) {
                    SHEET_EDIT_CASE -> verifyEditedWorkbook(book, label)
                    "excel-csv" -> {
                        val sheet = book.sheets.first()
                        assertCellText(sheet, "A1", "Product", label)
                        assertCellText(sheet, "A2", "Widget, large", label)
                        assertCellNumber(sheet, "B2", 3.0, label)
                        assertCellNumber(sheet, "C2", 12.5, label)
                        assertCellNumber(sheet, "D2", 37.5, label)
                    }

                    "excel-tsv" -> {
                        val sheet = book.sheets.first()
                        assertCellText(sheet, "A1", "Product", label)
                        assertCellText(sheet, "B1", "Qty", label)
                        assertCellText(sheet, "C1", "Note", label)
                        assertCellText(sheet, "A2", "Widget", label)
                        assertCellNumber(sheet, "B2", 3.0, label)
                        assertCellText(sheet, "C2", "Кириллица и Latin", label)
                    }

                    XLSB_EDIT_CASE -> {
                        val sheet = book.sheets.first()
                        assertCellText(sheet, "A1", "Lorem", label)
                        assertCellFormula(sheet, "C1", "B1*2", label)
                        assertCellCached(sheet, "C1", 222.0, label)
                        val rich = book.sheet("rich test")
                        assertTrue("$label: rich test sheet absent", rich != null)
                        assertCellText(
                            rich!!,
                            "A1",
                            "The quick brown fox jumps over the lazy dog",
                            label,
                        )
                    }

                    else -> {
                        val sheet = book.sheet("Data") ?: book.sheets.first()
                        assertCellText(sheet, "A1", "Product", label)
                        assertCellText(sheet, "A2", "Widget", label)
                        assertCellText(sheet, "A3", "Гайка", label)
                        assertCellNumber(sheet, "D4", 80.0, label)
                    }
                }
            }

            OfficeExportTarget.CSV -> verifyCsv(case, parseCsv(file, ','), label)

            OfficeExportTarget.PDF_CALC -> assertPdf(file, label, minimumPages = 1, exactPages = null)

            else -> throw AssertionError("$label: unexpected spreadsheet target")
        }
    }

    private fun verifyEditedWorkbook(book: XlsxBook, label: String) {
        val sheet = book.sheet("Data") ?: book.sheets.first()
        assertCellNumber(sheet, "A1", 150.0, label)
        assertCellBold(sheet, "A1", label)
        assertCellBold(sheet, "A2", label)
        assertCellFormula(sheet, "A2", "A1*2", label)
        assertCellCached(sheet, "A2", 300.0, label)
        assertCellBlank(sheet, "A3", label)
        assertCellText(sheet, "A4", "Гайка", label)
        assertCellBlank(sheet, "B1", label)
        assertCellText(sheet, "C1", "Qty", label)
    }

    private fun verifyCsv(case: Case, rows: List<List<String>>, label: String) {
        assertTrue("$label: CSV has no rows", rows.isNotEmpty())
        when (case.id) {
            SHEET_EDIT_CASE -> {
                assertEquals("$label: edited header cell A1", 150.0, rows[0].getOrNull(0)?.toDoubleOrNull())
                assertTrue("$label: inserted column B1 is not blank", rows[0].getOrNull(1).isNullOrBlank())
                assertEquals("$label: shifted header C1", "Qty", rows[0].getOrNull(2))
                assertEquals(
                    "$label: recalculated A2",
                    300.0,
                    rows.getOrNull(1)?.getOrNull(0)?.toDoubleOrNull(),
                )
                assertTrue(
                    "$label: shifted Гайка row absent",
                    rows.any { it.firstOrNull() == "Гайка" },
                )
            }

            "excel-csv" -> {
                assertEquals("$label: CSV header", listOf("Product", "Qty", "Price", "Total"), rows[0])
                assertEquals("$label: quoted comma field", "Widget, large", rows[1].firstOrNull())
                assertEquals("$label: Cyrillic field", "Гайка", rows[2].firstOrNull())
                assertEquals("$label: total", 37.5, rows[1].getOrNull(3)?.toDoubleOrNull())
            }

            "excel-tsv" -> {
                assertEquals("$label: CSV header", listOf("Product", "Qty", "Note"), rows[0])
                assertEquals("$label: Cyrillic field", "Кириллица и Latin", rows[1].getOrNull(2))
            }

            XLSB_EDIT_CASE -> {
                assertEquals("$label: first row prefix", listOf("Lorem", "111"), rows[0].take(2))
                assertEquals("$label: second row first cell", "ipsum", rows[1].firstOrNull())
                assertEquals("$label: recalculated C1", 222.0, rows[0].getOrNull(2)?.toDoubleOrNull())
            }

            else -> {
                assertEquals("$label: CSV header", listOf("Product", "Qty", "Price", "Total"), rows[0])
                assertEquals("$label: first product", "Widget", rows[1].firstOrNull())
                val cyrillic = rows.firstOrNull { it.firstOrNull() == "Гайка" }
                assertTrue("$label: Cyrillic product row absent", cyrillic != null)
                assertEquals("$label: Cyrillic row total", 42.5, cyrillic!!.getOrNull(3)?.toDoubleOrNull())
            }
        }
    }

    // ---- deck verification ---------------------------------------------------------------

    private fun verifyDeckExport(case: Case, exportTarget: OfficeExportTarget, file: File) {
        val label = "${case.id}/${exportTarget.name}"
        when (exportTarget) {
            OfficeExportTarget.PPTX -> {
                val deck = parsePptx(file)
                assertEquals("$label: slide count", 3, deck.slideTexts.size)
                assertEquals("$label: slide width", SLIDE_WIDTH_EMU, deck.sizeCx)
                assertEquals("$label: slide height", SLIDE_HEIGHT_EMU, deck.sizeCy)
                assertFalse("$label: export re-emitted a macro part", deck.hasVbaPart)
                assertTrue(
                    "$label: export is not a plain PresentationML deck",
                    deck.contentTypes.contains("presentationml.presentation.main") &&
                        !deck.contentTypes.contains("slideshow.main") &&
                        !deck.contentTypes.contains("template.main") &&
                        !deck.contentTypes.contains("macroEnabled"),
                )
                DECK_SLIDE_TEXTS.forEachIndexed { index, needles ->
                    val text = deck.slideTexts.getOrNull(index)
                    assertTrue("$label: slide ${index + 1} absent", text != null)
                    assertContainsAll("$label slide ${index + 1}", text!!, needles)
                }
                assertContainsAll(label, deck.notesText, DECK_NOTES)
            }

            OfficeExportTarget.PDF_IMPRESS -> assertPdf(file, label, minimumPages = 3, exactPages = 3)

            else -> throw AssertionError("$label: unexpected presentation target")
        }
    }

    private fun verifyExport(case: Case, exportTarget: OfficeExportTarget, file: File) {
        assertEquals(
            "${case.id}/${exportTarget.name}: extension",
            exportTarget.extension,
            file.extension.lowercase(),
        )
        when (case.family) {
            OfficeFamily.TEXT -> verifyWordExport(case, exportTarget, file)
            OfficeFamily.SPREADSHEET -> verifySheetExport(case, exportTarget, file)
            OfficeFamily.PRESENTATION -> verifyDeckExport(case, exportTarget, file)
        }
    }

    private fun assertContainsAll(label: String, text: String, needles: List<String>) {
        val haystack = normalizeSpace(text)
        needles.forEachIndexed { index, needle ->
            assertTrue(
                "$label: corpus text #$index missing",
                haystack.contains(normalizeSpace(needle)),
            )
        }
    }

    // ---- document readers ----------------------------------------------------------------

    private fun parseCsv(file: File, delimiter: Char): List<List<String>> {
        val text = String(file.readBytes(), Charsets.UTF_8)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val current = text[index]
            if (quoted) {
                if (current == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index += 2
                    } else {
                        quoted = false
                        index++
                    }
                } else {
                    field.append(current)
                    index++
                }
            } else {
                when {
                    current == '"' && field.isEmpty() -> {
                        quoted = true
                        index++
                    }

                    current == delimiter -> {
                        row.add(field.toString())
                        field.setLength(0)
                        index++
                    }

                    current == '\r' -> index++

                    current == '\n' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        rows.add(row)
                        row = ArrayList()
                        index++
                    }

                    else -> {
                        field.append(current)
                        index++
                    }
                }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    private fun parseDocx(file: File): DocxDoc {
        ZipFile(file).use { zip ->
            val documentBytes = zipBytes(zip, "word/document.xml")
                ?: throw AssertionError("docx: missing word/document.xml")
            val body = parseXml(documentBytes).documentElement.child(NS_W, "body")
                ?: throw AssertionError("docx: missing body")
            val paragraphs = ArrayList<DocxParagraph>()
            val tables = ArrayList<String>()
            for (child in body.children()) {
                when (child.localName) {
                    "p" -> paragraphs.add(parseDocxParagraph(child))
                    "tbl" -> tables.add(child.elementsNs(NS_W, "t").joinToString("") { it.textContent ?: "" })
                }
            }
            val types = zipBytes(zip, "[Content_Types].xml")?.let { String(it, Charsets.UTF_8) }.orEmpty()
            return DocxDoc(
                paragraphs = paragraphs,
                tableText = tables.joinToString("\n"),
                contentTypes = types,
                hasVbaPart = zipNames(zip).any { it.contains("vbaProject", ignoreCase = true) },
            )
        }
    }

    private fun parseDocxParagraph(paragraph: Element): DocxParagraph {
        val runs = ArrayList<DocxRun>()
        for (run in paragraph.elementsNs(NS_W, "r")) {
            val text = run.elementsNs(NS_W, "t").joinToString("") { it.textContent ?: "" }
            if (text.isEmpty()) continue
            val props = run.child(NS_W, "rPr")
            runs.add(
                DocxRun(
                    text = text,
                    bold = props?.child(NS_W, "b")?.isOn() == true,
                    italic = props?.child(NS_W, "i")?.isOn() == true,
                    underline = props?.child(NS_W, "u") != null,
                ),
            )
        }
        return DocxParagraph(
            text = paragraph.elementsNs(NS_W, "t").joinToString("") { it.textContent ?: "" },
            runs = runs,
        )
    }

    private fun parseOdt(file: File): OdtDoc {
        ZipFile(file).use { zip ->
            val mimetype = zipBytes(zip, "mimetype")?.let { String(it, Charsets.UTF_8).trim() }.orEmpty()
            val content = zipBytes(zip, "content.xml") ?: throw AssertionError("odt: missing content.xml")
            val doc = parseXml(content)
            val paragraphs = doc.elementsNs(NS_ODF_TEXT, "p") + doc.elementsNs(NS_ODF_TEXT, "h")
            return OdtDoc(mimetype = mimetype, text = paragraphs.joinToString("\n") { it.textContent ?: "" })
        }
    }

    private fun parseXlsx(file: File): XlsxBook {
        ZipFile(file).use { zip ->
            val names = zipNames(zip)
            val workbookBytes = zipBytes(zip, "xl/workbook.xml")
                ?: throw AssertionError("xlsx: missing xl/workbook.xml")
            val workbook = parseXml(workbookBytes)
            val rels = parseRels(zip, "xl/_rels/workbook.xml.rels")
            val shared = ArrayList<String>()
            zipBytes(zip, "xl/sharedStrings.xml")?.let { bytes ->
                for (item in parseXml(bytes).elementsNs(NS_S, "si")) {
                    shared.add(item.elementsNs(NS_S, "t").joinToString("") { it.textContent ?: "" })
                }
            }
            val styles = parseXlsxStyles(zip)
            val sheets = ArrayList<XlsxSheet>()
            for (sheetElement in workbook.elementsNs(NS_S, "sheet")) {
                val name = sheetElement.getAttribute("name")
                val relationshipId = sheetElement.getAttributeNS(NS_R, "id")
                val target = rels[relationshipId]
                    ?: throw AssertionError("xlsx: sheet '$name' has no relationship")
                val part = resolvePart("xl/workbook.xml", target)
                val sheetBytes = zipBytes(zip, part)
                    ?: throw AssertionError("xlsx: worksheet part for '$name' missing")
                val root = parseXml(sheetBytes)
                val cells = HashMap<String, XlsxCell>()
                for (cellElement in root.elementsNs(NS_S, "c")) {
                    val ref = cellElement.getAttribute("r")
                    if (ref.isEmpty()) continue
                    val type = cellElement.getAttribute("t").takeIf { it.isNotEmpty() }
                    val styleId = cellElement.getAttribute("s").toIntOrNull() ?: 0
                    val formula = cellElement.child(NS_S, "f")?.textContent?.trim()
                    val raw = cellElement.child(NS_S, "v")?.textContent
                    var text: String? = null
                    var number: Double? = null
                    when (type) {
                        "s" -> text = raw?.toIntOrNull()?.let { shared.getOrNull(it) }
                        "inlineStr" -> text = cellElement.child(NS_S, "is")
                            ?.elementsNs(NS_S, "t")
                            ?.joinToString("") { it.textContent ?: "" }

                        "b" -> text = if (raw == "1") "TRUE" else "FALSE"
                        "str", "e" -> text = raw
                        else -> {
                            text = raw
                            number = raw?.toDoubleOrNull()
                        }
                    }
                    val style = styles.cellXfs.getOrNull(styleId)
                    val bold = style?.let { styles.fonts.getOrNull(it.fontId)?.bold } ?: false
                    cells[ref] = XlsxCell(
                        ref = ref,
                        text = text,
                        number = number,
                        formula = formula,
                        cached = raw?.toDoubleOrNull(),
                        bold = bold,
                    )
                }
                sheets.add(XlsxSheet(name = name, cells = cells))
            }
            return XlsxBook(
                sheets = sheets,
                contentTypes = zipBytes(zip, "[Content_Types].xml")
                    ?.let { String(it, Charsets.UTF_8) }
                    .orEmpty(),
                hasVbaPart = names.any { it.contains("vbaProject", ignoreCase = true) },
            )
        }
    }

    private fun parseXlsxStyles(zip: ZipFile): XlsxStyles {
        val bytes = zipBytes(zip, "xl/styles.xml") ?: return XlsxStyles(emptyList(), emptyList())
        val root = parseXml(bytes).documentElement
        val fonts = root.child(NS_S, "fonts")?.children(NS_S, "font")?.map {
            XlsxFont(bold = it.child(NS_S, "b") != null)
        } ?: emptyList()
        val formats = root.child(NS_S, "cellXfs")?.children(NS_S, "xf")?.map {
            XlsxXf(fontId = it.getAttribute("fontId").toIntOrNull() ?: 0)
        } ?: emptyList()
        return XlsxStyles(fonts = fonts, cellXfs = formats)
    }

    private fun parsePptx(file: File): PptxDeck {
        ZipFile(file).use { zip ->
            val names = zipNames(zip)
            val presentationBytes = zipBytes(zip, "ppt/presentation.xml")
                ?: throw AssertionError("pptx: missing ppt/presentation.xml")
            val presentation = parseXml(presentationBytes).documentElement
            val size = presentation.child(NS_P, "sldSz")
            val width = size?.getAttribute("cx")?.toLongOrNull() ?: 0L
            val height = size?.getAttribute("cy")?.toLongOrNull() ?: 0L
            val slideEntries = names
                .filter { SLIDE_ENTRY.matches(it) }
                .sortedBy { it.substringAfter("slide").substringBefore('.').toIntOrNull() ?: 0 }
            val slideTexts = slideEntries.map { name ->
                val bytes = zipBytes(zip, name) ?: throw AssertionError("pptx: unreadable slide part")
                drawingText(parseXml(bytes))
            }
            val notesEntries = names
                .filter { NOTES_ENTRY.matches(it) }
                .sortedBy { it.substringAfter("notesSlide").substringBefore('.').toIntOrNull() ?: 0 }
            val notesText = notesEntries.joinToString("\n") { name ->
                val bytes = zipBytes(zip, name) ?: throw AssertionError("pptx: unreadable notes part")
                drawingText(parseXml(bytes))
            }
            return PptxDeck(
                slideTexts = slideTexts,
                notesText = notesText,
                sizeCx = width,
                sizeCy = height,
                contentTypes = zipBytes(zip, "[Content_Types].xml")
                    ?.let { String(it, Charsets.UTF_8) }
                    .orEmpty(),
                hasVbaPart = names.any { it.contains("vbaProject", ignoreCase = true) },
            )
        }
    }

    private fun drawingText(root: Document): String =
        root.elementsNs(NS_A, "p").joinToString("\n") { paragraph ->
            paragraph.elementsNs(NS_A, "t").joinToString("") { it.textContent ?: "" }
        }

    private fun rtfText(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder(raw.length)
        var index = 0
        var depth = 0
        var skipDepth = -1
        val groupUnicodeWidths = ArrayDeque<Int>()
        var unicodeWidth = 1
        var fallback = 0
        while (index < raw.length) {
            val current = raw[index]
            if (current == '\r' || current == '\n') {
                index++
                continue
            }
            if (current == '{' || current == '}') fallback = 0
            val skippingFallback = fallback > 0
            if (skippingFallback) fallback--
            when {
                current == '{' -> {
                    groupUnicodeWidths.addLast(unicodeWidth)
                    depth++
                    index++
                }

                current == '}' -> {
                    unicodeWidth = groupUnicodeWidths.removeLastOrNull() ?: 1
                    depth--
                    if (skipDepth >= 0 && depth < skipDepth) skipDepth = -1
                    index++
                }

                current == '\\' -> {
                    index++
                    if (index >= raw.length) break
                    when (val next = raw[index]) {
                        '\'' -> {
                            if (index + 2 < raw.length) {
                                val value = raw.substring(index + 1, index + 3).toIntOrNull(16)
                                if (value != null && skipDepth < 0 && !skippingFallback) out.append(value.toChar())
                                index += 3
                            } else {
                                index++
                            }
                        }

                        '\\', '{', '}' -> {
                            if (skipDepth < 0 && !skippingFallback) out.append(next)
                            index++
                        }

                        '*' -> {
                            if (!skippingFallback) skipDepth = depth
                            index++
                        }

                        else -> {
                            if (!next.isLetter()) {
                                index++
                            } else {
                                val wordStart = index
                                while (index < raw.length && raw[index].isLetter()) index++
                                val word = raw.substring(wordStart, index)
                                var sign = 1
                                var number = 0
                                var hasNumber = false
                                if (index < raw.length && (raw[index] == '-' || raw[index].isDigit())) {
                                    if (raw[index] == '-') {
                                        sign = -1
                                        index++
                                    }
                                    val digitsStart = index
                                    while (index < raw.length && raw[index].isDigit()) index++
                                    number = (raw.substring(digitsStart, index).toIntOrNull() ?: 0) * sign
                                    hasNumber = true
                                }
                                if (index < raw.length && raw[index] == ' ') index++
                                if (skippingFallback) continue
                                if (word == "uc" && hasNumber) {
                                    unicodeWidth = number.coerceAtLeast(0)
                                } else if (word in RTF_SKIP_DESTINATIONS) {
                                    skipDepth = depth
                                } else if (skipDepth < 0) {
                                    when (word) {
                                        "par", "line" -> out.append('\n')
                                        "tab" -> out.append('\t')
                                        "emdash" -> out.append('—')
                                        "endash" -> out.append('–')
                                        "u" -> {
                                            if (hasNumber) {
                                                val codePoint = if (number < 0) number + 0x10000 else number
                                                out.appendCodePoint(codePoint)
                                                fallback = unicodeWidth
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    if (skipDepth < 0 && !skippingFallback) out.append(current)
                    index++
                }
            }
        }
        return out.toString()
    }

    /**
     * Markdown legitimately inserts emphasis/HTML markers between runs, so a formatted source
     * sentence is not contiguous in the raw export. Remove markup before matching corpus text.
     */
    private fun markdownText(raw: String): String =
        HTML_TAG.replace(raw, "").replace("*", "").replace("_", "").replace("`", "")

    private fun htmlText(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val current = raw[index]
            if (current != '<') {
                out.append(current)
                index++
                continue
            }
            val end = raw.indexOf('>', index)
            if (end < 0) break
            val tag = raw.substring(index + 1, end).trim().lowercase()
            if (tag.startsWith("p") || tag.startsWith("/p") || tag.startsWith("br") ||
                tag.startsWith("li") || tag.startsWith("h") || tag.startsWith("div") ||
                tag.startsWith("tr") || tag.startsWith("table")
            ) {
                out.append('\n')
            }
            index = end + 1
        }
        return decodeHtmlEntities(out.toString())
    }

    private fun decodeHtmlEntities(text: String): String {
        var result = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        result = HEX_ENTITY.replace(result) { match ->
            match.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
        }
        result = DECIMAL_ENTITY.replace(result) { match ->
            match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
        }
        return result
    }

    private fun assertPdf(file: File, label: String, minimumPages: Int, exactPages: Int?) {
        val bytes = file.readBytes()
        val head = String(bytes, 0, minOf(bytes.size, 8), Charsets.ISO_8859_1)
        assertTrue("$label: PDF header absent", head.startsWith("%PDF-"))
        val tail = String(
            bytes,
            maxOf(0, bytes.size - 4096),
            minOf(4096, bytes.size),
            Charsets.ISO_8859_1,
        )
        assertTrue("$label: PDF trailer incomplete", tail.contains("%%EOF"))
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = PdfRenderer(descriptor)
            try {
                if (exactPages != null) {
                    assertEquals(
                        "$label: PDF page count",
                        exactPages.toLong(),
                        renderer.pageCount.toLong(),
                    )
                }
                assertTrue("$label: PDF has no pages", renderer.pageCount >= minimumPages)
                for (pageIndex in 0 until renderer.pageCount) {
                    val page = renderer.openPage(pageIndex)
                    try {
                        val width = 300
                        val sourceWidth = if (page.width > 0) page.width else 1
                        val height = ((width.toLong() * page.height) / sourceWidth)
                            .toInt()
                            .coerceIn(64, 4096)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pixels = IntArray(width * height)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            assertTrue("$label: PDF page ${pageIndex + 1} is blank", pixels.any { it != Color.WHITE })
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        } finally {
            descriptor.close()
        }
    }

    // ---- cell assertions -----------------------------------------------------------------

    private fun assertCellText(sheet: XlsxSheet, ref: String, expected: String, label: String) {
        val cell = sheet.cells[ref] ?: throw AssertionError("$label: cell $ref absent")
        assertEquals("$label: cell $ref text", expected, cell.text?.trim())
    }

    private fun assertCellNumber(sheet: XlsxSheet, ref: String, expected: Double, label: String) {
        val cell = sheet.cells[ref] ?: throw AssertionError("$label: cell $ref absent")
        val number = cell.number ?: throw AssertionError("$label: cell $ref is not numeric")
        assertTrue("$label: cell $ref value $number != $expected", abs(number - expected) <= 1e-6)
    }

    private fun assertCellFormula(sheet: XlsxSheet, ref: String, expected: String, label: String) {
        val cell = sheet.cells[ref] ?: throw AssertionError("$label: cell $ref absent")
        val formula = normalizeFormula(cell.formula)
        assertEquals("$label: cell $ref formula", normalizeFormula(expected), formula)
    }

    private fun assertCellCached(sheet: XlsxSheet, ref: String, expected: Double, label: String) {
        val cell = sheet.cells[ref] ?: throw AssertionError("$label: cell $ref absent")
        val cached = cell.cached ?: throw AssertionError("$label: cell $ref has no cached value")
        assertTrue(
            "$label: cell $ref cached $cached != $expected (recalculation not persisted)",
            abs(cached - expected) <= 1e-6,
        )
    }

    private fun assertCellBold(sheet: XlsxSheet, ref: String, label: String) {
        val cell = sheet.cells[ref] ?: throw AssertionError("$label: cell $ref absent")
        assertTrue("$label: cell $ref is not bold", cell.bold)
    }

    private fun assertCellBlank(sheet: XlsxSheet, ref: String, label: String) {
        val cell = sheet.cells[ref] ?: return
        assertTrue(
            "$label: row/column operation did not leave $ref blank",
            cell.text.isNullOrBlank() && cell.number == null && cell.formula == null,
        )
    }

    private fun normalizeFormula(value: String?): String {
        var formula = value.orEmpty().replace(" ", "")
        if (formula.startsWith("of:")) formula = formula.substring(3)
        return formula.removePrefix("=")
    }

    // ---- small helpers -------------------------------------------------------------------

    private fun zipNames(zip: ZipFile): List<String> {
        val names = ArrayList<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) names.add(entries.nextElement().name)
        return names
    }

    private fun zipBytes(zip: ZipFile, name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun Document.elementsNs(namespace: String, local: String): List<Element> =
        elementList(getElementsByTagNameNS(namespace, local))

    private fun Element.elementsNs(namespace: String, local: String): List<Element> =
        elementList(getElementsByTagNameNS(namespace, local))

    private fun elementList(nodes: org.w3c.dom.NodeList): List<Element> {
        val out = ArrayList<Element>(nodes.length)
        for (index in 0 until nodes.length) {
            (nodes.item(index) as? Element)?.let { out.add(it) }
        }
        return out
    }

    private fun Element.children(): List<Element> {
        val out = ArrayList<Element>()
        var node = firstChild
        while (node != null) {
            if (node is Element) out.add(node)
            node = node.nextSibling
        }
        return out
    }

    private fun Element.children(namespace: String, local: String): List<Element> =
        children().filter { it.namespaceURI == namespace && it.localName == local }

    private fun Element.child(namespace: String, local: String): Element? {
        var node = firstChild
        while (node != null) {
            if (node is Element && node.namespaceURI == namespace && node.localName == local) {
                return node
            }
            node = node.nextSibling
        }
        return null
    }

    private fun Element.isOn(): Boolean {
        val value = getAttributeNS(NS_W, "val")
        return value.isEmpty() || (value != "0" && value != "false" && value != "off")
    }

    private fun parseRels(zip: ZipFile, path: String): Map<String, String> {
        val bytes = zipBytes(zip, path) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (relationship in parseXml(bytes).documentElement.children(NS_REL, "Relationship")) {
            val id = relationship.getAttribute("Id")
            val target = relationship.getAttribute("Target")
            if (id.isNotEmpty() && target.isNotEmpty()) out[id] = target
        }
        return out
    }

    private fun resolvePart(base: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val baseDir = base.substringBeforeLast('/', "")
        val combined = if (baseDir.isEmpty()) target else "$baseDir/$target"
        val parts = ArrayList<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun normalizeSpace(text: String): String =
        SPACES.replace(text.replace('\u00a0', ' '), " ").trim()

    private fun failureText(failure: Throwable): String =
        failure.message ?: failure.javaClass.simpleName

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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- model ---------------------------------------------------------------------------

    private data class Case(
        val id: String,
        val family: OfficeFamily,
        val format: DocumentFormat,
        val asset: String,
        val sha256: String,
        val targets: List<OfficeExportTarget>,
        val needles: List<String> = emptyList(),
        val readOnly: Boolean = family == OfficeFamily.PRESENTATION,
    )

    private data class DocxRun(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
    )

    private data class DocxParagraph(val text: String, val runs: List<DocxRun>)

    private data class DocxDoc(
        val paragraphs: List<DocxParagraph>,
        val tableText: String,
        val contentTypes: String,
        val hasVbaPart: Boolean,
    )

    private data class OdtDoc(val mimetype: String, val text: String)

    private data class XlsxCell(
        val ref: String,
        val text: String?,
        val number: Double?,
        val formula: String?,
        val cached: Double?,
        val bold: Boolean,
    )

    private data class XlsxSheet(val name: String, val cells: Map<String, XlsxCell>)

    private data class XlsxBook(
        val sheets: List<XlsxSheet>,
        val contentTypes: String,
        val hasVbaPart: Boolean,
    ) {
        fun sheet(name: String): XlsxSheet? = sheets.firstOrNull { it.name == name }
    }

    private data class XlsxFont(val bold: Boolean)

    private data class XlsxXf(val fontId: Int)

    private data class XlsxStyles(val fonts: List<XlsxFont>, val cellXfs: List<XlsxXf>)

    private data class PptxDeck(
        val slideTexts: List<String>,
        val notesText: String,
        val sizeCx: Long,
        val sizeCy: Long,
        val contentTypes: String,
        val hasVbaPart: Boolean,
    )

    private companion object {
        const val SMOKE_ARG = "officeRoundTrip"
        const val RUN_ID_ARG = "officeRoundTripRunId"
        const val ISOLATION_ARG = "officeIsolationAck"
        const val KEEP_ARTIFACTS_ARG = "officeKeepArtifacts"
        const val APK_SHA_ARG = "officeApkSha256"
        const val CASE_ARG = "officeRoundTripCase"

        const val ARTIFACT_ROOT = "office-roundtrip-artifacts"
        const val MOBILE_DATA_KEY = "mobile_data"

        const val FROZEN_APK_SHA256 =
            "af8aa3a646555ec8a43033cb40bca128957008a22628a0dc7072b453220f99fc"

        const val WORD_EDIT_CASE = "word-docx"
        const val SHEET_EDIT_CASE = "excel-xlsx"
        const val XLSB_EDIT_CASE = "excel-xlsb"
        const val WORD_INSERTED_TEXT = "Вставка: Кириллица и Latin — bold"

        const val SLIDE_WIDTH_EMU = 12192000L
        const val SLIDE_HEIGHT_EMU = 6858000L

        val RUN_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{7,63}")

        val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val NS_S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
        val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val NS_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
        val NS_ODF_TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"

        val SLIDE_ENTRY = Regex("""ppt/slides/slide\d+\.xml""")
        val NOTES_ENTRY = Regex("""ppt/notesSlides/notesSlide\d+\.xml""")
        val SPACES = Regex("\\s+")
        val HTML_TAG = Regex("<[^>]*>")
        val HEX_ENTITY = Regex("""&#[xX]([0-9a-fA-F]+);""")
        val DECIMAL_ENTITY = Regex("""&#(\d+);""")

        val RTF_SKIP_DESTINATIONS = setOf(
            "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer",
            "headerl", "headerr", "footerl", "footerr", "listtable", "listoverridetable",
            "rsidtbl", "generator", "themedata", "colorschememapping", "datastore",
            "latentstyles", "xmlnstbl", "filetbl", "revtbl",
        )

        val WORD_TARGETS = listOf(
            OfficeExportTarget.DOCX,
            OfficeExportTarget.ODT,
            OfficeExportTarget.RTF,
            OfficeExportTarget.HTML,
            OfficeExportTarget.MARKDOWN,
            OfficeExportTarget.TXT,
            OfficeExportTarget.PDF_TEXT,
        )

        val EXCEL_TARGETS = listOf(
            OfficeExportTarget.XLSX,
            OfficeExportTarget.CSV,
            OfficeExportTarget.PDF_CALC,
        )

        val DECK_TARGETS = listOf(
            OfficeExportTarget.PPTX,
            OfficeExportTarget.PDF_IMPRESS,
        )

        val WORD_FOUR_PARAGRAPHS = listOf(
            "Peachy Word fixture — Печать",
            "Plain paragraph: The quick brown fox jumps over the lazy dog.",
            "Bold and italic and underline — Кириллица",
            "Кириллица: Съешь же ещё этих мягких французских булок, да выпей чаю.",
        )

        const val WORD_PLAIN_PARAGRAPH = "Plain paragraph: The quick brown fox jumps over the lazy dog."

        val WORD_THREE_PARAGRAPHS = listOf(
            "Peachy Word fixture — Печать",
            "Plain paragraph: The quick brown fox jumps over the lazy dog.",
            "Кириллица: Съешь же ещё этих мягких французских булок, да выпей чаю.",
        )

        val WORD_MARKDOWN = listOf(
            "Peachy Markdown fixture",
            "Печать документов",
            "Section: Кириллица",
            "First bullet",
            "Второй пункт",
        )

        val WORD_TEXT = listOf(
            "Peachy text fixture",
            "Plain paragraph: The quick brown fox jumps over the lazy dog.",
            "Кириллица: Съешь же ещё этих мягких французских булок, да выпей чаю.",
        )

        val FORMATTED_WORD_CASES = setOf(
            "word-docx", "word-docm", "word-dotx", "word-doc", "word-odt", "word-rtf",
        )

        val WORD_CASES = listOf(
            Case(
                "word-docx", OfficeFamily.TEXT, DocumentFormat.DOCX,
                "office-roundtrip/word/peachy-sample.docx",
                "6a7d48a8d7226f717e0d3bb8d958e4788ec657e40473edb281f778be1b457138",
                WORD_TARGETS, WORD_FOUR_PARAGRAPHS,
            ),
            Case(
                "word-docm", OfficeFamily.TEXT, DocumentFormat.DOCM,
                "office-roundtrip/word/peachy-sample.docm",
                "1227e59258830aa04dc4cb2337faeb7447d334f8fb99f87a06b392bfb029ce02",
                WORD_TARGETS, WORD_FOUR_PARAGRAPHS,
            ),
            Case(
                "word-dotx", OfficeFamily.TEXT, DocumentFormat.DOTX,
                "office-roundtrip/word/peachy-sample.dotx",
                "0f2ef960222f51be5f7b8c9feaaa95c2d1ce4ebe992deb4966e917064888adc3",
                WORD_TARGETS, WORD_FOUR_PARAGRAPHS,
            ),
            Case(
                "word-doc", OfficeFamily.TEXT, DocumentFormat.DOC,
                "office-roundtrip/word/peachy-sample.doc",
                "2f5cb66afaf853e7f1b2222e4eef8e0ae70397567047404d89886523cc734eba",
                WORD_TARGETS, WORD_THREE_PARAGRAPHS,
            ),
            Case(
                "word-odt", OfficeFamily.TEXT, DocumentFormat.ODT,
                "office-roundtrip/word/peachy-sample.odt",
                "b45acceb1f748516bfe2af48c14016c313fa85f918fc0e26089689c74417ca78",
                WORD_TARGETS, WORD_THREE_PARAGRAPHS,
            ),
            Case(
                "word-rtf", OfficeFamily.TEXT, DocumentFormat.RTF,
                "office-roundtrip/word/peachy-sample.rtf",
                "dd0f4e4c08190d421139a255f4a18ae96896bbc8536501be68e760d8863f74bd",
                WORD_TARGETS, WORD_THREE_PARAGRAPHS,
            ),
            Case(
                "word-md", OfficeFamily.TEXT, DocumentFormat.MD,
                "office-roundtrip/word/peachy-sample.md",
                "2eb93622d09a26ed6229d1f8655157af86608cc389644c64bdd03d0f33c2578d",
                WORD_TARGETS, WORD_MARKDOWN,
            ),
            Case(
                "word-txt", OfficeFamily.TEXT, DocumentFormat.TXT,
                "office-roundtrip/word/peachy-sample.txt",
                "5fca31c3a1492d0841f40cb6bca36286b8ecc883e49ab97f28ed1b2a110b0842",
                WORD_TARGETS, WORD_TEXT,
            ),
        )

        val EXCEL_CASES = listOf(
            Case(
                "excel-xlsx", OfficeFamily.SPREADSHEET, DocumentFormat.XLSX,
                "office-roundtrip/excel/peachy-sample.xlsx",
                "8f8f8c39eeb5ec2bd0b08bdee929f26fab02d727302896a9d1813bfb4e66b05a",
                EXCEL_TARGETS,
            ),
            Case(
                "excel-xlsm", OfficeFamily.SPREADSHEET, DocumentFormat.XLSM,
                "office-roundtrip/excel/peachy-sample.xlsm",
                "54a87dae0e093a173d6c088587ef1305298e0cd2fe4cf0d85f8bd3f186a9e2f6",
                EXCEL_TARGETS,
            ),
            Case(
                XLSB_EDIT_CASE, OfficeFamily.SPREADSHEET, DocumentFormat.XLSB,
                "office-roundtrip/excel/peachy-sample.xlsb",
                "8cf995ace93f9d0e3bf34b6bdb1355ff46db2d418f6f3fb7943fdc4ca1db4d1b",
                EXCEL_TARGETS,
            ),
            Case(
                "excel-xls", OfficeFamily.SPREADSHEET, DocumentFormat.XLS,
                "office-roundtrip/excel/peachy-sample.xls",
                "2bb32477622d65bb27c65ada3fea3694de6620d5feb228b946a687439e50ef93",
                EXCEL_TARGETS,
            ),
            Case(
                "excel-ods", OfficeFamily.SPREADSHEET, DocumentFormat.ODS,
                "office-roundtrip/excel/peachy-sample.ods",
                "1cd0063799afeb66ec9c7c7f78a20c6289f264ca493dbd4f9751baf6055132f4",
                EXCEL_TARGETS,
            ),
            Case(
                "excel-csv", OfficeFamily.SPREADSHEET, DocumentFormat.CSV,
                "office-roundtrip/excel/peachy-sample.csv",
                "49fbeadadcaab2a5f890706372e51d289002a6ffe63d9b318a55882eae69fc97",
                EXCEL_TARGETS,
            ),
            Case(
                "excel-tsv", OfficeFamily.SPREADSHEET, DocumentFormat.TSV,
                "office-roundtrip/excel/peachy-sample.tsv",
                "ef61443a1f3894fc676cc8a7eb72c9c7bc2cbb37ad310def8e0ea5eb99d268fa",
                EXCEL_TARGETS,
            ),
        )

        val DECK_CASES = listOf(
            Case(
                "powerpoint-pptx", OfficeFamily.PRESENTATION, DocumentFormat.PPTX,
                "office-roundtrip/powerpoint/peachy-sample.pptx",
                "fd8caa0fded6704e706f5c096d1810f99c0d9144170bbd9a300e68afb1d9fb51",
                DECK_TARGETS,
            ),
            Case(
                "powerpoint-ppsx", OfficeFamily.PRESENTATION, DocumentFormat.PPSX,
                "office-roundtrip/powerpoint/peachy-sample.ppsx",
                "291dcc0e5af373c47dea49c66979dd9f34fa0bfa661c16e57f4c3d98cc4b7ec8",
                DECK_TARGETS,
            ),
            Case(
                "powerpoint-potx", OfficeFamily.PRESENTATION, DocumentFormat.POTX,
                "office-roundtrip/powerpoint/peachy-sample.potx",
                "2509dc3198681dd61302d5ee1ba264cc8823d76414a24226627250f943a51019",
                DECK_TARGETS,
            ),
        )

        val DECK_SLIDE_TEXTS = listOf(
            listOf("Peachy Deck — Латиница", "Cyrillic subtitle: Проверка текста"),
            listOf("Bullets", "First bullet", "Второй пункт — Кириллица"),
            listOf("Text box shape"),
        )

        val DECK_NOTES = listOf("Speaker note: slide three.")
    }
}
