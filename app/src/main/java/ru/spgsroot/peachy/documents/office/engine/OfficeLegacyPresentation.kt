package ru.spgsroot.peachy.documents.office.engine

import org.json.JSONException
import org.json.JSONObject

/**
 * Typed, read-only interchange for a legacy `.ppt` already opened by [OfficeEngine].
 *
 * Produced only from `Document.getCommandValues(".uno:ExtractDocumentStructure?filter=slides&legacyText=1")`
 * on the pinned native build. The schema is the one emitted by the reviewed candidate:
 * `DocStructure.Slides["Slide N"]` with `Hidden`, optional `Notes`/`NotesParaCount`, and
 * `Objects["Objects N"]` carrying `Texts["Text N"].Paragraphs`, a row-major table
 * (`IsTable`, `Rows`, `Columns`, `Texts`), or neither for a shape without text.
 *
 * The first non-blank paragraph is lifted into [title] and is not repeated in [blocks]; both
 * preserve slide, shape and paragraph order. [Table.rows] is row-major and keeps empty cells.
 */
internal data class OfficeLegacySlide(
    val title: String,
    val hidden: Boolean,
    val blocks: List<OfficeLegacyBlock>,
    val notes: List<String>,
)

internal sealed interface OfficeLegacyBlock {
    data class Paragraph(val text: String) : OfficeLegacyBlock
    data class Table(val rows: List<List<String>>) : OfficeLegacyBlock
}

/** Hard caps for native JSON; violations fail the extraction instead of growing a UI list. */
internal object OfficeLegacyLimits {
    const val MAX_SLIDES = 4096
    const val MAX_OBJECTS_PER_SLIDE = 4096
    const val MAX_TEXTS_PER_OBJECT = 4096
    const val MAX_PARAGRAPHS_PER_TEXT = 4096
    const val MAX_NOTES_PER_SLIDE = 4096
    const val MAX_TABLE_EDGE = 512
    const val MAX_TABLE_CELLS = 16_384

    /** Whole-document text budget in UTF-16 units; the engine output is untrusted input. */
    const val MAX_TOTAL_CHARS = 8 * 1024 * 1024

    /**
     * Raw JSON cap checked before `JSONObject` builds a tree. Twice the text budget leaves room
     * for keys, escaping and empty paragraphs while keeping the pre-validation allocation bounded.
     */
    const val MAX_RAW_CHARS = MAX_TOTAL_CHARS * 2
}

/**
 * Parses the opt-in native JSON or returns `null` for any structural deviation. `null` is mapped
 * to a fixed [OfficeFailure.Engine] failure by the caller; the raw payload is never logged or
 * attached to an exception.
 */
internal fun parseLegacyPresentation(raw: String): List<OfficeLegacySlide>? {
    if (raw.length > OfficeLegacyLimits.MAX_RAW_CHARS) return null
    return try {
        val root = JSONObject(raw).getJSONObject("DocStructure")
        val slideCount = boundedCount(root, "SlideCount", OfficeLegacyLimits.MAX_SLIDES)
            ?: return null
        val slidesJson = root.optJSONObject("Slides")
        val slides: List<JSONObject> = when {
            slidesJson == null -> if (slideCount == 0) emptyList() else return null
            else -> orderedEntries(slidesJson, SLIDE_PREFIX, slideCount) ?: return null
        }
        val budget = LegacyBudget()
        val parsed = ArrayList<OfficeLegacySlide>(slides.size)
        for (slide in slides) {
            parsed.add(parseSlide(slide, budget) ?: return null)
        }
        parsed
    } catch (_: JSONException) {
        null
    }
}

private fun parseSlide(slide: JSONObject, budget: LegacyBudget): OfficeLegacySlide? {
    // `Hidden` is present only in the opt-in legacy schema; its absence is a schema violation.
    if (!slide.has("Hidden")) return null
    val hidden = slide.getBoolean("Hidden")
    val notes = parseNotes(slide, budget) ?: return null
    val objectCount = boundedCount(slide, "ObjectCount", OfficeLegacyLimits.MAX_OBJECTS_PER_SLIDE)
        ?: return null
    val objectsJson = slide.optJSONObject("Objects")
    val objects: List<JSONObject> = when {
        objectsJson == null -> if (objectCount == 0) emptyList() else return null
        else -> orderedEntries(objectsJson, OBJECT_PREFIX, objectCount) ?: return null
    }

    val blocks = ArrayList<OfficeLegacyBlock>()
    for (obj in objects) {
        if (obj.optBoolean("IsTable", false)) {
            blocks.add(parseTable(obj, budget) ?: return null)
        } else if (!parseParagraphBlocks(obj, budget, blocks)) {
            return null
        }
    }

    val titleIndex = blocks.indexOfFirst {
        it is OfficeLegacyBlock.Paragraph && it.text.isNotBlank()
    }
    val title = if (titleIndex >= 0) {
        (blocks.removeAt(titleIndex) as OfficeLegacyBlock.Paragraph).text
    } else {
        ""
    }
    return OfficeLegacySlide(title = title, hidden = hidden, blocks = blocks, notes = notes)
}

