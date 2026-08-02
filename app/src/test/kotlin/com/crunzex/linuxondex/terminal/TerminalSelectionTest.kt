package com.crunzex.linuxondex.terminal

import com.crunzex.linuxondex.ui.terminal.CtrlCOutcome
import com.crunzex.linuxondex.ui.terminal.CtrlCRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the pure selection extraction and the absolute-coordinate
 * mapping in [TerminalScreenBuffer] — the logic behind iTerm-style
 * mouse/touch copy — plus the [CtrlCRule] that decides whether Ctrl+C copies
 * that selection or interrupts the guest. Everything here is framework-free:
 * a selection is two absolute cell coordinates and
 * [TerminalScreenBuffer.textInSelection] turns them into the exact string the
 * clipboard should receive.
 */
class TerminalSelectionTest {

    private fun buffer(columns: Int = 10, rows: Int = 4) =
        TerminalScreenBuffer(columns, rows)

    private fun TerminalScreenBuffer.type(text: String) {
        for (character in text) writeChar(character)
    }

    /** Types [text] then moves to the start of the next line, like Enter. */
    private fun TerminalScreenBuffer.typeLine(text: String) {
        type(text)
        carriageReturn()
        lineFeed()
    }

    // ---- Single-line selection ---------------------------------------------

    @Test
    fun `single line selection is the inclusive substring`() {
        val screen = buffer()
        screen.type("hello")
        // Columns 1..3 inclusive of "hello" → "ell".
        assertEquals("ell", screen.textInSelection(0, 1, 0, 3))
    }

    @Test
    fun `single cell selection is one character`() {
        val screen = buffer()
        screen.type("hello")
        assertEquals("l", screen.textInSelection(0, 2, 0, 2))
    }

    @Test
    fun `single line selection right-trims trailing blanks`() {
        val screen = buffer(columns = 10)
        screen.type("hi")
        // Selecting the whole 10-column row must not copy the blank padding.
        assertEquals("hi", screen.textInSelection(0, 0, 0, 9))
    }

    // ---- Multi-line selection ----------------------------------------------

    @Test
    fun `multi line selection joins first middle and last spans`() {
        val screen = buffer(columns = 10, rows = 4)
        screen.typeLine("first")
        screen.typeLine("middle")
        screen.type("last")
        // First row from column 2, whole middle row, last row through column 2.
        assertEquals(
            "rst\nmiddle\nlas",
            screen.textInSelection(0, 2, 2, 2),
        )
    }

    @Test
    fun `multi line selection right-trims every line including the middle`() {
        val screen = buffer(columns = 10, rows = 4)
        screen.typeLine("ab")
        screen.typeLine("c")
        screen.type("de")
        assertEquals(
            "ab\nc\nde",
            screen.textInSelection(0, 0, 2, 9),
        )
    }

    // ---- Reversed endpoints -------------------------------------------------

    @Test
    fun `reversed single line endpoints normalize left to right`() {
        val screen = buffer()
        screen.type("abcdef")
        // Dragged right-to-left: columns 4 down to 1 still yields "bcde".
        assertEquals("bcde", screen.textInSelection(0, 4, 0, 1))
        assertEquals(
            screen.textInSelection(0, 1, 0, 4),
            screen.textInSelection(0, 4, 0, 1),
        )
    }

    @Test
    fun `reversed multi line endpoints normalize top to bottom`() {
        val screen = buffer(columns = 10, rows = 4)
        screen.typeLine("first")
        screen.typeLine("middle")
        screen.type("last")
        // Dragged bottom-right to top-left must match the forward selection.
        assertEquals(
            screen.textInSelection(0, 1, 2, 4),
            screen.textInSelection(2, 4, 0, 1),
        )
        assertEquals("irst\nmiddle\nlast", screen.textInSelection(2, 4, 0, 1))
    }

    // ---- Spanning scrollback into the live grid ----------------------------

