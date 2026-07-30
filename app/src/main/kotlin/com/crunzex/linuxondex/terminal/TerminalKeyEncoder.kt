package com.crunzex.linuxondex.terminal

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Turns Android key events into the bytes a terminal expects — the piece
 * that makes a hardware keyboard (DeX) feel native: Ctrl+C interrupts,
 * arrows navigate vim, Tab completes, and a bare Enter answers a prompt.
 *
 * Pure function of its inputs, so every mapping is unit-testable without
 * instrumentation.
 */
object TerminalKeyEncoder {

    /**
     * Encodes one key press.
     *
     * @param keyCode the [KeyEvent] key code
     * @param unicodeChar the character for the key *without* Ctrl applied
     *   (pass `event.getUnicodeChar(metaState and META_CTRL_MASK.inv())`)
     * @param ctrl/alt whether those modifiers are held
     * @param applicationCursorKeys DECCKM: arrows send SS3 instead of CSI
     * @return the bytes to write, or null when the key is not the
     *   terminal's to handle (volume, back, bare modifiers, …)
     */
    fun encode(
        keyCode: Int,
        unicodeChar: Int,
        ctrl: Boolean,
        alt: Boolean,
        applicationCursorKeys: Boolean,
    ): ByteArray? {
        specialKeySequence(keyCode, applicationCursorKeys)?.let { sequence ->
            return if (alt) byteArrayOf(ESC) + sequence else sequence
        }

        // Dead keys (combining accents) wait for the next key; not ours.
        if (unicodeChar and KeyCharacterMap.COMBINING_ACCENT != 0) return null
        if (unicodeChar == 0) return null

        val encoded = if (ctrl) {
            byteArrayOf(controlByte(unicodeChar) ?: return null)
        } else {
            String(Character.toChars(unicodeChar)).toByteArray(Charsets.UTF_8)
        }
        // Alt as "Meta sends escape", like iTerm's default profile.
        return if (alt) byteArrayOf(ESC) + encoded else encoded
    }

    /**
     * Ctrl+key to control byte: letters map to 1..26, and the punctuation
     * group follows the ASCII convention (Ctrl+[ = ESC, Ctrl+Space = NUL…).
     */
    private fun controlByte(character: Int): Byte? {
        val upper = Character.toUpperCase(character)
        return when {
            upper in 'A'.code..'Z'.code -> (upper - 'A'.code + 1).toByte()
            character == ' '.code || character == '@'.code -> 0
            character == '['.code -> 27
            character == '\\'.code -> 28
            character == ']'.code -> 29
            character == '^'.code || character == '~'.code -> 30
            character == '_'.code || character == '/'.code -> 31
            character == '?'.code -> 127.toByte()
            else -> null
        }
    }

    private fun specialKeySequence(
        keyCode: Int,
        applicationCursorKeys: Boolean,
    ): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> bytes("\r")
        KeyEvent.KEYCODE_DEL -> byteArrayOf(DELETE) // Backspace key
        KeyEvent.KEYCODE_TAB -> bytes("\t")
        KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(ESC)
        KeyEvent.KEYCODE_DPAD_UP -> cursor("A", applicationCursorKeys)
        KeyEvent.KEYCODE_DPAD_DOWN -> cursor("B", applicationCursorKeys)
        KeyEvent.KEYCODE_DPAD_RIGHT -> cursor("C", applicationCursorKeys)
        KeyEvent.KEYCODE_DPAD_LEFT -> cursor("D", applicationCursorKeys)
        KeyEvent.KEYCODE_MOVE_HOME -> cursor("H", applicationCursorKeys)
        KeyEvent.KEYCODE_MOVE_END -> cursor("F", applicationCursorKeys)
        KeyEvent.KEYCODE_INSERT -> bytes("[2~")
        KeyEvent.KEYCODE_FORWARD_DEL -> bytes("[3~")
        KeyEvent.KEYCODE_PAGE_UP -> bytes("[5~")
        KeyEvent.KEYCODE_PAGE_DOWN -> bytes("[6~")
        KeyEvent.KEYCODE_F1 -> bytes("OP")
        KeyEvent.KEYCODE_F2 -> bytes("OQ")
        KeyEvent.KEYCODE_F3 -> bytes("OR")
        KeyEvent.KEYCODE_F4 -> bytes("OS")
        KeyEvent.KEYCODE_F5 -> bytes("[15~")
        KeyEvent.KEYCODE_F6 -> bytes("[17~")
        KeyEvent.KEYCODE_F7 -> bytes("[18~")
        KeyEvent.KEYCODE_F8 -> bytes("[19~")
        KeyEvent.KEYCODE_F9 -> bytes("[20~")
        KeyEvent.KEYCODE_F10 -> bytes("[21~")
        KeyEvent.KEYCODE_F11 -> bytes("[23~")
        KeyEvent.KEYCODE_F12 -> bytes("[24~")
        else -> null
    }

    /** DECCKM: normal mode sends CSI ("["), application mode SS3. */
    private fun cursor(letter: String, applicationMode: Boolean): ByteArray =
        bytes(if (applicationMode) "O$letter" else "[$letter")

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private const val ESC: Byte = 0x1B
    private const val DELETE: Byte = 0x7F
}
