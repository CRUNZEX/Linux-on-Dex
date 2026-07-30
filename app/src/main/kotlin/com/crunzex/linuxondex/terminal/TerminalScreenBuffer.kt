package com.crunzex.linuxondex.terminal

/** Cell attribute bits stored per character cell. */
object TerminalCellFlags {
    const val BOLD = 1
    const val FAINT = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val INVERSE = 16
    const val INVISIBLE = 32
    const val STRIKETHROUGH = 64
}

/** The attributes new characters are written with (set by SGR sequences). */
class TerminalPen {
    var foreground: Int = TerminalColors.DEFAULT_FOREGROUND
    var background: Int = TerminalColors.DEFAULT_BACKGROUND
    var flags: Int = 0

    fun reset() {
        foreground = TerminalColors.DEFAULT_FOREGROUND
        background = TerminalColors.DEFAULT_BACKGROUND
        flags = 0
    }

    fun copyFrom(other: TerminalPen) {
        foreground = other.foreground
        background = other.background
        flags = other.flags
    }
}

/**
 * One row of character cells. Parallel primitive arrays instead of a cell
 * object keep a full screen plus scrollback to a handful of small
 * allocations and make row copies plain array copies.
 */
class TerminalRow(val columns: Int) {
    val chars = CharArray(columns) { ' ' }
    val foreground = IntArray(columns) { TerminalColors.DEFAULT_FOREGROUND }
    val background = IntArray(columns) { TerminalColors.DEFAULT_BACKGROUND }
    val flags = ByteArray(columns)

    fun set(column: Int, character: Char, pen: TerminalPen) {
        chars[column] = character
        foreground[column] = pen.foreground
        background[column] = pen.background
        flags[column] = pen.flags.toByte()
    }

    /** Blanks [from, until) using the pen's background (BCE, like xterm). */
    fun fill(from: Int, until: Int, pen: TerminalPen) {
        for (column in from until until) {
            chars[column] = ' '
            foreground[column] = TerminalColors.DEFAULT_FOREGROUND
            background[column] = pen.background
            flags[column] = 0
        }
    }

    /** Opens [count] blank cells at [from], dropping cells off the end. */
    fun shiftRight(from: Int, count: Int, pen: TerminalPen) {
        val moved = columns - from - count
        if (moved > 0) {
            chars.copyInto(chars, from + count, from, from + moved)
            foreground.copyInto(foreground, from + count, from, from + moved)
            background.copyInto(background, from + count, from, from + moved)
            flags.copyInto(flags, from + count, from, from + moved)
        }
        fill(from, minOf(from + count, columns), pen)
    }

    /** Deletes [count] cells at [from], pulling the tail left. */
    fun shiftLeft(from: Int, count: Int, pen: TerminalPen) {
        val moved = columns - from - count
        if (moved > 0) {
            chars.copyInto(chars, from, from + count, from + count + moved)
            foreground.copyInto(foreground, from, from + count, from + count + moved)
            background.copyInto(background, from, from + count, from + count + moved)
            flags.copyInto(flags, from, from + count, from + count + moved)
        }
        fill(maxOf(from, columns - count), columns, pen)
    }

    fun copyFrom(other: TerminalRow) {
        val overlap = minOf(columns, other.columns)
        other.chars.copyInto(chars, 0, 0, overlap)
        other.foreground.copyInto(foreground, 0, 0, overlap)
        other.background.copyInto(background, 0, 0, overlap)
        other.flags.copyInto(flags, 0, 0, overlap)
    }

    /** The row's text without trailing blanks — used by copy-screen. */
    fun trimmedText(): String {
        var end = columns
        while (end > 0 && chars[end - 1] == ' ') end--
        return String(chars, 0, end)
    }
}

/**
 * The terminal's character grid: a primary screen with scrollback, an
 * alternate screen (vim, htop, less), a cursor, a scroll region, and the
 * editing operations escape sequences map onto.
 *
 * Row 0 is the top visible line; columns and rows are 0-based here (the
 * parser converts from 1-based sequence parameters).
 *
 * Thread safety: every public method takes the object monitor. The renderer
 * must hold the same monitor — `synchronized(buffer) { … }` — while it reads
 * rows, and can then use rows directly without copying.
 */
