package ru.spgsroot.peachy.documents.office.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.DocumentKind
import ru.spgsroot.peachy.documents.office.engine.OfficeExportPlan
import ru.spgsroot.peachy.documents.office.engine.OfficeExportTarget

class OfficeExportTicketsTest {

    private fun plan(target: OfficeExportTarget) =
        OfficeExportPlan(target, File("build/staged/office-0123456789abcdef.${target.extension}"))

    @Test
    fun `xlsb imports but never exports as xlsb`() {
        val targets = OfficeExportTickets.targetsFor(DocumentFormat.XLSB, null)
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.none { it.extension == "xlsb" })
        assertTrue(targets.none { it.mime.contains("binary.macroEnabled") })
        assertTrue(OfficeExportTarget.XLSX in targets)
        assertTrue(OfficeExportTarget.PDF_CALC in targets)
    }

    @Test
    fun `presentations export a copy only`() {
        assertEquals(
            listOf(OfficeExportTarget.PPTX, OfficeExportTarget.PDF_IMPRESS),
            OfficeExportTickets.targetsFor(DocumentFormat.PPTX, null),
        )
    }

    @Test
    fun `no macro enabled export target exists for any family`() {
        listOf(
            DocumentFormat.DOCX,
            DocumentFormat.DOCM,
            DocumentFormat.XLSX,
            DocumentFormat.XLSM,
            DocumentFormat.PPTX,
        ).forEach { format ->
            val targets = OfficeExportTickets.targetsFor(format, null)
            assertTrue("$format", targets.isNotEmpty())
            targets.forEach { target ->
                assertFalse(target.extension, target.extension in setOf("docm", "xlsm", "pptm", "potm"))
                assertFalse(target.mime.contains("macroEnabled"))
            }
        }
    }

    @Test
    fun `ticket carries the target MIME and a safe name`() {
        val ticket = OfficeExportTickets.ticket(
            plan(OfficeExportTarget.DOCX),
            "Отчёт / Q3: черновик.docx",
        )
        assertEquals(OfficeExportTarget.DOCX, ticket.target)
        assertEquals(OfficeExportTarget.DOCX.mime, ticket.mime)
        assertEquals("Отчёт Q3 черновик.docx", ticket.fileName)
        assertEquals(DocumentKind.WORD, ticket.kind)
    }

    @Test
    fun `file name keeps the target extension and falls back when empty`() {
        assertEquals(
            "Document.pdf",
            OfficeExportTickets.fileName("  ..  ", OfficeExportTarget.PDF_TEXT),
        )
        assertEquals(
            "Notes.xlsx",
            OfficeExportTickets.fileName("Notes", OfficeExportTarget.XLSX),
        )
    }

    @Test
    fun `pdf tickets are checked as PDFs during delivery`() {
        val ticket = OfficeExportTickets.ticket(plan(OfficeExportTarget.PDF_CALC), "Budget.xlsx")
        assertEquals(DocumentKind.PDF, ticket.kind)
        assertEquals("Budget.pdf", ticket.fileName)
        assertEquals(OfficeExportTarget.PDF_CALC.mime, ticket.mime)
    }
}
