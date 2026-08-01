package com.crunzex.linuxondex.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the way full-screen monitors (`top`, `htop` without curses
 * optimisations) repaint: enter the alternate screen, then every frame
 * addresses each row absolutely and erases its tail.
 *
 * Pins the property the user actually sees: after any number of frames the
 * screen equals the latest frame exactly — no ghost rows from earlier
 * frames, no gaps, and leaving the program restores the shell screen.
 */
class TopStyleRedrawReplayTest {

    private val screen = TerminalScreenBuffer(COLUMNS, ROWS)
    private val parser = AnsiTerminalParser(screen)

    private fun feed(text: String) {
        for (character in text) parser.processChar(character)
    }

    /** Feeds one escape sequence, ESC prefix included. */
    private fun escape(sequence: String) = feed("\u001B$sequence")

    /** One absolute-addressed frame: row text + erase-to-end per line. */
    private fun drawFrame(lines: List<String>) {
        escape("[H")
        lines.forEachIndexed { index, line ->
            escape("[${index + 1};1H")
            feed(line)
            escape("[K")
        }
        // Frames can shrink: clear everything below the last drawn row.
        escape("[${lines.size + 1};1H")
        escape("[J")
    }

    private fun visibleRowText(row: Int): String =
        screen.rowForViewport(row, scrollbackOffset = 0).trimmedText()

    @Test
    fun `every frame fully replaces the previous one`() {
        feed("shell prompt $ ")
        escape("[?1049h") // top starts: alternate screen

        drawFrame(
            List(ROWS) { row -> "pid ${row * 100} process-frame-one-row-$row" }
        )
        drawFrame(
            List(ROWS - 4) { row -> "pid ${row + 1} frame-two-row-$row" }
        )

        for (row in 0 until ROWS - 4) {
            assertEquals("pid ${row + 1} frame-two-row-$row", visibleRowText(row))
        }
        for (row in ROWS - 4 until ROWS) {
            assertEquals("stale rows must be erased", "", visibleRowText(row))
        }
    }

    @Test
    fun `rows drawn out of order still land on their addressed lines`() {
        escape("[?1049h")

        // top repaints only the rows that changed, in whatever order.
        escape("[5;1H"); feed("row five"); escape("[K")
        escape("[2;1H"); feed("row two"); escape("[K")
        escape("[9;1H"); feed("row nine"); escape("[K")

        assertEquals("row two", visibleRowText(1))
        assertEquals("row five", visibleRowText(4))
        assertEquals("row nine", visibleRowText(8))
        assertEquals("", visibleRowText(0))
        assertEquals("", visibleRowText(5))
    }

    @Test
    fun `quitting restores the shell screen untouched`() {
        feed("dex@dex:~\$ top")
        escape("[?1049h")
        drawFrame(List(ROWS) { row -> "monitor row $row" })
        escape("[?1049l") // top exits

        assertEquals("dex@dex:~\$ top", visibleRowText(0))
        assertTrue((1 until ROWS).all { visibleRowText(it).isEmpty() })
    }

    private companion object {
        const val COLUMNS = 90
        const val ROWS = 30
    }
}