    @Test
    fun `selection spans scrollback history into the live grid`() {
        val screen = buffer(columns = 5, rows = 2)
        screen.typeLine("aa") // scrolls nothing yet
        screen.typeLine("bb") // pushes "aa" into scrollback
        screen.type("cc")
        // scrollback = [aa], live grid = [bb, cc] → absolute 0=aa, 1=bb, 2=cc.
        assertEquals(1, screen.scrollbackSize)
        assertEquals("aa\nbb\ncc", screen.textInSelection(0, 0, 2, 1))
    }

    @Test
    fun `selection wholly inside scrollback reads history rows`() {
        val screen = buffer(columns = 5, rows = 2)
        screen.typeLine("aa")
        screen.typeLine("bb")
        screen.typeLine("cc")
        screen.type("dd")
        // scrollback = [aa, bb], live grid = [cc, dd].
        assertEquals(2, screen.scrollbackSize)
        assertEquals("aa\nbb", screen.textInSelection(0, 0, 1, 1))
    }

    // ---- Absolute-coordinate mapping ---------------------------------------

    @Test
    fun `viewport rows map to stable absolute rows across scrolling`() {
        val screen = buffer(columns = 5, rows = 2)
        screen.typeLine("aa")
        screen.typeLine("bb")
        screen.type("cc")
        // scrollback = [aa], live grid = [bb, cc], scrollbackSize = 1.

        // Live (offset 0): the two visible rows are the live grid.
        assertEquals(1, screen.absoluteRowForViewport(0, 0))
        assertEquals(2, screen.absoluteRowForViewport(1, 0))

        // Scrolled one row into history: top line is now the history row.
        assertEquals(0, screen.absoluteRowForViewport(0, 1))
        assertEquals(1, screen.absoluteRowForViewport(1, 1))
    }

    @Test
    fun `viewportRowForAbsolute inverts absoluteRowForViewport`() {
        val screen = buffer(columns = 5, rows = 2)
        screen.typeLine("aa")
        screen.typeLine("bb")
        screen.type("cc")

        for (offset in 0..1) {
            for (viewRow in 0 until screen.rows) {
                val absolute = screen.absoluteRowForViewport(viewRow, offset)
                assertEquals(viewRow, screen.viewportRowForAbsolute(absolute, offset))
            }
        }
    }

    @Test
    fun `rowForAbsolute agrees with the rendered viewport rows`() {
        val screen = buffer(columns = 5, rows = 2)
        screen.typeLine("aa")
        screen.typeLine("bb")
        screen.type("cc")
        assertEquals("aa", screen.rowForAbsolute(0).trimmedText())
        assertEquals("bb", screen.rowForAbsolute(1).trimmedText())
        assertEquals("cc", screen.rowForAbsolute(2).trimmedText())
    }

    @Test
    fun `rowForAbsolute clamps out-of-range indices to the edge rows`() {
        val screen = buffer(columns = 5, rows = 2)
        screen.typeLine("aa")
        screen.typeLine("bb")
        screen.type("cc")
        // Absolute range is 0..2; anything outside clamps to the ends.
        assertEquals("aa", screen.rowForAbsolute(-10).trimmedText())
        assertEquals("cc", screen.rowForAbsolute(999).trimmedText())
    }

    // ---- Double-click word ranges -------------------------------------------
    //
    // wordRangeAt is what a double-click asks for: the inclusive columns of the
    // run of non-blank characters around the clicked cell, or null when there
    // is nothing there worth selecting.

    @Test
    fun `word range covers the word a click lands inside`() {
        val screen = buffer(columns = 20)
        screen.type("cat file.txt now")
        // "file.txt" spans columns 4..11; column 6 is the 'l' inside it.
        assertEquals(4..11, screen.wordRangeAt(0, 6))
    }

    @Test
    fun `word range feeds textInSelection the exact word`() {
        val screen = buffer(columns = 20)
        screen.type("cat file.txt now")
        val word = screen.wordRangeAt(0, 6)!!
        assertEquals("file.txt", screen.textInSelection(0, word.first, 0, word.last))
    }

