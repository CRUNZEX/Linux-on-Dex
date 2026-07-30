package com.crunzex.linuxondex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.crunzex.linuxondex.display.PpmImage
import com.crunzex.linuxondex.engine.qemu.QemuAccelerator
import com.crunzex.linuxondex.engine.qemu.QemuVmEngine
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.CpuConfig
import com.crunzex.linuxondex.vm.DiskImageManager
import com.crunzex.linuxondex.vm.DisplayConfig
import com.crunzex.linuxondex.vm.ScreenResolution
import com.crunzex.linuxondex.vm.StorageConfig
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Boots the Ubuntu Desktop ARM64 installer ISO end-to-end.
 *
 * Ubuntu's own bootloader passes `console=tty0`, which leaves the serial
 * port silent for the whole boot. The app's direct kernel boot replaces that
 * command line, so stages 1 and 2 below are observable at all. The test
 * checks three escalating milestones, each with evidence the previous one
 * cannot fake:
 *
 *  1. Kernel started    — the kernel banner reaches the serial console.
 *  2. Userspace up      — systemd and casper report in.
 *  3. Graphics up       — a framebuffer capture with desktop-grade colour
 *                         variety, which a two-tone text console cannot fake.
 *
 * Push the ISO before running:
 * ```
 * adb push ubuntu-*-desktop-arm64.iso \
 *   /sdcard/Android/data/com.crunzex.linuxondex/files/isos/
 * ```
 * Skipped when the ISO is absent. Under nested emulation this test takes
 * tens of minutes; on real hardware it is far quicker.
 */
@RunWith(AndroidJUnit4::class)
class UbuntuDesktopBootTest {

    private lateinit var paths: VmPaths
    private lateinit var engine: QemuVmEngine
    private var isoFile: File? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        paths = VmPaths(context)
        paths.createRuntimeDirectories()
        isoFile = paths.externalIsosDir?.listFiles()
            ?.firstOrNull { it.name.startsWith("ubuntu") && it.extension == "iso" }
        engine = QemuVmEngine(
            paths = paths,
            payloadInstaller = PayloadInstaller(context, paths),
            diskManager = DiskImageManager(paths),
            accelerator = QemuAccelerator.TCG,
        )
    }

    @Test
    fun ubuntuDesktopIsoBootsIntoAGraphicalSession() = runBlocking {
        val iso = isoFile
        assumeTrue("Ubuntu ISO not pushed, skipping", iso != null && iso.exists())
        requireNotNull(iso)

        val config = VmConfig(
            id = "ubuntu",
            name = "Ubuntu Desktop",
            cpu = CpuConfig(coreCount = 4),
            memoryMb = GUEST_MEMORY_MB,
            storage = StorageConfig(diskSizeGb = 32),
            display = DisplayConfig(
                resolution = ScreenResolution.WXGA_1280_800,
                vncDisplayNumber = 9,
            ),
            installerIsoPath = iso.absolutePath,
        )

        engine.start(config)
        try {
            assertTrue("engine should be running", engine.state.value is VmState.Running)

            // 1. The guest kernel started. Direct kernel boot redirects the
            //    console to the serial port, so the banner is visible here;
            //    without it the ISO's bootloader would own the console.
            val kernelBanner = waitForSerialMarker(
                markers = listOf("Linux version"),
                timeoutSeconds = KERNEL_START_TIMEOUT_SECONDS,
            )
            assertTrue(
                "kernel never announced itself within ${KERNEL_START_TIMEOUT_SECONDS}s; " +
                    "serial tail:\n" + engine.readSerialLog().takeLast(1_200),
                kernelBanner != null,
            )
            println("MILESTONE 1 — kernel started (serial marker: \"$kernelBanner\")")

            // 2. Userspace came up. A kernel console is still two-tone
            //    text, so the framebuffer cannot prove this — the guest's own
            //    log output can.
            val userspaceMarker = waitForSerialMarker(
                markers = listOf("systemd", "casper", "/cdrom", "Ubuntu 26"),
                timeoutSeconds = USERSPACE_TIMEOUT_SECONDS,
            )
            assertTrue(
                "kernel/userspace never reported in within ${USERSPACE_TIMEOUT_SECONDS}s; " +
                    "serial tail:\n" + engine.readSerialLog().takeLast(1_200),
                userspaceMarker != null,
            )
            println("MILESTONE 2 — kernel booted into userspace (marker: \"$userspaceMarker\")")

            // 3. A real graphical session, not a console framebuffer.
            val desktopFrame = waitForFrame(
                description = "graphical session painted",
                timeoutSeconds = DESKTOP_TIMEOUT_SECONDS,
            ) { image ->
                image.colourCount() >= GRAPHICAL_MIN_COLOURS &&
                    image.litPixelFraction() >= GRAPHICAL_MIN_LIT_FRACTION
            }
            assertTrue(
                "no graphical session within ${DESKTOP_TIMEOUT_SECONDS}s; " +
                    "last frame: $lastFrameDescription",
                desktopFrame != null,
            )
            println("MILESTONE 3 — graphical session up (${desktopFrame?.name})")
        } finally {
            engine.stop(gracePeriod = 15.seconds)
        }
    }

    private var lastFrameDescription: String = "(none captured)"

    private fun waitForSerialMarker(markers: List<String>, timeoutSeconds: Int): String? {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L
        while (System.currentTimeMillis() < deadline) {
            val serialLog = engine.readSerialLog()
            markers.firstOrNull { serialLog.contains(it, ignoreCase = true) }?.let { return it }
            failIfEngineDied()
            Thread.sleep(SERIAL_POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Polls the guest framebuffer until [isSatisfied] accepts a frame,
     * keeping only frames that matched so the run leaves usable evidence.
     */
    private fun waitForFrame(
        description: String,
        timeoutSeconds: Int,
        isSatisfied: (PpmImage) -> Boolean,
    ): File? {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L
        while (System.currentTimeMillis() < deadline) {
            val capture = engine.captureScreenshot()
            if (capture != null) {
                val image = runCatching { PpmImage.decode(capture) }.getOrNull()
                if (image != null) {
                    lastFrameDescription = image.describe()
                    println("  waiting for $description — frame: $lastFrameDescription")
                    if (isSatisfied(image)) return capture
                }
                capture.delete()
            }
            failIfEngineDied()
            Thread.sleep(SCREENSHOT_INTERVAL_MS)
        }
        return null
    }

    private fun failIfEngineDied() {
        val state = engine.state.value
        if (state is VmState.Failed) {
            throw AssertionError("VM failed during boot: ${state.error.technicalDetail}")
        }
    }

    companion object {
        private const val GUEST_MEMORY_MB = 4096

        private const val KERNEL_START_TIMEOUT_SECONDS = 300
        private const val USERSPACE_TIMEOUT_SECONDS = 900
        private const val DESKTOP_TIMEOUT_SECONDS = 2_400

        private const val SERIAL_POLL_INTERVAL_MS = 3_000L
        private const val SCREENSHOT_INTERVAL_MS = 20_000L

        /** A desktop session (wallpaper, icons, anti-aliased text). */
        private const val GRAPHICAL_MIN_COLOURS = 60
        private const val GRAPHICAL_MIN_LIT_FRACTION = 0.25f
    }
}
