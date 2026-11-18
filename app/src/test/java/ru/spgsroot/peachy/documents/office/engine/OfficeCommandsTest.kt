package ru.spgsroot.peachy.documents.office.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeCommandsTest {

    @Test
    fun commandsUseTheirExactPinnedUnoNames() {
        assertEquals(".uno:Bold", OfficeCommand.BOLD.uno)
        assertEquals(".uno:Italic", OfficeCommand.ITALIC.uno)
        assertEquals(".uno:Underline", OfficeCommand.UNDERLINE.uno)
        assertEquals(".uno:DefaultBullet", OfficeCommand.BULLETS.uno)
        assertEquals(".uno:DefaultNumbering", OfficeCommand.NUMBERING.uno)
        assertEquals(".uno:SelectAll", OfficeCommand.SELECT_ALL.uno)
        assertEquals(".uno:InsertRowsBefore", OfficeCommand.INSERT_ROWS_BEFORE.uno)
        assertEquals(".uno:InsertColumnsAfter", OfficeCommand.INSERT_COLUMNS_AFTER.uno)
        assertEquals(".uno:MergeCells", OfficeCommand.MERGE_CELLS.uno)
    }

    @Test
    fun sheetCommandsNeverReachWriterAndViceVersa() {
        assertFalse(OfficeCommand.MERGE_CELLS.allowedFor(OfficeFamily.TEXT))
        assertFalse(OfficeCommand.SPLIT_CELL.allowedFor(OfficeFamily.TEXT))
        assertFalse(OfficeCommand.DELETE_ROWS.allowedFor(OfficeFamily.TEXT))
        assertFalse(OfficeCommand.CENTER_PARA.allowedFor(OfficeFamily.SPREADSHEET))
        assertFalse(OfficeCommand.BULLETS.allowedFor(OfficeFamily.SPREADSHEET))
        assertTrue(OfficeCommand.BOLD.allowedFor(OfficeFamily.SPREADSHEET))
        assertTrue(OfficeCommand.UNDO.allowedFor(OfficeFamily.TEXT))
        assertTrue(OfficeCommand.UNDO.allowedFor(OfficeFamily.SPREADSHEET))
    }

    @Test
    fun presentationsGetNoEditingCommands() {
        assertTrue(OfficeCommand.allowedFor(OfficeFamily.PRESENTATION).isEmpty())
        for (command in OfficeCommand.entries) {
            assertFalse(command.name, command.allowedFor(OfficeFamily.PRESENTATION))
        }
    }

    @Test
    fun textIsTypedAsCodePointsAndLineBreaksBecomeKeys() {
        val tokens = OfficeTextInput.tokens("Привет")!!
        assertEquals(6, tokens.size)
        val first = tokens[0] as OfficeInputToken.Character
        assertEquals('П'.code, first.codePoint)
    }

    @Test
    fun carriageReturnsNormalizeToASingleReturnKey() {
        val tokens = OfficeTextInput.tokens("a\r\nb")!!
        assertEquals(3, tokens.size)
        assertEquals(OfficeInputToken.Key(OfficeKey.RETURN), tokens[1])
        assertEquals(2, OfficeTextInput.tokens("\r\n\r\n")!!.size)
    }

    @Test
    fun tabsBecomeTheTabKey() {
        val tokens = OfficeTextInput.tokens("a\tb")!!
        assertEquals(3, tokens.size)
        assertEquals(OfficeInputToken.Key(OfficeKey.TAB), tokens[1])
    }

    @Test
    fun controlCharactersAreRejected() {
        assertNull(OfficeTextInput.tokens("a\u0001b"))
        assertNull(OfficeTextInput.tokens("\u0000"))
        assertNull(OfficeTextInput.tokens("\u007F"))
    }

    @Test
    fun supplementaryCodePointsStaySingleTokens() {
        val tokens = OfficeTextInput.tokens("😀")!!
        assertEquals(1, tokens.size)
        assertEquals(0x1F600, (tokens[0] as OfficeInputToken.Character).codePoint)
        assertNull(OfficeTextInput.tokens("\uD83D"))
        assertNull(OfficeTextInput.tokens("\uDE00"))
    }

    @Test
    fun textLengthIsCapped() {
        assertEquals(OfficeTextInput.MAX_CODE_POINTS, OfficeTextInput.tokens("a".repeat(OfficeTextInput.MAX_CODE_POINTS))!!.size)
        assertNull(OfficeTextInput.tokens("a".repeat(OfficeTextInput.MAX_CODE_POINTS + 1)))
        // The cap counts code points, not UTF-16 units.
        assertEquals(OfficeTextInput.MAX_CODE_POINTS / 2, OfficeTextInput.tokens("😀".repeat(OfficeTextInput.MAX_CODE_POINTS / 2))!!.size)
    }

    @Test
    fun keyCodesAreTheUnoAwtConstants() {
        assertEquals(1280, OfficeKey.RETURN.code)
        assertEquals(1283, OfficeKey.BACKSPACE.code)
        assertEquals(1286, OfficeKey.DELETE.code)
        assertEquals(1282, OfficeKey.TAB.code)
        assertEquals(1026, OfficeKey.LEFT.code)
        assertEquals(1027, OfficeKey.RIGHT.code)
        assertEquals(0, OfficeKeyEvent.PRESS)
        assertEquals(1, OfficeKeyEvent.RELEASE)
        assertEquals(1, OfficeMouseEvent.BUTTON_LEFT)
    }
}
