package ru.spgsroot.peachy.documents.office.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.office.engine.OfficeCommand
import ru.spgsroot.peachy.documents.office.engine.OfficeDocumentInfo
import ru.spgsroot.peachy.documents.office.engine.OfficeExportFailure
import ru.spgsroot.peachy.documents.office.engine.OfficeFailure
import ru.spgsroot.peachy.documents.office.engine.OfficeFamily
import ru.spgsroot.peachy.documents.office.engine.OfficeFeature
import ru.spgsroot.peachy.documents.office.engine.OfficeKey
import ru.spgsroot.peachy.documents.office.engine.OfficePart
import ru.spgsroot.peachy.documents.office.engine.OfficePartMode
import ru.spgsroot.peachy.documents.office.engine.OfficeRefusal
import ru.spgsroot.peachy.documents.office.engine.OfficeStage
import ru.spgsroot.peachy.documents.office.engine.OfficeUnavailableReason

class OfficeEditPolicyTest {

    private fun info(
        family: OfficeFamily,
        format: DocumentFormat,
        readOnly: Boolean = !family.editable,
        parts: List<OfficePart> = listOf(OfficePart(0, "")),
    ) = OfficeDocumentInfo(
        family = family,
        format = format,
        readOnly = readOnly,
        partMode = if (family.supportsPartMode) OfficePartMode.SLIDES else null,
        parts = parts,
        widthTwips = 12_240,
        heightTwips = 15_840,
        pageRects = emptyList(),
    )

    @Test
    fun `commands are only the adapter allowlist for the family`() {
        val writer = info(OfficeFamily.TEXT, DocumentFormat.DOCX)
        val writerCommands = OfficeEditPolicy.commands(writer).toSet()
        assertTrue(OfficeCommand.BOLD in writerCommands)
        assertTrue(OfficeCommand.BULLETS in writerCommands)
        assertFalse(OfficeCommand.INSERT_ROWS_AFTER in writerCommands)
        assertFalse(OfficeCommand.MERGE_CELLS in writerCommands)

        val sheet = info(OfficeFamily.SPREADSHEET, DocumentFormat.XLSX)
        val sheetCommands = OfficeEditPolicy.commands(sheet).toSet()
        assertTrue(OfficeCommand.INSERT_ROWS_AFTER in sheetCommands)
        assertTrue(OfficeCommand.MERGE_CELLS in sheetCommands)
        assertFalse(OfficeCommand.BULLETS in sheetCommands)

        val slides = info(OfficeFamily.PRESENTATION, DocumentFormat.PPTX)
        assertTrue(OfficeEditPolicy.commands(slides).isEmpty())
        assertFalse(OfficeEditPolicy.isEditable(slides))
    }

    @Test
    fun `insert policy mirrors the adapter tokenizer`() {
        assertTrue(OfficeEditPolicy.isInsertable("Привет, мир\nвторая строка"))
        assertTrue(OfficeEditPolicy.isInsertable("a".repeat(OfficeEditPolicy.MAX_TEXT_CODE_POINTS)))
        assertFalse(
            OfficeEditPolicy.isInsertable("a".repeat(OfficeEditPolicy.MAX_TEXT_CODE_POINTS + 1)),
        )
        assertFalse(OfficeEditPolicy.isInsertable("bell\u0007"))
        assertFalse(OfficeEditPolicy.isInsertable(""))
    }

    @Test
    fun `mutating commands and keys are distinguished from navigation`() {
        assertTrue(OfficeEditPolicy.mutates(OfficeCommand.BOLD))
        assertTrue(OfficeEditPolicy.mutates(OfficeCommand.UNDO))
        assertFalse(OfficeEditPolicy.mutates(OfficeCommand.SELECT_ALL))
        assertTrue(OfficeEditPolicy.mutates(OfficeKey.BACKSPACE))
        assertTrue(OfficeEditPolicy.mutates(OfficeKey.RETURN))
        assertFalse(OfficeEditPolicy.mutates(OfficeKey.LEFT))
    }

    @Test
    fun `notices name the real limitations`() {
        val writer = info(OfficeFamily.TEXT, DocumentFormat.DOCX)
        assertTrue(
            OfficeNotice.EDITS_IN_MEMORY in OfficeEditPolicy.notices(DocumentFormat.DOCX, writer),
        )

        val xlsb = info(OfficeFamily.SPREADSHEET, DocumentFormat.XLSB)
        assertTrue(
            OfficeNotice.XLSB_EXPORT in OfficeEditPolicy.notices(DocumentFormat.XLSB, xlsb),
        )

        val xlsm = info(OfficeFamily.SPREADSHEET, DocumentFormat.XLSM)
        assertTrue(
            OfficeNotice.MACRO_INPUT in OfficeEditPolicy.notices(DocumentFormat.XLSM, xlsm),
        )

        val legacy = info(OfficeFamily.TEXT, DocumentFormat.DOC)
        assertTrue(
            OfficeNotice.LEGACY_FIDELITY in OfficeEditPolicy.notices(DocumentFormat.DOC, legacy),
        )

        val slides = info(OfficeFamily.PRESENTATION, DocumentFormat.PPTX)
        assertTrue(
            OfficeNotice.READ_ONLY_PRESENTATION in
                OfficeEditPolicy.notices(DocumentFormat.PPTX, slides),
        )
        assertFalse(
            OfficeNotice.EDITS_IN_MEMORY in
                OfficeEditPolicy.notices(DocumentFormat.PPTX, slides),
        )
    }

    @Test
    fun `leaving an edited or busy document requires a decision`() {
        assertEquals(OfficeLeave.CONFIRM, OfficeEditPolicy.leave(edited = true, busy = false))
        assertEquals(OfficeLeave.BUSY, OfficeEditPolicy.leave(edited = false, busy = true))
        assertEquals(OfficeLeave.BUSY, OfficeEditPolicy.leave(edited = true, busy = true))
        assertEquals(OfficeLeave.LEAVE, OfficeEditPolicy.leave(edited = false, busy = false))
    }

    @Test
    fun `failures map to typed UI problems`() {
        assertEquals(
            OfficeProblem.ENGINE_UNAVAILABLE,
            OfficeFailure.Unavailable(OfficeUnavailableReason.MACRO_LOCK).toProblem(),
        )
        assertEquals(
            OfficeProblem.READ_ONLY,
            OfficeFailure.Refused(OfficeRefusal.READ_ONLY).toProblem(),
        )
        assertEquals(
            OfficeProblem.ENGINE_BUSY,
            OfficeFailure.AlreadyOpen(OfficeFamily.TEXT).toProblem(),
        )
        assertEquals(
            OfficeProblem.EXPORT_INVALID,
            OfficeFailure.ExportFailure(OfficeExportFailure.BAD_SIGNATURE).toProblem(),
        )
        assertEquals(
            OfficeProblem.RENDER_FAILED,
            OfficeFailure.Engine(OfficeStage.RENDER).toProblem(),
        )
        assertEquals(
            OfficeProblem.READ_ONLY,
            OfficeFailure.Unsupported(OfficeFeature.PRESENTATION_EDITING, OfficeFamily.PRESENTATION)
                .toProblem(),
        )
        assertEquals(OfficeProblem.NOT_OPEN, OfficeFailure.NotOpen.toProblem())
    }
}
