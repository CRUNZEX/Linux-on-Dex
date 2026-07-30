package com.crunzex.linuxondex.display

import java.io.File
import java.io.InputStream

/**
 * Decoder for the binary PPM (P6) images QEMU's `screendump` produces, plus
 * the few statistics needed to reason about what the guest is showing.
 *
 * Distinguishing a text-mode boot menu from a real graphical session is the
 * whole point: a GRUB menu uses a handful of colours, while a desktop paints
 * hundreds. [colourCount] makes that difference measurable instead of
 * relying on "the screen is not black", which every boot menu satisfies.
 */
class PpmImage(
    val widthPx: Int,
    val heightPx: Int,
    /** Raw RGB triples, row-major, `widthPx * heightPx * 3` bytes. */
    private val rgbBytes: ByteArray,
) {

    val pixelCount: Int get() = widthPx * heightPx

    /** Fraction of pixels that are not essentially black, in 0.0f..1.0f. */
    fun litPixelFraction(): Float {
        if (pixelCount == 0) return 0f
        var litPixels = 0
        forEachPixel { red, green, blue ->
            if (red + green + blue > DARK_PIXEL_SUM_THRESHOLD) litPixels++
        }
        return litPixels.toFloat() / pixelCount
    }

    /**
     * Number of distinct colours after quantising each channel to 4 bits.
     * Quantising ignores compression-style noise and anti-aliasing while
     * still separating "two-tone text screen" from "photographic desktop".
     */
    fun colourCount(): Int {
        val seen = HashSet<Int>()
        forEachPixel { red, green, blue ->
            val quantised = ((red and 0xF0) shl 4) or (green and 0xF0) or ((blue and 0xF0) shr 4)
            seen.add(quantised)
        }
        return seen.size
    }

    private inline fun forEachPixel(action: (red: Int, green: Int, blue: Int) -> Unit) {
        var index = 0
        while (index + 2 < rgbBytes.size) {
            action(
                rgbBytes[index].toInt() and 0xFF,
                rgbBytes[index + 1].toInt() and 0xFF,
                rgbBytes[index + 2].toInt() and 0xFF,
            )
            index += 3
        }
    }

    /** One-line summary for test output and diagnostics logs. */
    fun describe(): String = "${widthPx}x$heightPx, ${colourCount()} colours, " +
        "%.1f%% lit".format(litPixelFraction() * 100)

    companion object {
        private const val DARK_PIXEL_SUM_THRESHOLD = 60

        fun decode(file: File): PpmImage = file.inputStream().buffered().use(::decode)

        /**
         * Parses a binary PPM. The header is `P6`, then width, height and
         * max-value tokens separated by whitespace, with `#` comments
         * allowed between them.
         */
        fun decode(input: InputStream): PpmImage {
            val magic = readToken(input)
            require(magic == "P6") { "not a binary PPM image (magic: $magic)" }
            val width = readToken(input).toIntOrNull()
                ?: throw IllegalArgumentException("PPM width is not a number")
            val height = readToken(input).toIntOrNull()
                ?: throw IllegalArgumentException("PPM height is not a number")
            val maxValue = readToken(input).toIntOrNull()
                ?: throw IllegalArgumentException("PPM max value is not a number")
            require(width > 0 && height > 0) { "invalid PPM dimensions ${width}x$height" }
            require(maxValue == 255) { "only 8-bit PPM images are supported (max: $maxValue)" }

            val pixelBytes = ByteArray(width * height * 3)
            var filled = 0
            while (filled < pixelBytes.size) {
                val read = input.read(pixelBytes, filled, pixelBytes.size - filled)
                if (read < 0) break // tolerate a truncated capture; caller sees the stats
                filled += read
            }
            return PpmImage(width, height, pixelBytes.copyOf(filled))
        }

        /** Reads one whitespace-delimited token, skipping `#` comments. */
        private fun readToken(input: InputStream): String {
            val token = StringBuilder()
            var byte = input.read()
            while (byte >= 0) {
                val character = byte.toChar()
                when {
                    character == '#' -> {
                        while (byte >= 0 && byte.toChar() != '\n') byte = input.read()
                    }
                    character.isWhitespace() -> if (token.isNotEmpty()) return token.toString()
                    else -> token.append(character)
                }
                byte = input.read()
            }
            if (token.isEmpty()) throw IllegalArgumentException("unexpected end of PPM header")
            return token.toString()
        }
    }
}
