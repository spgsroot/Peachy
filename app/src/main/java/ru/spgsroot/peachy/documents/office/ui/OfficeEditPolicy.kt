package ru.spgsroot.peachy.documents.office.ui

import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.office.engine.OfficeCommand
import ru.spgsroot.peachy.documents.office.engine.OfficeDocumentInfo
import ru.spgsroot.peachy.documents.office.engine.OfficeExportFailure
import ru.spgsroot.peachy.documents.office.engine.OfficeFailure
import ru.spgsroot.peachy.documents.office.engine.OfficeFeature
import ru.spgsroot.peachy.documents.office.engine.OfficeKey
import ru.spgsroot.peachy.documents.office.engine.OfficeRefusal
import ru.spgsroot.peachy.documents.office.engine.OfficeStage
import ru.spgsroot.peachy.documents.office.engine.OfficeTextInput

/** What the editor may honestly offer for the loaded document. */
enum class OfficeNotice {
    /** In-memory edits: the delivered bridge has no write-back into the working copy. */
    EDITS_IN_MEMORY,

    /** Legacy binary import: text and layout are approximate. */
    LEGACY_FIDELITY,

    /** Macro-bearing container: macros never run and are never exported. */
    MACRO_INPUT,

    /** `.xlsb` has no export filter at all; the export is another format. */
    XLSB_EXPORT,

    /** Presentations are read-only; the export is a copy. */
    READ_ONLY_PRESENTATION,
}

/** A typed, localized failure. Never carries engine text, a path or document content. */
enum class OfficeProblem {
    ENGINE_UNAVAILABLE,
    ENGINE_BROKEN,
    ENGINE_BUSY,
    OPEN_FAILED,
    FAMILY_MISMATCH,
    INVALID_DOCUMENT,
    UNMANAGED_SOURCE,
    PART_OUT_OF_RANGE,
    INPUT_INVALID,
    READ_ONLY,
    COMMAND_NOT_ALLOWED,
    RENDER_FAILED,
    EXPORT_FAILED,
    EXPORT_INVALID,
    SAVE_FAILED,
    DELETE_FAILED,
    NOT_OPEN,
    ENGINE_FAILED,
}

/** Leaving while an engine/SAF operation is in flight is not offered: it would cut a copy short. */
enum class OfficeLeave { LEAVE, CONFIRM, BUSY }

object OfficeEditPolicy {

    /** Mirrors `OfficeTextInput.MAX_CODE_POINTS`; the adapter also enforces it. */
    const val MAX_TEXT_CODE_POINTS = OfficeTextInput.MAX_CODE_POINTS

    /** Only commands the adapter's allowlist grants to this family may reach the UI. */
    fun commands(info: OfficeDocumentInfo): List<OfficeCommand> =
        OfficeCommand.entries.filter { it.allowedFor(info.family) && info.family.editable && !info.readOnly }

    fun isEditable(info: OfficeDocumentInfo?): Boolean =
        info != null && info.family.editable && !info.readOnly

    /**
     * Same tokenizer the adapter runs, so the UI disables an insert before the engine refuses it.
     * Rejects blank input, more than 2048 code points, control characters and lone surrogates.
     */
    fun isInsertable(text: String): Boolean {
        if (text.isEmpty()) return false
        val tokens = OfficeTextInput.tokens(text) ?: return false
        return tokens.isNotEmpty()
    }

    fun mutates(command: OfficeCommand): Boolean = command != OfficeCommand.SELECT_ALL

    fun mutates(key: OfficeKey): Boolean = key in MUTATING_KEYS

    /**
     * Leaving an edited document must be an explicit decision. Nothing here can write the working
     * copy back, so the only exits are "save a copy", "discard the in-memory edits" or "stay".
     */
    fun leave(edited: Boolean, busy: Boolean): OfficeLeave = when {
        busy -> OfficeLeave.BUSY
        edited -> OfficeLeave.CONFIRM
        else -> OfficeLeave.LEAVE
    }

    fun notices(format: DocumentFormat, info: OfficeDocumentInfo?): List<OfficeNotice> {
        val notices = mutableListOf<OfficeNotice>()
        if (info != null && !info.family.editable) notices += OfficeNotice.READ_ONLY_PRESENTATION
        if (format == DocumentFormat.XLSB) notices += OfficeNotice.XLSB_EXPORT
        if (format == DocumentFormat.DOCM || format == DocumentFormat.XLSM) {
            notices += OfficeNotice.MACRO_INPUT
        }
        if (format == DocumentFormat.DOC) notices += OfficeNotice.LEGACY_FIDELITY
        if (isEditable(info)) notices += OfficeNotice.EDITS_IN_MEMORY
        return notices
    }

    private val MUTATING_KEYS = setOf(
        OfficeKey.BACKSPACE,
        OfficeKey.DELETE,
        OfficeKey.RETURN,
        OfficeKey.TAB,
    )
}

/** Total mapping from the bridge's typed failures to UI copy; no message text is forwarded. */
fun OfficeFailure.toProblem(): OfficeProblem = when (this) {
    is OfficeFailure.Unavailable -> OfficeProblem.ENGINE_UNAVAILABLE

    is OfficeFailure.Refused -> when (reason) {
        OfficeRefusal.OPEN_FAILED -> OfficeProblem.OPEN_FAILED
        OfficeRefusal.FAMILY_MISMATCH -> OfficeProblem.FAMILY_MISMATCH
        OfficeRefusal.INVALID_DOCUMENT_SIZE -> OfficeProblem.INVALID_DOCUMENT
        OfficeRefusal.UNMANAGED_SOURCE -> OfficeProblem.UNMANAGED_SOURCE
        OfficeRefusal.PART_OUT_OF_RANGE -> OfficeProblem.PART_OUT_OF_RANGE
        OfficeRefusal.INVALID_INPUT -> OfficeProblem.INPUT_INVALID
        OfficeRefusal.READ_ONLY -> OfficeProblem.READ_ONLY
        OfficeRefusal.COMMAND_NOT_ALLOWED -> OfficeProblem.COMMAND_NOT_ALLOWED
        OfficeRefusal.PART_MODE_UNAVAILABLE -> OfficeProblem.READ_ONLY
    }

    is OfficeFailure.Engine -> when (stage) {
        OfficeStage.RENDER -> OfficeProblem.RENDER_FAILED
        OfficeStage.EXPORT -> OfficeProblem.EXPORT_FAILED
        OfficeStage.AVAILABILITY, OfficeStage.CLOSE -> OfficeProblem.ENGINE_BROKEN
        else -> OfficeProblem.ENGINE_FAILED
    }

    is OfficeFailure.ExportFailure -> when (reason) {
        OfficeExportFailure.BAD_SIGNATURE,
        OfficeExportFailure.EMPTY_OUTPUT,
        OfficeExportFailure.MISSING_OUTPUT,
        -> OfficeProblem.EXPORT_INVALID

        else -> OfficeProblem.EXPORT_FAILED
    }

    is OfficeFailure.Unsupported -> when (feature) {
        OfficeFeature.PRESENTATION_EDITING -> OfficeProblem.READ_ONLY
        else -> OfficeProblem.INPUT_INVALID
    }

    OfficeFailure.NotOpen -> OfficeProblem.NOT_OPEN
    is OfficeFailure.AlreadyOpen -> OfficeProblem.ENGINE_BUSY
}
