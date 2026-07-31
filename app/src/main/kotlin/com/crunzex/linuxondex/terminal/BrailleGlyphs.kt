package com.crunzex.linuxondex.terminal

/**
 * Braille patterns, U+2800–U+28FF, described as a 2×4 grid of dots.
 *
 * These characters are how terminal programs draw fine graphs: btop, gotop
 * and friends pack eight on/off dots into every cell, which is four times
 * the vertical resolution a block character can offer. They are only useful
 * if each one occupies exactly one cell.
 *
 * Android's monospace font has no braille, so the system substitutes a
 * fallback face whose glyphs are neither the same size nor the same advance
 * as the terminal's cell. The result smears across neighbouring characters
 * and hides them — a CPU meter that swallows the number beside it. Describing
 * the dots here lets the view draw them at exactly cell size instead, which
 * is what a terminal built for this does.
 */
object BrailleGlyphs {

    const val FIRST_CODEPOINT = 0x2800
    const val LAST_CODEPOINT = 0x28FF

    /** A braille cell is two dots wide and four tall. */
    const val DOT_COLUMNS = 2
    const val DOT_ROWS = 4

    fun isBraillePattern(character: Char): Boolean =
        character.code in FIRST_CODEPOINT..LAST_CODEPOINT

    /**
     * Whether the dot at [dotColumn] (0–1) and [dotRow] (0–3) is raised.
     *
     * Unicode numbers braille dots down the left column (1, 2, 3) and then
     * the right (4, 5, 6), with 7 and 8 added underneath afterwards, so the
     * bit for a position is looked up rather than calculated.
     */
    fun hasDot(character: Char, dotColumn: Int, dotRow: Int): Boolean {
        if (!isBraillePattern(character)) return false
        if (dotColumn !in 0 until DOT_COLUMNS || dotRow !in 0 until DOT_ROWS) return false
        val bit = DOT_BITS[dotRow * DOT_COLUMNS + dotColumn]
        return (character.code - FIRST_CODEPOINT) and bit != 0
    }

    /** Dot bits in reading order: left dot then right dot, top row first. */
    private val DOT_BITS = intArrayOf(
        0x01, 0x08, // dots 1 and 4
        0x02, 0x10, // dots 2 and 5
        0x04, 0x20, // dots 3 and 6
        0x40, 0x80, // dots 7 and 8
    )
}
