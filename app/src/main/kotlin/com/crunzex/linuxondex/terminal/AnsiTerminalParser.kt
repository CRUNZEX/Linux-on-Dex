package com.crunzex.linuxondex.terminal

/**
 * Streaming xterm-compatible escape-sequence interpreter. Bytes from the
 * guest go in; cell updates land in a [TerminalScreenBuffer]; the handful of
 * sequences that require an answer (cursor position, device attributes) go
 * back through [respond].
 *
 * Covers what real programs need — colours (16/256/true), cursor
 * addressing, erase/insert/delete, scroll regions, the alternate screen,
 * DEC line drawing, bracketed paste — and consumes what it does not
 * implement, so unknown sequences degrade to "ignored", never to garbage
 * on screen.
 *
 * Not thread-safe by itself: feed it from one reader thread. The screen
 * buffer does its own locking against the renderer.
 */
class AnsiTerminalParser(
    private val screen: TerminalScreenBuffer,
    private val respond: (ByteArray) -> Unit = {},
    private val onTitleChanged: (String) -> Unit = {},
    private val onBell: () -> Unit = {},
) {

    private enum class State {
        GROUND,
        ESCAPE,
        CSI,
        OSC,
        /** DCS/SOS/PM/APC bodies: consumed until ST, never displayed. */
        STRING_IGNORE,
        DESIGNATE_G0,
        DESIGNATE_G1,
        /** ESC % / ESC # and friends: one final character to swallow. */
        CONSUME_ONE,
    }

    private var state = State.GROUND

    // ---- UTF-8 assembly ----------------------------------------------------
    private var utf8Remaining = 0
    private var utf8Codepoint = 0

    // ---- CSI assembly ------------------------------------------------------
    private val parameters = ArrayList<Int>(8)
    private val parameterHasColon = ArrayList<Boolean>(8)
    private var currentParameter = -1
    private var currentHasColon = false
    private var privateMarker = ' '
    private var intermediate = ' '

    // ---- OSC assembly ------------------------------------------------------
    private val oscBuffer = StringBuilder()
    private var oscEscapePending = false

    // ---- Character sets ----------------------------------------------------
    private var g0IsLineDrawing = false
    private var g1IsLineDrawing = false
    private var activeIsG1 = false

    /** Keypad application mode (ESC = / ESC >); the key encoder reads it. */
    var applicationKeypad = false
        private set

    // ---- Input -------------------------------------------------------------

    fun feed(bytes: ByteArray, offset: Int, count: Int) {
        for (index in offset until offset + count) {
            feedByte(bytes[index].toInt() and 0xFF)
        }
    }

    private fun feedByte(byte: Int) {
        if (utf8Remaining > 0) {
            if (byte and 0xC0 == 0x80) {
                utf8Codepoint = (utf8Codepoint shl 6) or (byte and 0x3F)
                if (--utf8Remaining == 0) emitCodepoint(utf8Codepoint)
                return
            }
            // Broken sequence: show one replacement, reprocess this byte.
            utf8Remaining = 0
            processChar(REPLACEMENT)
        }
        when {
            byte < 0x80 -> processChar(byte.toChar())
            byte and 0xE0 == 0xC0 -> startUtf8(byte and 0x1F, 1)
            byte and 0xF0 == 0xE0 -> startUtf8(byte and 0x0F, 2)
            byte and 0xF8 == 0xF0 -> startUtf8(byte and 0x07, 3)
            else -> processChar(REPLACEMENT)
        }
    }

    private fun startUtf8(initialBits: Int, continuations: Int) {
        utf8Codepoint = initialBits
        utf8Remaining = continuations
    }

    private fun emitCodepoint(codepoint: Int) {
        // Cells hold a single UTF-16 unit; anything beyond the BMP is rare
        // in terminal output and rendered as the replacement mark.
        processChar(if (codepoint <= 0xFFFF) codepoint.toChar() else REPLACEMENT)
    }

    // ---- Character dispatch -------------------------------------------------

    fun processChar(character: Char) {
        when (state) {
            State.GROUND -> processGround(character)
            State.ESCAPE -> processEscape(character)
            State.CSI -> processCsi(character)
            State.OSC -> processOsc(character)
            State.STRING_IGNORE -> processStringIgnore(character)
            State.DESIGNATE_G0 -> designateCharset(g0 = true, character)
            State.DESIGNATE_G1 -> designateCharset(g0 = false, character)
            State.CONSUME_ONE -> state = State.GROUND
        }
    }

    private fun processGround(character: Char) {
        if (character.code < 0x20 || character == DEL) {
            executeControl(character)
        } else {
            screen.writeChar(translate(character))
        }
    }

    /** C0 controls act even mid-sequence (xterm behaviour). */
    private fun executeControl(character: Char) {
        when (character) {
            '' -> onBell()
            '\b' -> screen.backspace()
            '\t' -> screen.horizontalTab()
            '\n', '', '' -> screen.lineFeed()
            '\r' -> screen.carriageReturn()
            SO -> activeIsG1 = true
            SI -> activeIsG1 = false
            ESC -> {
                resetCsiAssembly()
                state = State.ESCAPE
            }
            else -> Unit // NUL, ENQ, DEL, … — ignored
        }
    }

    private fun translate(character: Char): Char {
        val lineDrawing = if (activeIsG1) g1IsLineDrawing else g0IsLineDrawing
        if (!lineDrawing) return character
        val index = character.code - DEC_GRAPHICS_FIRST.code
        return if (index in DEC_GRAPHICS.indices) DEC_GRAPHICS[index] else character
    }

    // ---- ESC ----------------------------------------------------------------

    private fun processEscape(character: Char) {
        state = State.GROUND
        when (character) {
            '[' -> state = State.CSI
            ']' -> {
                oscBuffer.setLength(0)
                oscEscapePending = false
                state = State.OSC
            }
            'P', 'X', '^', '_' -> state = State.STRING_IGNORE
            '(' -> state = State.DESIGNATE_G0
            ')' -> state = State.DESIGNATE_G1
            '#', '%' -> state = State.CONSUME_ONE
            '7' -> screen.saveCursor()
            '8' -> screen.restoreCursor()
            'D' -> screen.lineFeed()                       // IND
            'E' -> { screen.carriageReturn(); screen.lineFeed() } // NEL
            'H' -> screen.setTabStopAtCursor()             // HTS
            'M' -> screen.reverseLineFeed()                // RI
            'Z' -> respond(DEVICE_ATTRIBUTES_REPLY)        // DECID
            'c' -> fullReset()                             // RIS
            '=' -> applicationKeypad = true
            '>' -> applicationKeypad = false
            '\\' -> Unit                                   // stray ST
            else -> if (character.code < 0x20) executeControl(character)
        }
    }

    private fun fullReset() {
        screen.fullReset()
        g0IsLineDrawing = false
        g1IsLineDrawing = false
        activeIsG1 = false
        applicationKeypad = false
    }

    private fun designateCharset(g0: Boolean, designator: Char) {
        val lineDrawing = designator == '0'
        if (g0) g0IsLineDrawing = lineDrawing else g1IsLineDrawing = lineDrawing
        state = State.GROUND
    }

    // ---- CSI ----------------------------------------------------------------

    private fun resetCsiAssembly() {
        parameters.clear()
        parameterHasColon.clear()
        currentParameter = -1
        currentHasColon = false
        privateMarker = ' '
        intermediate = ' '
    }

    private fun processCsi(character: Char) {
        when {
            character.code < 0x20 -> executeControl(character)
            character in '0'..'9' -> {
                val digit = character - '0'
                currentParameter =
                    if (currentParameter < 0) digit
                    else (currentParameter * 10 + digit).coerceAtMost(MAX_PARAMETER)
            }
            character == ';' || character == ':' -> {
                pushParameter()
                currentHasColon = character == ':'
            }
            character in "?>=<" && parameters.isEmpty() && currentParameter < 0 ->
                privateMarker = character
            character.code in 0x20..0x2F -> intermediate = character
            character.code in 0x40..0x7E -> {
                pushParameter()
                state = State.GROUND
                dispatchCsi(character)
            }
            else -> state = State.GROUND
        }
    }

    private fun pushParameter() {
        parameters.add(if (currentParameter < 0) 0 else currentParameter)
        parameterHasColon.add(currentHasColon)
        currentParameter = -1
        currentHasColon = false
    }

    /** Parameter [index], defaulting missing/zero values to [default]. */
    private fun parameter(index: Int, default: Int): Int {
        val value = parameters.getOrElse(index) { 0 }
        return if (value == 0) default else value
    }

    private fun dispatchCsi(command: Char) {
        if (privateMarker == '?') {
            dispatchPrivateCsi(command)
            return
        }
        if (privateMarker == '>') {
            if (command == 'c') respond(SECONDARY_ATTRIBUTES_REPLY)
            return
        }
        when (command) {
            '@' -> screen.insertChars(parameter(0, 1))
            'A' -> screen.moveCursorBy(-parameter(0, 1), 0)
            'B' -> screen.moveCursorBy(parameter(0, 1), 0)
            'C' -> screen.moveCursorBy(0, parameter(0, 1))
            'D' -> screen.moveCursorBy(0, -parameter(0, 1))
            'E' -> { screen.moveCursorBy(parameter(0, 1), 0); screen.carriageReturn() }
            'F' -> { screen.moveCursorBy(-parameter(0, 1), 0); screen.carriageReturn() }
            'G', '`' -> screen.moveCursorToColumn(parameter(0, 1) - 1)
            'H', 'f' -> screen.moveCursorTo(parameter(0, 1) - 1, parameter(1, 1) - 1)
            'I' -> repeat(parameter(0, 1)) { screen.horizontalTab() }
            'J' -> screen.eraseInDisplay(parameters.getOrElse(0) { 0 })
            'K' -> screen.eraseInLine(parameters.getOrElse(0) { 0 })
            'L' -> screen.insertLines(parameter(0, 1))
            'M' -> screen.deleteLines(parameter(0, 1))
            'P' -> screen.deleteChars(parameter(0, 1))
            'S' -> screen.scrollUp(parameter(0, 1))
            'T' -> screen.scrollDown(parameter(0, 1))
            'X' -> screen.eraseChars(parameter(0, 1))
            'Z' -> repeat(parameter(0, 1)) { screen.backwardTab() }
            'a' -> screen.moveCursorBy(0, parameter(0, 1))
            'c' -> respond(DEVICE_ATTRIBUTES_REPLY)
            'd' -> screen.moveCursorToRow(parameter(0, 1) - 1)
            'e' -> screen.moveCursorBy(parameter(0, 1), 0)
            'g' -> screen.clearTabStops(parameters.getOrElse(0) { 0 })
            'h' -> if (parameters.contains(MODE_INSERT)) screen.insertMode = true
            'l' -> if (parameters.contains(MODE_INSERT)) screen.insertMode = false
            'm' -> applyGraphicRendition()
            'n' -> respondToStatusRequest()
            'r' -> if (parameters.isEmpty() || parameters.all { it == 0 }) {
                screen.resetScrollRegion()
                screen.moveCursorTo(0, 0)
            } else {
                screen.setScrollRegion(parameter(0, 1) - 1, parameter(1, screen.rows) - 1)
            }
            's' -> screen.saveCursor()
            't' -> respondToWindowRequest()
            'u' -> screen.restoreCursor()
            else -> Unit // cursor styles ('q'), … — ignored
        }
    }

    /**
     * Window manipulation. Only the size reports are answered — the rest
     * (move, resize, iconify) ask a terminal to change itself, which is not
     * something a guest gets to decide here.
     *
     * Answering matters: this is how a guest learns the terminal's size
     * without anything being typed into it. `resize`, ncurses and the
     * images' own console helper all ask this way, so the size stays right
     * after a rotation or a window drag with nothing visible on screen.
     */
    private fun respondToWindowRequest() {
        when (parameters.getOrElse(0) { 0 }) {
            REPORT_TEXT_AREA_IN_CHARS ->
                respond("[8;${screen.rows};${screen.columns}t".toByteArray())
            REPORT_SCREEN_SIZE_IN_CHARS ->
                respond("[9;${screen.rows};${screen.columns}t".toByteArray())
        }
    }

    private fun dispatchPrivateCsi(command: Char) {
        val enable = when (command) {
            'h' -> true
            'l' -> false
            else -> return
        }
        for (mode in parameters) {
            when (mode) {
                1 -> screen.applicationCursorKeys = enable
                6 -> {
                    screen.originMode = enable
                    screen.moveCursorTo(0, 0)
                }
                7 -> screen.autowrap = enable
                25 -> screen.cursorVisible = enable
                47, 1047 ->
                    if (enable) screen.switchToAlternateScreen(clear = false)
                    else screen.switchToPrimaryScreen()
                1048 -> if (enable) screen.saveCursor() else screen.restoreCursor()
                1049 -> if (enable) {
                    screen.saveCursor()
                    screen.switchToAlternateScreen(clear = true)
                } else {
                    screen.switchToPrimaryScreen()
                    screen.restoreCursor()
                }
                2004 -> screen.bracketedPaste = enable
                else -> Unit // mouse reporting, focus events, blink, … — ignored
            }
        }
    }

    private fun respondToStatusRequest() {
        when (parameters.getOrElse(0) { 0 }) {
            5 -> respond(STATUS_OK_REPLY)
            6 -> respond(
                // CPR: 1-based cursor position. The guest-side fix_console
                // helper derives the terminal size from this answer.
                "[${screen.cursorRow + 1};${screen.cursorColumn + 1}R".toByteArray()
            )
        }
    }

    // ---- SGR ----------------------------------------------------------------

    private fun applyGraphicRendition() {
        if (parameters.isEmpty()) {
            screen.pen.reset()
            return
        }
        var index = 0
        while (index < parameters.size) {
            index += applySgrCode(index)
        }
    }

    /**
     * How many parameters starting at [index] belong to one code.
     *
     * Colon-joined values are sub-parameters of the code they follow
     * (`4:3` is one curly underline, not an underline *and* an italic).
     * Counting them keeps an unrecognised code from spilling its
     * sub-parameters into the next one, where they would be read as
     * attributes of their own and leave colours set that were never asked
     * for.
     */
    private fun subParameterRun(index: Int): Int {
        var length = 1
        while (index + length < parameters.size &&
            parameterHasColon.getOrElse(index + length) { false }
        ) {
            length++
        }
        return length
    }

    /** Applies the code at [index]; returns how many parameters it used. */
    private fun applySgrCode(index: Int): Int {
        val pen = screen.pen
        when (val code = parameters[index]) {
            0 -> pen.reset()
            1 -> pen.flags = pen.flags or TerminalCellFlags.BOLD
            2 -> pen.flags = pen.flags or TerminalCellFlags.FAINT
            3 -> pen.flags = pen.flags or TerminalCellFlags.ITALIC
            4 -> pen.flags = pen.flags or TerminalCellFlags.UNDERLINE
            7 -> pen.flags = pen.flags or TerminalCellFlags.INVERSE
            8 -> pen.flags = pen.flags or TerminalCellFlags.INVISIBLE
            9 -> pen.flags = pen.flags or TerminalCellFlags.STRIKETHROUGH
            21 -> pen.flags = pen.flags or TerminalCellFlags.UNDERLINE
            22 -> pen.flags = pen.flags and
                (TerminalCellFlags.BOLD or TerminalCellFlags.FAINT).inv()
            23 -> pen.flags = pen.flags and TerminalCellFlags.ITALIC.inv()
            24 -> pen.flags = pen.flags and TerminalCellFlags.UNDERLINE.inv()
            27 -> pen.flags = pen.flags and TerminalCellFlags.INVERSE.inv()
            28 -> pen.flags = pen.flags and TerminalCellFlags.INVISIBLE.inv()
            29 -> pen.flags = pen.flags and TerminalCellFlags.STRIKETHROUGH.inv()
            in 30..37 -> pen.foreground = TerminalColors.indexed(code - 30)
            38 -> return 1 + applyExtendedColour(index) { pen.foreground = it }
            39 -> pen.foreground = TerminalColors.DEFAULT_FOREGROUND
            in 40..47 -> pen.background = TerminalColors.indexed(code - 40)
            48 -> return 1 + applyExtendedColour(index) { pen.background = it }
            49 -> pen.background = TerminalColors.DEFAULT_BACKGROUND
            in 90..97 -> pen.foreground = TerminalColors.indexed(code - 90 + 8)
            in 100..107 -> pen.background = TerminalColors.indexed(code - 100 + 8)
            // Blink, underline colour (58/59), fonts and other rarities:
            // ignored, but their sub-parameters must still be stepped over.
            else -> return subParameterRun(index)
        }
        return 1
    }

    /**
     * SGR 38/48 extensions: `5;index` for a palette entry, `2;r;g;b` for a
     * true colour.
     *
     * Both come in a semicolon form and a colon form, and the colon form may
     * carry an empty colour-space id (`38:2::r:g:b`). The colon form is one
     * parameter group, so its length is known; the semicolon form is not, so
     * its arguments are counted by hand.
     */
    private fun applyExtendedColour(index: Int, apply: (Int) -> Unit): Int {
        val colonForm = parameterHasColon.getOrElse(index + 1) { false }
        val groupLength = if (colonForm) subParameterRun(index) else 0
        when (parameters.getOrNull(index + 1)) {
            5 -> {
                apply(TerminalColors.indexed(parameters.getOrElse(index + 2) { 0 }))
                return if (colonForm) groupLength - 1 else 2
            }
            2 -> {
                // Colon form with a colour-space id: 38 : 2 : id : r : g : b.
                val hasColourSpaceId = colonForm && groupLength >= COLON_RGB_WITH_SPACE_ID
                val first = if (hasColourSpaceId) index + 3 else index + 2
                apply(
                    TerminalColors.rgb(
                        parameters.getOrElse(first) { 0 },
                        parameters.getOrElse(first + 1) { 0 },
                        parameters.getOrElse(first + 2) { 0 },
                    )
                )
                return if (colonForm) groupLength - 1 else 4
            }
            else -> return if (colonForm) groupLength - 1 else 1
        }
    }

    // ---- OSC ----------------------------------------------------------------

    private fun processOsc(character: Char) {
        if (oscEscapePending) {
            oscEscapePending = false
            if (character == '\\') {
                finishOsc()
            } else {
                // Not a terminator after all; drop the pending ESC.
                state = State.OSC
            }
            return
        }
        when (character) {
            '' -> finishOsc()
            ESC -> oscEscapePending = true
            else -> if (oscBuffer.length < MAX_OSC_LENGTH) oscBuffer.append(character)
        }
    }

    private fun finishOsc() {
        state = State.GROUND
        val body = oscBuffer.toString()
        val separator = body.indexOf(';')
        if (separator <= 0) return
        when (body.take(separator)) {
            "0", "2" -> onTitleChanged(body.substring(separator + 1))
        }
    }

    private fun processStringIgnore(character: Char) {
        if (oscEscapePending) {
            oscEscapePending = false
            if (character == '\\') state = State.GROUND
            return
        }
        when (character) {
            ESC -> oscEscapePending = true
            '' -> state = State.GROUND
        }
    }

    companion object {
        private const val ESC = ''
        private const val DEL = ''
        private const val SO = ''
        private const val SI = ''
        private const val REPLACEMENT = '�'
        private const val MAX_PARAMETER = 65_535
        private const val MAX_OSC_LENGTH = 1_024
        private const val MODE_INSERT = 4

        /** CSI 18 t — "how many characters fit in the text area?" */
        private const val REPORT_TEXT_AREA_IN_CHARS = 18

        /** CSI 19 t — the same question about the whole screen. */
        private const val REPORT_SCREEN_SIZE_IN_CHARS = 19

        /** `38 : 2 : id : r : g : b` — six values, versus five without the id. */
        private const val COLON_RGB_WITH_SPACE_ID = 6

        private val DEVICE_ATTRIBUTES_REPLY = "[?6c".toByteArray()
        private val SECONDARY_ATTRIBUTES_REPLY = "[>0;0;0c".toByteArray()
        private val STATUS_OK_REPLY = "[0n".toByteArray()

        /** DEC special graphics, indexed from '`' (0x60) to '~' (0x7E). */
        private const val DEC_GRAPHICS_FIRST = '`'
        private val DEC_GRAPHICS = charArrayOf(
            '◆', '▒', '␉', '␌', '␍', '␊', '°', '±', '␤', '␋',
            '┘', '┐', '┌', '└', '┼', '⎺', '⎻', '─', '⎼', '⎽',
            '├', '┤', '┴', '┬', '│', '≤', '≥', 'π', '≠', '£', '·',
        )
    }
}