    @Test
    fun `word at the start of the row stops at the left edge`() {
        val screen = buffer(columns = 20)
        screen.type("hello world")
        assertEquals(0..4, screen.wordRangeAt(0, 1))
    }

    @Test
    fun `word at the end of the row stops at the right edge`() {
        val screen = buffer(columns = 11)
        screen.type("hello world") // fills the row exactly, no trailing blank
        assertEquals(6..10, screen.wordRangeAt(0, 8))
    }

    @Test
    fun `word filling the whole row spans every column`() {
        val screen = buffer(columns = 5)
        screen.type("abcde")
        assertEquals(0..4, screen.wordRangeAt(0, 2))
    }

    @Test
    fun `clicking the first or the last character selects the whole word`() {
        val screen = buffer(columns = 20)
        screen.type("ab cd ef")
        // "cd" is columns 3..4 — both of its cells select all of it.
        assertEquals(3..4, screen.wordRangeAt(0, 3))
        assertEquals(3..4, screen.wordRangeAt(0, 4))
    }

    @Test
    fun `clicking a space selects nothing`() {
        val screen = buffer(columns = 20)
        screen.type("ab cd")
        assertNull(screen.wordRangeAt(0, 2))
    }

    @Test
    fun `a row of only blanks selects nothing at any column`() {
        val screen = buffer(columns = 10)
        screen.type("   ")
        for (column in 0 until 10) assertNull(screen.wordRangeAt(0, column))
    }

    @Test
    fun `punctuation stays inside the word`() {
        val screen = buffer(columns = 20)
        screen.type("cd /usr/local/bin")
        // Only spaces break a word, so the whole path comes back as one.
        val word = screen.wordRangeAt(0, 8)!!
        assertEquals(3..16, word)
        assertEquals("/usr/local/bin", screen.textInSelection(0, word.first, 0, word.last))
    }

    @Test
    fun `word range reads rows that scrolled into history`() {
        val screen = buffer(columns = 8, rows = 2)
        screen.typeLine("ab cd")
        screen.typeLine("ef")
        screen.type("gh")
        // scrollback = [ab cd], live grid = [ef, gh] → absolute 0 is history.
        assertEquals(1, screen.scrollbackSize)
        val word = screen.wordRangeAt(0, 4)!!
        assertEquals(3..4, word)
        assertEquals("cd", screen.textInSelection(0, word.first, 0, word.last))
    }

    @Test
    fun `out-of-range columns clamp instead of throwing`() {
        val screen = buffer(columns = 10)
        screen.type("hi")
        // Left of the row clamps onto column 0, which is inside "hi".
        assertEquals(0..1, screen.wordRangeAt(0, -5))
        // Right of the row clamps onto blank padding: nothing to select.
        assertNull(screen.wordRangeAt(0, 999))
    }

    @Test
    fun `out-of-range rows clamp to the edge rows`() {
        val screen = buffer(columns = 8, rows = 2)
        screen.typeLine("ab cd")
        screen.typeLine("ef")
        screen.type("gh")
        // Absolute range is 0..2: history "ab cd" and the live "ef"/"gh".
        assertEquals(0..1, screen.wordRangeAt(-10, 0))
        assertEquals(0..1, screen.wordRangeAt(999, 0))
    }

    // ---- Ctrl+C: copy or interrupt ------------------------------------------
    //
    // Selecting no longer copies, so Ctrl+C carries both jobs: it copies when
    // something is highlighted and interrupts otherwise. The second half is the
    // one that must never regress — a terminal that cannot send SIGINT is a
    // terminal that cannot stop a runaway command.

    @Test
    fun `ctrl+C with a visible selection copies it`() {
        assertEquals(
            CtrlCOutcome.COPY_SELECTION,
            CtrlCRule.outcomeFor(shiftPressed = false, hasVisibleSelection = true),
        )
    }

    @Test
    fun `ctrl+C without a selection still interrupts the guest`() {
        assertEquals(
            CtrlCOutcome.SEND_INTERRUPT,
            CtrlCRule.outcomeFor(shiftPressed = false, hasVisibleSelection = false),
        )
    }

