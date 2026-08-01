package com.crunzex.linuxondex.engine.proot

/**
 * Translates keystrokes for a shell that reads from a pipe instead of a
 * terminal device.
 *
 * The Enter key sends carriage return, which is what a serial console needs:
 * the tty's line discipline turns it into the newline the shell reads. A
 * PRoot session has no tty — the app talks to the shell over its stdin pipe
 * — so nothing performs that conversion and a shell waiting for a line never
 * sees the end of one. Every typed command would simply be ignored.
 */
internal object PipeLineEndings {

    private const val CARRIAGE_RETURN = '\r'.code.toByte()
    private const val LINE_FEED = '\n'.code.toByte()

    /**
     * [keystrokes] with every carriage return turned into a newline, and
     * CR LF pairs collapsed so a paste does not submit twice.
     *
     * Returns the original array when there is nothing to translate, which
     * is the common case (ordinary characters).
     */
    fun forShellPipe(keystrokes: ByteArray): ByteArray {
        if (keystrokes.none { it == CARRIAGE_RETURN }) return keystrokes

        val translated = ArrayList<Byte>(keystrokes.size)
        keystrokes.forEachIndexed { index, byte ->
            when {
                byte == CARRIAGE_RETURN -> {
                    val followedByLineFeed = keystrokes.getOrNull(index + 1) == LINE_FEED
                    if (!followedByLineFeed) translated.add(LINE_FEED)
                }
                else -> translated.add(byte)
            }
        }
        return translated.toByteArray()
    }
}
