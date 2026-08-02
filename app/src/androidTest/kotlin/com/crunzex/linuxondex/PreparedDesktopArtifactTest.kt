package com.crunzex.linuxondex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crunzex.linuxondex.display.PpmImage
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.engine.qemu.QemuAccelerator
import com.crunzex.linuxondex.engine.qemu.QemuVmEngine
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.CpuConfig
import com.crunzex.linuxondex.vm.DiskImageManager
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
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/** Boots the actual v1.1.10 qcow2 release artifacts under Android TCG. */
@RunWith(AndroidJUnit4::class)
class PreparedDesktopArtifactTest {

    private lateinit var paths: VmPaths
    private lateinit var engine: QemuVmEngine

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        paths = VmPaths(context)
        paths.createRuntimeDirectories()
        engine = QemuVmEngine(
            paths = paths,
            payloadInstaller = PayloadInstaller(context, paths),
            diskManager = DiskImageManager(paths),
            accelerator = QemuAccelerator.TCG,
        )
    }

    @Test
    fun xfceImageAutologinsToTerminalAndDesktop() = runBlocking {
        verifyDesktopArtifact(
            imageName = XFCE_IMAGE_NAME,
            profileName = "xfce",
            expectedSessionProcess = "xfce4-session",
            vncDisplayNumber = 6,
        )
    }

    @Test
    fun gnomeImageAutologinsToFlashbackDesktop() = runBlocking {
        verifyDesktopArtifact(
            imageName = GNOME_IMAGE_NAME,
            profileName = "gnome",
            expectedSessionProcess = "gnome-flashback",
            vncDisplayNumber = 7,
        )
    }

    private suspend fun verifyDesktopArtifact(
        imageName: String,
        profileName: String,
        expectedSessionProcess: String,
        vncDisplayNumber: Int,
    ) {
        val pushedImage = paths.vmImagesDir?.resolve(imageName)
        assumeTrue("$imageName not pushed, skipping", pushedImage?.exists() == true)
        requireNotNull(pushedImage)

        // adb creates a shell-labelled inode that a native app process cannot
        // open directly under SELinux. The production document import writes
        // the destination through the app, so reproduce that real ownership
        // boundary and keep the release artifact itself immutable.
        val image = createAppOwnedBootCopy(pushedImage)

        val config = VmConfig(
            id = "desktop-$profileName-test",
            name = "Ubuntu $profileName artifact test",
            cpu = CpuConfig(coreCount = 4),
            memoryMb = 3_072,
            display = DisplayConfig(
                resolution = ScreenResolution.WXGA_1280_800,
                vncDisplayNumber = vncDisplayNumber,
            ),
            preparedImage = PreparedImageConfig(
                displayName = "Ubuntu 24.04 $profileName",
                diskImagePath = image.absolutePath,
                username = "dex",
                password = "linuxondex",
                firstBootCompleted = true,
                format = PreparedImageFormat.QCOW2_DISK,
            ),
        )

        engine.start(config)
        try {
            assertTrue("engine should be running", engine.state.value is VmState.Running)
            val promptFound = waitForSerialMarker("dex@dex", SERIAL_AUTOLOGIN_TIMEOUT_SECONDS)
            assertTrue(
                "serial console did not autologin; tail:\n${engine.readSerialLog().takeLast(2_000)}",
                promptFound,
            )

            verifyAutologinConsole(index = 0, label = "primary serial")
            verifyAutologinConsole(index = 1, label = "hvc0")

            val primaryConsole = engine.openConsole(0)
                ?: throw AssertionError("primary serial console unavailable")
            primaryConsole.use { console ->
                val validationCommand =
                    "if systemctl is-active --quiet display-manager.service; " +
                        "then printf 'DISPLAY_' && printf 'MANAGER=ACTIVE\\n'; fi; " +
                        "if timeout 60 sh -c \"until pgrep -f '$expectedSessionProcess' >/dev/null; " +
                        "do sleep 1; done\"; " +
                        "then printf 'SESSION_' && printf 'PROCESS=ACTIVE\\n'; fi; " +
                        "if apt-config dump | grep -q 'Acquire::Queue-Mode.*access'; " +
                        "then printf 'APT_' && printf 'TUNING=ACTIVE\\n'; fi; " +
                        "grep -q '1.1.10' /etc/linux-on-dex-release && " +
                        "printf 'RELEASE_' && printf 'VERSION=1.1.10\\n'; " +
                        "printf 'DESKTOP_' && printf 'PROBE_DONE\\n'\n"
                console.write(validationCommand.toByteArray())
                val output = readUntil(console, "DESKTOP_PROBE_DONE", CONSOLE_PROBE_TIMEOUT_MILLIS)
                assertTrue("display manager is inactive: $output", output.contains("DISPLAY_MANAGER=ACTIVE"))
                assertTrue("$profileName session is inactive: $output", output.contains("SESSION_PROCESS=ACTIVE"))
                assertTrue("apt tuning is missing: $output", output.contains("APT_TUNING=ACTIVE"))
                assertTrue("release metadata is stale: $output", output.contains("RELEASE_VERSION=1.1.10"))
            }

            val frame = waitForDesktopFrame(DESKTOP_FRAME_TIMEOUT_SECONDS)
            assertTrue("$profileName did not paint a desktop; last frame: $lastFrameDescription", frame != null)
            println("$profileName desktop ready — $lastFrameDescription")
        } finally {
            engine.stop(gracePeriod = 15.seconds)
            image.delete()
        }
    }

    private fun createAppOwnedBootCopy(pushedImage: File): File {
        val destination = pushedImage.resolveSibling(
            "${pushedImage.nameWithoutExtension}.instrumentation.qcow2",
        )
        pushedImage.copyTo(destination, overwrite = true)
        assertTrue("app-owned qcow2 copy is incomplete", destination.length() == pushedImage.length())
        return destination
    }

    private fun verifyAutologinConsole(index: Int, label: String) {
        val console = engine.openConsole(index)
            ?: throw AssertionError("$label console unavailable")
        console.use {
            it.write("printf 'CONSOLE_' && printf 'AUTOLOGIN_OK\\n'\n".toByteArray())
            val output = readUntil(it, "CONSOLE_AUTOLOGIN_OK", CONSOLE_PROBE_TIMEOUT_MILLIS)
            assertTrue("$label did not accept a shell command without login: $output", output.contains("CONSOLE_AUTOLOGIN_OK"))
        }
    }

    private fun waitForSerialMarker(marker: String, timeoutSeconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L
        while (System.currentTimeMillis() < deadline) {
            if (engine.readSerialLog().contains(marker)) return true
            failIfEngineDied()
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private var lastFrameDescription = "(none captured)"

    private fun waitForDesktopFrame(timeoutSeconds: Int): File? {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L
        while (System.currentTimeMillis() < deadline) {
            val capture = engine.captureScreenshot()
            if (capture != null) {
                val image = runCatching { PpmImage.decode(capture) }.getOrNull()
                if (image != null) {
                    lastFrameDescription = image.describe()
                    if (image.colourCount() >= DESKTOP_MIN_COLOURS &&
                        image.litPixelFraction() >= DESKTOP_MIN_LIT_FRACTION
                    ) {
                        return capture
                    }
                }
                capture.delete()
            }
            failIfEngineDied()
            Thread.sleep(SCREENSHOT_INTERVAL_MILLIS)
        }
        return null
    }

    private fun failIfEngineDied() {
        val state = engine.state.value
        if (state is VmState.Failed) {
            throw AssertionError("VM failed: ${state.error.technicalDetail}")
        }
    }

    private fun readUntil(
        console: SerialConsoleConnection,
        marker: String,
        timeoutMs: Long,
    ): String {
        val output = StringBuffer()
        val readFinished = AtomicBoolean(false)
        val reader = thread(start = true, isDaemon = true, name = "desktop-console-reader") {
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
            Thread.sleep(CONSOLE_POLL_INTERVAL_MILLIS)
        }
        reader.interrupt()
        return output.toString()
    }

    companion object {
        private const val XFCE_IMAGE_NAME =
            "linux-on-dex-ubuntu-24.04-desktop-xfce-arm64.qcow2"
        private const val GNOME_IMAGE_NAME =
            "linux-on-dex-ubuntu-24.04-desktop-gnome-arm64.qcow2"
        private const val SERIAL_AUTOLOGIN_TIMEOUT_SECONDS = 900
        private const val DESKTOP_FRAME_TIMEOUT_SECONDS = 600
        private const val CONSOLE_PROBE_TIMEOUT_MILLIS = 75_000L
        private const val CONSOLE_POLL_INTERVAL_MILLIS = 25L
        private const val POLL_INTERVAL_MILLIS = 3_000L
        private const val SCREENSHOT_INTERVAL_MILLIS = 15_000L
        private const val DESKTOP_MIN_COLOURS = 20
        private const val DESKTOP_MIN_LIT_FRACTION = 0.20f
    }
}
