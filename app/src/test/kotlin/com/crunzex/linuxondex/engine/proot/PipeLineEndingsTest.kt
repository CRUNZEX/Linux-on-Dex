package com.crunzex.linuxondex.engine.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The Enter key sends carriage return. A shell reading a raw pipe (the
 * bundled-Alpine session) needs a newline instead, or it never sees the end
 * of a command line and silently ignores everything typed.
 *
 * Expectations are written with visible escapes so a failure message shows
 * which line ending actually came through.
 */
class PipeLineEndingsTest {

    private fun translate(keystrokes: String): String =
        String(PipeLineEndings.forShellPipe(keystrokes.toByteArray())).escapeLineEndings()

    private fun String.escapeLineEndings(): String =
        replace("\r", "\\r").replace("\n", "\\n")

    @Test
    fun `Enter becomes a newline the shell recognises`() {
        assertEquals("uname -m\\n", translate("uname -m\r"))
    }

    @Test
    fun `a pasted CRLF submits once, not twice`() {
        assertEquals("one\\ntwo\\n", translate("one\r\ntwo\r\n"))
    }

    @Test
    fun `every line of a multi-line paste is submitted`() {
        assertEquals("cd /tmp\\nls\\npwd\\n", translate("cd /tmp\rls\rpwd\r"))
    }

    @Test
    fun `ordinary keystrokes are passed through untouched`() {
        val typed = "echo hi".toByteArray()

        assertSame(
            "no copy when there is nothing to translate",
            typed,
            PipeLineEndings.forShellPipe(typed),
        )
    }

    @Test
    fun `control bytes survive translation`() {
        // Ctrl-C and an arrow key must reach the shell unchanged.
        assertEquals("\u0003\u001B[A\\n", translate("\u0003\u001B[A\r"))
    }
}
