package com.crunzex.linuxondex.display.vnc

import org.junit.Assert.assertEquals
import org.junit.Test

class VncViewGeometryTest {

    @Test
    fun `fit mode letterboxes with one uniform scale`() {
        // 1280x800 frame in a 1600x2298 portrait window: width is the
        // limiting axis, so both scales are 1600/1280 = 1.25.
        val scales = VncViewGeometry.drawScales(1600, 2298, 1280, 800, stretchToFill = false)

        assertEquals(1.25f, scales.scaleX, 0.0001f)
        assertEquals(1.25f, scales.scaleY, 0.0001f)
    }

    @Test
    fun `stretch mode fills both axes independently`() {
        val scales = VncViewGeometry.drawScales(1600, 2298, 1280, 800, stretchToFill = true)

        assertEquals(1.25f, scales.scaleX, 0.0001f)
        assertEquals(2298f / 800f, scales.scaleY, 0.0001f)
    }

    @Test
    fun `fit never up-crops - the taller frame is limited by height`() {
        val scales = VncViewGeometry.drawScales(1600, 900, 1280, 1024, stretchToFill = false)

        assertEquals(900f / 1024f, scales.scaleX, 0.0001f)
        assertEquals(900f / 1024f, scales.scaleY, 0.0001f)
    }

    @Test
    fun `degenerate sizes return identity instead of dividing by zero`() {
        listOf(
            VncViewGeometry.drawScales(0, 100, 1280, 800, false),
            VncViewGeometry.drawScales(100, 0, 1280, 800, true),
            VncViewGeometry.drawScales(100, 100, 0, 800, false),
            VncViewGeometry.drawScales(100, 100, 1280, 0, true),
        ).forEach { scales ->
            assertEquals(1f, scales.scaleX, 0.0f)
            assertEquals(1f, scales.scaleY, 0.0f)
        }
    }
}
