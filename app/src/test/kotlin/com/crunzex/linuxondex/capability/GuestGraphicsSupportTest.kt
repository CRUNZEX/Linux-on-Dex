package com.crunzex.linuxondex.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestGraphicsSupportTest {

    @Test
    fun `packaged renderer exposes the contained virgl route`() {
        val verdict = GuestGraphicsSupport.measure(nativeVirglRendererAvailable = true)

        assertEquals(GuestGraphicsSupport.Renderer.HARDWARE_VIRGL, verdict.renderer)
        assertTrue(verdict.isHardware)
        assertTrue(GuestGraphicsSupport.explain(verdict).contains("falls back"))
    }
}
