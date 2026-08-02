package com.crunzex.linuxondex.display.vnc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pointer sprite's length is implied by the rectangle size rather than
 * stated in the message, so getting this arithmetic wrong by a single byte
 * desynchronises every rectangle that follows — the stream would decode as
 * garbage rather than fail cleanly.
 */
class CursorEncodingTest {

    @Test
    fun `sprite is the image plus a one-bit-per-pixel mask`() {
        // 16x16 at 2 bytes per pixel = 512, mask rows of 16 bits = 2 bytes x 16.
        assertEquals(512 + 32, RfbProtocol.cursorRectangleByteCount(16, 16))
    }

    @Test
    fun `mask rows are padded to whole bytes`() {
        // 9 pixels wide needs 2 mask bytes per row, not 9/8 of a byte.
        assertEquals(9 * 1 * RfbProtocol.BYTES_PER_PIXEL + 2, RfbProtocol.cursorRectangleByteCount(9, 1))
        // A width that exactly fills bytes must not gain a spare one.
        assertEquals(8 * 1 * RfbProtocol.BYTES_PER_PIXEL + 1, RfbProtocol.cursorRectangleByteCount(8, 1))
    }

    @Test
    fun `an empty cursor reads no payload at all`() {
        assertEquals(0, RfbProtocol.cursorRectangleByteCount(0, 0))
    }

    @Test
    fun `a typical GNOME pointer stays a small read`() {
        // 24x24 is the usual Adwaita size; well under one network packet.
        val byteCount = RfbProtocol.cursorRectangleByteCount(24, 24)

        assertEquals(24 * 24 * 2 + 3 * 24, byteCount)
    }
}
