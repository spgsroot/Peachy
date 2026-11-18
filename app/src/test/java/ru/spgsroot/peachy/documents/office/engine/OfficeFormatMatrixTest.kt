package ru.spgsroot.peachy.documents.office.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.spgsroot.peachy.documents.DocumentFormat

class OfficeFormatMatrixTest {

    @Test
    fun wordInputsBelongToTheTextFamily() {
        for (format in listOf(
            DocumentFormat.DOCX,
            DocumentFormat.DOCM,
            DocumentFormat.DOTX,
            DocumentFormat.DOC,
            DocumentFormat.ODT,
            DocumentFormat.RTF,
            DocumentFormat.MD,
            DocumentFormat.TXT,
        )) {
            val policy = OfficeFormatMatrix.policy(format)!!
            assertEquals(format.name, OfficeFamily.TEXT, policy.family)
            assertTrue(format.name, policy.editable)
        }
    }

    @Test
    fun legacyDocIsLabelledLimitedFidelity() {
        assertEquals(OfficeFidelity.LIMITED_LEGACY, OfficeFormatMatrix.policy(DocumentFormat.DOC)!!.fidelity)
        assertEquals(OfficeFidelity.FULL, OfficeFormatMatrix.policy(DocumentFormat.DOCX)!!.fidelity)
    }

    @Test
    fun macroBearingContainersAreFlaggedButStayImportable() {
        // The pinned build sets MacroExecMode::NEVER_EXECUTE (HAVE_FEATURE_ANDROID_LOK=0), so the
        // matrix keeps these openable while the bridge never exports macro-enabled targets.
        for (format in listOf(DocumentFormat.DOCM, DocumentFormat.XLSM, DocumentFormat.XLSB, DocumentFormat.PPT)) {
            val policy = OfficeFormatMatrix.policy(format)!!
            assertTrue(format.name, policy.macroBearing)
        }
        assertFalse(OfficeFormatMatrix.policy(DocumentFormat.DOCX)!!.macroBearing)
    }

    @Test
    fun xlsbImportsIntoTheSpreadsheetFamilyAndExportsXlsx() {
        val policy = OfficeFormatMatrix.policy(DocumentFormat.XLSB)!!
        assertEquals(OfficeFamily.SPREADSHEET, policy.family)
        assertTrue(OfficeFormatMatrix.allows(DocumentFormat.XLSB, OfficeExportTarget.XLSX))
        assertTrue(OfficeFormatMatrix.allows(DocumentFormat.XLSB, OfficeExportTarget.CSV))
        assertEquals(OfficeExportTarget.XLSX, OfficeExportTarget.defaultFor(OfficeFamily.SPREADSHEET))
    }

    @Test
    fun legacyPresentationsAreReadOnlyAndLimited() {
        val policy = OfficeFormatMatrix.policy(DocumentFormat.PPT)!!
        assertEquals(OfficeFamily.PRESENTATION, policy.family)
        assertFalse(policy.editable)
        assertEquals(OfficeFidelity.LIMITED_LEGACY, policy.fidelity)
        assertEquals(OfficeFidelity.FULL, OfficeFormatMatrix.policy(DocumentFormat.PPTX)!!.fidelity)
    }

    @Test
    fun thisEngineDoesNotOwnPdfOrImages() {
        assertNull(OfficeFormatMatrix.policy(DocumentFormat.PDF))
        assertNull(OfficeFormatMatrix.policy(DocumentFormat.PNG))
        assertNull(OfficeFormatMatrix.policy(DocumentFormat.JPG))
        assertTrue(OfficeFormatMatrix.targetsFor(DocumentFormat.PDF).isEmpty())
    }

    @Test
    fun crossFamilyExportsAreRefused() {
        assertFalse(OfficeFormatMatrix.allows(DocumentFormat.DOCX, OfficeExportTarget.XLSX))
        assertFalse(OfficeFormatMatrix.allows(DocumentFormat.XLSX, OfficeExportTarget.DOCX))
        assertFalse(OfficeFormatMatrix.allows(DocumentFormat.PPTX, OfficeExportTarget.XLSX))
        assertFalse(OfficeFormatMatrix.allows(DocumentFormat.PDF, OfficeExportTarget.PDF_TEXT))
    }

    @Test
    fun noApprovedTargetReEmitsMacros() {
        val macroTargets = setOf("docm", "xlsm", "pptm", "potm")
        for (target in OfficeExportTarget.entries) {
            assertFalse(target.name, target.formatArgument in macroTargets)
            assertFalse(target.name, target.extension in macroTargets)
            assertFalse(target.name, target.engineFilterName.contains("VBA"))
        }
    }

    @Test
    fun pdfTargetsUseThePerFamilyFilter() {
        assertEquals("writer_pdf_Export", OfficeExportTarget.PDF_TEXT.engineFilterName)
        assertEquals("calc_pdf_Export", OfficeExportTarget.PDF_CALC.engineFilterName)
        assertEquals("impress_pdf_Export", OfficeExportTarget.PDF_IMPRESS.engineFilterName)
        assertEquals("pdf", OfficeExportTarget.PDF_TEXT.formatArgument)
    }

    @Test
    fun spreadsheetTextExportsUseTheVerifiedCsvToken() {
        assertEquals("csv", OfficeExportTarget.CSV.formatArgument)
        assertEquals("44,34,76,1", OfficeExportTarget.CSV.options)
        assertEquals("Text - txt - csv (StarCalc)", OfficeExportTarget.CSV.engineFilterName)
        // No "tsv" entry exists in aCalcExtensionMap, so the file is written by the csv token.
        assertEquals("csv", OfficeExportTarget.TSV.formatArgument)
        assertEquals("9,34,76,1", OfficeExportTarget.TSV.options)
    }
}
