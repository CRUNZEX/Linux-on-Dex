package com.crunzex.linuxondex.display.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RfbProtocolTest {

    @Test
    fun `pixel format block is exactly 16 bytes of little endian rgb565`() {
        val format = RfbProtocol.clientPixelFormat()

        assertEquals(16, format.size)
        assertEquals(16, format[0].toInt())   // bits-per-pixel
        assertEquals(16, format[1].toInt())   // depth
        assertEquals(0, format[2].toInt())    // little endian
        assertEquals(1, format[3].toInt())    // true colour
        assertEquals(0, format[4].toInt())    // red max, high byte (u16 big-endian)
        assertEquals(31, format[5].toInt())   // red max, low byte — 5 bits
        assertEquals(0, format[6].toInt())    // green max, high byte
        assertEquals(63, format[7].toInt())   // green max, low byte — 6 bits
        assertEquals(0, format[8].toInt())    // blue max, high byte
        assertEquals(31, format[9].toInt())   // blue max, low byte — 5 bits
        assertEquals(11, format[10].toInt())  // red shift
        assertEquals(5, format[11].toInt())   // green shift
        assertEquals(0, format[12].toInt())   // blue shift
        assertEquals(0, format[13].toInt())   // padding
        assertEquals(0, format[14].toInt())
        assertEquals(0, format[15].toInt())
    }

    @Test
    fun `bytes per pixel matches the bits per pixel we request`() {
        // The frame path sizes every buffer from BYTES_PER_PIXEL; if it ever
        // disagreed with the negotiated format we would overrun or truncate.
        assertEquals(2, RfbProtocol.BYTES_PER_PIXEL)
        assertEquals(
            RfbProtocol.BYTES_PER_PIXEL * 8,
            RfbProtocol.clientPixelFormat()[0].toInt(),
        )
    }

    @Test
    fun `alpha cursor size includes encoding word and fixed argb pixels`() {
        assertEquals(4 + 16 * 12 * 4, RfbProtocol.alphaCursorRectangleByteCount(16, 12))
    }

    @Test
    fun `cursor payload calculations reject integer overflow`() {
        assertThrows(IllegalArgumentException::class.java) {
            RfbProtocol.alphaCursorRectangleByteCount(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RfbProtocol.cursorRectangleByteCount(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    @Test
    fun `full intensity rgb565 decodes to exactly opaque white`() {
        // Bit replication, not a plain shift: 0xFFFF must be 0xFFFFFF, never
        // 0xF8FCF8, or every bright surface picks up a tint.
        val pixels = RfbProtocol.decodeRawRect(rgb565(0xFFFF), width = 1, height = 1)

        assertEquals(0xFFFFFFFF.toInt(), pixels[0])
    }

    @Test
    fun `zero rgb565 decodes to exactly opaque black`() {
        val pixels = RfbProtocol.decodeRawRect(rgb565(0x0000), width = 1, height = 1)

        assertEquals(0xFF000000.toInt(), pixels[0])
    }

    @Test
    fun `channel shifts survive the round trip`() {
        // Pure channels catch both a swapped red/blue shift and a byte-order
        // mistake, since each has a distinctive little-endian byte pair.
        val pixels = RfbProtocol.decodeRawRect(
            rgb565(0xF800, 0x07E0, 0x001F),
            width = 3,
            height = 1,
        )

        assertEquals(0xFFFF0000.toInt(), pixels[0])
        assertEquals(0xFF00FF00.toInt(), pixels[1])
        assertEquals(0xFF0000FF.toInt(), pixels[2])
    }

    @Test
    fun `mid tone rgb565 expands to the nearest 8 bit value`() {
        // 0x8410 is r5=16, g6=32, b5=16 — the middle of each channel.
        // Replicating the high bits gives 132 / 130 / 132.
        val pixels = RfbProtocol.decodeRawRect(rgb565(0x8410), width = 1, height = 1)

        assertEquals(0xFF848284.toInt(), pixels[0])
    }

    @Test
    fun `every pixel of a multi row rect is decoded`() {
        val pixels = RfbProtocol.decodeRawRect(
            rgb565(0xF800, 0x001F, 0x001F, 0xF800),
            width = 2,
            height = 2,
        )

        assertEquals(4, pixels.size)
        assertEquals(0xFFFF0000.toInt(), pixels[0])
        assertEquals(0xFF0000FF.toInt(), pixels[1])
        assertEquals(0xFF0000FF.toInt(), pixels[2])
        assertEquals(0xFFFF0000.toInt(), pixels[3])
    }

    @Test
    fun `truncated raw rect is rejected`() {
        // Three bytes cannot hold two 16-bit pixels.
        val tooShort = ByteArray(3)

        assertThrows(IllegalArgumentException::class.java) {
            RfbProtocol.decodeRawRect(tooShort, width = 2, height = 1)
        }
    }

    @Test
    fun `undersized destination is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RfbProtocol.decodeRawRectInto(rgb565(0xFFFF, 0xFFFF), IntArray(1), pixelCount = 2)
        }
    }

    /** Packs RGB565 words the way the server sends them: little endian. */
    private fun rgb565(vararg words: Int): ByteArray {
        val bytes = ByteArray(words.size * RfbProtocol.BYTES_PER_PIXEL)
        words.forEachIndexed { index, word ->
            bytes[index * 2] = (word and 0xFF).toByte()
            bytes[index * 2 + 1] = ((word ushr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
