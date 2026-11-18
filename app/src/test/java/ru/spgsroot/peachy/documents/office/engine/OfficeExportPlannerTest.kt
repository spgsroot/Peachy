package ru.spgsroot.peachy.documents.office.engine

import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.spgsroot.peachy.documents.DocumentFormat

class OfficeExportPlannerTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "office-export-${UUID.randomUUID()}")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun exportDir(): File = File(File(root, "documents/session"), "exports").apply { mkdirs() }

    private fun writeFile(name: String, bytes: ByteArray): File {
        root.mkdirs()
        return File(root, name).apply { writeBytes(bytes) }
    }

    private fun writeFile(name: String, text: String): File = writeFile(name, text.toByteArray(Charsets.UTF_8))

    @Test
    fun stagedFileStaysInsideTheSessionExportDirectory() {
        val directory = exportDir()
        val plan = OfficeExportPlanner.plan(
            DocumentFormat.DOCX, OfficeExportTarget.DOCX, directory, "abcd1234",
        ).getOrThrow()
        assertEquals(directory.canonicalFile, plan.stagedFile.parentFile?.canonicalFile)
        assertEquals("office-abcd1234.docx", plan.stagedFile.name)
        assertEquals("docx", plan.formatArgument)
        assertEquals("", plan.optionsArgument)
    }

    @Test
    fun csvPlanCarriesTheUtf8FilterToken() {
        val plan = OfficeExportPlanner.plan(
            DocumentFormat.XLSX, OfficeExportTarget.CSV, exportDir(), "abcd1234",
        ).getOrThrow()
        assertEquals("csv", plan.formatArgument)
        assertEquals("44,34,76,1", plan.optionsArgument)
        assertEquals("office-abcd1234.csv", plan.stagedFile.name)
    }

    @Test
    fun crossFamilyPlanIsRefused() {
        val failure = OfficeExportPlanner.plan(
            DocumentFormat.DOCX, OfficeExportTarget.XLSX, exportDir(), "abcd1234",
        ).exceptionOrNull() as OfficeFailure.ExportFailure
        assertEquals(OfficeExportFailure.NOT_ALLOWED, failure.reason)
    }

    @Test
    fun aTokenCannotEscapeTheDirectory() {
        for (token in listOf("../escape", "", "ABC", "x".repeat(65), "abcd 1234")) {
            val failure = OfficeExportPlanner.plan(
                DocumentFormat.DOCX, OfficeExportTarget.DOCX, exportDir(), token,
            ).exceptionOrNull() as OfficeFailure.ExportFailure
            assertEquals(OfficeExportFailure.PATH_ESCAPE, failure.reason)
        }
    }

    @Test
    fun relativeExportDirectoriesAreRefused() {
        val failure = OfficeExportPlanner.plan(
            DocumentFormat.DOCX, OfficeExportTarget.DOCX, File("exports"), "abcd1234",
        ).exceptionOrNull() as OfficeFailure.ExportFailure
        assertEquals(OfficeExportFailure.PATH_ESCAPE, failure.reason)
    }

    @Test
    fun anEmptyOrMissingExportIsNotDeliverable() {
        assertEquals(
            OfficeExportFailure.MISSING_OUTPUT,
            OfficeExportValidator.check(File(root, "absent.docx"), OfficeExportTarget.DOCX),
        )
        val empty = writeFile("empty.docx", "")
        assertEquals(
            OfficeExportFailure.EMPTY_OUTPUT,
            OfficeExportValidator.check(empty, OfficeExportTarget.DOCX),
        )
    }

    @Test
    fun zipTargetsRequireAPkHeader() {
        val good = writeFile("good.docx", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
        assertNull(OfficeExportValidator.check(good, OfficeExportTarget.DOCX))

        val bad = writeFile("bad.docx", "not a zip")
        assertEquals(
            OfficeExportFailure.BAD_SIGNATURE,
            OfficeExportValidator.check(bad, OfficeExportTarget.DOCX),
        )
    }

    @Test
    fun pdfAndRtfTargetsCheckTheirOwnHeaders() {
        val pdf = writeFile("out.pdf", "%PDF-1.7\n")
        assertNull(OfficeExportValidator.check(pdf, OfficeExportTarget.PDF_TEXT))

        val notPdf = writeFile("not.pdf", "{\\rtf1 not a pdf}")
        assertEquals(
            OfficeExportFailure.BAD_SIGNATURE,
            OfficeExportValidator.check(notPdf, OfficeExportTarget.PDF_TEXT),
        )

        val rtf = writeFile("out.rtf", "  {\\rtf1\\ansi hello}")
        assertNull(OfficeExportValidator.check(rtf, OfficeExportTarget.RTF))
    }

    @Test
    fun htmlAndTextTargetsCheckForTextualContent() {
        val html = writeFile("out.html", "\n  <!DOCTYPE html><html></html>")
        assertNull(OfficeExportValidator.check(html, OfficeExportTarget.HTML))

        val csv = writeFile(
            "out.csv",
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a,b\r\n1,2\r\n".toByteArray(),
        )
        assertNull(OfficeExportValidator.check(csv, OfficeExportTarget.CSV))

        val binary = writeFile("binary.csv", byteArrayOf('a'.code.toByte(), 0x00, 'b'.code.toByte()))
        assertEquals(
            OfficeExportFailure.BAD_SIGNATURE,
            OfficeExportValidator.check(binary, OfficeExportTarget.CSV),
        )
    }

    @Test
    fun anImplausiblyLargeExportIsRejectedBeforeReading() {
        val huge = object : File(root, "huge.docx") {
            override fun isFile(): Boolean = true
            override fun length(): Long = OfficeExportValidator.MAX_EXPORT_BYTES + 1
        }
        assertEquals(
            OfficeExportFailure.TOO_LARGE,
            OfficeExportValidator.check(huge, OfficeExportTarget.DOCX),
        )
    }
}
