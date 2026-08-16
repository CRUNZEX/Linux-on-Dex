package com.crunzex.linuxondex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.engine.proot.AppManagedNativeX11Server
import com.crunzex.linuxondex.engine.proot.GraphicsBridgePolicy
import com.crunzex.linuxondex.engine.proot.ProotEngine
import com.crunzex.linuxondex.engine.proot.RendererStage
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.DisplayConfig
import com.crunzex.linuxondex.vm.PreparedImageConfig
import com.crunzex.linuxondex.vm.PreparedImageFormat
import com.crunzex.linuxondex.vm.ScreenResolution
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the GPU-offloaded desktop pipeline end to end on the emulator:
 * the whole GNOME session renders through the Android virgl bridge, terminal
 * shells adopt the session renderer, and a bridge death mid-session walks
 * the complete safety chain (supervisor abandons virpipe → marker → the
 * next boot is sticky on the CPU renderer).
 *
 * The engine runs with [GraphicsBridgePolicy.ANY_WORKING_RENDERER] because
 * the emulator has no hardware-backed EGL at all — its translator lacks
 * surfaceless contexts and bundled ANGLE lands on CPU lavapipe. The guest
 * pipeline is identical either way; only the final rasterizer differs on
 * real hardware. Production keeps HARDWARE_BACKED_ONLY.
 */
@RunWith(AndroidJUnit4::class)
class GpuDesktopPipelineTest {

