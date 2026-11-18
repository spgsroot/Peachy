package ru.spgsroot.peachy.documents.office.ui

import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.office.engine.OfficeFormatMatrix
import ru.spgsroot.peachy.documents.office.engine.OfficeLegacyBlock
import ru.spgsroot.peachy.documents.office.engine.OfficeLegacySlide

/**
 * The single switch that decides whether an imported Office session may reach the editor.
 *
 * `false` is the shipped state. An explicitly packaged debug APK can contain the native
 * engine, but packaging and its build manifest do not establish macro or external-link isolation.
 * Enable only after the intended build and runtime isolation checks pass. The editor still
 * requires a successful capability probe and `open` before displaying a document.
 *
 * A manifest declaration is *not* proof of macro or network isolation. Do not replace this gate
 * with an asset or manifest check.
 */
object OfficeIntegration {
    const val ENABLED: Boolean = false
}

enum class OfficeEntryRefusal {
    /** The build has no authorized Office integration; the hub keeps its "not available" state. */
    INTEGRATION_DISABLED,

    /** The stored session format id is not a format this app knows. */
    UNKNOWN_FORMAT,

    /** PDF and images are owned by other editors; the Office screen must not adopt them. */
    NOT_OFFICE,
}

sealed interface OfficeEntryDecision {
    data class Open(val format: DocumentFormat) : OfficeEntryDecision
    data class Refused(val reason: OfficeEntryRefusal) : OfficeEntryDecision
}

object OfficeEntryPolicy {
    fun decide(
        format: DocumentFormat?,
        enabled: Boolean = OfficeIntegration.ENABLED,
    ): OfficeEntryDecision {
        if (!enabled) return OfficeEntryDecision.Refused(OfficeEntryRefusal.INTEGRATION_DISABLED)
        if (format == null) return OfficeEntryDecision.Refused(OfficeEntryRefusal.UNKNOWN_FORMAT)
        if (OfficeFormatMatrix.policy(format) == null) {
            return OfficeEntryDecision.Refused(OfficeEntryRefusal.NOT_OFFICE)
        }
        // `.ppt` is admitted only for the text-only surface; the route never renders its slides.
        return OfficeEntryDecision.Open(format)
    }
}

/**
 * What the read-only `.ppt` surface may show. It never becomes a substitute viewer: only real
 * extracted text, in file order, without preview tiles or export actions.
 */
internal object OfficeLegacyPolicy {

    /** True when at least one slide carries authored text in its title, blocks or notes. */
    fun hasText(slides: List<OfficeLegacySlide>): Boolean = slides.any { slide ->
        slide.title.isNotBlank() ||
            slide.notes.any { it.isNotBlank() } ||
            slide.blocks.any { block ->
                when (block) {
                    is OfficeLegacyBlock.Paragraph -> block.text.isNotBlank()
                    is OfficeLegacyBlock.Table -> block.hasText()
                }
            }
    }
}

private fun OfficeLegacyBlock.Table.hasText(): Boolean =
    rows.any { row -> row.any { cell -> cell.isNotBlank() } }
