package com.crunzex.linuxondex

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crunzex.linuxondex.engine.qemu.QmpClient
import com.crunzex.linuxondex.engine.runtime.NativeCommand
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.usb.OpenUsbDevice
import com.crunzex.linuxondex.usb.UsbDeviceKind
import com.crunzex.linuxondex.vm.UsbDeviceSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Physical-hardware USB proof for a Samsung phone or an emulator configured
 * with host USB forwarding.
 *
 * Each test starts a paused QEMU machine with an XHCI controller, sends the
 * descriptor returned by Android over QMP/SCM_RIGHTS, creates `usb-host`, and
 * asks QEMU for the guest bus contents. Tests skip when the requested class
 * is not attached or the one-time Android permission has not been granted;
 * they fail—not skip—if an eligible device reaches QEMU but passthrough is
 * rejected. Run once after enabling the device in the app's USB settings.
 */
@RunWith(AndroidJUnit4::class)
class UsbPassthroughHardwareTest {

    private lateinit var context: Context
    private lateinit var paths: VmPaths
    private lateinit var usbManager: UsbManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        paths = VmPaths(context).also(VmPaths::createRuntimeDirectories)
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    @Test
    fun storageDeviceAppearsOnTheGuestUsbBus() {
        verifyPhysicalDevice(UsbDeviceKind.STORAGE)
    }

    @Test
    fun networkAdapterAppearsOnTheGuestUsbBus() {
        verifyPhysicalDevice(UsbDeviceKind.NETWORK)
    }

    private fun verifyPhysicalDevice(expectedKind: UsbDeviceKind) {
        val device = usbManager.deviceList.values.firstOrNull { classify(it) == expectedKind }
        assumeTrue("No attached ${expectedKind.displayName}; hardware test skipped", device != null)
        requireNotNull(device)
        assumeTrue(
            "Grant USB permission in Linux on DeX before running this test",
            usbManager.hasPermission(device),
        )

        val socketFile = paths.socketsDir.resolve("usb-hardware-test.qmp")
        socketFile.delete()
        val qemuProcess = startPausedUsbMachine(socketFile)
        try {
            val qmp = waitForQmp(socketFile, qemuProcess)
            qmp.use {
                val connection = usbManager.openDevice(device)
                    ?: throw AssertionError("Android refused to open ${device.deviceName}")
                try {
                    ParcelFileDescriptor.fromFd(connection.fileDescriptor).use { descriptor ->
                        val openDevice = OpenUsbDevice(
                            spec = UsbDeviceSpec(
                                vendorId = device.vendorId,
                                productId = device.productId,
                                label = device.productName ?: expectedKind.displayName,
                            ),
                            deviceName = device.deviceName,
                            androidDeviceId = device.deviceId,
                            descriptor = descriptor,
                        )
                        val fdSetId = qmp.addFileDescriptorToNewSet(descriptor.fileDescriptor)
                        try {
                            val guestBusBeforeAttach = qmp.queryUsbDevices()
                            qmp.attachUsbHostDevice(openDevice.qemuDeviceId, fdSetId)
                            Thread.sleep(500)
                            val guestBus = qmp.queryUsbDevices()
                            assertFalse(
                                "device_add succeeded but the guest USB bus stayed empty",
                                guestBus.isBlank(),
                            )
                            assertTrue(
                                "guest USB bus did not identify ${openDevice.qemuDeviceId}: $guestBus",
                                guestBus.contains(openDevice.qemuDeviceId),
                            )
                            assertTrue(
                                "guest USB bus did not change after device_add: $guestBus",
                                guestBus != guestBusBeforeAttach,
                            )
                        } finally {
                            runCatching { qmp.detachDevice(openDevice.qemuDeviceId) }
                            runCatching { qmp.removeFileDescriptorSet(fdSetId) }
                        }
                    }
                } finally {
                    connection.close()
                }
                runCatching { qmp.requestImmediateQuit() }
            }
        } finally {
            if (qemuProcess.isAlive) qemuProcess.destroyForcibly()
            socketFile.delete()
        }
    }

    private fun startPausedUsbMachine(socketFile: File): Process = NativeCommand(
        program = paths.qemuSystemBinary,
        arguments = listOf(
            "-machine", "virt,gic-version=3",
            "-accel", "tcg,thread=multi,tb-size=128",
            "-cpu", "max,pauth=off",
            "-smp", "1",
            "-m", "128",
            "-nodefaults",
            "-device", "qemu-xhci,id=usb",
            "-qmp", "unix:${socketFile.absolutePath},server=on,wait=off",
            "-display", "none",
            "-monitor", "none",
            "-S",
        ),
        environment = paths.processEnvironment(),
        workingDirectory = paths.vmRootDir,
    ).start(redirectErrorStream = true)

    private fun waitForQmp(socketFile: File, process: Process): QmpClient {
        val deadline = System.currentTimeMillis() + 15_000L
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw AssertionError("QEMU exited before USB test setup completed")
            }
            try {
                return QmpClient.connect(socketFile)
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(100)
            }
        }
        throw AssertionError("QMP did not start for USB test", lastError)
    }

    private fun classify(device: UsbDevice): UsbDeviceKind {
        val classCodes = buildList {
            add(device.deviceClass)
            repeat(device.interfaceCount) { index -> add(device.getInterface(index).interfaceClass) }
        }
        return UsbDeviceKind.fromUsbClassCodes(classCodes)
    }
}
