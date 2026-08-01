package com.crunzex.linuxondex.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Squeezing the terminal window — a split-screen tablet, a dragged DeX
 * window — must keep the newest output and the prompt on screen. Losing the
 * bottom rows is what made a resized window look like garbage.
 */
class TerminalResizeTest {

    private fun screenWith(lines: List<String>, columns: Int = 40, rows: Int = 24):
        TerminalScreenBuffer {
        val screen = TerminalScreenBuffer(columns, rows, scrollbackLimit = 500)
        val parser = AnsiTerminalParser(screen)
        lines.forEach { line ->
            line.forEach(parser::processChar)
            parser.processChar('\r')
            parser.processChar('\n')
        }
        return screen
    }

    private fun visibleRow(screen: TerminalScreenBuffer, row: Int): String =
        screen.rowForViewport(row, scrollbackOffset = 0).trimmedText()

    @Test
    fun `shrinking keeps the newest lines, not the oldest`() {
        val screen = screenWith((1..12).map { "line $it" })

        screen.resize(newColumns = 40, newRows = 6)

        // The prompt line the user was looking at is line 12; it must survive.
        val visible = (0 until 6).map { visibleRow(screen, it) }
        assertTrue("newest line lost: $visible", visible.contains("line 12"))
        assertTrue("stale top kept: $visible", !visible.contains("line 1"))
    }

    @Test
    fun `rows pushed off the top are kept in scrollback`() {
        val screen = screenWith((1..12).map { "line $it" })
        val historyBefore = screen.scrollbackSize

        screen.resize(newColumns = 40, newRows = 6)

        assertTrue(
            "resize must not silently discard output",
            screen.scrollbackSize > historyBefore,
        )
    }

    @Test
    fun `growing the window disturbs nothing`() {
        val screen = screenWith((1..5).map { "line $it" })

        screen.resize(newColumns = 60, newRows = 40)

        assertEquals("line 1", visibleRow(screen, 0))
        assertEquals("line 5", visibleRow(screen, 4))
    }

    @Test
    fun `the drop calculation only ever trims what does not fit`() {
        // Content already fits, or the window is growing: nothing moves.
        assertEquals(0, TerminalScreenBuffer.rowsDroppedWhenShrinking(lastUsedRow = 3, targetRows = 24))
        assertEquals(0, TerminalScreenBuffer.rowsDroppedWhenShrinking(lastUsedRow = 23, targetRows = 24))
        // 24 rows of content into a 10-row window: the oldest 14 scroll off.
        assertEquals(14, TerminalScreenBuffer.rowsDroppedWhenShrinking(lastUsedRow = 23, targetRows = 10))
    }

    @Test
    fun `a very narrow window is still a usable terminal`() {
        val screen = screenWith(listOf("a rather long line of output"))

        screen.resize(newColumns = 1, newRows = 1)

        // Clamped to the minimum rather than crashing on a zero-sized grid.
        assertTrue(screen.columns >= TerminalScreenBuffer.MIN_COLUMNS)
        assertTrue(screen.rows >= TerminalScreenBuffer.MIN_ROWS)
    }
}

/**
 * A guest learns the terminal's size by asking for it, and the answer is an
 * escape sequence it consumes — nothing is ever typed into whatever happens
 * to be running. That is what makes a resize safe at a boot menu, in an
 * installer, or inside a full-screen program.
 */
class TerminalSizeReportTest {

    private fun replyTo(request: String, columns: Int, rows: Int): String {
        val screen = TerminalScreenBuffer(columns, rows)
        val answers = StringBuilder()
        val parser = AnsiTerminalParser(screen, respond = { answers.append(String(it)) })
        request.forEach(parser::processChar)
        return answers.toString()
    }

    @Test
    fun `the text area size is reported in characters`() {
        assertEquals(
            "[8;24;80t",
            replyTo("[18t", columns = 80, rows = 24),
        )
    }

    @Test
    fun `the screen size question is answered too`() {
        assertEquals(
            "[9;37;52t",
            replyTo("[19t", columns = 52, rows = 37),
        )
    }

    @Test
    fun `window commands that would change the terminal are ignored`() {
        // Move, resize, iconify, raise: a guest does not get to decide those.
        assertEquals("", replyTo("[1t[3;0;0t[8;40;100t", 80, 24))
    }

    @Test
    fun `the cursor position report still answers`() {
        // The images' console helper derives the size from this one.
        assertEquals("[1;1R", replyTo("[6n", columns = 80, rows = 24))
    }
}
