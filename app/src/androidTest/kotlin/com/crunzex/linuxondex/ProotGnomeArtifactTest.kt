package com.crunzex.linuxondex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.engine.proot.ProotEngine
import com.crunzex.linuxondex.engine.proot.AppManagedNativeX11Server
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.DisplayConfig
import com.crunzex.linuxondex.vm.DisplayEndpoint
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
        engine = ProotEngine(
            paths,
            PayloadInstaller(context, paths),
            AppManagedNativeX11Server(context, paths),
        )
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
            assertTrue(
                "GNOME rootfs should use native X11",
                (engine.state.value as VmState.Running).displayEndpoint is DisplayEndpoint.NativeX11,
            )
            verifyGuestSession()
            verifyClosedConsolesReleaseTheirProcessTrees()
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
                    "if test \"\$(cat /usr/local/share/linux-on-dex/display-backend)\" = native-x11 && " +
                    "test -S /tmp/.X11-unix/X1; " +
                    "then printf 'DISPLAY_' && printf 'STATUS=NATIVE_X11\\n'; fi; " +
                    "grep -q '1.3.0-beta2' /etc/linux-on-dex-release && " +
                    "printf 'RELEASE_' && printf 'VERSION=1.3.0-beta2\\n'; " +
                    "if grep -q 'GALLIUM_DRIVER=llvmpipe' /usr/local/bin/dex-desktop && " +
                    "test -x /usr/local/bin/dex-gpu; " +
                    "then printf 'GRAPHICS_' && printf 'STATUS=ISOLATED\\n'; fi; " +
                    "old_shell=\$(pgrep -x gnome-shell | head -1); " +
                    "kill -KILL \"\$old_shell\"; " +
                    "new_shell=''; for attempt in \$(seq 1 15); do " +
                    "new_shell=\$(pgrep -x gnome-shell | head -1); " +
                    "if test -n \"\$new_shell\" && test \"\$new_shell\" != \"\$old_shell\"; " +
                    "then break; fi; sleep 1; done; " +
                    "if test -n \"\$new_shell\" && test \"\$new_shell\" != \"\$old_shell\" && " +
                    "test -S /tmp/.X11-unix/X1; " +
                    "then printf 'RECOVERY_' && printf 'STATUS=GNOME_RESTARTED_X11_STABLE\\n'; fi; " +
                    "printf 'GNOME_' && printf 'ARTIFACT_OK\\n'\n"
            it.write(command.toByteArray())
            val output = readUntil(it, "GNOME_ARTIFACT_OK", CONSOLE_TIMEOUT_MILLIS)

            assertTrue("GNOME validation command timed out: $output", output.contains("GNOME_ARTIFACT_OK"))
            assertTrue("real GNOME Shell is not running cleanly: $output", output.contains("SESSION_STATUS=GNOME_SHELL"))
            assertTrue("Android groups remain unnamed: $output", output.contains("GROUP_STATUS=NAMED"))
            assertTrue("VS Code is missing: $output", output.contains("CODE_STATUS=INSTALLED"))
            assertTrue("Firefox is missing: $output", output.contains("FIREFOX_STATUS=INSTALLED"))
            assertTrue("native X11 is unavailable: $output", output.contains("DISPLAY_STATUS=NATIVE_X11"))
            assertTrue("release metadata is stale: $output", output.contains("RELEASE_VERSION=1.3.0-beta2"))
            assertTrue(
                "GNOME still shares the native graphics failure domain: $output",
                output.contains("GRAPHICS_STATUS=ISOLATED"),
            )
            assertTrue(
                "GNOME did not recover while preserving native X11: $output",
                output.contains("RECOVERY_STATUS=GNOME_RESTARTED_X11_STABLE"),
            )
        }
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
        private const val CONSOLE_REAP_STRESS_COUNT = 4
        private const val CONSOLE_PROCESS_COUNT_TOLERANCE = 2
        private const val CONSOLE_REAP_BASELINE_SETTLE_MILLIS = 10_000L
        private val GUEST_PROCESS_COUNT_PATTERN = Regex("GUEST_PROCESS_COUNT=(\\d+)")
    }
}
