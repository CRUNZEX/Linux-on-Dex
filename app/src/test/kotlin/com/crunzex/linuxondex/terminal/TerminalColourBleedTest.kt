package com.crunzex.linuxondex.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Colour must not follow the boot down the screen.
 *
 * Erasing and scrolling fill with the current background — that is what
 * `xterm-256color` promises and what programs rely on. The consequence is
 * that whatever background a program leaves set keeps being painted onto
 * every line that scrolls in afterwards, and a boot menu is exactly the kind
 * of program that hands over without resetting.
 *
 * The bytes below are the real hand-off from Alpine's boot menu, captured
 * from a serial console: it clears the screen, sets white-on-black, prints
 * "Booting …" and never resets again. On a real terminal that is invisible,
 * because black *is* the background. It is only visible if the palette's
 * black is some other shade.
 */
class TerminalColourBleedTest {

    /** Exactly what the menu sends as it hands over to the kernel. */
    private val bootMenuHandover =
        "[0m[30m[40m[2J[01;01H[0m[37m[40m" +
            "  Booting `Alpine Linux v3.22, with Linux virt'\n\r"

    private fun screenAfter(stream: String, columns: Int = 80, rows: Int = 24) =
        TerminalScreenBuffer(columns, rows).also { screen ->
            val parser = AnsiTerminalParser(screen)
            stream.forEach(parser::processChar)
        }

    @Test
    fun `a program that leaves black set paints nothing visible`() {
        val screen = screenAfter(bootMenuHandover)

        // Every cell the menu erased, and every line the kernel scrolls in
        // after it, has to look like an empty terminal.
        val backgrounds = (0 until screen.rows)
            .flatMap { row -> screen.rowForViewport(row, 0).background.toList() }
            .toSet()
        assertEquals(setOf(TerminalColors.DEFAULT_BACKGROUND), backgrounds)
    }

    @Test
    fun `lines scrolled in long after the handover stay clean`() {
        val kernelOutput = (1..40).joinToString("") { "[    ${it}.000000] booting\n\r" }
        val screen = screenAfter(bootMenuHandover + kernelOutput)

        val backgrounds = (0 until screen.rows)
            .flatMap { row -> screen.rowForViewport(row, 0).background.toList() }
            .toSet()
        assertEquals(setOf(TerminalColors.DEFAULT_BACKGROUND), backgrounds)
    }

    @Test
    fun `a colour a program really did ask for is still painted`() {
        // The fix must not turn into "ignore backgrounds": red stays red.
        val screen = screenAfter("[41m[2J")

        assertEquals(
            TerminalColors.indexed(1),
            screen.rowForViewport(0, 0).background[0],
        )
        assertNotEquals(TerminalColors.DEFAULT_BACKGROUND, TerminalColors.indexed(1))
    }

    @Test
    fun `black is the background, and bright black still shows`() {
        assertEquals(TerminalColors.DEFAULT_BACKGROUND, TerminalColors.indexed(0))
        // Dim text uses colour 8; it has to remain readable.
        assertNotEquals(TerminalColors.DEFAULT_BACKGROUND, TerminalColors.indexed(8))
    }

    @Test
    fun `an unhandled colon-form attribute does not leak into the next code`() {
        // SGR 58 (underline colour) is not implemented. Its sub-parameters
        // must be stepped over, or the trailing "2" would read as faint and
        // the rest as colours nobody asked for.
        val screen = screenAfter("[58:2::255:0:0;31mX")

        assertEquals(TerminalColors.indexed(1), screen.rowForViewport(0, 0).foreground[0])
        assertEquals(0, screen.rowForViewport(0, 0).flags[0].toInt())
    }
}