    @Test
    fun `ctrl+shift+C copies whatever is selected`() {
        assertEquals(
            CtrlCOutcome.COPY_SELECTION,
            CtrlCRule.outcomeFor(shiftPressed = true, hasVisibleSelection = true),
        )
    }

    @Test
    fun `ctrl+shift+C never interrupts even with nothing selected`() {
        // The explicit copy chord is always consumed: reaching for it is never
        // a request to kill the running command.
        assertEquals(
            CtrlCOutcome.COPY_SELECTION,
            CtrlCRule.outcomeFor(shiftPressed = true, hasVisibleSelection = false),
        )
    }

    @Test
    fun `interrupt is the outcome only for plain ctrl+C with no selection`() {
        // The whole truth table in one place: exactly one cell interrupts.
        for (shift in listOf(false, true)) {
            for (selection in listOf(false, true)) {
                val expected =
                    if (!shift && !selection) CtrlCOutcome.SEND_INTERRUPT
                    else CtrlCOutcome.COPY_SELECTION
                assertEquals(
                    "shift=$shift selection=$selection",
                    expected,
                    CtrlCRule.outcomeFor(shift, selection),
                )
            }
        }
    }

    // ---- Ctrl+C held down ---------------------------------------------------

    @Test
    fun `a fresh press decides from scratch`() {
        for (shift in listOf(false, true)) {
            for (selection in listOf(false, true)) {
                assertEquals(
                    CtrlCRule.outcomeFor(shift, selection),
                    CtrlCRule.outcomeWhileHeld(
                        outcomeSoFar = null,
                        shiftPressed = shift,
                        hasVisibleSelection = selection,
                    ),
                )
            }
        }
    }

    @Test
    fun `a repeat keeps copying after the copy cleared the selection`() {
        // The regression this guards: press copies and clears the highlight,
        // then the auto-repeat sees no selection and would fire SIGINT at the
        // command the user was merely copying from.
        assertEquals(
            CtrlCOutcome.COPY_SELECTION,
            CtrlCRule.outcomeWhileHeld(
                outcomeSoFar = CtrlCOutcome.COPY_SELECTION,
                shiftPressed = false,
                hasVisibleSelection = false,
            ),
        )
    }

    @Test
    fun `a repeat keeps interrupting even if a selection appears mid-hold`() {
        assertEquals(
            CtrlCOutcome.SEND_INTERRUPT,
            CtrlCRule.outcomeWhileHeld(
                outcomeSoFar = CtrlCOutcome.SEND_INTERRUPT,
                shiftPressed = false,
                hasVisibleSelection = true,
            ),
        )
    }

    @Test
    fun `a committed hold ignores every later modifier state`() {
        for (committed in CtrlCOutcome.entries) {
            for (shift in listOf(false, true)) {
                for (selection in listOf(false, true)) {
                    assertEquals(
                        committed,
                        CtrlCRule.outcomeWhileHeld(committed, shift, selection),
                    )
                }
            }
        }
    }

    @Test
    fun `copying then pressing again interrupts`() {
        // A whole user sequence: select, hold Ctrl+C (copy, then repeats), let
        // go, press once more with the highlight now gone — that second press
        // is a fresh decision and reaches the guest as SIGINT.
        var hold: CtrlCOutcome? = null
        var hasSelection = true

        hold = CtrlCRule.outcomeWhileHeld(hold, shiftPressed = false, hasVisibleSelection = hasSelection)
        assertEquals(CtrlCOutcome.COPY_SELECTION, hold)
        hasSelection = false // the copy dropped the highlight

        hold = CtrlCRule.outcomeWhileHeld(hold, shiftPressed = false, hasVisibleSelection = hasSelection)
        assertEquals(CtrlCOutcome.COPY_SELECTION, hold)

        hold = null // key released, so the next press decides afresh
        hold = CtrlCRule.outcomeWhileHeld(hold, shiftPressed = false, hasVisibleSelection = hasSelection)
        assertEquals(CtrlCOutcome.SEND_INTERRUPT, hold)
    }
}
