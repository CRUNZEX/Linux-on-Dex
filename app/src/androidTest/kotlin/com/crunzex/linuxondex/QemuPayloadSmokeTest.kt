package com.crunzex.linuxondex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.crunzex.linuxondex.engine.runtime.NativeCommand
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.engine.proot.AndroidVirglBridge

/**
 * On-device proof that the repackaged Termux payload actually runs under this
 * app's SELinux domain on Android 13-16: exec from nativeLibraryDir, linker
 * resolution of renamed libraries, and firmware extraction.
 *
 * If these pass on a device/emulator, approaches 1 and 2 (QEMU) are viable.
 */
@RunWith(AndroidJUnit4::class)
class QemuPayloadSmokeTest {

    private lateinit var paths: VmPaths

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        paths = VmPaths(context)
        paths.createRuntimeDirectories()
    }

    @Test
    fun qemuSystemBinaryExecutesAndReportsVersion() {
        val result = NativeCommand(
            program = paths.qemuSystemBinary,
            arguments = listOf("--version"),
            environment = paths.processEnvironment(),
        ).runAndCaptureOutput()

        assertEquals("qemu --version should exit 0; output: ${result.output}", 0, result.exitCode)
        assertTrue(
            "unexpected version banner: ${result.output}",
            result.output.contains("QEMU emulator version"),
        )
    }

    @Test
    fun qemuImgExecutesAndReportsVersion() {
        val result = NativeCommand(
            program = paths.qemuImgBinary,
            arguments = listOf("--version"),
            environment = paths.processEnvironment(),
        ).runAndCaptureOutput()

        assertEquals("qemu-img --version should exit 0; output: ${result.output}", 0, result.exitCode)
        assertTrue(
            "unexpected version banner: ${result.output}",
            result.output.contains("qemu-img version"),
        )
    }

    @Test
    fun qemuPayloadContainsTheUsbControllerAndLibusbHostBackend() {
        val result = NativeCommand(
            program = paths.qemuSystemBinary,
            arguments = listOf("-device", "help"),
            environment = paths.processEnvironment(),
        ).runAndCaptureOutput()

        assertEquals("qemu -device help failed: ${result.output}", 0, result.exitCode)
        assertTrue("qemu-xhci is missing from the payload", result.output.contains("qemu-xhci"))
        assertTrue("usb-host/libusb is missing from the payload", result.output.contains("usb-host"))
    }

    @Test
    fun prootBinaryExecutes() {
        val result = NativeCommand(
            program = paths.prootBinary,
            arguments = listOf("--version"),
            environment = paths.processEnvironment() +
                ("PROOT_LOADER" to paths.prootLoaderBinary.absolutePath),
        ).runAndCaptureOutput()

        assertEquals("proot --version should exit 0; output: ${result.output}", 0, result.exitCode)
        assertTrue(
            "unexpected proot banner: ${result.output}",
            result.output.contains("proot", ignoreCase = true),
        )
    }

    @Test
    fun nativeVirglBridgePublishesItsSocket() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PayloadInstaller(context, paths).ensureInstalled()
        val bridge = AndroidVirglBridge(paths)

        try {
            assertTrue(
                "virgl/ANGLE did not publish ${paths.virglSocket}; see virgl-renderer.log",
                bridge.start(),
            )
            assertTrue("virgl bridge process/socket is not alive", bridge.isRunning)
            assertTrue(
                "ANGLE Vulkan backend was not installed",
                paths.vmRootDir.resolve("angle/vulkan/libEGL_angle.so").exists(),
            )
        } finally {
            bridge.stop()
        }
        assertTrue("virgl socket leaked after stop", !paths.virglSocket.exists())
    }

    @Test
    fun payloadInstallerExtractsUefiFirmware() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PayloadInstaller(context, paths).ensureInstalled()

        assertTrue("edk2 code firmware missing", paths.firmwareCode.exists())
        assertEquals(
            "UEFI pflash images must be exactly 64 MiB",
            64L * 1024 * 1024,
            paths.firmwareCode.length(),
        )
        assertTrue("edk2 vars template missing", paths.firmwareVarsTemplate.exists())
    }
}
