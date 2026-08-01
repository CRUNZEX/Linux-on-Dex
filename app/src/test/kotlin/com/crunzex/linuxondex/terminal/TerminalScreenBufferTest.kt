package com.crunzex.linuxondex.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenBufferTest {

    private fun buffer(columns: Int = 10, rows: Int = 4) =
        TerminalScreenBuffer(columns, rows)

    private fun TerminalScreenBuffer.type(text: String) {
        for (character in text) writeChar(character)
    }

    private fun TerminalScreenBuffer.lineAt(row: Int): String =
        rowForViewport(row, 0).trimmedText()

    // ---- Writing and wrapping ----------------------------------------------

    @Test
    fun `writes characters and advances the cursor`() {
        val screen = buffer()
        screen.type("hi")
        assertEquals("hi", screen.lineAt(0))
        assertEquals(2, screen.cursorColumn)
    }

    @Test
    fun `autowrap continues on the next line only when the next char arrives`() {
        val screen = buffer(columns = 4, rows = 3)
        screen.type("abcd")
        // DEC semantics: the cursor hangs at the last column after filling it.
        assertEquals(3, screen.cursorColumn)
        assertEquals(0, screen.cursorRow)
        screen.type("e")
        assertEquals("abcd", screen.lineAt(0))
        assertEquals("e", screen.lineAt(1))
    }

    @Test
    fun `disabled autowrap overwrites the last column instead`() {
        val screen = buffer(columns = 4, rows = 2)
        screen.autowrap = false
        screen.type("abcdXY")
        assertEquals("abcY", screen.lineAt(0))
        assertEquals(0, screen.cursorRow)
    }

    @Test
    fun `carriage return and line feed move as a typewriter`() {
        val screen = buffer()
        screen.type("one")
        screen.carriageReturn()
        screen.lineFeed()
        screen.type("two")
        assertEquals("one", screen.lineAt(0))
        assertEquals("two", screen.lineAt(1))
    }

    @Test
    fun `insert mode shifts the tail right`() {
        val screen = buffer()
        screen.type("world")
        screen.moveCursorTo(0, 0)
        screen.insertMode = true
        screen.type("X")
        assertEquals("Xworld", screen.lineAt(0))
    }

    // ---- Scrolling and scrollback ------------------------------------------

    @Test
    fun `line feed at the bottom scrolls and captures scrollback`() {
        val screen = buffer(columns = 5, rows = 2)
        screen.type("aa")
        screen.carriageReturn(); screen.lineFeed()
        screen.type("bb")
        screen.carriageReturn(); screen.lineFeed() // scrolls "aa" into history
        screen.type("cc")
        assertEquals("bb", screen.lineAt(0))
        assertEquals("cc", screen.lineAt(1))
        assertEquals(1, screen.scrollbackSize)
        assertEquals("aa", screen.rowForViewport(0, 1).trimmedText())
    }

    @Test
    fun `scroll region confines scrolling and skips scrollback`() {
        val screen = buffer(columns = 5, rows = 4)
        screen.type("top")
        screen.setScrollRegion(1, 2)
        screen.moveCursorTo(1, 0) // relative to full screen (origin mode off)
        screen.type("a"); screen.carriageReturn(); screen.lineFeed()
        screen.type("b"); screen.carriageReturn(); screen.lineFeed() // scrolls region
        screen.type("c")
        assertEquals("top", screen.lineAt(0)) // untouched above the region
        assertEquals("b", screen.lineAt(1))
        assertEquals("c", screen.lineAt(2))
        assertEquals(0, screen.scrollbackSize) // regions never feed history
    }

    @Test
    fun `reverse line feed at the top scrolls content down`() {
        val screen = buffer(columns = 5, rows = 3)
        screen.type("one")
        screen.moveCursorTo(0, 0)
        screen.reverseLineFeed()
        screen.type("new")
        assertEquals("new", screen.lineAt(0))
        assertEquals("one", screen.lineAt(1))
    }

    // ---- Erasing -------------------------------------------------------------

    @Test
    fun `erase in line variants`() {
        val screen = buffer()
        screen.type("abcdef")
        screen.moveCursorTo(0, 3)
        screen.eraseInLine(0) // cursor to end
        assertEquals("abc", screen.lineAt(0))

        screen.type("XYZ") // line is now "abcXYZ", cursor past the Z
        screen.moveCursorTo(0, 3)
        screen.eraseInLine(1) // start THROUGH cursor: "abcX" goes, "YZ" stays
        assertEquals("YZ", screen.lineAt(0).trim())
    }

    @Test
    fun `erase in display below the cursor`() {
        val screen = buffer(columns = 5, rows = 3)
        screen.type("111")
        screen.moveCursorTo(1, 0); screen.type("222")
        screen.moveCursorTo(2, 0); screen.type("333")
        screen.moveCursorTo(1, 1)
        screen.eraseInDisplay(0)
        assertEquals("111", screen.lineAt(0))
        assertEquals("2", screen.lineAt(1))
        assertEquals("", screen.lineAt(2))
    }

    @Test
    fun `erase uses the pen background colour like xterm`() {
        val screen = buffer()
        screen.pen.background = TerminalColors.indexed(4)
        screen.eraseInLine(2)
        val row = screen.rowForViewport(0, 0)
        assertEquals(TerminalColors.indexed(4), row.background[5])
    }

    // ---- Line and character editing ------------------------------------------

    @Test
    fun `insert and delete lines respect the cursor row`() {
        val screen = buffer(columns = 5, rows = 3)
        screen.type("aa")
        screen.moveCursorTo(1, 0); screen.type("bb")
        screen.moveCursorTo(2, 0); screen.type("cc")

        screen.moveCursorTo(1, 0)
        screen.insertLines(1)
        assertEquals("aa", screen.lineAt(0))
        assertEquals("", screen.lineAt(1))
        assertEquals("bb", screen.lineAt(2))

        screen.deleteLines(1)
        assertEquals("bb", screen.lineAt(1))
    }

    @Test
    fun `delete characters pulls the tail left`() {
        val screen = buffer()
        screen.type("abcdef")
        screen.moveCursorTo(0, 1)
        screen.deleteChars(2)
        assertEquals("adef", screen.lineAt(0))
    }

    @Test
    fun `erase characters blanks without moving`() {
        val screen = buffer()
        screen.type("abcdef")
        screen.moveCursorTo(0, 1)
        screen.eraseChars(2)
        assertEquals("a  def", screen.lineAt(0))
    }

    // ---- Alternate screen -----------------------------------------------------

    @Test
    fun `alternate screen isolates content and restores the primary`() {
        val screen = buffer()
        screen.type("primary")
        screen.switchToAlternateScreen(clear = true)
        assertTrue(screen.isAlternateScreen)
        assertEquals("", screen.lineAt(0))
        screen.type("alt")
        screen.switchToPrimaryScreen()
        assertEquals("primary", screen.lineAt(0))
    }

    @Test
    fun `saved cursors are tracked per screen`() {
        val screen = buffer()
        screen.moveCursorTo(1, 5)
        screen.saveCursor()
        screen.switchToAlternateScreen(clear = true)
        screen.moveCursorTo(3, 2)
        screen.saveCursor()
        screen.switchToPrimaryScreen()
        screen.moveCursorTo(0, 0)
        screen.restoreCursor()
        assertEquals(1, screen.cursorRow)
        assertEquals(5, screen.cursorColumn)
    }

    // ---- Origin mode and resize ----------------------------------------------

    @Test
    fun `origin mode addresses rows relative to the scroll region`() {
        val screen = buffer(columns = 5, rows = 4)
        screen.setScrollRegion(1, 2)
        screen.originMode = true
        screen.moveCursorTo(0, 0)
        assertEquals(1, screen.cursorRow)
        // And it cannot leave the region.
        screen.moveCursorTo(3, 0)
        assertEquals(2, screen.cursorRow)
    }

    @Test
    fun `resize keeps the newest content and clamps the cursor`() {
        val screen = buffer(columns = 10, rows = 4)
        screen.type("keep me")
        screen.moveCursorTo(3, 9)
        screen.resize(6, 2)
        assertEquals(6, screen.columns)
        assertEquals(2, screen.rows)
        // The cursor sat on the last row, so shrinking anchors there: the
        // rows the user is looking at survive and the older text scrolls
        // into history rather than the newest text being thrown away.
        assertTrue(screen.cursorRow <= 1)
        assertTrue(screen.cursorColumn <= 5)
        assertTrue("older output must be kept in history", screen.scrollbackSize > 0)
    }

    @Test
    fun `full reset returns to power-on state`() {
        val screen = buffer()
        screen.type("junk")
        screen.pen.foreground = 0x123456
        screen.switchToAlternateScreen(clear = false)
        screen.bracketedPaste = true
        screen.fullReset()
        assertFalse(screen.isAlternateScreen)
        assertFalse(screen.bracketedPaste)
        assertEquals("", screen.lineAt(0))
        assertEquals(TerminalColors.DEFAULT_FOREGROUND, screen.pen.foreground)
    }

    // ---- Tabs ------------------------------------------------------------------

    @Test
    fun `tabs stop every eight columns by default`() {
        val screen = buffer(columns = 20, rows = 2)
        screen.horizontalTab()
        assertEquals(8, screen.cursorColumn)
        screen.horizontalTab()
        assertEquals(16, screen.cursorColumn)
        screen.backwardTab()
        assertEquals(8, screen.cursorColumn)
    }

    @Test
    fun `revision advances on visible changes`() {
        val screen = buffer()
        val before = screen.revision
        screen.type("x")
        assertTrue(screen.revision > before)
    }
}
