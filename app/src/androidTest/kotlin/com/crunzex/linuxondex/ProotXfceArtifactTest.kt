package com.crunzex.linuxondex

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crunzex.linuxondex.display.vnc.RfbClient
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.engine.proot.ProotEngine
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.PreparedImageConfig
import com.crunzex.linuxondex.vm.PreparedImageFormat
import com.crunzex.linuxondex.vm.ScreenResolution
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Imports and starts the release GNOME-like XFCE rootfs, then verifies the
 * desktop, accelerated graphics, and shared-display paths users receive.
 *
 * Push the artifact before running:
 * ```
 * adb push dist/v1.1.13/linux-on-dex-ubuntu-24.04-proot-gnome-like-xfce-arm64.rootfs.tar.gz \
 *   /sdcard/Android/data/com.crunzex.linuxondex/files/vm-images/
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ProotXfceArtifactTest {

    private lateinit var paths: VmPaths
    private lateinit var engine: ProotEngine
    private var archive: File? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        paths = VmPaths(context)
        paths.createRuntimeDirectories()
        archive = paths.vmImagesDir?.listFiles()?.firstOrNull {
            it.name == ROOTFS_ARCHIVE_NAME
        }
        engine = ProotEngine(paths, PayloadInstaller(context, paths))
    }

    @Test
    fun gnomeLikeDesktopStartsWithAcceleratedGraphicsAndSharedDisplay() = runBlocking {
        val rootfsArchive = archive
        assumeTrue("XFCE release rootfs not pushed, skipping", rootfsArchive?.exists() == true)
        requireNotNull(rootfsArchive)

        val config = VmConfig(
            id = "xfce-artifact-test",
            name = "Ubuntu GNOME-like artifact test",
            display = com.crunzex.linuxondex.vm.DisplayConfig(
                resolution = ScreenResolution.WXGA_1280_800,
                vncDisplayNumber = 8,
            ),
            preparedImage = PreparedImageConfig(
                displayName = "Ubuntu 24.04 GNOME-like XFCE",
                diskImagePath = rootfsArchive.absolutePath,
                format = PreparedImageFormat.PROOT_ROOTFS,
            ),
        )

        engine.start(config)
        try {
            val running = engine.state.value
            assertTrue("desktop engine should be running, got $running", running is VmState.Running)
            assertTrue(
                "desktop should publish VNC port ${config.vncPort}, got $running",
                (running as VmState.Running).vncPort == config.vncPort,
            )

            val console = engine.openSerialConsole()
                ?: throw AssertionError("no shell from running XFCE rootfs")
            console.use {
                val validationCommand =
                    "dex-gpu eglinfo -B -p surfaceless 2>&1; " +
                        "printf 'EGL_STATUS=%s\\n' \"$?\"; " +
                        "if pgrep -x xfsettingsd >/dev/null && " +
                        "pgrep -x xfwm4 >/dev/null && " +
                        "pgrep -x xfce4-panel >/dev/null && " +
                        "! pgrep -x xfdesktop >/dev/null; " +
                        "then printf 'SESSION_' && printf 'STATUS=LEAN\\n'; " +
                        "else printf 'SESSION_' && printf 'STATUS=INVALID\\n'; fi; " +
                        "if grep -q -- '-AlwaysShared' /usr/local/bin/dex-desktop; " +
                        "then printf 'VNC_' && printf 'STATUS=SHARED\\n'; " +
                        "else printf 'VNC_' && printf 'STATUS=INVALID\\n'; fi; " +
                        "if grep -q 'max_frame_rate=240' /usr/local/bin/dex-desktop; " +
                        "then printf 'FPS_' && printf 'STATUS=240\\n'; " +
                        "else printf 'FPS_' && printf 'STATUS=INVALID\\n'; fi; " +
                        "if command -v code >/dev/null && test -x /usr/share/code/code; " +
                        "then printf 'CODE_' && printf 'STATUS=INSTALLED\\n'; " +
                        "else printf 'CODE_' && printf 'STATUS=MISSING\\n'; fi; " +
                        "if command -v firefox >/dev/null && test -x /usr/lib/firefox/firefox; " +
                        "then printf 'FIREFOX_' && printf 'STATUS=INSTALLED\\n'; " +
                        "else printf 'FIREFOX_' && printf 'STATUS=MISSING\\n'; fi; " +
                        "if grep -q 'GALLIUM_DRIVER=llvmpipe' /usr/local/bin/dex-desktop && " +
                        "test -x /usr/local/bin/dex-gpu; " +
                        "then printf 'GRAPHICS_' && printf 'STATUS=ISOLATED\\n'; fi; " +
                        "if grep -q '1.1.13' /etc/linux-on-dex-release; " +
                        "then printf 'RELEASE_' && printf 'VERSION=1.1.13\\n'; fi; " +
                        "/usr/local/bin/dex-name-groups 2>/dev/null || true; " +
                        "if groups 2>&1 | grep -q 'cannot find name'; " +
                        "then printf 'GROUP_' && printf 'STATUS=INVALID\\n'; " +
                        "else printf 'GROUP_' && printf 'STATUS=NAMED\\n'; fi; " +
                        "printf 'XFCE_' && printf 'ARTIFACT_OK\\n'\n"
                console.write(validationCommand.toByteArray())
                val output = readUntil(console, "XFCE_ARTIFACT_OK", timeoutMs = 30_000)
                assertTrue("XFCE artifact probe did not complete: $output", output.contains("XFCE_ARTIFACT_OK"))
                assertTrue("eglinfo failed inside Ubuntu: $output", output.contains("EGL_STATUS=0"))
                assertTrue(
                    "Ubuntu Mesa did not render through the native virgl bridge: $output",
                    output.contains("OpenGL core profile renderer: virgl", ignoreCase = true),
                )
                assertTrue(
                    "GNOME-like session started unexpected services: $output",
                    output.contains("SESSION_STATUS=LEAN"),
                )
                assertTrue(
                    "TigerVNC is not configured for shared viewers: $output",
                    output.contains("VNC_STATUS=SHARED"),
                )
                assertTrue("TigerVNC is not capped at 240 FPS: $output", output.contains("FPS_STATUS=240"))
                assertTrue("VS Code is not installed: $output", output.contains("CODE_STATUS=INSTALLED"))
                assertTrue("Firefox is not installed: $output", output.contains("FIREFOX_STATUS=INSTALLED"))
                assertTrue(
                    "desktop and native application graphics are not isolated: $output",
                    output.contains("GRAPHICS_STATUS=ISOLATED"),
                )
                assertTrue("release metadata is stale: $output", output.contains("RELEASE_VERSION=1.1.13"))
                assertTrue("Android group IDs are unnamed: $output", output.contains("GROUP_STATUS=NAMED"))
            }

            verifyTwoViewersRemainConnected(config.vncPort)
            verifyInputReachesTheXDesktop(config.vncPort)
        } finally {
            engine.stop(gracePeriod = 5.seconds)
        }
    }

    /** RFB packets must become real X11 pointer, button and keyboard events. */
    private fun verifyInputReachesTheXDesktop(vncPort: Int) {
        val frameCount = AtomicInteger(0)
        val disconnectReason = AtomicReference<String?>(null)
        val client = createFrameCountingClient(vncPort, frameCount, disconnectReason)
        var reader: Thread? = null
        val console = engine.openSerialConsole()
            ?: throw AssertionError("no shell for VNC input validation")

        try {
            client.connect()
            reader = startReader("input", client)
            assertTrue("input viewer received no frame", waitUntil { frameCount.get() > 0 })

            console.use {
                val startEventMonitor =
                    "rm -f /tmp/dex-xev.log /tmp/dex-xev.pid /tmp/dex-xev-window; " +
                        "DISPLAY=:1 stdbuf -oL xev -geometry 300x200+40+40 " +
                        ">/tmp/dex-xev.log 2>&1 & echo $! >/tmp/dex-xev.pid; " +
                        "attempt=0; while ! DISPLAY=:1 xdotool search --name 'Event Tester' " +
                        ">/tmp/dex-xev-window 2>/dev/null; do attempt=${'$'}((attempt+1)); " +
                        "[ ${'$'}attempt -lt 50 ] || break; sleep 0.1; done; " +
                        "window=$(head -1 /tmp/dex-xev-window); " +
                        "if [ -n \"${'$'}window\" ]; " +
                        "then printf 'XEV_' && printf 'WINDOW=READY\\n'; fi; " +
                        "printf 'XEV_' && printf 'SETUP_DONE\\n'\n"
                it.write(startEventMonitor.toByteArray())
                val readyOutput = readUntil(it, "XEV_SETUP_DONE", timeoutMs = 15_000)
                assertTrue("xev setup did not complete: $readyOutput", readyOutput.contains("XEV_SETUP_DONE"))
                assertTrue("xev window was not mapped: $readyOutput", readyOutput.contains("XEV_WINDOW=READY"))

                Thread.sleep(INPUT_MONITOR_SETTLE_MILLIS)
                // The first real RFB click focuses the mapped test window. This
                // matches DeX usage and avoids relying on optional EWMH focus
                // hints that lightweight window managers need not implement.
                assertTrue("RFB pointer move was rejected", client.sendPointerEvent(100, 100, 0))
                assertTrue("RFB left press was rejected", client.sendPointerEvent(100, 100, 1))
                assertTrue("RFB left release was rejected", client.sendPointerEvent(100, 100, 0))
                assertTrue("RFB key press was rejected", client.sendKeyEvent('a'.code, true))
                assertTrue("RFB key release was rejected", client.sendKeyEvent('a'.code, false))
                assertTrue("RFB final pointer move was rejected", client.sendPointerEvent(321, 222, 0))

                val inspectEvents =
                    "attempt=0; until { grep -q 'ButtonPress event' /tmp/dex-xev.log && " +
                        "grep -q 'ButtonRelease event' /tmp/dex-xev.log && " +
                        "grep -q 'KeyPress event' /tmp/dex-xev.log && " +
                        "grep -q 'KeyRelease event' /tmp/dex-xev.log; }; do " +
                        "attempt=${'$'}((attempt+1)); [ ${'$'}attempt -lt 50 ] || break; sleep 0.1; done; " +
                        "DISPLAY=:1 xdotool getmouselocation --shell; " +
                        "grep -q 'ButtonPress event' /tmp/dex-xev.log && printf 'BUTTON_' && printf 'PRESS=YES\\n'; " +
                        "grep -q 'ButtonRelease event' /tmp/dex-xev.log && printf 'BUTTON_' && printf 'RELEASE=YES\\n'; " +
                        "grep -q 'KeyPress event' /tmp/dex-xev.log && printf 'KEY_' && printf 'PRESS=YES\\n'; " +
                        "grep -q 'KeyRelease event' /tmp/dex-xev.log && printf 'KEY_' && printf 'RELEASE=YES\\n'; " +
                        "kill $(cat /tmp/dex-xev.pid) 2>/dev/null || true; " +
                        "printf 'VNC_' && printf 'INPUT_DONE\\n'\n"
                it.write(inspectEvents.toByteArray())
                val output = readUntil(it, "VNC_INPUT_DONE", timeoutMs = 15_000)

                assertTrue("RFB pointer X did not reach X11: $output", output.contains("X=321"))
                assertTrue("RFB pointer Y did not reach X11: $output", output.contains("Y=222"))
                assertTrue("left press did not reach X11: $output", output.contains("BUTTON_PRESS=YES"))
                assertTrue("left release did not reach X11: $output", output.contains("BUTTON_RELEASE=YES"))
                assertTrue("key press did not reach X11: $output", output.contains("KEY_PRESS=YES"))
                assertTrue("key release did not reach X11: $output", output.contains("KEY_RELEASE=YES"))
            }
        } finally {
            client.close()
            reader?.join(READER_JOIN_TIMEOUT_MILLIS)
            console.close()
        }
    }

    private fun verifyTwoViewersRemainConnected(vncPort: Int) {
        val firstFrames = AtomicInteger(0)
        val secondFrames = AtomicInteger(0)
        val firstDisconnect = AtomicReference<String?>(null)
        val secondDisconnect = AtomicReference<String?>(null)
        val firstClient = createFrameCountingClient(vncPort, firstFrames, firstDisconnect)
        val secondClient = createFrameCountingClient(vncPort, secondFrames, secondDisconnect)
        var firstReader: Thread? = null
        var secondReader: Thread? = null

        try {
            firstClient.connect()
            firstReader = startReader("first", firstClient)
            assertTrue(
                "first VNC viewer received no frame; disconnect=${firstDisconnect.get()}",
                waitUntil { firstFrames.get() > 0 },
            )

            secondClient.connect()
            secondReader = startReader("second", secondClient)
            assertTrue(
                "second VNC viewer received no frame; disconnect=${secondDisconnect.get()}",
                waitUntil { secondFrames.get() > 0 },
            )

            val framesBeforeProbe = firstFrames.get()
            firstClient.requestFramebufferUpdate(incremental = false)
            assertTrue(
                "second viewer evicted the first; disconnect=${firstDisconnect.get()}",
                waitUntil { firstFrames.get() > framesBeforeProbe },
            )
        } finally {
            firstClient.close()
            secondClient.close()
            firstReader?.join(READER_JOIN_TIMEOUT_MILLIS)
            secondReader?.join(READER_JOIN_TIMEOUT_MILLIS)
        }
    }

    private fun createFrameCountingClient(
        vncPort: Int,
        frameCount: AtomicInteger,
        disconnectReason: AtomicReference<String?>,
    ) = RfbClient(
        host = "127.0.0.1",
        port = vncPort,
        listener = object : RfbClient.Listener {
            override fun onFramebufferReady(bitmap: Bitmap) = Unit

            override fun onFrameUpdated() {
                frameCount.incrementAndGet()
            }

            override fun onDisconnected(reason: String) {
                disconnectReason.set(reason)
            }
        },
    )

    private fun startReader(label: String, client: RfbClient): Thread = thread(
        start = true,
        name = "artifact-vnc-$label",
    ) {
        client.runReadLoop()
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + VIEWER_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(VIEWER_POLL_MILLIS)
        }
        return condition()
    }

    private fun readUntil(
        console: SerialConsoleConnection,
        marker: String,
        timeoutMs: Long,
    ): String {
        val collected = StringBuilder()
        val buffer = ByteArray(4_096)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val count = console.read(buffer)
            if (count < 0) break
            collected.append(String(buffer, 0, count))
            if (collected.contains(marker)) break
        }
        return collected.toString()
    }

    companion object {
        private const val ROOTFS_ARCHIVE_NAME =
            "linux-on-dex-ubuntu-24.04-proot-gnome-like-xfce-arm64.rootfs.tar.gz"
        private const val VIEWER_TIMEOUT_MILLIS = 10_000L
        private const val VIEWER_POLL_MILLIS = 50L
        private const val READER_JOIN_TIMEOUT_MILLIS = 2_000L
        private const val INPUT_MONITOR_SETTLE_MILLIS = 250L
    }
}
