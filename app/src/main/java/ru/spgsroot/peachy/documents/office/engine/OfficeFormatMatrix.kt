package ru.spgsroot.peachy.documents.office.engine

import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.DocumentKind

/** How faithful the pinned import filter is for a given input, as promised to the user. */
enum class OfficeFidelity {
    /** The format has a real import filter in the pinned revision. */
    FULL,

    /** Legacy binary import: text and layout are best effort, never a fidelity promise. */
    LIMITED_LEGACY,
}

/**
 * What the bridge offers for one sniffed input format. `null` from [OfficeFormatMatrix.policy]
 * means "not this engine": PDF belongs to MuPDF, images to the raster editor.
 */
data class OfficeImportPolicy(
    val family: OfficeFamily,
    val editable: Boolean,
    val fidelity: OfficeFidelity,
    /** Macro-bearing container. Macros never run in the pinned build; exports never re-emit them. */
    val macroBearing: Boolean,
)

/** Byte-level proof expected from an exported file before it may reach SAF. */
enum class OfficeSignature { ZIP, PDF, RTF, HTML, TEXT }

/**
 * CSV filter option token, documented by LibreOffice (`csv_params`): field separator 44 (comma),
 * text delimiter 34 (quote), charset 76 (`RTL_TEXTENCODING_UTF8`, `include/rtl/textenc.h`), first
 * line 1. Tokens 5-15 keep the filter's defaults. Top-level `const val` on purpose: enum entries are
 * initialised before a companion object, so an entry argument must not read one.
 */
private const val CSV_FILTER_OPTIONS = "44,34,76,1"

/** The same token set with 9 (tab) as the field separator; the csv filter writes the tsv file. */
private const val TSV_FILTER_OPTIONS = "9,34,76,1"

/**
 * The approved export matrix. [engineFilterName] documents which filter `Document.saveAs` resolves
 * for [formatArgument] in the pinned `desktop/source/lib/init.cxx` extension maps; the bridge passes
 * [formatArgument] (an extension token), never the filter name, and never the macro-enabled tokens
 * (`docm`, `xlsm`, `pptm`).
 */
enum class OfficeExportTarget(
    val extension: String,
    val formatArgument: String,
    val family: OfficeFamily,
    val mime: String,
    val engineFilterName: String,
    val options: String,
    val signature: OfficeSignature,
) {
    DOCX("docx", "docx", OfficeFamily.TEXT, DocumentFormat.DOCX.mime,
        "Office Open XML Text", "", OfficeSignature.ZIP),
    ODT("odt", "odt", OfficeFamily.TEXT, DocumentFormat.ODT.mime,
        "writer8", "", OfficeSignature.ZIP),
    RTF("rtf", "rtf", OfficeFamily.TEXT, DocumentFormat.RTF.mime,
        "Rich Text Format", "", OfficeSignature.RTF),
    HTML("html", "html", OfficeFamily.TEXT, "text/html",
        "HTML (StarWriter)", "", OfficeSignature.HTML),
    MARKDOWN("md", "md", OfficeFamily.TEXT, DocumentFormat.MD.mime,
        "Markdown", "", OfficeSignature.TEXT),
    TXT("txt", "txt", OfficeFamily.TEXT, DocumentFormat.TXT.mime,
        "Text", "", OfficeSignature.TEXT),
    PDF_TEXT("pdf", "pdf", OfficeFamily.TEXT, DocumentFormat.PDF.mime,
        "writer_pdf_Export", "", OfficeSignature.PDF),

    XLSX("xlsx", "xlsx", OfficeFamily.SPREADSHEET, DocumentFormat.XLSX.mime,
        "Calc Office Open XML", "", OfficeSignature.ZIP),
    CSV("csv", "csv", OfficeFamily.SPREADSHEET, DocumentFormat.CSV.mime,
        "Text - txt - csv (StarCalc)", CSV_FILTER_OPTIONS, OfficeSignature.TEXT),
    TSV("tsv", "csv", OfficeFamily.SPREADSHEET, DocumentFormat.TSV.mime,
        "Text - txt - csv (StarCalc)", TSV_FILTER_OPTIONS, OfficeSignature.TEXT),
    ODS("ods", "ods", OfficeFamily.SPREADSHEET, DocumentFormat.ODS.mime,
        "calc8", "", OfficeSignature.ZIP),
    PDF_CALC("pdf", "pdf", OfficeFamily.SPREADSHEET, DocumentFormat.PDF.mime,
        "calc_pdf_Export", "", OfficeSignature.PDF),

    PPTX("pptx", "pptx", OfficeFamily.PRESENTATION, DocumentFormat.PPTX.mime,
        "Impress MS PowerPoint 2007 XML", "", OfficeSignature.ZIP),
    PDF_IMPRESS("pdf", "pdf", OfficeFamily.PRESENTATION, DocumentFormat.PDF.mime,
        "impress_pdf_Export", "", OfficeSignature.PDF);

    companion object {
        /** Targets that write the edits out of the working copy, per family. */
        fun forFamily(family: OfficeFamily): List<OfficeExportTarget> =
            entries.filter { it.family == family }

        fun defaultFor(family: OfficeFamily): OfficeExportTarget? = when (family) {
            OfficeFamily.TEXT -> DOCX
            OfficeFamily.SPREADSHEET -> XLSX
            OfficeFamily.PRESENTATION -> PPTX
        }
    }
}

/**
 * The pinned import/export matrix. See `.impeccable/office-engine-report.md` §7 and §8 for the
 * source evidence of the filter set.
 */
object OfficeFormatMatrix {

    fun policy(format: DocumentFormat): OfficeImportPolicy? = when (format.kind) {
        DocumentKind.WORD -> OfficeImportPolicy(
            family = OfficeFamily.TEXT,
            editable = true,
            fidelity = if (format == DocumentFormat.DOC) OfficeFidelity.LIMITED_LEGACY else OfficeFidelity.FULL,
            macroBearing = format == DocumentFormat.DOCM,
        )

        DocumentKind.EXCEL -> OfficeImportPolicy(
            family = OfficeFamily.SPREADSHEET,
            editable = true,
            fidelity = OfficeFidelity.FULL,
            macroBearing = format == DocumentFormat.XLSM || format == DocumentFormat.XLSB,
        )

        DocumentKind.POWERPOINT -> OfficeImportPolicy(
            family = OfficeFamily.PRESENTATION,
            editable = false,
            // `.ppt` is a legacy binary import; the plan promises text/limited layout only.
            fidelity = if (format == DocumentFormat.PPT) OfficeFidelity.LIMITED_LEGACY else OfficeFidelity.FULL,
            macroBearing = format == DocumentFormat.PPT,
        )

        DocumentKind.PDF, DocumentKind.IMAGE -> null
    }

    fun familyOf(format: DocumentFormat): OfficeFamily? = policy(format)?.family

    /** True when [target] can be produced from [source] without crossing families. */
    fun allows(source: DocumentFormat, target: OfficeExportTarget): Boolean {
        val policy = policy(source) ?: return false
        return policy.family == target.family
    }

    fun targetsFor(source: DocumentFormat): List<OfficeExportTarget> {
        val policy = policy(source) ?: return emptyList()
        return OfficeExportTarget.forFamily(policy.family)
    }
}
