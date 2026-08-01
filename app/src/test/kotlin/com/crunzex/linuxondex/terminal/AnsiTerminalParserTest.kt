package com.crunzex.linuxondex.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTerminalParserTest {

    private class Harness(columns: Int = 20, rows: Int = 5) {
        val screen = TerminalScreenBuffer(columns, rows)
        val responses = mutableListOf<String>()
        var title = ""
        val parser = AnsiTerminalParser(
            screen = screen,
            respond = { bytes -> responses.add(String(bytes, Charsets.US_ASCII)) },
            onTitleChanged = { title = it },
        )

        fun feed(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            parser.feed(bytes, 0, bytes.size)
        }

        fun line(row: Int): String = screen.rowForViewport(row, 0).trimmedText()
        fun cell(row: Int, column: Int) = screen.rowForViewport(row, 0).let {
            Triple(it.chars[column], it.foreground[column], it.background[column])
        }
    }

    private val esc = ""

    // ---- Plain text and cursor movement -------------------------------------

    @Test
    fun `renders plain text with newlines`() {
        val h = Harness()
        h.feed("hello\r\nworld")
        assertEquals("hello", h.line(0))
        assertEquals("world", h.line(1))
    }

    @Test
    fun `cursor addressing is one-based`() {
        val h = Harness()
        h.feed("$esc[2;3HX")
        assertEquals('X', h.cell(1, 2).first)
    }

    @Test
    fun `relative cursor moves clamp at the edges`() {
        val h = Harness()
        h.feed("$esc[99A$esc[99D")
        assertEquals(0, h.screen.cursorRow)
        assertEquals(0, h.screen.cursorColumn)
        h.feed("$esc[2B$esc[4C")
        assertEquals(2, h.screen.cursorRow)
        assertEquals(4, h.screen.cursorColumn)
    }

    @Test
    fun `column and row addressing via CHA and VPA`() {
        val h = Harness()
        h.feed("$esc[5G")
        assertEquals(4, h.screen.cursorColumn)
        h.feed("$esc[3d")
        assertEquals(2, h.screen.cursorRow)
    }

    // ---- SGR colours ----------------------------------------------------------

    @Test
    fun `basic ANSI colours apply to written cells`() {
        val h = Harness()
        h.feed("$esc[31mR$esc[42mG$esc[0mN")
        assertEquals(TerminalColors.indexed(1), h.cell(0, 0).second)
        assertEquals(TerminalColors.indexed(2), h.cell(0, 1).third)
        assertEquals(TerminalColors.DEFAULT_FOREGROUND, h.cell(0, 2).second)
    }

    @Test
    fun `256-colour and truecolour selections`() {
        val h = Harness()
        h.feed("$esc[38;5;196mA")
        assertEquals(TerminalColors.indexed(196), h.cell(0, 0).second)
        h.feed("$esc[38;2;10;20;30mB")
        assertEquals(TerminalColors.rgb(10, 20, 30), h.cell(0, 1).second)
    }

    @Test
    fun `colon-form truecolour with empty colourspace id`() {
        val h = Harness()
        h.feed("$esc[38:2::10:20:30mZ")
        assertEquals(TerminalColors.rgb(10, 20, 30), h.cell(0, 0).second)
    }

    @Test
    fun `bold and inverse become cell flags`() {
        val h = Harness()
        h.feed("$esc[1;7mX")
        val flags = h.screen.rowForViewport(0, 0).flags[0].toInt()
        assertTrue(flags and TerminalCellFlags.BOLD != 0)
        assertTrue(flags and TerminalCellFlags.INVERSE != 0)
    }

    @Test
    fun `bright colours via 90s range`() {
        val h = Harness()
        h.feed("$esc[92mG")
        assertEquals(TerminalColors.indexed(10), h.cell(0, 0).second)
    }

    // ---- Erase and edit ---------------------------------------------------------

    @Test
    fun `erase display and line dispatch`() {
        val h = Harness()
        h.feed("abcdef$esc[3;1Hbottom")
        h.feed("$esc[1;4H$esc[K") // erase to end of line 1 from col 4
        assertEquals("abc", h.line(0))
        h.feed("$esc[2J")
        assertEquals("", h.line(2))
    }

    @Test
    fun `insert and delete lines through CSI L and M`() {
        val h = Harness()
        h.feed("one\r\ntwo\r\nthree")
        h.feed("$esc[2;1H$esc[1L")
        assertEquals("one", h.line(0))
        assertEquals("", h.line(1))
        assertEquals("two", h.line(2))
        h.feed("$esc[2;1H$esc[1M")
        assertEquals("two", h.line(1))
    }

    @Test
    fun `insert mode set and reset through SM RM`() {
        val h = Harness()
        h.feed("world$esc[1;1H$esc[4hX$esc[4l")
        assertEquals("Xworld", h.line(0))
        assertFalse(h.screen.insertMode)
    }

    // ---- Modes -------------------------------------------------------------------

    @Test
    fun `alternate screen via 1049 saves and restores`() {
        val h = Harness()
        h.feed("shell$esc[?1049h")
        assertTrue(h.screen.isAlternateScreen)
        h.feed("vim content")
        h.feed("$esc[?1049l")
        assertFalse(h.screen.isAlternateScreen)
        assertEquals("shell", h.line(0))
        assertEquals(5, h.screen.cursorColumn) // restored after "shell"
    }

    @Test
    fun `application cursor keys and bracketed paste flags`() {
        val h = Harness()
        h.feed("$esc[?1h$esc[?2004h")
        assertTrue(h.screen.applicationCursorKeys)
        assertTrue(h.screen.bracketedPaste)
        h.feed("$esc[?1l$esc[?2004l")
        assertFalse(h.screen.applicationCursorKeys)
        assertFalse(h.screen.bracketedPaste)
    }

    @Test
    fun `cursor visibility follows DECTCEM`() {
        val h = Harness()
        h.feed("$esc[?25l")
        assertFalse(h.screen.cursorVisible)
        h.feed("$esc[?25h")
        assertTrue(h.screen.cursorVisible)
    }

    @Test
    fun `scroll region set through DECSTBM confines line feeds`() {
        val h = Harness(columns = 10, rows = 4)
        h.feed("$esc[2;3r") // rows 2..3 (1-based)
        h.feed("$esc[2;1Ha\r\nb\r\nc")
        assertEquals("b", h.line(1))
        assertEquals("c", h.line(2))
        assertEquals("", h.line(3))
    }

    // ---- Responses -----------------------------------------------------------------

    @Test
    fun `cursor position report answers with one-based coordinates`() {
        val h = Harness()
        h.feed("$esc[3;5H$esc[6n")
        assertEquals(listOf("$esc[3;5R"), h.responses)
    }

    @Test
    fun `device attributes and status requests are answered`() {
        val h = Harness()
        h.feed("$esc[c$esc[5n")
        assertEquals(listOf("$esc[?6c", "$esc[0n"), h.responses)
    }

    // ---- OSC titles -------------------------------------------------------------------

    @Test
    fun `window title arrives from OSC 0 with BEL terminator`() {
        val h = Harness()
        h.feed("$esc]0;dex@dex: ~")
        assertEquals("dex@dex: ~", h.title)
    }

    @Test
    fun `window title arrives from OSC 2 with ST terminator`() {
        val h = Harness()
        h.feed("$esc]2;hello$esc\\after")
        assertEquals("hello", h.title)
        assertEquals("after", h.line(0))
    }

    // ---- Charsets ----------------------------------------------------------------------

    @Test
    fun `DEC line drawing translates box characters and switches back`() {
        val h = Harness()
        h.feed("$esc(0qqk$esc(Bq")
        assertEquals("──┐q", h.line(0))
    }

    // ---- Robustness ----------------------------------------------------------------------

    @Test
    fun `utf8 sequences split across feeds decode once complete`() {
        val h = Harness()
        val thai = "ส".toByteArray(Charsets.UTF_8) // 3 bytes
        h.parser.feed(thai, 0, 1)
        h.parser.feed(thai, 1, 2)
        assertEquals("ส", h.line(0))
    }

    @Test
    fun `unknown sequences are consumed without visible garbage`() {
        val h = Harness()
        h.feed("$esc[99;99t$esc[?1004h${esc}P+q544e$esc\\ok")
        assertEquals("ok", h.line(0))
    }

    @Test
    fun `control characters execute inside an open sequence`() {
        val h = Harness()
        // CR executes mid-CSI, then EL(1) erases start THROUGH the cursor —
        // column 0 only — so 'a' is blanked and 'b' survives.
        h.feed("ab$esc[\r1K")
        assertEquals(0, h.screen.cursorColumn)
        assertEquals("b", h.line(0).trim())
    }

    @Test
    fun `full reset via RIS clears everything`() {
        val h = Harness()
        h.feed("dirty$esc[31m${esc}c")
        assertEquals("", h.line(0))
        assertEquals(TerminalColors.DEFAULT_FOREGROUND, h.screen.pen.foreground)
    }
}
