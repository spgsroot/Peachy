package ru.spgsroot.peachy.documents.office.engine

import ru.spgsroot.peachy.documents.DocumentFormat

/**
 * Typed model of the LibreOffice bridge. Everything here is plain Kotlin/JVM: the JNI adapter and
 * the Android types live in the other files of this package, so the policy in this file is unit
 * testable without a runtime.
 *
 * Messages on [OfficeFailure] are fixed developer strings. They never contain a file name, a path,
 * a password or engine output, because these objects may end up in logs or crash reports.
 */

/** The three LibreOffice document types the bridge can drive (`Document.DOCTYPE_*`). */
enum class OfficeFamily(val documentType: Int) {
    TEXT(0),
    SPREADSHEET(1),
    PRESENTATION(2);

    /** Only Impress exposes parts with two modes; Writer and Calc parts are plain pages/sheets. */
    val supportsPartMode: Boolean get() = this == PRESENTATION

    /** Plan §4 requires PowerPoint reading only; no editing entry point is exposed for it. */
    val editable: Boolean get() = this != PRESENTATION

    companion object {
        fun fromDocumentType(type: Int): OfficeFamily? = entries.firstOrNull { it.documentType == type }
    }
}

/** `Document.PART_MODE_SLIDE` / `PART_MODE_NOTES` (only meaningful for presentations). */
enum class OfficePartMode(val code: Int) {
    SLIDES(0),
    NOTES(1),
}

data class OfficePart(val index: Int, val name: String)

/** A page rectangle in twips, as returned by `Document.getPartPageRectangles()`. */
data class OfficePageRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Everything the UI needs after a successful load. [readOnly] is decided by the pinned format
 * matrix, not by the engine: the bridge never edits presentations.
 */
data class OfficeDocumentInfo(
    val family: OfficeFamily,
    val format: DocumentFormat,
    val readOnly: Boolean,
    val partMode: OfficePartMode?,
    val parts: List<OfficePart>,
    val widthTwips: Long,
    val heightTwips: Long,
    val pageRects: List<OfficePageRect>,
) {
    val partCount: Int get() = parts.size
}

/**
 * One `paintTile` call. [canvasWidth]/[canvasHeight] are the pixel dimensions of the buffer
 * (`nCanvasWidth` * `nCanvasHeight` * 4 bytes); the tile rectangle is in twips. See
 * `include/LibreOfficeKit/LibreOfficeKit.hxx` (paintTile) at the pinned commit.
 */
data class OfficeTileRequest(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val tileXTwips: Int,
    val tileYTwips: Int,
    val tileWidthTwips: Int,
    val tileHeightTwips: Int,
)

enum class OfficeUnavailableReason {
    /** `org.libreoffice.kit.*` is not in the APK. */
    BRIDGE_CLASSES,

    /** The JNI class is present but the native libraries failed to load. */
    NATIVE_LIBRARIES,

    /** `assets/program` / `assets/unpack` (the unpacked engine tree) is missing or unusable. */
    ENGINE_ASSETS,

    /** No or invalid `office-engine/build-manifest.properties` pinned build declaration. */
    BUILD_MANIFEST,

    /** The declared build does not use the only verified Android pixel path. */
    TILE_MODE,

    /** `initializeNative` returned false or the handle was null. */
    INITIALISATION,

    /** The pinned build's macro lock is not declared, so the engine is refused. */
    MACRO_LOCK,
}

enum class OfficeRefusal {
    /** The engine returned no document for this working copy. */
    OPEN_FAILED,

    /** The loaded document type does not match the sniffed format. */
    FAMILY_MISMATCH,

    /** The engine reported a document size that cannot be rendered. */
    INVALID_DOCUMENT_SIZE,

    /** The caller pointed at something the bridge refuses to open (see [OfficeEngine.open]). */
    UNMANAGED_SOURCE,

    /** The caller asked for a part index out of range. */
    PART_OUT_OF_RANGE,

    /** The text/value argument violates the static input policy. */
    INVALID_INPUT,

    /** The requested edits are not offered for this family/format. */
    READ_ONLY,

    /** The command exists but is not in this family's allowlist. */
    COMMAND_NOT_ALLOWED,

    /** Part modes exist only for presentations. */
    PART_MODE_UNAVAILABLE,
}

enum class OfficeFeature {
    ARBITRARY_UNO_COMMAND,
    INTERACTIVE_PASSWORD,
    HTML_PASTE,
    TEXT_EXTRACTION,
    CELL_ADDRESSING,
    PRESENTATION_EDITING,
    EXTERNAL_URL_OPEN,
    MACRO_EXPORT,

    /** A format that this engine does not own (PDF, images). */
    UNSUPPORTED_FORMAT,
}

enum class OfficeStage {
    AVAILABILITY,
    INIT,
    OPEN,
    RENDER,
    INPUT,
    PART,
    EXPORT,
    /** Read-only legacy presentation structure extraction (`getCommandValues`). */
    EXTRACT,
    CLOSE,
}

enum class OfficeExportFailure {
    NOT_ALLOWED,
    MISSING_OUTPUT,
    EMPTY_OUTPUT,
    TOO_LARGE,
    BAD_SIGNATURE,
    PATH_ESCAPE,
}

/** The single exception type of the bridge; the typed cause is always in [failure]. */
sealed class OfficeFailure(message: String) : Exception(message) {
    class Unavailable(val reason: OfficeUnavailableReason) :
        OfficeFailure("office engine unavailable: ${reason.name}")

    class Refused(val reason: OfficeRefusal) :
        OfficeFailure("office operation refused: ${reason.name}")

    class Engine(val stage: OfficeStage) :
        OfficeFailure("office engine call failed: ${stage.name}")

    class ExportFailure(val reason: OfficeExportFailure) :
        OfficeFailure("office export failed: ${reason.name}")

    class Unsupported(val feature: OfficeFeature, val family: OfficeFamily?) :
        OfficeFailure("office feature unsupported: ${feature.name}")

    data object NotOpen : OfficeFailure("no office document is open")

    class AlreadyOpen(private val family: OfficeFamily) :
        OfficeFailure("another office document is open: ${family.name}")
}

/** Result of the fail-closed capability probe. */
sealed class OfficeAvailability {
    data class Available(val manifest: OfficeBuildManifest) : OfficeAvailability()
    data class Unavailable(val reason: OfficeUnavailableReason) : OfficeAvailability()
}

/** Observable bridge state; a UI must only show an editor for [Open]. */
sealed class OfficeEngineState {
    data object Idle : OfficeEngineState()
    data class Unavailable(val reason: OfficeUnavailableReason) : OfficeEngineState()
    data class Open(val info: OfficeDocumentInfo) : OfficeEngineState()
}

/**
 * Engine callbacks reduced to signals. Payloads are dropped deliberately: LOK sends document
 * content (cell formulas, comments, hyperlink targets) in them and the bridge must never retain,
 * log or forward that text.
 */
enum class OfficeSignal {
    INVALIDATE_TILES,
    DOCUMENT_SIZE_CHANGED,
    PART_CHANGED,
    CURSOR_CHANGED,
    STATE_CHANGED,
    ERROR,
}
