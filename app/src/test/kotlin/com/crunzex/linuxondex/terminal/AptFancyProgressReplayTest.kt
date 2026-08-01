package com.crunzex.linuxondex.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the exact escape-sequence dance `apt` performs for its fancy
 * progress (Dpkg::Progress-Fancy): reserve the last row with DECSTBM, print
 * package lines inside the region, and redraw the bar on the reserved row
 * via save-cursor / position / restore-cursor.
 *
 * These tests pin the whole choreography end to end, because the visible
 * bug they guard against is dramatic: without a working scroll region the
 * bar scrolls up with the text and a copy of it is left behind on every
 * line of output.
 */
class AptFancyProgressReplayTest {

    private val screen = TerminalScreenBuffer(COLUMNS, ROWS, scrollbackLimit = 200)
    private val parser = AnsiTerminalParser(screen)

    private fun feed(text: String) {
        for (character in text) parser.processChar(character)
    }

    /** What apt runs once before installing, sized for [ROWS] rows. */
    private fun setUpAptScrollArea() {
        feed("\n")
        feed("7")                 // save cursor
        feed("[0;${ROWS - 1}r")   // reserve the last row (1-based bottom)
        feed("8")                 // restore cursor
        feed("[1A")               // compensate for the reserving newline
    }

    /** One bar redraw, exactly as apt emits it. */
    private fun drawProgressBar(percent: Int) {
        feed("7")                 // save cursor
        feed("[$ROWS;0f")         // jump to the reserved bottom row
        feed("[42m")
        feed("Progress: [ $percent%]")
        feed("[0m")
        feed("[K")
        feed("8")                 // restore cursor
    }

    private fun visibleRowText(row: Int): String =
        screen.rowForViewport(row, scrollbackOffset = 0).trimmedText()

    private fun scrollbackText(): List<String> = buildList {
        for (offset in screen.scrollbackSize downTo 1) {
            add(screen.rowForViewport(0, scrollbackOffset = offset).trimmedText())
        }
    }

    @Test
    fun `the progress bar stays pinned to the bottom row while text scrolls`() {
        setUpAptScrollArea()

        repeat(PACKAGE_LINES) { lineNumber ->
            feed("Unpacking package-$lineNumber ...\r\n")
            drawProgressBar(percent = lineNumber)
        }

        assertEquals(
            "Progress: [ ${PACKAGE_LINES - 1}%]",
            visibleRowText(ROWS - 1),
        )
        // The row directly above the bar is the blank input line (its text
        // scrolled up when the trailing newline was printed); the most
        // recent package lines sit above it, in order.
        assertEquals("", visibleRowText(ROWS - 2))
        assertEquals("Unpacking package-${PACKAGE_LINES - 1} ...", visibleRowText(ROWS - 3))
        assertEquals("Unpacking package-${PACKAGE_LINES - 2} ...", visibleRowText(ROWS - 4))
    }

    @Test
    fun `bar redraws never leak into the scrolled-out history`() {
        setUpAptScrollArea()

        repeat(PACKAGE_LINES) { lineNumber ->
            feed("Unpacking package-$lineNumber ...\r\n")
            drawProgressBar(percent = lineNumber)
        }

        val history = scrollbackText()
        assertTrue("expected lines to have scrolled into history", history.isNotEmpty())
        assertTrue(
            "history must hold only package lines, got: $history",
            history.all { it.startsWith("Unpacking package-") },
        )
        assertFalse(history.any { it.contains("Progress:") })
    }

    @Test
    fun `the fetch percent line overwrites in place instead of stacking`() {
        setUpAptScrollArea()

        // During downloads apt redraws the reserved row far more often than
        // it prints a new "Get:" line.
        feed("Get:1 https://deb.debian.org trixie main arm64 tool [1 kB]\r\n")
        repeat(30) { pulse -> drawProgressBar(percent = pulse) }
        feed("Get:2 https://deb.debian.org trixie main arm64 other [1 kB]\r\n")

        val allRows = (0 until ROWS).map(::visibleRowText)
        assertEquals(
            "the percent line must exist exactly once, on the reserved row",
            1,
            allRows.count { it.contains("Progress:") },
        )
        assertEquals("Progress: [ 29%]", visibleRowText(ROWS - 1))
    }

    @Test
    fun `resetting the region at the end releases the reserved row`() {
        setUpAptScrollArea()
        feed("Unpacking one ...\r\n")
        drawProgressBar(percent = 50)

        // apt's teardown: full-screen region again, then it clears the bar.
        feed("7[0;${ROWS}r8")
        feed("7[$ROWS;0f[K8")

        assertEquals("", visibleRowText(ROWS - 1))
        // The region is genuinely gone: a newline on the last row now
        // scrolls the whole screen, not a sub-region.
        feed("[$ROWS;1H")
        feed("tail line\r\n")
        assertEquals("tail line", visibleRowText(ROWS - 2))
    }

    @Test
    fun `a region requested beyond the screen clamps to the full screen`() {
        // A guest whose stty size is stale asks for more rows than exist;
        // the region must clamp instead of corrupting state. (The bar can
        // then scroll with the text, which is exactly why the guest images
        // re-sync their size before every prompt.)
        feed("[0;${ROWS + 16}r")
        feed("[${ROWS + 17};0f")
        feed("X")

        assertEquals("X", visibleRowText(ROWS - 1))
    }

    private companion object {
        const val COLUMNS = 80
        const val ROWS = 24
        const val PACKAGE_LINES = 40
    }
}
