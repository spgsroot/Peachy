package ru.spgsroot.peachy.documents.office.ui

import java.io.File
import java.util.Locale
import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.DocumentKind
import ru.spgsroot.peachy.documents.office.engine.OfficeDocumentInfo
import ru.spgsroot.peachy.documents.office.engine.OfficeExportPlan
import ru.spgsroot.peachy.documents.office.engine.OfficeExportTarget
import ru.spgsroot.peachy.documents.office.engine.OfficeFamily
import ru.spgsroot.peachy.documents.office.engine.OfficeFormatMatrix

/**
 * A staged export that already passed the engine's byte-level validation. The MIME and the file
 * name are typed here, from the pinned target, so the SAF intent cannot be built from a document
 * string or a user-edited name.
 */
data class OfficeExportTicket(
    val target: OfficeExportTarget,
    val file: File,
    val mime: String,
    val fileName: String,
    val kind: DocumentKind?,
)

object OfficeExportTickets {

    /** Targets the pinned matrix allows for [format]; never `.xlsb`, never a macro-enabled format. */
    fun targetsFor(format: DocumentFormat, info: OfficeDocumentInfo?): List<OfficeExportTarget> {
        val policy = OfficeFormatMatrix.policy(format) ?: return emptyList()
        if (info != null && info.family != policy.family) return emptyList()
        return OfficeFormatMatrix.targetsFor(format)
    }

    fun ticket(plan: OfficeExportPlan, displayName: String): OfficeExportTicket {
        val target = plan.target
        val format = DocumentFormat.fromName("copy.${target.extension}")
        val kind = format?.kind ?: when (target.family) {
            OfficeFamily.TEXT -> DocumentKind.WORD
            OfficeFamily.SPREADSHEET -> DocumentKind.EXCEL
            OfficeFamily.PRESENTATION -> DocumentKind.POWERPOINT
        }
        return OfficeExportTicket(
            target = target,
            file = plan.stagedFile,
            mime = target.mime,
            fileName = fileName(displayName, target),
            kind = kind,
        )
    }

    /**
     * ASCII-safe SAF title in the target's extension. The display name is a label from the import
     * name, not a path: separators, control characters and reserved characters become spaces.
     */
    fun fileName(displayName: String, target: OfficeExportTarget): String {
        val base = displayName
            .map { if (it.isISOControl() || it in "\\/:*?\"<>|") ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .substringBeforeLast('.')
            .trim(' ', '.')
            .take(MAX_NAME_LENGTH)
            .trim(' ', '.')
        return "${base.ifEmpty { DEFAULT_NAME }}.${target.extension.lowercase(Locale.ROOT)}"
    }

    private const val MAX_NAME_LENGTH = 120
    private const val DEFAULT_NAME = "Document"
}