    private lateinit var paths: VmPaths
    private lateinit var engine: ProotEngine
    private var archive: File? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        paths = VmPaths(context).also { it.createRuntimeDirectories() }
        archive = paths.vmImagesDir?.resolve(ROOTFS_ARCHIVE_NAME)
        engine = ProotEngine(
            paths = paths,
            payloadInstaller = PayloadInstaller(context, paths),
            nativeX11Server = AppManagedNativeX11Server(context, paths),
            bridgePolicy = GraphicsBridgePolicy.ANY_WORKING_RENDERER,
        )
    }

    @Test
    fun gpuDesktopRunsAndFallsBackSafely() = runBlocking {
        val rootfsArchive = archive
        assumeTrue("GNOME rootfs not pushed, skipping", rootfsArchive?.exists() == true)
        requireNotNull(rootfsArchive)

        // A previous run may have left a downgrade or failure marker behind;
        // this test owns the whole ladder walk, so start clean.
        paths.rendererStageFile.delete()
        paths.prootGuestTmpDir.resolve(GPU_FAILURE_MARKER_NAME).delete()

        val config = desktopConfig(rootfsArchive)

        engine.start(config)
        try {
            assertTrue("engine should be running", engine.state.value is VmState.Running)
            simulateAttachedViewer()
            verifyDesktopRendersOnVirpipe()
            verifyBridgeDeathIsAbandonedInSession()
        } finally {
            engine.stop(gracePeriod = 5.seconds)
        }

        // The supervisor's verdict must have become sticky: the next boot
        // starts straight on the CPU renderer and clears the marker.
        engine.start(config)
        try {
            simulateAttachedViewer()
            val stageFileText = paths.rendererStageFile.readText()
            assertTrue(
                "renderer ladder should remember NATIVE, was: $stageFileText",
                stageFileText.startsWith(RendererStage.NATIVE.name),
            )
            assertFalse(
                "the failure marker must be consumed at start",
                paths.prootGuestTmpDir.resolve(GPU_FAILURE_MARKER_NAME).exists(),
            )
            val environment = readGnomeShellEnvironment()
            assertTrue(
                "after the sticky downgrade GNOME must run on llvmpipe: $environment",
                environment.contains("GALLIUM_DRIVER=llvmpipe"),
            )
        } finally {
            engine.stop(gracePeriod = 5.seconds)
        }
    }

    /**
     * GPU sessions hold the first GNOME start until a viewer attaches (or a
     * grace period passes) so the attach-resize never lands beneath a live
     * compositor. This headless test stands in for the viewer by writing
     * the same marker the app writes on viewer arrival.
     */
    private fun simulateAttachedViewer() {
        paths.prootGuestTmpDir.resolve(VIEWER_ATTACHED_MARKER_NAME).writeText("")
    }

    /** GNOME, terminals and the published renderer file all say virpipe. */
    private fun verifyDesktopRendersOnVirpipe() {
        val environment = readGnomeShellEnvironment()
        assertTrue(
            "gnome-shell must render through the GPU bridge: $environment",
            environment.contains("GALLIUM_DRIVER=virpipe"),
        )
        assertTrue(
            "Mutter must talk GLES to the GLES-backed bridge: $environment",
            environment.contains("COGL_DRIVER=gles2"),
        )
        assertFalse(
            "no forced GL version: lying about bridge capabilities crashes " +
                "compositors (Tab S9 SIGSEGV): $environment",
            environment.contains("MESA_GL_VERSION_OVERRIDE"),
        )
        assertTrue(
            "GTK4 apps should draw with GL in GPU sessions: $environment",
            environment.contains("GSK_RENDERER=gl"),
        )

        val output = executeGuestCommand(
            command = "printf 'TERM_DRIVER=%s\\n' \"\$GALLIUM_DRIVER\"; " +
                "cat /run/linux-on-dex/renderer.env 2>/dev/null; " +
                "sleep $STABILITY_SOAK_SECONDS; " +
                "pgrep -x gnome-shell >/dev/null && printf 'SHELL_' && printf 'ALIVE\\n'; " +
                "printf 'TERM_CHECK_' && printf 'DONE\\n'\n",
            marker = "TERM_CHECK_DONE",
        )
        assertTrue(
            "login shells must adopt the session renderer: $output",
            output.contains("TERM_DRIVER=virpipe"),
        )
        assertTrue(
            "gnome-shell should survive a $STABILITY_SOAK_SECONDS-second soak: $output",
            output.contains("SHELL_ALIVE"),
        )
    }

    /**
     * Kills the bridge socket, then the compositor. The supervisor's next
     * restart must notice the dead bridge, continue on llvmpipe, and leave
     * the failure marker for the app.
     */
    private fun verifyBridgeDeathIsAbandonedInSession() {
        assertTrue("bridge socket should exist while running", paths.virglSocket.exists())
        assertTrue("could not remove the bridge socket", paths.virglSocket.delete())

        // SIGKILL, not SIGTERM: a graceful TERM lets GNOME log out cleanly
        // and the supervisor rightly ends the whole session on a clean exit.
        // The abandonment path exists for dirty deaths.
        val output = executeGuestCommand(
            command = "pkill -KILL -x gnome-shell; " +
                "for i in \$(seq 1 40); do " +
                "NEW_PID=\$(pgrep -x gnome-shell | head -1); " +
                "if [ -n \"\$NEW_PID\" ] && tr '\\0' '\\n' < /proc/\$NEW_PID/environ " +
                "| grep -q 'GALLIUM_DRIVER=llvmpipe'; then break; fi; sleep 1; done; " +
                "tr '\\0' '\\n' < /proc/\$(pgrep -x gnome-shell | head -1)/environ " +
                "| grep --color=never GALLIUM_DRIVER; " +
                "test -f /tmp/$GPU_FAILURE_MARKER_NAME && printf 'MARKER_' && printf 'WRITTEN\\n'; " +
                "printf 'ABANDON_CHECK_' && printf 'DONE\\n'\n",
            marker = "ABANDON_CHECK_DONE",
            timeoutMillis = 90_000L,
        )
        assertTrue(
            "the restarted GNOME must run on llvmpipe: $output",
            output.contains("GALLIUM_DRIVER=llvmpipe"),
        )
        assertTrue(
            "the supervisor must record the abandonment for the app: $output",
            output.contains("MARKER_WRITTEN"),
        )
    }

    private fun readGnomeShellEnvironment(): String = executeGuestCommand(
        command = "for i in \$(seq 1 60); do pgrep -x gnome-shell >/dev/null && break; sleep 1; done; " +
            "tr '\\0' '\\n' < /proc/\$(pgrep -x gnome-shell | head -1)/environ " +
            "| grep --color=never -E 'GALLIUM|MESA_GL|GSK|COGL'; " +
            "printf 'ENV_READ_' && printf 'DONE\\n'\n",
        marker = "ENV_READ_DONE",
        timeoutMillis = 90_000L,
    )

    private fun desktopConfig(rootfsArchive: File) = VmConfig(
        id = "gpu-pipeline-test",
        name = "GPU desktop pipeline test",
        display = DisplayConfig(
            resolution = ScreenResolution.WXGA_1280_800,
            vncDisplayNumber = 9,
        ),
        preparedImage = PreparedImageConfig(
            displayName = "Ubuntu 24.04 GNOME Shell",
            diskImagePath = rootfsArchive.absolutePath,
            format = PreparedImageFormat.PROOT_ROOTFS,
        ),
    )

    private fun executeGuestCommand(
        command: String,
        marker: String,
        timeoutMillis: Long = 60_000L,
    ): String {
        val console = engine.openSerialConsole()
            ?: throw AssertionError("guest shell unavailable")
        return console.use {
            Thread.sleep(CONSOLE_STARTUP_MILLIS)
            it.write(CONSOLE_SIZE_RESPONSES.toByteArray())
            Thread.sleep(CONSOLE_RESPONSE_SETTLE_MILLIS)
            it.write(command.toByteArray())
            readUntil(it, marker, timeoutMillis)
        }
    }

    private fun readUntil(
        console: SerialConsoleConnection,
        marker: String,
        timeoutMs: Long,
    ): String {
        val output = StringBuffer()
        val readFinished = AtomicBoolean(false)
        val reader = thread(start = true, isDaemon = true, name = "gpu-pipeline-reader") {
            val buffer = ByteArray(4_096)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val count = console.read(buffer)
                    if (count < 0) break
                    output.append(String(buffer, 0, count))
                    if (output.contains(marker)) break
                }
            } catch (_: Exception) {
                // Closing the owned console interrupts its blocking read.
            } finally {
                readFinished.set(true)
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!readFinished.get() && System.currentTimeMillis() < deadline) {
            if (output.contains(marker)) break
            Thread.sleep(25L)
        }
        reader.interrupt()
        return output.toString()
    }

    companion object {
        private const val ROOTFS_ARCHIVE_NAME =
            "linux-on-dex-ubuntu-24.04-proot-gnome-arm64.rootfs.tar.gz"
        private const val GPU_FAILURE_MARKER_NAME = ".dex-gpu-desktop-failed"
        private const val VIEWER_ATTACHED_MARKER_NAME = ".dex-viewer-attached"
        private const val STABILITY_SOAK_SECONDS = 20
        private const val CONSOLE_STARTUP_MILLIS = 1_500L
        private const val CONSOLE_RESPONSE_SETTLE_MILLIS = 100L
        private const val CONSOLE_SIZE_RESPONSES = "\u001B[24;80R\u001B[24;80R"
    }
}
