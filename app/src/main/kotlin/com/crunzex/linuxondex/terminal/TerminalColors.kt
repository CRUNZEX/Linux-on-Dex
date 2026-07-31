package com.crunzex.linuxondex.terminal

/**
 * The xterm 256-colour palette and the terminal's default colours, resolved
 * to ARGB ints at write time so rows store plain colours and the renderer
 * never needs a palette lookup.
 */
object TerminalColors {

    /** Near-white on near-black: classic terminal, matches the app theme. */
    const val DEFAULT_FOREGROUND = 0xFFD6E4D8.toInt()
    const val DEFAULT_BACKGROUND = 0xFF101014.toInt()

    /** Cursor block colour when focused. */
    const val CURSOR = 0xFF8AB4F8.toInt()

    /**
     * ANSI colours 0-15 follow the widely-used "Tango dark" values, chosen
     * for legibility on the dark background above.
     *
     * Colour 0 is the one deliberate change: it is the terminal's own
     * background rather than Tango's dark grey, exactly as every dark
     * terminal scheme defines black. Anything else and "black background"
     * is a *visible* rectangle — and because erasing and scrolling fill
     * with the current background, a program that leaves `SGR 40` set
     * paints every line that scrolls in afterwards. Alpine's boot menu
     * ends on `ESC[0m ESC[37m ESC[40m` and never resets, so that band
     * followed the whole boot down the screen.
     */
    private val ANSI_16 = intArrayOf(
        DEFAULT_BACKGROUND, // 0 black — the background, so "on black" is invisible
        0xFFCC0000.toInt(), // 1 red
        0xFF4E9A06.toInt(), // 2 green
        0xFFC4A000.toInt(), // 3 yellow
        0xFF3465A4.toInt(), // 4 blue
        0xFF75507B.toInt(), // 5 magenta
        0xFF06989A.toInt(), // 6 cyan
        0xFFD3D7CF.toInt(), // 7 white
        0xFF555753.toInt(), // 8 bright black
        0xFFEF2929.toInt(), // 9 bright red
        0xFF8AE234.toInt(), // 10 bright green
        0xFFFCE94F.toInt(), // 11 bright yellow
        0xFF729FCF.toInt(), // 12 bright blue
        0xFFAD7FA8.toInt(), // 13 bright magenta
        0xFF34E2E2.toInt(), // 14 bright cyan
        0xFFEEEEEC.toInt(), // 15 bright white
    )

    /** The standard xterm cube uses these six intensity levels per channel.
     *  Declared before [PALETTE_256], whose initializer reads it. */
    private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

    /** Full 256-entry palette: 16 ANSI + 6×6×6 cube + 24 greys. */
    private val PALETTE_256 = IntArray(256) { index ->
        when {
            index < 16 -> ANSI_16[index]
            index < 232 -> colourCubeEntry(index - 16)
            else -> greyRampEntry(index - 232)
        }
    }

    fun indexed(index: Int): Int = PALETTE_256[index.coerceIn(0, 255)]

    fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or
            ((red and 0xFF) shl 16) or
            ((green and 0xFF) shl 8) or
            (blue and 0xFF)

    private fun colourCubeEntry(cubeIndex: Int): Int {
        val red = CUBE_LEVELS[cubeIndex / 36]
        val green = CUBE_LEVELS[(cubeIndex / 6) % 6]
        val blue = CUBE_LEVELS[cubeIndex % 6]
        return rgb(red, green, blue)
    }

    private fun greyRampEntry(step: Int): Int {
        val level = 8 + step * 10
        return rgb(level, level, level)
    }
}
