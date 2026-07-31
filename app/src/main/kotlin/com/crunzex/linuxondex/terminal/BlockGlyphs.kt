package com.crunzex.linuxondex.terminal

/**
 * Block elements, U+2580–U+2595, described as a rectangle to fill rather
 * than a character to typeset.
 *
 * These are how a terminal program draws a bar: btop's meters are made of
 * full blocks and shades, and its graphs of eighth-height blocks. Every one
 * has to line up exactly with its neighbours, or a bar looks like a fence.
 *
 * Fonts do not cooperate. Android's monospace draws the shades (░ ▒ ▓) as a
 * stipple of tiny dots, so a dim meter reads as noise instead of a soft bar,
 * and the eighth blocks are rounded to whatever the hinting prefers, so a
 * graph steps unevenly. Filling the rectangle directly gives the clean,
 * continuous bars a desktop terminal shows.
 *
 * The quadrant characters (U+2596 and up) are deliberately left to the font:
 * they need several rectangles each, and nothing here draws with them.
 */
object BlockGlyphs {

    /**
     * The part of a cell a block character covers, in fractions of the cell,
     * plus how opaque it is. A shade is a full-cell rectangle drawn faintly.
     */
    data class CellFill(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val alpha: Int,
    )

    /** The fill for [character], or null when the font should draw it. */
    fun fillFor(character: Char): CellFill? = when (character) {
        '█' -> wholeCell(OPAQUE)
        '░' -> wholeCell(LIGHT_SHADE_ALPHA)
        '▒' -> wholeCell(MEDIUM_SHADE_ALPHA)
        '▓' -> wholeCell(DARK_SHADE_ALPHA)
        '▀' -> fromTop(eighths = 4)
        '▔' -> fromTop(eighths = 1)
        '▁' -> fromBottom(eighths = 1)
        '▂' -> fromBottom(eighths = 2)
        '▃' -> fromBottom(eighths = 3)
        '▄' -> fromBottom(eighths = 4)
        '▅' -> fromBottom(eighths = 5)
        '▆' -> fromBottom(eighths = 6)
        '▇' -> fromBottom(eighths = 7)
        '▉' -> fromLeft(eighths = 7)
        '▊' -> fromLeft(eighths = 6)
        '▋' -> fromLeft(eighths = 5)
        '▌' -> fromLeft(eighths = 4)
        '▍' -> fromLeft(eighths = 3)
        '▎' -> fromLeft(eighths = 2)
        '▏' -> fromLeft(eighths = 1)
        '▐' -> fromRight(eighths = 4)
        '▕' -> fromRight(eighths = 1)
        else -> null
    }

    private fun wholeCell(alpha: Int) = CellFill(0f, 0f, 1f, 1f, alpha)

    private fun fromTop(eighths: Int) =
        CellFill(0f, 0f, 1f, eighths / EIGHTHS, OPAQUE)

    private fun fromBottom(eighths: Int) =
        CellFill(0f, 1f - eighths / EIGHTHS, 1f, 1f, OPAQUE)

    private fun fromLeft(eighths: Int) =
        CellFill(0f, 0f, eighths / EIGHTHS, 1f, OPAQUE)

    private fun fromRight(eighths: Int) =
        CellFill(1f - eighths / EIGHTHS, 0f, 1f, 1f, OPAQUE)

    private const val EIGHTHS = 8f
    private const val OPAQUE = 255

    // A quarter, a half and three quarters, which is what the three shade
    // characters mean and what a desktop terminal renders them as.
    private const val LIGHT_SHADE_ALPHA = 64
    private const val MEDIUM_SHADE_ALPHA = 128
    private const val DARK_SHADE_ALPHA = 192
}
