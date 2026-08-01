package com.crunzex.linuxondex.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PpmImageTest {

    private fun ppmBytes(
        width: Int,
        height: Int,
        header: String = "P6\n$width $height\n255\n",
        pixels: (index: Int) -> Triple<Int, Int, Int>,
    ): ByteArray {
        val body = ByteArray(width * height * 3)
        for (index in 0 until width * height) {
            val (red, green, blue) = pixels(index)
            body[index * 3] = red.toByte()
            body[index * 3 + 1] = green.toByte()
            body[index * 3 + 2] = blue.toByte()
        }
        return header.toByteArray() + body
    }

    @Test
    fun `decodes dimensions from the header`() {
        val bytes = ppmBytes(4, 2) { Triple(0, 0, 0) }

        val image = PpmImage.decode(ByteArrayInputStream(bytes))

        assertEquals(4, image.widthPx)
        assertEquals(2, image.heightPx)
        assertEquals(8, image.pixelCount)
    }

    @Test
    fun `header comments are skipped`() {
        val bytes = ppmBytes(2, 2, header = "P6\n# written by QEMU\n2 2\n255\n") {
            Triple(255, 255, 255)
        }

        val image = PpmImage.decode(ByteArrayInputStream(bytes))

        assertEquals(2, image.widthPx)
        assertEquals(1f, image.litPixelFraction(), 0.001f)
    }

    @Test
    fun `an all black frame reads as unlit`() {
        val bytes = ppmBytes(8, 8) { Triple(0, 0, 0) }

        val image = PpmImage.decode(ByteArrayInputStream(bytes))

        assertEquals(0f, image.litPixelFraction(), 0.001f)
    }

    @Test
    fun `a half white frame reads as half lit`() {
        val bytes = ppmBytes(10, 10) { index ->
            if (index < 50) Triple(255, 255, 255) else Triple(0, 0, 0)
        }

        val image = PpmImage.decode(ByteArrayInputStream(bytes))

        assertEquals(0.5f, image.litPixelFraction(), 0.001f)
    }

    @Test
    fun `a two tone text screen has a tiny colour count`() {
        // What a GRUB menu looks like: white glyphs on black.
        val bytes = ppmBytes(40, 40) { index ->
            if (index % 7 == 0) Triple(255, 255, 255) else Triple(0, 0, 0)
        }

        val image = PpmImage.decode(ByteArrayInputStream(bytes))

        assertTrue("expected few colours, got ${image.colourCount()}", image.colourCount() <= 4)
    }

    @Test
    fun `a graphical frame has a large colour count`() {
        // What a desktop looks like: a broad spread of distinct colours.
        val bytes = ppmBytes(64, 64) { index ->
            Triple((index * 3) % 256, (index * 5) % 256, (index * 7) % 256)
        }

        val image = PpmImage.decode(ByteArrayInputStream(bytes))

        assertTrue("expected many colours, got ${image.colourCount()}", image.colourCount() > 50)
    }

    @Test
    fun `a truncated capture still decodes what arrived`() {
        val complete = ppmBytes(4, 4) { Triple(200, 100, 50) }
        val truncated = complete.copyOf(complete.size - 12)

        val image = PpmImage.decode(ByteArrayInputStream(truncated))

        assertEquals(4, image.widthPx)
        assertTrue(image.litPixelFraction() > 0f)
    }

    @Test
    fun `a non ppm file is rejected`() {
        val notPpm = "GIF89a...".toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            PpmImage.decode(ByteArrayInputStream(notPpm))
        }
    }
}
