package com.crunzex.linuxondex.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A meter drawn out of block characters is only as good as its geometry:
 * neighbouring cells have to meet exactly, and a bar that is "five eighths
 * tall" has to be five eighths tall, or btop's graphs look like a fence.
 */
class BlockGlyphsTest {

    @Test
    fun `a full block covers its whole cell`() {
        val fill = BlockGlyphs.fillFor('█')!!
        assertEquals(BlockGlyphs.CellFill(0f, 0f, 1f, 1f, 255), fill)
    }

    @Test
    fun `the three shades are a quarter, a half and three quarters`() {
        assertEquals(64, BlockGlyphs.fillFor('░')!!.alpha)
        assertEquals(128, BlockGlyphs.fillFor('▒')!!.alpha)
        assertEquals(192, BlockGlyphs.fillFor('▓')!!.alpha)
        // A shade is the whole cell, drawn faintly — not a smaller rectangle.
        assertEquals(BlockGlyphs.CellFill(0f, 0f, 1f, 1f, 64), BlockGlyphs.fillFor('░'))
    }

    @Test
    fun `the eighth blocks grow from the bottom in equal steps`() {
        val heights = listOf('▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')
            .map { BlockGlyphs.fillFor(it)!! }
            .map { it.bottom - it.top }

        assertEquals((1..8).map { it / 8f }, heights)
        // Each one sits on the floor of the cell, so a row of them lines up.
        assertTrue(listOf('▁', '▄', '▇').all { BlockGlyphs.fillFor(it)!!.bottom == 1f })
    }

    @Test
    fun `the eighth blocks grow from the left in equal steps`() {
        val widths = listOf('▏', '▎', '▍', '▌', '▋', '▊', '▉', '█')
            .map { BlockGlyphs.fillFor(it)!! }
            .map { it.right - it.left }

        assertEquals((1..8).map { it / 8f }, widths)
        assertTrue(listOf('▏', '▌', '▉').all { BlockGlyphs.fillFor(it)!!.left == 0f })
    }

    @Test
    fun `halves and thin edges sit on the side they name`() {
        assertEquals(BlockGlyphs.CellFill(0f, 0f, 1f, 0.5f, 255), BlockGlyphs.fillFor('▀'))
        assertEquals(BlockGlyphs.CellFill(0.5f, 0f, 1f, 1f, 255), BlockGlyphs.fillFor('▐'))
        assertEquals(BlockGlyphs.CellFill(0f, 0f, 1f, 0.125f, 255), BlockGlyphs.fillFor('▔'))
        assertEquals(BlockGlyphs.CellFill(0.875f, 0f, 1f, 1f, 255), BlockGlyphs.fillFor('▕'))
    }

    @Test
    fun `everything else is left to the font`() {
        // Box drawing renders well already, and the quadrants are unused.
        assertNull(BlockGlyphs.fillFor('─'))
        assertNull(BlockGlyphs.fillFor('┌'))
        assertNull(BlockGlyphs.fillFor('▖'))
        assertNull(BlockGlyphs.fillFor('A'))
        assertNull(BlockGlyphs.fillFor(' '))
        assertNull(BlockGlyphs.fillFor('⣿'))
    }
}