private fun parseNotes(slide: JSONObject, budget: LegacyBudget): List<String>? {
    if (!slide.has("Notes")) {
        return if (slide.optInt("NotesParaCount", 0) == 0) emptyList() else null
    }
    val notesJson = slide.optJSONArray("Notes") ?: return null
    val declared = boundedCount(slide, "NotesParaCount", OfficeLegacyLimits.MAX_NOTES_PER_SLIDE)
        ?: return null
    if (declared != notesJson.length()) return null
    val notes = ArrayList<String>(declared)
    for (index in 0 until declared) {
        if (notesJson.isNull(index)) return null
        notes.add(budget.take(notesJson.getString(index)) ?: return null)
    }
    return notes
}

private fun parseParagraphBlocks(
    obj: JSONObject,
    budget: LegacyBudget,
    into: MutableList<OfficeLegacyBlock>,
): Boolean {
    val textCount = optionalCount(obj, "TextCount", OfficeLegacyLimits.MAX_TEXTS_PER_OBJECT)
        ?: return false
    val texts = parseTexts(obj, textCount, budget) ?: return false
    for (paragraphs in texts) {
        for (paragraph in paragraphs) {
            into.add(OfficeLegacyBlock.Paragraph(paragraph))
        }
    }
    return true
}

private fun parseTable(obj: JSONObject, budget: LegacyBudget): OfficeLegacyBlock.Table? {
    val rows = boundedCount(obj, "Rows", OfficeLegacyLimits.MAX_TABLE_EDGE) ?: return null
    val columns = boundedCount(obj, "Columns", OfficeLegacyLimits.MAX_TABLE_EDGE) ?: return null
    if (rows <= 0 || columns <= 0) return null
    val cellCount = rows * columns
    if (cellCount > OfficeLegacyLimits.MAX_TABLE_CELLS) return null

    val textCount = optionalCount(obj, "TextCount", cellCount) ?: return null
    // The native schema omits Texts for a wholly empty table; otherwise it emits every cell.
    if (textCount != 0 && textCount != cellCount) return null
    val texts = parseTexts(obj, textCount, budget) ?: return null

    val grid = ArrayList<List<String>>(rows)
    var next = 0
    for (_row in 0 until rows) {
        val line = ArrayList<String>(columns)
        for (_column in 0 until columns) {
            // A table cell without a Text entry is an empty cell, not an error.
            line.add(if (next < texts.size) texts[next].joinToString("\n") else "")
            next++
        }
        grid.add(line)
    }
    return OfficeLegacyBlock.Table(grid)
}

/** Every `Text N` has exactly `ParaCount` strings; the outer list keeps numeric `Text` order. */
private fun parseTexts(obj: JSONObject, expected: Int, budget: LegacyBudget): List<List<String>>? {
    val textsJson = obj.optJSONObject("Texts")
    val texts: List<JSONObject> = when {
        textsJson == null -> if (expected == 0) emptyList() else return null
        else -> orderedEntries(textsJson, TEXT_PREFIX, expected) ?: return null
    }
    val parsed = ArrayList<List<String>>(texts.size)
    for (text in texts) {
        val paragraphCount =
            boundedCount(text, "ParaCount", OfficeLegacyLimits.MAX_PARAGRAPHS_PER_TEXT)
                ?: return null
        val paragraphs = text.optJSONArray("Paragraphs")
        if (paragraphs == null) {
            if (paragraphCount != 0) return null
            parsed.add(emptyList())
            continue
        }
        if (paragraphs.length() != paragraphCount) return null
        val values = ArrayList<String>(paragraphCount)
        for (index in 0 until paragraphCount) {
            if (paragraphs.isNull(index)) return null
            values.add(budget.take(paragraphs.getString(index)) ?: return null)
        }
        parsed.add(values)
    }
    return parsed
}

private fun boundedCount(json: JSONObject, key: String, max: Int): Int? {
    val value = json.optInt(key, -1)
    return if (value in 0..max) value else null
}

/**
 * `TextCount` is written only for shapes with text; pictures, charts and empty placeholders still
 * occupy an `Objects n` slot but legitimately carry no `TextCount`/`Texts`.
 */
private fun optionalCount(json: JSONObject, key: String, max: Int): Int? =
    if (json.has(key)) boundedCount(json, key, max) else 0

/**
 * Reads a `prefix + decimal index` object map in numeric index order. Keys must cover exactly
 * `0 until expected`; no locale-sensitive sort or object iteration order is involved.
 */
private fun orderedEntries(container: JSONObject, prefix: String, expected: Int): List<JSONObject>? {
    if (container.length() != expected) return null
    val ordered = ArrayList<JSONObject>(expected)
    for (index in 0 until expected) {
        ordered.add(container.optJSONObject("$prefix$index") ?: return null)
    }
    return ordered
}

/** Per-extraction budget so one hostile response cannot allocate unbounded Java strings. */
private class LegacyBudget {
    private var remaining = OfficeLegacyLimits.MAX_TOTAL_CHARS

    fun take(value: String): String? {
        if (value.length > remaining) return null
        remaining -= value.length
        return value
    }
}

private const val SLIDE_PREFIX = "Slide "
private const val OBJECT_PREFIX = "Objects "
private const val TEXT_PREFIX = "Text "
