package com.crunzex.linuxondex.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dot layout has to be exactly right: btop draws its graphs by packing
 * eight independent dots into one cell, so a single mis-mapped bit turns a
 * CPU trace into noise. Unicode numbers the dots down the left column and
 * then the right, with 7 and 8 added underneath later, which is the part
 * that is easy to get wrong.
 */
class BrailleGlyphsTest {

    private fun dotGrid(character: Char): String =
        (0 until BrailleGlyphs.DOT_ROWS).joinToString("\n") { row ->
            (0 until BrailleGlyphs.DOT_COLUMNS).joinToString("") { column ->
                if (BrailleGlyphs.hasDot(character, column, row)) "#" else "."
            }
        }

    @Test
    fun `the blank pattern has no dots and the full one has all eight`() {
        assertTrue((0 until 2).all { column ->
            (0 until 4).all { row -> !BrailleGlyphs.hasDot('⠀', column, row) }
        })
        assertTrue((0 until 2).all { column ->
            (0 until 4).all { row -> BrailleGlyphs.hasDot('⣿', column, row) }
        })
    }

    @Test
    fun `each numbered dot lands where Unicode puts it`() {
        // Dots 1-3 run down the left column, 4-6 down the right, then 7 and 8.
        assertEquals4x2("#.\n..\n..\n..", dotGrid('⠁')) // dot 1
        assertEquals4x2("..\n#.\n..\n..", dotGrid('⠂')) // dot 2
        assertEquals4x2("..\n..\n#.\n..", dotGrid('⠄')) // dot 3
        assertEquals4x2(".#\n..\n..\n..", dotGrid('⠈')) // dot 4
        assertEquals4x2("..\n.#\n..\n..", dotGrid('⠐')) // dot 5
        assertEquals4x2("..\n..\n.#\n..", dotGrid('⠠')) // dot 6
        assertEquals4x2("..\n..\n..\n#.", dotGrid('⡀')) // dot 7
        assertEquals4x2("..\n..\n..\n.#", dotGrid('⢀')) // dot 8
    }

    @Test
    fun `a bar graph column fills from the bottom up`() {
        // U+28C0 is dots 7 and 8: the bottom row, both sides — exactly what
        // btop draws for a trace sitting near zero.
        assertEquals4x2("..\n..\n..\n##", dotGrid('⣀'))
    }

    @Test
    fun `only braille codepoints are treated as braille`() {
        assertTrue(BrailleGlyphs.isBraillePattern('⠀'))
        assertTrue(BrailleGlyphs.isBraillePattern('⣿'))
        assertFalse(BrailleGlyphs.isBraillePattern('⟿'))
        assertFalse(BrailleGlyphs.isBraillePattern('⤀'))
        // Box drawing and blocks keep coming from the font, which has them.
        assertFalse(BrailleGlyphs.isBraillePattern('─'))
        assertFalse(BrailleGlyphs.isBraillePattern('█'))
        assertFalse(BrailleGlyphs.isBraillePattern('A'))
    }

    @Test
    fun `positions outside the grid are simply not dots`() {
        assertFalse(BrailleGlyphs.hasDot('⣿', -1, 0))
        assertFalse(BrailleGlyphs.hasDot('⣿', 2, 0))
        assertFalse(BrailleGlyphs.hasDot('⣿', 0, 4))
    }

    private fun assertEquals4x2(expected: String, actual: String) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
