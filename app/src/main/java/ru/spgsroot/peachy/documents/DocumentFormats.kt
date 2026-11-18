package ru.spgsroot.peachy.documents

import java.util.Locale

internal const val MIB = 1024L * 1024L

/** Largest file the picker may hand over at all, whatever the family cap says. */
internal const val ABSOLUTE_MAX_DOCUMENT_BYTES = 100L * MIB

/** Headroom kept free, mirroring InternalStorageManager.MIN_FREE_BYTES. */
internal const val MIN_FREE_BYTES = 50L * MIB

/**
 * The formats the substrate recognises, with the exact MIME and extension used for SAF delivery.
 * [id] is also the value stored in `document_sessions.format`; changing one is a migration.
 *
 * Caps follow the engine report §6.2: PDF 100 MiB, Word 50 MiB, Excel 25 MiB, PowerPoint 100 MiB,
 * image 100 MiB.
 */
enum class DocumentFormat(
    val id: String,
    val mime: String,
    val extension: String,
    val kind: DocumentKind,
    val capBytes: Long,
) {
    PDF("pdf", "application/pdf", "pdf", DocumentKind.PDF, 100L * MIB),

    DOCX(
        "docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "docx", DocumentKind.WORD, 50L * MIB,
    ),
    DOCM("docm", "application/vnd.ms-word.document.macroEnabled.12", "docm", DocumentKind.WORD, 50L * MIB),
    DOTX(
        "dotx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "dotx", DocumentKind.WORD, 50L * MIB,
    ),
    DOC("doc", "application/msword", "doc", DocumentKind.WORD, 50L * MIB),
    ODT("odt", "application/vnd.oasis.opendocument.text", "odt", DocumentKind.WORD, 50L * MIB),
    RTF("rtf", "application/rtf", "rtf", DocumentKind.WORD, 50L * MIB),
    MD("md", "text/markdown", "md", DocumentKind.WORD, 50L * MIB),
    TXT("txt", "text/plain", "txt", DocumentKind.WORD, 50L * MIB),

    XLSX(
        "xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xlsx", DocumentKind.EXCEL, 25L * MIB,
    ),
    XLSM("xlsm", "application/vnd.ms-excel.sheet.macroEnabled.12", "xlsm", DocumentKind.EXCEL, 25L * MIB),
    XLSB("xlsb", "application/vnd.ms-excel.sheet.binary.macroEnabled.12", "xlsb", DocumentKind.EXCEL, 25L * MIB),
    XLS("xls", "application/vnd.ms-excel", "xls", DocumentKind.EXCEL, 25L * MIB),
    ODS("ods", "application/vnd.oasis.opendocument.spreadsheet", "ods", DocumentKind.EXCEL, 25L * MIB),
    CSV("csv", "text/csv", "csv", DocumentKind.EXCEL, 25L * MIB),
    TSV("tsv", "text/tab-separated-values", "tsv", DocumentKind.EXCEL, 25L * MIB),

    PPTX(
        "pptx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "pptx", DocumentKind.POWERPOINT, 100L * MIB,
    ),
    PPSX(
        "ppsx",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "ppsx", DocumentKind.POWERPOINT, 100L * MIB,
    ),
    POTX(
        "potx",
        "application/vnd.openxmlformats-officedocument.presentationml.template",
        "potx", DocumentKind.POWERPOINT, 100L * MIB,
    ),
    PPT("ppt", "application/vnd.ms-powerpoint", "ppt", DocumentKind.POWERPOINT, 100L * MIB),

    PNG("png", "image/png", "png", DocumentKind.IMAGE, 100L * MIB),
    JPG("jpg", "image/jpeg", "jpg", DocumentKind.IMAGE, 100L * MIB),
    WEBP("webp", "image/webp", "webp", DocumentKind.IMAGE, 100L * MIB),
    BMP("bmp", "image/bmp", "bmp", DocumentKind.IMAGE, 100L * MIB),
    HEIC("heic", "image/heic", "heic", DocumentKind.IMAGE, 100L * MIB);

    /** Text formats are labelled from the declared extension; their bytes only prove "text". */
    val isText: Boolean get() = this == RTF || this == MD || this == TXT || this == CSV || this == TSV

    val isTextContainer: Boolean get() = this == MD || this == TXT || this == CSV || this == TSV

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Extension aliases that mean an existing format. */
        private val aliases = mapOf(
            "jpeg" to JPG,
            "jpe" to JPG,
            "heif" to HEIC,
            "markdown" to MD,
        )

        fun fromId(id: String?): DocumentFormat? = id?.let { byId[it.trim().lowercase(Locale.ROOT)] }

        /** The declared extension is a *label* only; every format is proven from bytes. */
        fun fromName(name: String?): DocumentFormat? {
            val clean = name?.substringAfterLast('.')?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (clean.isEmpty() || clean.length > 10 || !clean.all { it.isLetterOrDigit() }) return null
            return aliases[clean] ?: byId[clean]
        }

        /**
         * MIME types offered to `OpenDocument`. Providers that report `application/octet-stream`
         * still reach the picker through the generic entry, and the sniffed bytes decide the format.
         */
        val pickerMimeTypes: Array<String> = (
            entries.map { it.mime } + listOf(
                "application/octet-stream",
                "text/*",
                "image/*",
            )
            ).distinct().toTypedArray()
    }
}

enum class DocumentFormatError {
    EMPTY,
    TOO_LARGE,
    NOT_ENOUGH_SPACE,
    UNKNOWN_FORMAT,
    UNSUPPORTED_FORMAT,
    MISMATCH,
    CORRUPT,
    CANCELLED,
}

/** A typed, user-reportable failure. Messages are developer-facing; the UI has its own copy. */
class DocumentFormatException(
    val reason: DocumentFormatError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A format the picker MIME filter may still name but the substrate cannot open. */
internal fun unsupported(name: String, detail: String): Nothing =
    throw DocumentFormatException(DocumentFormatError.UNSUPPORTED_FORMAT, "$name: $detail")

/** Suggested SAF title: a sanitised base name plus the sniffed extension. */
fun suggestedDocumentName(displayName: String?, format: DocumentFormat): String {
    val base = (displayName ?: "")
        .replace('\\', '/')
        .substringAfterLast('/')
        .map { if (it.isISOControl() || it in "\\/:*?\"<>|") ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .substringBeforeLast('.')
        .trim(' ', '.')
        .take(120)
        .trim(' ', '.')
    return "${base.ifEmpty { "Document" }}.${format.extension}"
}
