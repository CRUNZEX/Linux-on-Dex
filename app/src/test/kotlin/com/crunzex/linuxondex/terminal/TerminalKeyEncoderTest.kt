package com.crunzex.linuxondex.terminal

import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalKeyEncoderTest {

    private fun encode(
        keyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        unicodeChar: Int = 0,
        ctrl: Boolean = false,
        alt: Boolean = false,
        applicationCursorKeys: Boolean = false,
    ) = TerminalKeyEncoder.encode(keyCode, unicodeChar, ctrl, alt, applicationCursorKeys)

    @Test
    fun `plain characters pass through as UTF-8`() {
        assertArrayEquals(byteArrayOf('a'.code.toByte()), encode(unicodeChar = 'a'.code))
        // Thai character: multi-byte UTF-8 must survive intact.
        assertArrayEquals("ส".toByteArray(Charsets.UTF_8), encode(unicodeChar = 'ส'.code))
    }

    @Test
    fun `bare enter sends carriage return`() {
        // The fix for "root has no password but Enter did nothing": a real
        // terminal answers an empty prompt with a single CR.
        assertArrayEquals(byteArrayOf(0x0D), encode(keyCode = KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun `backspace sends DEL as terminals expect`() {
        assertArrayEquals(byteArrayOf(0x7F), encode(keyCode = KeyEvent.KEYCODE_DEL))
    }

    @Test
    fun `control combinations map to control bytes`() {
        assertArrayEquals(byteArrayOf(0x03), encode(unicodeChar = 'c'.code, ctrl = true))
        assertArrayEquals(byteArrayOf(0x04), encode(unicodeChar = 'd'.code, ctrl = true))
        assertArrayEquals(byteArrayOf(0x00), encode(unicodeChar = ' '.code, ctrl = true))
        assertArrayEquals(byteArrayOf(0x1B), encode(unicodeChar = '['.code, ctrl = true))
        assertArrayEquals(byteArrayOf(0x1F), encode(unicodeChar = '_'.code, ctrl = true))
    }

    @Test
    fun `alt prefixes escape like iTerm's meta key`() {
        assertArrayEquals(
            byteArrayOf(0x1B, 'x'.code.toByte()),
            encode(unicodeChar = 'x'.code, alt = true),
        )
        // Alt applies to special keys too.
        assertArrayEquals(
            byteArrayOf(0x1B, 0x1B, '['.code.toByte(), 'A'.code.toByte()),
            encode(keyCode = KeyEvent.KEYCODE_DPAD_UP, alt = true),
        )
    }

    @Test
    fun `arrows switch between normal and application mode`() {
        assertArrayEquals(
            "[A".toByteArray(),
            encode(keyCode = KeyEvent.KEYCODE_DPAD_UP),
        )
        assertArrayEquals(
            "OA".toByteArray(),
            encode(keyCode = KeyEvent.KEYCODE_DPAD_UP, applicationCursorKeys = true),
        )
    }

    @Test
    fun `navigation and function keys use xterm sequences`() {
        assertArrayEquals("[5~".toByteArray(), encode(keyCode = KeyEvent.KEYCODE_PAGE_UP))
        assertArrayEquals("[3~".toByteArray(), encode(keyCode = KeyEvent.KEYCODE_FORWARD_DEL))
        assertArrayEquals("OP".toByteArray(), encode(keyCode = KeyEvent.KEYCODE_F1))
        assertArrayEquals("[15~".toByteArray(), encode(keyCode = KeyEvent.KEYCODE_F5))
        assertArrayEquals("[H".toByteArray(), encode(keyCode = KeyEvent.KEYCODE_MOVE_HOME))
    }

    @Test
    fun `keys that produce nothing are not consumed`() {
        assertNull(encode(keyCode = KeyEvent.KEYCODE_VOLUME_UP))
        // Dead keys wait for composition; the terminal must not eat them.
        assertNull(encode(unicodeChar = '´'.code or KeyCharacterMap.COMBINING_ACCENT))
        // Ctrl+key with no control mapping stays unhandled.
        assertNull(encode(unicodeChar = '5'.code, ctrl = true))
    }
}
