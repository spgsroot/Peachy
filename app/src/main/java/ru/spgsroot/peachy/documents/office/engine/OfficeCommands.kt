package ru.spgsroot.peachy.documents.office.engine

/**
 * Key codes are `com.sun.star.awt.Key` constants (`offapi/com/sun/star/awt/Key.idl` at the pinned
 * commit), which is exactly what the upstream Android client passes to `Document.postKeyEvent`.
 */
object OfficeKeyCode {
    const val DOWN = 1024
    const val UP = 1025
    const val LEFT = 1026
    const val RIGHT = 1027
    const val HOME = 1028
    const val END = 1029
    const val PAGE_UP = 1030
    const val PAGE_DOWN = 1031
    const val RETURN = 1280
    const val ESCAPE = 1281
    const val TAB = 1282
    const val BACKSPACE = 1283
    const val DELETE = 1286
}

/** `Document.KEY_EVENT_PRESS` / `KEY_EVENT_RELEASE`. */
object OfficeKeyEvent {
    const val PRESS = 0
    const val RELEASE = 1
}

/** `Document.MOUSE_EVENT_*` and `Document.MOUSE_BUTTON_LEFT`. */
object OfficeMouseEvent {
    const val BUTTON_DOWN = 0
    const val BUTTON_UP = 1
    const val MOVE = 2
    const val BUTTON_LEFT = 1
    const val MODIFIER_NONE = 0
}

/** The control keys the bridge is allowed to synthesize. */
enum class OfficeKey(val code: Int) {
    BACKSPACE(OfficeKeyCode.BACKSPACE),
    DELETE(OfficeKeyCode.DELETE),
    RETURN(OfficeKeyCode.RETURN),
    TAB(OfficeKeyCode.TAB),
    LEFT(OfficeKeyCode.LEFT),
    RIGHT(OfficeKeyCode.RIGHT),
    UP(OfficeKeyCode.UP),
    DOWN(OfficeKeyCode.DOWN),
    HOME(OfficeKeyCode.HOME),
    END(OfficeKeyCode.END),
    PAGE_UP(OfficeKeyCode.PAGE_UP),
    PAGE_DOWN(OfficeKeyCode.PAGE_DOWN),
    ESCAPE(OfficeKeyCode.ESCAPE),
}

/**
 * The closed set of UNO commands the bridge may post. Every name is taken from the pinned
 * `sw/`/`sc/` UI configuration (no user or document input can add a command); arguments stay
 * `null`/fixed so no JSON from the document or the caller is ever executed. Commands that need a
 * property payload (`FontHeight`, `StyleApply`, …) are deliberately absent.
 */
enum class OfficeCommand(val uno: String) {
    BOLD(".uno:Bold"),
    ITALIC(".uno:Italic"),
    UNDERLINE(".uno:Underline"),
    CENTER_PARA(".uno:CenterPara"),
    JUSTIFY_PARA(".uno:JustifyPara"),
    BULLETS(".uno:DefaultBullet"),
    NUMBERING(".uno:DefaultNumbering"),
    UNDO(".uno:Undo"),
    REDO(".uno:Redo"),
    SELECT_ALL(".uno:SelectAll"),
    DELETE(".uno:Delete"),
    INSERT_ROWS_BEFORE(".uno:InsertRowsBefore"),
    INSERT_ROWS_AFTER(".uno:InsertRowsAfter"),
    INSERT_COLUMNS_BEFORE(".uno:InsertColumnsBefore"),
    INSERT_COLUMNS_AFTER(".uno:InsertColumnsAfter"),
    DELETE_ROWS(".uno:DeleteRows"),
    DELETE_COLUMNS(".uno:DeleteColumns"),
    MERGE_CELLS(".uno:MergeCells"),
    SPLIT_CELL(".uno:SplitCell");

    /**
     * Computed, not a constructor argument: enum entries are initialized before a companion object,
     * so a constructor argument must never read a companion field.
     */
    fun allowedFor(family: OfficeFamily): Boolean = family in familiesOf(this)

    companion object {
        fun allowedFor(family: OfficeFamily): List<OfficeCommand> = entries.filter { it.allowedFor(family) }
    }
}

private val TEXT_ONLY: Set<OfficeFamily> = setOf(OfficeFamily.TEXT)
private val SHEET_ONLY: Set<OfficeFamily> = setOf(OfficeFamily.SPREADSHEET)
private val TEXT_AND_SHEET: Set<OfficeFamily> = setOf(OfficeFamily.TEXT, OfficeFamily.SPREADSHEET)

private fun familiesOf(command: OfficeCommand): Set<OfficeFamily> = when (command) {
    OfficeCommand.CENTER_PARA,
    OfficeCommand.JUSTIFY_PARA,
    OfficeCommand.BULLETS,
    OfficeCommand.NUMBERING,
    -> TEXT_ONLY

    OfficeCommand.INSERT_ROWS_BEFORE,
    OfficeCommand.INSERT_ROWS_AFTER,
    OfficeCommand.INSERT_COLUMNS_BEFORE,
    OfficeCommand.INSERT_COLUMNS_AFTER,
    OfficeCommand.DELETE_ROWS,
    OfficeCommand.DELETE_COLUMNS,
    OfficeCommand.MERGE_CELLS,
    OfficeCommand.SPLIT_CELL,
    -> SHEET_ONLY

    else -> TEXT_AND_SHEET
}

/** One synthetic input unit produced from a text fragment. */
sealed class OfficeInputToken {
    data class Character(val codePoint: Int) : OfficeInputToken()
    data class Key(val key: OfficeKey) : OfficeInputToken()
}

/**
 * The typing path. `Document.paste` cannot be used for this: the JNI wrapper passes the UTF-16
 * length of the Java string as the byte count of the UTF-8 buffer
 * (`desktop/source/lib/lokandroid.cxx`, `Java_..._paste`), so any non-ASCII text would be truncated.
 * Upstream's Android client inserts text through `postKeyEvent` code points instead, and so does the
 * bridge.
 */
object OfficeTextInput {
    const val MAX_CODE_POINTS = 2048

    /** Normalizes line endings and rejects control characters the engine must not receive. */
    fun tokens(text: String): List<OfficeInputToken>? {
        if (text.codePointCount() > MAX_CODE_POINTS) return null
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val tokens = ArrayList<OfficeInputToken>(normalized.length)
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            index += codePointWidth(codePoint)
            when {
                codePoint == '\n'.code -> tokens.add(OfficeInputToken.Key(OfficeKey.RETURN))
                codePoint == '\t'.code -> tokens.add(OfficeInputToken.Key(OfficeKey.TAB))
                codePoint < 0x20 || codePoint == 0x7F -> return null
                // A lone UTF-16 surrogate is not a character LOK can insert.
                codePoint in 0xD800..0xDFFF -> return null
                else -> tokens.add(OfficeInputToken.Character(codePoint))
            }
        }
        return tokens
    }

    private fun codePointWidth(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

    private fun String.codePointCount(): Int {
        var count = 0
        var index = 0
        while (index < length) {
            index += codePointWidth(codePointAt(index))
            count++
        }
        return count
    }
}