class TerminalScreenBuffer(
    columns: Int,
    rows: Int,
    private val scrollbackLimit: Int = DEFAULT_SCROLLBACK_ROWS,
) {
    var columns = columns.coerceAtLeast(MIN_COLUMNS); private set
    var rows = rows.coerceAtLeast(MIN_ROWS); private set

    val pen = TerminalPen()

    var cursorRow = 0; private set
    var cursorColumn = 0; private set
    var cursorVisible = true

    // Modes toggled by DECSET/DECRST — the parser writes, views read.
    var autowrap = true
    var insertMode = false
    var originMode = false
    var applicationCursorKeys = false
    var bracketedPaste = false

    /** Bumped on every visible change; views compare and redraw. */
    @Volatile
    var revision = 0L; private set

    private var primaryGrid = Array(this.rows) { TerminalRow(this.columns) }
    private var alternateGrid = Array(this.rows) { TerminalRow(this.columns) }
    private var grid = primaryGrid
    var isAlternateScreen = false; private set

    private val scrollback = ArrayDeque<TerminalRow>()

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    /** DEC autowrap: the cursor "hangs" after the last column until the
     * next printable character, which first wraps to a new line. */
    private var wrapPending = false

    private var tabStops = defaultTabStops(this.columns)

    private val savedCursor = SavedCursor()
    private val savedAlternateCursor = SavedCursor()

    private class SavedCursor {
        var row = 0
        var column = 0
        val pen = TerminalPen()
    }

    // ---- Writing -----------------------------------------------------------

    @Synchronized
    fun writeChar(character: Char) {
        if (wrapPending) {
            wrapPending = false
            cursorColumn = 0
            lineFeedLocked()
        }
        val row = grid[cursorRow]
        if (insertMode) row.shiftRight(cursorColumn, 1, pen)
        row.set(cursorColumn, character, pen)
        if (cursorColumn < columns - 1) {
            cursorColumn++
        } else if (autowrap) {
            wrapPending = true
        }
        touched()
    }

    // ---- Cursor ------------------------------------------------------------

    /** Absolute move; [row]/[column] are 0-based, already parser-converted. */
    @Synchronized
    fun moveCursorTo(row: Int, column: Int) {
        val (regionTop, regionBottom) = effectiveOriginBounds()
        cursorRow = (regionTop + row).coerceIn(regionTop, regionBottom)
        cursorColumn = column.coerceIn(0, columns - 1)
        wrapPending = false
        touched()
    }

    @Synchronized
    fun moveCursorBy(deltaRows: Int, deltaColumns: Int) {
        // Relative moves never cross the scroll region boundaries (vim
        // depends on this when redrawing its status line).
        val top = if (cursorRow >= scrollTop) scrollTop else 0
        val bottom = if (cursorRow <= scrollBottom) scrollBottom else rows - 1
        cursorRow = (cursorRow + deltaRows).coerceIn(top, bottom)
        cursorColumn = (cursorColumn + deltaColumns).coerceIn(0, columns - 1)
        wrapPending = false
        touched()
    }

    @Synchronized
    fun moveCursorToColumn(column: Int) {
        cursorColumn = column.coerceIn(0, columns - 1)
        wrapPending = false
        touched()
    }

    @Synchronized
    fun moveCursorToRow(row: Int) {
        val (regionTop, regionBottom) = effectiveOriginBounds()
        cursorRow = (regionTop + row).coerceIn(regionTop, regionBottom)
        wrapPending = false
        touched()
    }

    @Synchronized
    fun carriageReturn() {
        cursorColumn = 0
        wrapPending = false
        touched()
    }

    @Synchronized
    fun lineFeed() {
        lineFeedLocked()
        touched()
    }

    @Synchronized
    fun reverseLineFeed() {
        if (cursorRow == scrollTop) scrollRegionDown(1) else cursorRow--
        wrapPending = false
        touched()
    }

    @Synchronized
    fun backspace() {
        if (cursorColumn > 0) cursorColumn--
        wrapPending = false
        touched()
    }

    @Synchronized
    fun horizontalTab() {
        var column = cursorColumn + 1
        while (column < columns - 1 && !tabStops[column]) column++
        cursorColumn = column.coerceAtMost(columns - 1)
        wrapPending = false
        touched()
    }

    @Synchronized
    fun backwardTab() {
        var column = cursorColumn - 1
        while (column > 0 && !tabStops[column]) column--
        cursorColumn = column.coerceAtLeast(0)
        wrapPending = false
        touched()
    }

    @Synchronized
    fun setTabStopAtCursor() {
        tabStops[cursorColumn] = true
    }

    /** TBC: [mode] 0 clears at cursor, 3 clears all. */
    @Synchronized
    fun clearTabStops(mode: Int) {
        when (mode) {
            0 -> tabStops[cursorColumn] = false
            3 -> tabStops.fill(false)
        }
    }

    @Synchronized
    fun saveCursor() {
        val slot = if (isAlternateScreen) savedAlternateCursor else savedCursor
        slot.row = cursorRow
        slot.column = cursorColumn
        slot.pen.copyFrom(pen)
    }

    @Synchronized
    fun restoreCursor() {
        val slot = if (isAlternateScreen) savedAlternateCursor else savedCursor
        cursorRow = slot.row.coerceIn(0, rows - 1)
        cursorColumn = slot.column.coerceIn(0, columns - 1)
        pen.copyFrom(slot.pen)
        wrapPending = false
        touched()
    }

    // ---- Erasing and editing ----------------------------------------------

    /** ED: [mode] 0 = cursor→end, 1 = start→cursor, 2 = all, 3 = all+history. */
    @Synchronized
    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                grid[cursorRow].fill(cursorColumn, columns, pen)
                for (row in cursorRow + 1 until rows) grid[row].fill(0, columns, pen)
            }
            1 -> {
                for (row in 0 until cursorRow) grid[row].fill(0, columns, pen)
                grid[cursorRow].fill(0, cursorColumn + 1, pen)
            }
            2 -> for (row in 0 until rows) grid[row].fill(0, columns, pen)
            3 -> {
                for (row in 0 until rows) grid[row].fill(0, columns, pen)
                scrollback.clear()
            }
        }
        wrapPending = false
        touched()
    }

    /** EL: [mode] 0 = cursor→end, 1 = start→cursor, 2 = whole line. */
    @Synchronized
    fun eraseInLine(mode: Int) {
        val row = grid[cursorRow]
        when (mode) {
            0 -> row.fill(cursorColumn, columns, pen)
            1 -> row.fill(0, cursorColumn + 1, pen)
            2 -> row.fill(0, columns, pen)
        }
        wrapPending = false
        touched()
    }

    /** ECH: blanks [count] cells from the cursor without moving anything. */
    @Synchronized
    fun eraseChars(count: Int) {
        grid[cursorRow].fill(
            cursorColumn,
            (cursorColumn + count.coerceAtLeast(1)).coerceAtMost(columns),
            pen,
        )
        touched()
    }

    @Synchronized
    fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        shiftRowsDown(cursorRow, scrollBottom, count.coerceAtLeast(1))
        cursorColumn = 0
        wrapPending = false
        touched()
    }

    @Synchronized
    fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        shiftRowsUp(cursorRow, scrollBottom, count.coerceAtLeast(1))
        cursorColumn = 0
        wrapPending = false
        touched()
    }

    @Synchronized
    fun insertChars(count: Int) {
        grid[cursorRow].shiftRight(cursorColumn, count.coerceIn(1, columns - cursorColumn), pen)
        touched()
    }

    @Synchronized
    fun deleteChars(count: Int) {
        grid[cursorRow].shiftLeft(cursorColumn, count.coerceIn(1, columns - cursorColumn), pen)
        touched()
    }

    // ---- Scrolling ---------------------------------------------------------

    /** DECSTBM with 0-based inclusive bounds. Homes the cursor, per spec. */
    @Synchronized
    fun setScrollRegion(top: Int, bottom: Int) {
        val safeTop = top.coerceIn(0, rows - 2)
        val safeBottom = bottom.coerceIn(safeTop + 1, rows - 1)
        scrollTop = safeTop
        scrollBottom = safeBottom
        cursorRow = if (originMode) scrollTop else 0
        cursorColumn = 0
        wrapPending = false
        touched()
    }

    @Synchronized
    fun resetScrollRegion() {
        scrollTop = 0
        scrollBottom = rows - 1
        touched()
    }

    @Synchronized
    fun scrollUp(lines: Int) {
        scrollRegionUp(lines.coerceAtLeast(1))
        touched()
    }

    @Synchronized
    fun scrollDown(lines: Int) {
        scrollRegionDown(lines.coerceAtLeast(1))
        touched()
    }

    // ---- Alternate screen --------------------------------------------------

    /** Enter the alternate screen (1049 also clears it), as vim/htop do. */
    @Synchronized
    fun switchToAlternateScreen(clear: Boolean) {
        if (!isAlternateScreen) {
            grid = alternateGrid
            isAlternateScreen = true
        }
        if (clear) for (row in grid) row.fill(0, columns, pen)
        resetScrollRegionLocked()
        cursorRow = 0
        cursorColumn = 0
        wrapPending = false
        touched()
    }

    @Synchronized
    fun switchToPrimaryScreen() {
        if (isAlternateScreen) {
            grid = primaryGrid
            isAlternateScreen = false
            resetScrollRegionLocked()
            wrapPending = false
            touched()
        }
    }

    // ---- Reset and resize --------------------------------------------------

    /** RIS: back to power-on state, keeping the geometry. */
    @Synchronized
    fun fullReset() {
        pen.reset()
        for (row in primaryGrid) row.fill(0, columns, pen)
        for (row in alternateGrid) row.fill(0, columns, pen)
        grid = primaryGrid
        isAlternateScreen = false
        scrollback.clear()
        cursorRow = 0
        cursorColumn = 0
        wrapPending = false
        autowrap = true
        insertMode = false
        originMode = false
        applicationCursorKeys = false
        bracketedPaste = false
        cursorVisible = true
        tabStops = defaultTabStops(columns)
        resetScrollRegionLocked()
        touched()
    }

    /**
     * Changes the geometry, keeping the top-left of both screens. A shell
     * redraw (or the guest-side `fix_console`) restores anything clipped.
     */
    @Synchronized
    fun resize(newColumns: Int, newRows: Int) {
        val targetColumns = newColumns.coerceAtLeast(MIN_COLUMNS)
        val targetRows = newRows.coerceAtLeast(MIN_ROWS)
        if (targetColumns == columns && targetRows == rows) return

        primaryGrid = resizeGrid(primaryGrid, targetColumns, targetRows)
        alternateGrid = resizeGrid(alternateGrid, targetColumns, targetRows)
        grid = if (isAlternateScreen) alternateGrid else primaryGrid

        columns = targetColumns
        rows = targetRows
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorColumn = cursorColumn.coerceIn(0, columns - 1)
        tabStops = defaultTabStops(columns)
        wrapPending = false
        resetScrollRegionLocked()
        touched()
    }

    // ---- Render access -----------------------------------------------------

    /** Rows of history available to scroll back through. */
    val scrollbackSize: Int
        @Synchronized get() = scrollback.size

    /**
     * The row shown at viewport line [viewRow] when scrolled [scrollbackOffset]
     * rows into history (0 = live). Hold the buffer monitor while using it.
     */
    @Synchronized
    fun rowForViewport(viewRow: Int, scrollbackOffset: Int): TerminalRow {
        if (isAlternateScreen || scrollbackOffset <= 0) {
            return grid[viewRow.coerceIn(0, rows - 1)]
        }
        val offset = scrollbackOffset.coerceAtMost(scrollback.size)
        val historyIndex = scrollback.size - offset + viewRow
        return if (historyIndex < scrollback.size) {
            scrollback[historyIndex]
        } else {
            grid[(historyIndex - scrollback.size).coerceIn(0, rows - 1)]
        }
    }

    /** The visible screen as text, for copy-screen. */
    @Synchronized
    fun screenText(): String =
        (0 until rows).joinToString("\n") { grid[it].trimmedText() }

    // ---- Selection ---------------------------------------------------------
    //
    // A selection has to survive new output scrolling the screen, so it is
    // anchored in *absolute* row coordinates rather than viewport rows:
    //
    //   absolute row 0 .. scrollbackSize-1  → history (oldest first)
    //   absolute row scrollbackSize ..       → the live grid, top row first
    //
    // A live row keeps its absolute index when it scrolls into history: the
    // row moves from grid[0] to scrollback[N] while scrollbackSize grows from
    // N to N+1, so absolute row N still names the same text. (The only drift
    // is when the oldest history is trimmed past [scrollbackLimit], which
    // shifts every index down by one — rare, and only at the far end of
    // history.) The view stores two absolute cell coordinates and asks the
    // buffer to extract the text between them.

    /**
     * The absolute row shown at viewport line [viewRow] when the view is
     * scrolled [scrollbackOffset] rows into history (0 = live at the bottom).
     * Mirrors [rowForViewport] so a tap the user sees and the row the
     * extractor reads name the same line.
     */
    @Synchronized
    fun absoluteRowForViewport(viewRow: Int, scrollbackOffset: Int): Int {
        if (isAlternateScreen || scrollbackOffset <= 0) {
            return scrollback.size + viewRow
        }
        val offset = scrollbackOffset.coerceAtMost(scrollback.size)
        return scrollback.size - offset + viewRow
    }

    /**
     * The inverse of [absoluteRowForViewport]: where [absoluteRow] currently
     * sits in the viewport. The result may fall outside `0 until rows` when
     * the row has scrolled off-screen — callers test visibility themselves.
     */
    @Synchronized
    fun viewportRowForAbsolute(absoluteRow: Int, scrollbackOffset: Int): Int {
        if (isAlternateScreen || scrollbackOffset <= 0) {
            return absoluteRow - scrollback.size
        }
        val offset = scrollbackOffset.coerceAtMost(scrollback.size)
        return absoluteRow - scrollback.size + offset
    }

    /**
     * The row at [absoluteRow] (history or live), clamped to the valid range.
     * Lets the renderer highlight and the copy extractor read the exact same
     * cells the [absoluteRowForViewport] mapping points at.
     */
    @Synchronized
    fun rowForAbsolute(absoluteRow: Int): TerminalRow = rowForAbsoluteLocked(absoluteRow)

    /**
     * The text between two absolute cell coordinates, normalised the way a
     * desktop terminal copies it:
     *  - the two endpoints are ordered, so the caller may drag in any
     *    direction (right-to-left, bottom-to-top);
     *  - a single-row selection is the substring between the columns;
     *  - a multi-row selection is the first row from [startColumn] to its end,
     *    every middle row in full, and the last row up to [endColumn];
     *  - each line is right-trimmed of trailing blanks, because terminal cells
     *    are blank-padded and copying that padding is never what the user meant.
     *
     * Both columns are inclusive of the cell they name. Out-of-range rows and
     * columns are clamped, so a drag past the edges simply selects to the edge.
     */
    @Synchronized
    fun textInSelection(
        startAbsoluteRow: Int,
        startColumn: Int,
        endAbsoluteRow: Int,
        endColumn: Int,
    ): String {
        val forward = startAbsoluteRow < endAbsoluteRow ||
            (startAbsoluteRow == endAbsoluteRow && startColumn <= endColumn)
        val topRow = if (forward) startAbsoluteRow else endAbsoluteRow
        val topColumn = if (forward) startColumn else endColumn
        val bottomRow = if (forward) endAbsoluteRow else startAbsoluteRow
        val bottomColumn = if (forward) endColumn else startColumn

        if (topRow == bottomRow) {
            val row = rowForAbsoluteLocked(topRow)
            return trimTrailingBlanks(rowSegment(row, topColumn, bottomColumn))
        }

        val builder = StringBuilder()
        val first = rowForAbsoluteLocked(topRow)
        builder.append(trimTrailingBlanks(rowSegment(first, topColumn, first.columns - 1)))
        for (absoluteRow in topRow + 1 until bottomRow) {
            val row = rowForAbsoluteLocked(absoluteRow)
            builder.append('\n')
            builder.append(trimTrailingBlanks(rowSegment(row, 0, row.columns - 1)))
        }
        val last = rowForAbsoluteLocked(bottomRow)
        builder.append('\n')
        builder.append(trimTrailingBlanks(rowSegment(last, 0, bottomColumn)))
        return builder.toString()
    }

    /**
     * The inclusive column range of the "word" at ([absoluteRow], [column]),
     * or null when that cell has no word to select.
     *
     * This is iTerm's default double-click meaning of a word: a run of
     * non-blank characters bounded by blanks or by the edges of the row.
     * Punctuation stays inside the run, so double-clicking `/usr/local/bin`
     * or `main.kt:42` yields the whole path — that is what the user is
     * reaching for, and any narrower rule would break it into fragments.
     *
     * Landing on a blank cell returns null rather than the run of blanks:
     * terminal rows are blank-padded, so selecting padding is never useful.
     *
     * The row and the column are clamped into range, so a click past the end
     * of the grid answers about the nearest real cell instead of throwing —
     * the view maps pixels to cells and must not have to pre-validate them.
     */
    @Synchronized
    fun wordRangeAt(absoluteRow: Int, column: Int): IntRange? {
        val row = rowForAbsoluteLocked(absoluteRow)
        if (row.columns <= 0) return null
        val origin = column.coerceIn(0, row.columns - 1)
        if (!isWordCharacter(row.chars[origin])) return null

        var first = origin
        while (first > 0 && isWordCharacter(row.chars[first - 1])) first--
        var last = origin
        while (last < row.columns - 1 && isWordCharacter(row.chars[last + 1])) last++
        return first..last
    }

    // ---- Internals ---------------------------------------------------------

    /**
     * Whether a cell belongs to a word. Whitespace is the boundary: cells
     * start out as spaces and every erase refills them with spaces, so
     * "not blank" is exactly "carries a glyph the user can see".
     */
    private fun isWordCharacter(character: Char): Boolean = !character.isWhitespace()

    private fun rowForAbsoluteLocked(absoluteRow: Int): TerminalRow {
        val clamped = absoluteRow.coerceIn(0, scrollback.size + rows - 1)
        return if (clamped < scrollback.size) {
            scrollback[clamped]
        } else {
            grid[clamped - scrollback.size]
        }
    }

    /** Columns [fromColumn]..[toColumnInclusive] of [row] as text, clamped. */
    private fun rowSegment(row: TerminalRow, fromColumn: Int, toColumnInclusive: Int): String {
        val from = fromColumn.coerceIn(0, row.columns)
        val toExclusive = (toColumnInclusive + 1).coerceIn(from, row.columns)
        return String(row.chars, from, toExclusive - from)
    }

    private fun trimTrailingBlanks(text: String): String {
        var end = text.length
        while (end > 0 && text[end - 1] == ' ') end--
        return if (end == text.length) text else text.substring(0, end)
    }


    private fun lineFeedLocked() {
        if (cursorRow == scrollBottom) scrollRegionUp(1) else {
            cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
        }
    }

    private fun scrollRegionUp(lines: Int) {
        val count = lines.coerceAtMost(scrollBottom - scrollTop + 1)
        repeat(count) {
            // History only ever receives lines that scroll off the top of
            // the full primary screen — never alternate-screen content.
            if (!isAlternateScreen && scrollTop == 0) {
                pushScrollback(grid[0])
            }
            shiftRowsUp(scrollTop, scrollBottom, 1)
        }
    }

    private fun scrollRegionDown(lines: Int) {
        val count = lines.coerceAtMost(scrollBottom - scrollTop + 1)
        shiftRowsDown(scrollTop, scrollBottom, count)
    }

    /** Moves rows in [top..bottom] up by [count], blanking the bottom. */
    private fun shiftRowsUp(top: Int, bottom: Int, count: Int) {
        val distance = count.coerceAtMost(bottom - top + 1)
        for (row in top..bottom - distance) {
            grid[row].copyFrom(grid[row + distance])
        }
        for (row in bottom - distance + 1..bottom) grid[row].fill(0, columns, pen)
    }

    /** Moves rows in [top..bottom] down by [count], blanking the top. */
    private fun shiftRowsDown(top: Int, bottom: Int, count: Int) {
        val distance = count.coerceAtMost(bottom - top + 1)
        for (row in bottom downTo top + distance) {
            grid[row].copyFrom(grid[row - distance])
        }
        for (row in top until top + distance) grid[row].fill(0, columns, pen)
    }

    private fun pushScrollback(row: TerminalRow) {
        val copy = TerminalRow(columns)
        copy.copyFrom(row)
        scrollback.addLast(copy)
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
    }

    private fun resizeGrid(
        old: Array<TerminalRow>,
        newColumns: Int,
        newRows: Int,
    ): Array<TerminalRow> = Array(newRows) { rowIndex ->
        TerminalRow(newColumns).also { row ->
            if (rowIndex < old.size) row.copyFrom(old[rowIndex])
        }
    }

    private fun effectiveOriginBounds(): Pair<Int, Int> =
        if (originMode) scrollTop to scrollBottom else 0 to rows - 1

    private fun resetScrollRegionLocked() {
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun touched() {
        revision++
    }

    companion object {
        const val MIN_COLUMNS = 2
        const val MIN_ROWS = 2
        const val DEFAULT_SCROLLBACK_ROWS = 2_000

        private fun defaultTabStops(columns: Int) =
            BooleanArray(columns) { it > 0 && it % 8 == 0 }
    }
}
