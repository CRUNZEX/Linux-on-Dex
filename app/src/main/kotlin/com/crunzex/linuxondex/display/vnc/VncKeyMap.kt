package com.crunzex.linuxondex.display.vnc

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Android [KeyEvent] → X11 keysym translation for the VNC KeyEvent message.
 * Printable Latin-1 characters use their value directly. Other Unicode
 * characters use the X11 Unicode keysym form (`0x01000000 | codePoint`).
 */
object VncKeyMap {

    private val specialKeys: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_ENTER to 0xFF0D,
        KeyEvent.KEYCODE_NUMPAD_ENTER to 0xFF8D,
        KeyEvent.KEYCODE_DEL to 0xFF08,          // backspace
        KeyEvent.KEYCODE_FORWARD_DEL to 0xFFFF,  // delete
        KeyEvent.KEYCODE_TAB to 0xFF09,
        KeyEvent.KEYCODE_ESCAPE to 0xFF1B,
        KeyEvent.KEYCODE_DPAD_UP to 0xFF52,
        KeyEvent.KEYCODE_DPAD_DOWN to 0xFF54,
        KeyEvent.KEYCODE_DPAD_LEFT to 0xFF51,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0xFF53,
        KeyEvent.KEYCODE_MOVE_HOME to 0xFF50,
        KeyEvent.KEYCODE_MOVE_END to 0xFF57,
        KeyEvent.KEYCODE_PAGE_UP to 0xFF55,
        KeyEvent.KEYCODE_PAGE_DOWN to 0xFF56,
        KeyEvent.KEYCODE_INSERT to 0xFF63,
        KeyEvent.KEYCODE_SHIFT_LEFT to 0xFFE1,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 0xFFE2,
        KeyEvent.KEYCODE_CTRL_LEFT to 0xFFE3,
        KeyEvent.KEYCODE_CTRL_RIGHT to 0xFFE4,
        KeyEvent.KEYCODE_ALT_LEFT to 0xFFE9,
        KeyEvent.KEYCODE_ALT_RIGHT to 0xFFEA,
        KeyEvent.KEYCODE_META_LEFT to 0xFFEB,
        KeyEvent.KEYCODE_META_RIGHT to 0xFFEC,
        KeyEvent.KEYCODE_F1 to 0xFFBE, KeyEvent.KEYCODE_F2 to 0xFFBF,
        KeyEvent.KEYCODE_F3 to 0xFFC0, KeyEvent.KEYCODE_F4 to 0xFFC1,
        KeyEvent.KEYCODE_F5 to 0xFFC2, KeyEvent.KEYCODE_F6 to 0xFFC3,
        KeyEvent.KEYCODE_F7 to 0xFFC4, KeyEvent.KEYCODE_F8 to 0xFFC5,
        KeyEvent.KEYCODE_F9 to 0xFFC6, KeyEvent.KEYCODE_F10 to 0xFFC7,
        KeyEvent.KEYCODE_F11 to 0xFFC8, KeyEvent.KEYCODE_F12 to 0xFFC9,
    )

    /** Returns the keysym for this event, or null when there is no mapping. */
    fun keysymFor(event: KeyEvent): Int? {
        specialKeys[event.keyCode]?.let { return it }
        val unicode = event.unicodeChar
        // Strip the combining-accent flag; ignore pure modifier state changes.
        val printable = unicode and KeyCharacterMap.COMBINING_ACCENT.inv()
        return keysymForCodePoint(printable)
    }

    /** Converts committed IME text to the X11 keysym carried by RFB. */
    fun keysymForCodePoint(codePoint: Int): Int? = when {
        codePoint == '\n'.code || codePoint == '\r'.code -> specialKeys[KeyEvent.KEYCODE_ENTER]
        codePoint == '\t'.code -> specialKeys[KeyEvent.KEYCODE_TAB]
        codePoint in ASCII_PRINTABLE_RANGE -> codePoint
        codePoint in LATIN_1_PRINTABLE_RANGE -> codePoint
        Character.isValidCodePoint(codePoint) && !Character.isISOControl(codePoint) ->
            UNICODE_KEYSYM_PREFIX or codePoint
        else -> null
    }

    private val ASCII_PRINTABLE_RANGE = 0x20..0x7E
    private val LATIN_1_PRINTABLE_RANGE = 0xA0..0xFF
    private const val UNICODE_KEYSYM_PREFIX = 0x01000000
}
