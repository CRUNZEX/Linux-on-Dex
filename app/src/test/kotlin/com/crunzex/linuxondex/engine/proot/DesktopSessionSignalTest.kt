package com.crunzex.linuxondex.engine.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopSessionSignalTest {

    @Test
    fun `a compositor restart line carries its exit code`() {
        val signal = DesktopSessionSignal.fromSupervisorLine(
            "[dex-desktop] GNOME exited with code 139; restarting in 1s"
        )
        assertEquals(DesktopSessionSignal.CompositorRestarting(exitCode = 139), signal)
    }

    @Test
    fun `a GPU abandonment line carries the supervisor's reason`() {
        val signal = DesktopSessionSignal.fromSupervisorLine(
            "[dex-desktop] GPU desktop abandoned: GNOME exited 139 within 16s " +
                "on the GPU renderer; continuing on llvmpipe"
        )
        assertEquals(
            DesktopSessionSignal.GpuDesktopAbandoned(
                reason = "GNOME exited 139 within 16s on the GPU renderer"
            ),
            signal,
        )
    }

    @Test
    fun `abandonment wins over the restart pattern when both could match`() {
        // The supervisor prints the abandonment before the restart line, but
        // a combined line must still classify as the more specific signal.
        val signal = DesktopSessionSignal.fromSupervisorLine(
            "[dex-desktop] GPU desktop abandoned: GNOME exited with code 139; " +
                "restarting soon; continuing on llvmpipe"
        )
        assertEquals(
            DesktopSessionSignal.GpuDesktopAbandoned(
                reason = "GNOME exited with code 139; restarting soon"
            ),
            signal,
        )
    }

    @Test
    fun `ordinary desktop and guest lines carry no signal`() {
        listOf(
            "[dex-desktop] starting the native GNOME Shell X11 session",
            "[dex-desktop] GNOME session ended cleanly",
            "dbus-daemon[123]: Activating service name='ca.desrt.dconf'",
            // The tag is required: a guest program echoing this text must
            // not trigger app-side reactions.
            "GNOME exited with code 139; restarting in 1s",
        ).forEach { line ->
            assertNull("no signal expected for: $line", DesktopSessionSignal.fromSupervisorLine(line))
        }
    }
}
