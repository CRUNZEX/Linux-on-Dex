package com.crunzex.linuxondex

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crunzex.linuxondex.display.vnc.RfbClient
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.engine.proot.ProotEngine
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.DisplayConfig
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/** Verifies the clean GNOME Shell artifact and its requested desktop apps. */
@RunWith(AndroidJUnit4::class)
class ProotGnomeArtifactTest {

    private lateinit var engine: ProotEngine
    private var archive: File? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = VmPaths(context).also { it.createRuntimeDirectories() }
        archive = paths.vmImagesDir?.resolve(ROOTFS_ARCHIVE_NAME)
        engine = ProotEngine(paths, PayloadInstaller(context, paths))
    }

    @Test
    fun realGnomeShellStartsWithCodeAndFirefox() = runBlocking {
        val rootfsArchive = archive
        assumeTrue("GNOME release rootfs not pushed, skipping", rootfsArchive?.exists() == true)
        requireNotNull(rootfsArchive)

        val config = VmConfig(
            id = "gnome-artifact-test",
            name = "Ubuntu GNOME Shell artifact test",
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

        engine.start(config)
        try {
            assertTrue("GNOME PRoot engine should be running", engine.state.value is VmState.Running)
            verifyGuestSession()
            verifyClosedConsolesReleaseTheirProcessTrees()
            verifyDesktopProducesFramesAndAcceptsInput(config.vncPort)
        } finally {
            engine.stop(gracePeriod = 5.seconds)
        }
    }

    /** Closed terminals must not leak PRoot, script, or bash processes. */
    private fun verifyClosedConsolesReleaseTheirProcessTrees() {
        // D-Bus activation continues briefly after GNOME first paints. Count
        // only after that legitimate startup growth has settled, otherwise
        // calendar/dconf helpers look like console-process leaks.
        Thread.sleep(CONSOLE_REAP_BASELINE_SETTLE_MILLIS)
        val processesBefore = readGuestProcessCount()
        repeat(CONSOLE_REAP_STRESS_COUNT) { iteration ->
            val marker = "CONSOLE_REAP_$iteration"
            val output = executeGuestCommand(
                command = "printf '$marker\\n'\n",
                marker = marker,
                timeoutMillis = CONSOLE_TIMEOUT_MILLIS,
            )
            assertTrue("console $iteration did not execute: $output", output.contains(marker))
        }
        val processesAfter = readGuestProcessCount()

        assertTrue(
            "closed consoles leaked guest processes: before=$processesBefore after=$processesAfter",
            processesAfter <= processesBefore + CONSOLE_PROCESS_COUNT_TOLERANCE,
        )
    }

    private fun readGuestProcessCount(): Int {
        val output = executeGuestCommand(
            command = "printf 'GUEST_' && printf 'PROCESS_COUNT=%s\\n' " +
                "\"\$(ps -eo pid= | wc -l | tr -d ' ')\"; " +
                "printf 'GUEST_' && printf 'COUNT_DONE\\n'\n",
            marker = "GUEST_COUNT_DONE",
            timeoutMillis = CONSOLE_TIMEOUT_MILLIS,
        )
        return GUEST_PROCESS_COUNT_PATTERN.find(output)?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("guest process count was unavailable: $output")
    }

    private fun verifyGuestSession() {
        val console = engine.openSerialConsole()
            ?: throw AssertionError("GNOME PRoot shell unavailable")
        console.use {
            // `script` is still wiring the pseudo-terminal immediately after
            // Process.start(). Sending bytes before its child bash exists can
            // be lost on slower first boots, so wait for that small handoff.
            Thread.sleep(CONSOLE_STARTUP_MILLIS)
            // Readline measures the terminal by moving to 999,999 and asking
            // for a cursor-position report. The production terminal emulator
            // answers it; this raw instrumentation pipe must do the same.
            it.write(CONSOLE_SIZE_RESPONSES.toByteArray())
            Thread.sleep(CONSOLE_RESPONSE_SETTLE_MILLIS)
            val command =
                "if pgrep -x gnome-shell >/dev/null && " +
                    "! pgrep -x gnome-flashback >/dev/null && " +
                    "! pgrep -x gnome-panel >/dev/null && ! pgrep -x openbox >/dev/null; " +
                    "then printf 'SESSION_' && printf 'STATUS=GNOME_SHELL\\n'; fi; " +
                    "/usr/local/bin/dex-name-groups 2>/dev/null || true; " +
                    "if ! groups 2>&1 | grep -q 'cannot find name'; " +
                    "then printf 'GROUP_' && printf 'STATUS=NAMED\\n'; fi; " +
                    "if command -v code >/dev/null && test -x /usr/share/code/code; " +
                    "then printf 'CODE_' && printf 'STATUS=INSTALLED\\n'; fi; " +
                    "if command -v firefox >/dev/null && test -x /usr/lib/firefox/firefox; " +
                    "then printf 'FIREFOX_' && printf 'STATUS=INSTALLED\\n'; fi; " +
                    "if grep -q 'MAX_FRAME_RATE=240' /usr/local/bin/dex-desktop; " +
                    "then printf 'FPS_' && printf 'STATUS=240\\n'; fi; " +
                    "grep -q '1.1.13' /etc/linux-on-dex-release && " +
                    "printf 'RELEASE_' && printf 'VERSION=1.1.13\\n'; " +
                    "if grep -q 'GALLIUM_DRIVER=llvmpipe' /usr/local/bin/dex-desktop && " +
                    "test -x /usr/local/bin/dex-gpu; " +
                    "then printf 'GRAPHICS_' && printf 'STATUS=ISOLATED\\n'; fi; " +
                    "old_shell=\$(pgrep -x gnome-shell | head -1); " +
                    "old_xvnc=\$(pgrep -x Xtigervnc | head -1); " +
                    "kill -KILL \"\$old_shell\"; " +
                    "new_shell=''; for attempt in \$(seq 1 15); do " +
                    "new_shell=\$(pgrep -x gnome-shell | head -1); " +
                    "if test -n \"\$new_shell\" && test \"\$new_shell\" != \"\$old_shell\"; " +
                    "then break; fi; sleep 1; done; " +
                    "new_xvnc=\$(pgrep -x Xtigervnc | head -1); " +
                    "if test -n \"\$new_shell\" && test \"\$new_shell\" != \"\$old_shell\" && " +
                    "test \"\$new_xvnc\" = \"\$old_xvnc\"; " +
                    "then printf 'RECOVERY_' && printf 'STATUS=GNOME_RESTARTED_XVNC_STABLE\\n'; fi; " +
                    "printf 'GNOME_' && printf 'ARTIFACT_OK\\n'\n"
            it.write(command.toByteArray())
            val output = readUntil(it, "GNOME_ARTIFACT_OK", CONSOLE_TIMEOUT_MILLIS)

            assertTrue("GNOME validation command timed out: $output", output.contains("GNOME_ARTIFACT_OK"))
            assertTrue("real GNOME Shell is not running cleanly: $output", output.contains("SESSION_STATUS=GNOME_SHELL"))
            assertTrue("Android groups remain unnamed: $output", output.contains("GROUP_STATUS=NAMED"))
            assertTrue("VS Code is missing: $output", output.contains("CODE_STATUS=INSTALLED"))
            assertTrue("Firefox is missing: $output", output.contains("FIREFOX_STATUS=INSTALLED"))
            assertTrue("VNC is not capped at 240 FPS: $output", output.contains("FPS_STATUS=240"))
            assertTrue("release metadata is stale: $output", output.contains("RELEASE_VERSION=1.1.13"))
            assertTrue(
                "GNOME still shares the native graphics failure domain: $output",
                output.contains("GRAPHICS_STATUS=ISOLATED"),
            )
            assertTrue(
                "GNOME did not recover while preserving Xvnc: $output",
                output.contains("RECOVERY_STATUS=GNOME_RESTARTED_XVNC_STABLE"),
            )
        }
    }

    private fun verifyDesktopProducesFramesAndAcceptsInput(vncPort: Int) {
        val frameCount = AtomicInteger(0)
        val disconnectReason = AtomicReference<String?>(null)
        val framebuffer = AtomicReference<Bitmap?>(null)
        val client = RfbClient(
            host = "127.0.0.1",
            port = vncPort,
            listener = object : RfbClient.Listener {
                override fun onFramebufferReady(bitmap: Bitmap) {
                    framebuffer.set(bitmap)
                }
                override fun onFrameUpdated() {
                    frameCount.incrementAndGet()
                }
                override fun onDisconnected(reason: String) {
                    disconnectReason.set(reason)
                }
            },
        )
        val reader = thread(start = false, name = "gnome-artifact-vnc") {
            client.runReadLoop()
        }
        try {
            client.connect()
            reader.start()
            val desktopDeadline = System.currentTimeMillis() + FRAME_TIMEOUT_MILLIS
            while (!hasVisibleDesktop(framebuffer.get()) && System.currentTimeMillis() < desktopDeadline) {
                Thread.sleep(50)
            }
            assertTrue(
                "GNOME Shell produced no visible desktop; frames=${frameCount.get()}, " +
                    "disconnect=${disconnectReason.get()}",
                hasVisibleDesktop(framebuffer.get()),
            )

            val initialSignature = framebufferSignature(requireNotNull(framebuffer.get()))
            assertTrue("Activities press was not queued", client.sendPointerEvent(42, 16, 1))
            assertTrue("Activities release was not queued", client.sendPointerEvent(42, 16, 0))
            val inputDeadline = System.currentTimeMillis() + INPUT_FEEDBACK_TIMEOUT_MILLIS
            while (framebufferSignature(requireNotNull(framebuffer.get())) == initialSignature &&
                System.currentTimeMillis() < inputDeadline
            ) {
                Thread.sleep(50)
            }
            assertTrue(
                "GNOME Activities did not react to the RFB click",
                framebufferSignature(requireNotNull(framebuffer.get())) != initialSignature,
            )

            verifyApplicationLaunchesPreserveDesktop(
                client = client,
                frameCount = frameCount,
                framebuffer = framebuffer,
                disconnectReason = disconnectReason,
            )
        } finally {
            client.close()
            reader.join(READER_JOIN_TIMEOUT_MILLIS)
        }
    }

    /**
     * Launches the four desktop applications users reach from GNOME's app
     * grid. The v1.1.12 regression appeared only after the first application
     * created a graphics context, so package-presence checks could not catch
     * it. The shell and Xvnc PIDs must remain stable and the framebuffer must
     * remain visibly nonblank throughout the heavy launch.
     */
    private fun verifyApplicationLaunchesPreserveDesktop(
        client: RfbClient,
        frameCount: AtomicInteger,
        framebuffer: AtomicReference<Bitmap?>,
        disconnectReason: AtomicReference<String?>,
    ) {
        val baselineProcesses = readDesktopProcessIds()
        val applications = listOf(
            DesktopApplication(
                displayName = "terminal",
                launchCommand = "gnome-terminal",
                processProbeCommand = "pgrep -f '^/usr/libexec/gnome-terminal-server'",
            ),
            DesktopApplication(
                displayName = "files",
                launchCommand = "nautilus",
                processProbeCommand = "pgrep -x nautilus",
            ),
            DesktopApplication(
                displayName = "firefox",
                launchCommand = "firefox",
                processProbeCommand = "pgrep -f '^/usr/lib/firefox/firefox'",
            ),
            DesktopApplication(
                displayName = "code",
                launchCommand = "code",
                processProbeCommand = "pgrep -x code",
            ),
        )

        applications.forEach { application ->
            val framesBeforeLaunch = frameCount.get()
            launchApplicationThroughRunDialog(client, application.launchCommand)
            val output = verifyApplicationAndDesktopProcesses(application, baselineProcesses)

            assertTrue(
                "${application.displayName} did not launch without restarting the desktop: $output",
                output.contains("APP_STATUS=STABLE"),
            )
            assertTrue(
                "${application.displayName} produced no RFB framebuffer update",
                frameCount.get() > framesBeforeLaunch,
            )
            Thread.sleep(APPLICATION_STABILITY_MILLIS)
            assertTrue(
                "desktop became blank after opening ${application.displayName}",
                hasVisibleDesktop(framebuffer.get()),
            )
            assertTrue(
                "VNC disconnected after opening ${application.displayName}: ${disconnectReason.get()}",
                disconnectReason.get() == null,
            )
            closeActiveApplication(client)
        }
    }

    private fun verifyApplicationAndDesktopProcesses(
        application: DesktopApplication,
        baselineProcesses: DesktopProcessIds,
    ): String {
        val command =
            "app_ready=0; for attempt in \$(seq 1 20); do " +
                "if ${application.processProbeCommand} >/dev/null; " +
                "then app_ready=1; break; fi; sleep 1; done; " +
                "new_shell=\$(pgrep -x gnome-shell | head -1); " +
                "new_xvnc=\$(pgrep -x Xtigervnc | head -1); " +
                "printf 'APP_PROCESSES name=${application.displayName} ready=%s shell=%s xvnc=%s\\n' " +
                "\"\$app_ready\" \"\$new_shell\" \"\$new_xvnc\"; " +
                "if test \"\$app_ready\" = 1 && " +
                "test \"\$new_shell\" = '${baselineProcesses.gnomeShellPid}' && " +
                "test \"\$new_xvnc\" = '${baselineProcesses.xvncPid}'; " +
                "then printf 'APP_' && printf 'STATUS=STABLE\\n'; fi; " +
                "printf 'APP_' && printf 'CHECK_DONE\\n'\n"
        return executeGuestCommand(command, "APP_CHECK_DONE", APPLICATION_START_TIMEOUT_MILLIS)
    }

    private fun launchApplicationThroughRunDialog(client: RfbClient, launchCommand: String) {
        sendKeyStroke(client, KEY_ESCAPE)
        Thread.sleep(ACTIVITIES_TRANSITION_MILLIS)
        assertTrue("Alt press was rejected", client.sendKeyEvent(KEY_ALT_LEFT, true))
        sendKeyStroke(client, KEY_F2)
        assertTrue("Alt release was rejected", client.sendKeyEvent(KEY_ALT_LEFT, false))
        Thread.sleep(ACTIVITIES_TRANSITION_MILLIS)
        launchCommand.forEach { character -> sendKeyStroke(client, character.code) }
        Thread.sleep(ACTIVITIES_TRANSITION_MILLIS)
        sendKeyStroke(client, KEY_RETURN)
        Thread.sleep(APPLICATION_LAUNCH_SETTLE_MILLIS)
    }

    private fun sendKeyStroke(client: RfbClient, keySym: Int) {
        assertTrue("RFB key press was rejected for $keySym", client.sendKeyEvent(keySym, true))
        assertTrue("RFB key release was rejected for $keySym", client.sendKeyEvent(keySym, false))
    }

    private fun closeActiveApplication(client: RfbClient) {
        assertTrue("Alt press was rejected", client.sendKeyEvent(KEY_ALT_LEFT, true))
        sendKeyStroke(client, KEY_F4)
        assertTrue("Alt release was rejected", client.sendKeyEvent(KEY_ALT_LEFT, false))
        Thread.sleep(APPLICATION_CLOSE_SETTLE_MILLIS)
    }

    private fun readDesktopProcessIds(): DesktopProcessIds {
        val command =
            "printf 'SESSION_' && printf 'PIDS=%s,%s\\n' " +
                "\"\$(pgrep -x gnome-shell | head -1)\" " +
                "\"\$(pgrep -x Xtigervnc | head -1)\"; " +
                "printf 'SESSION_' && printf 'QUERY_DONE\\n'\n"
        val output = executeGuestCommand(command, "SESSION_QUERY_DONE", CONSOLE_TIMEOUT_MILLIS)
        val match = SESSION_PID_PATTERN.find(output)
            ?: throw AssertionError("desktop process IDs were unavailable: $output")
        return DesktopProcessIds(
            gnomeShellPid = match.groupValues[1].toInt(),
            xvncPid = match.groupValues[2].toInt(),
        )
    }

    private fun executeGuestCommand(command: String, marker: String, timeoutMillis: Long): String {
        val console = engine.openSerialConsole()
            ?: throw AssertionError("GNOME PRoot shell unavailable")
        return console.use {
            Thread.sleep(CONSOLE_STARTUP_MILLIS)
            it.write(CONSOLE_SIZE_RESPONSES.toByteArray())
            Thread.sleep(CONSOLE_RESPONSE_SETTLE_MILLIS)
            it.write(command.toByteArray())
            readUntil(it, marker, timeoutMillis)
        }
    }

    private fun hasVisibleDesktop(bitmap: Bitmap?): Boolean {
        bitmap ?: return false
        synchronized(bitmap) {
            val firstColour = bitmap.getPixel(0, 0)
            for (y in 0 until bitmap.height step PIXEL_SAMPLE_STEP) {
                for (x in 0 until bitmap.width step PIXEL_SAMPLE_STEP) {
                    if (bitmap.getPixel(x, y) != firstColour) return true
                }
            }
        }
        return false
    }

    private fun framebufferSignature(bitmap: Bitmap): Int = synchronized(bitmap) {
        var signature = 17
        for (y in 0 until bitmap.height step PIXEL_SAMPLE_STEP) {
            for (x in 0 until bitmap.width step PIXEL_SAMPLE_STEP) {
                signature = 31 * signature + bitmap.getPixel(x, y)
            }
        }
        signature
    }

    private fun readUntil(
        console: SerialConsoleConnection,
        marker: String,
        timeoutMs: Long,
    ): String {
        val output = StringBuffer()
        val readFinished = AtomicBoolean(false)
        val reader = thread(start = true, isDaemon = true, name = "gnome-console-reader") {
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
            Thread.sleep(CONSOLE_POLL_MILLIS)
        }
        reader.interrupt()
        return output.toString()
    }

    companion object {
        private const val ROOTFS_ARCHIVE_NAME =
            "linux-on-dex-ubuntu-24.04-proot-gnome-arm64.rootfs.tar.gz"
        private const val CONSOLE_TIMEOUT_MILLIS = 30_000L
        private const val CONSOLE_STARTUP_MILLIS = 1_500L
        private const val CONSOLE_RESPONSE_SETTLE_MILLIS = 100L
        private const val CONSOLE_SIZE_RESPONSES = "\u001B[24;80R\u001B[24;80R"
        private const val CONSOLE_POLL_MILLIS = 25L
        private const val FRAME_TIMEOUT_MILLIS = 15_000L
        private const val INPUT_FEEDBACK_TIMEOUT_MILLIS = 5_000L
        private const val APPLICATION_START_TIMEOUT_MILLIS = 90_000L
        private const val APPLICATION_STABILITY_MILLIS = 3_000L
        private const val ACTIVITIES_TRANSITION_MILLIS = 1_000L
        private const val APPLICATION_LAUNCH_SETTLE_MILLIS = 5_000L
        private const val APPLICATION_CLOSE_SETTLE_MILLIS = 3_000L
        private const val CONSOLE_REAP_STRESS_COUNT = 4
        private const val CONSOLE_PROCESS_COUNT_TOLERANCE = 2
        private const val CONSOLE_REAP_BASELINE_SETTLE_MILLIS = 10_000L
        private const val READER_JOIN_TIMEOUT_MILLIS = 2_000L
        private const val PIXEL_SAMPLE_STEP = 32
        private const val KEY_ESCAPE = 0xFF1B
        private const val KEY_RETURN = 0xFF0D
        private const val KEY_ALT_LEFT = 0xFFE9
        private const val KEY_F2 = 0xFFBF
        private const val KEY_F4 = 0xFFC1
        private val SESSION_PID_PATTERN = Regex("SESSION_PIDS=(\\d+),(\\d+)")
        private val GUEST_PROCESS_COUNT_PATTERN = Regex("GUEST_PROCESS_COUNT=(\\d+)")
    }

    private data class DesktopProcessIds(
        val gnomeShellPid: Int,
        val xvncPid: Int,
    )

    private data class DesktopApplication(
        val displayName: String,
        val launchCommand: String,
        val processProbeCommand: String,
    )
}
