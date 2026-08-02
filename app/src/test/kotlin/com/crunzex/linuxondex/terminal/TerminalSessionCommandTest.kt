package com.crunzex.linuxondex.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSessionCommandTest {

    @Test
    fun `the size sync command clears the input line and runs stty`() {
        val bytes = TerminalSession.buildSttySizeCommand(rows = 35, columns = 96)

        // Kill-line first, so half-typed input never corrupts the command;
        // carriage return last, because terminals send CR for Enter.
        assertEquals(0x15, bytes.first().toInt())
        assertEquals(0x0D, bytes.last().toInt())
        assertEquals(
            "stty rows 35 cols 96",
            String(bytes, 1, bytes.size - 2, Charsets.US_ASCII),
        )
    }
}

/**
 * Pasting multi-line text is where a terminal most visibly goes wrong: send
 * the wrong line break and every line lands one column further right than
 * the last, which on a narrow phone window looks like garbage.
 */
class TerminalPasteTest {

    @Test
    fun `each line break becomes the carriage return Enter sends`() {
        assertEquals(
            "one\rtwo\rthree",
            TerminalSession.normalizePastedLineEndings("one\ntwo\nthree"),
        )
    }

    @Test
    fun `windows line endings do not produce a double break`() {
        assertEquals(
            "one\rtwo",
            TerminalSession.normalizePastedLineEndings("one\r\ntwo"),
        )
    }

    @Test
    fun `text that already uses carriage returns is left alone`() {
        assertEquals("a\rb", TerminalSession.normalizePastedLineEndings("a\rb"))
    }

    @Test
    fun `a single line and an empty paste are unchanged`() {
        assertEquals("sudo apt update", TerminalSession.normalizePastedLineEndings("sudo apt update"))
        assertEquals("", TerminalSession.normalizePastedLineEndings(""))
    }

    @Test
    fun `a trailing newline still runs the last line`() {
        assertEquals("echo hi\r", TerminalSession.normalizePastedLineEndings("echo hi\n"))
    }
}
