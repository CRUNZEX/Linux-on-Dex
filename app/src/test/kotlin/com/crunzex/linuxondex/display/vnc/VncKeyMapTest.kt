package com.crunzex.linuxondex.display.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VncKeyMapTest {

    @Test
    fun `ASCII and Latin-1 retain their standard keysyms`() {
        assertEquals('A'.code, VncKeyMap.keysymForCodePoint('A'.code))
        assertEquals(0xE9, VncKeyMap.keysymForCodePoint(0xE9))
    }

    @Test
    fun `non Latin text uses the X11 Unicode keysym range`() {
        assertEquals(0x01000E01, VncKeyMap.keysymForCodePoint(0x0E01)) // Thai ko kai
        assertEquals(0x0101F642, VncKeyMap.keysymForCodePoint(0x1F642))
    }

    @Test
    fun `committed line endings and tabs map to control keysyms`() {
        assertEquals(0xFF0D, VncKeyMap.keysymForCodePoint('\n'.code))
        assertEquals(0xFF0D, VncKeyMap.keysymForCodePoint('\r'.code))
        assertEquals(0xFF09, VncKeyMap.keysymForCodePoint('\t'.code))
    }

    @Test
    fun `invalid and unsupported control code points are rejected`() {
        assertNull(VncKeyMap.keysymForCodePoint(-1))
        assertNull(VncKeyMap.keysymForCodePoint(0x1F))
        assertNull(VncKeyMap.keysymForCodePoint(0x11_0000))
    }
}
