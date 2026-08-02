package com.crunzex.linuxondex.vm

import com.crunzex.linuxondex.engine.qemu.QemuAccelerator
import com.crunzex.linuxondex.engine.qemu.QemuCommandBuilder
import com.crunzex.linuxondex.engine.qemu.QemuLaunchPlan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passing real devices to the guest.
 *
 * Nothing about passthrough belongs on the command line. Android only ever
 * gives an app an already-open descriptor, and the only way to move one into
 * another process is to send it over a socket — which means QEMU has to be
 * running first. Attaching afterwards also keeps a device that cannot be
 * claimed from taking the whole boot down with it, which is exactly what a
 * command-line `usb-host` did.
 */
class UsbPassthroughTest {

    private val ethernetAdapter = UsbDeviceSpec(
        vendorId = 0x0bda,
        productId = 0x8153,
        label = "USB Ethernet",
    )

    private val webcam = UsbDeviceSpec(
        vendorId = 0x04e8,
        productId = 0x20d5,
        label = "S65VC Webcam",
    )

    private fun commandFor(usb: UsbConfig) = QemuCommandBuilder.build(
        QemuLaunchPlan(
            config = VmConfig(id = "primary", name = "Linux VM", usb = usb),
            accelerator = QemuAccelerator.TCG,
            firmwareCode = File("/vm/code.fd"),
            efiVarsFile = File("/vm/vars.fd"),
            rootDiskFile = File("/vm/root.qcow2"),
            installerIso = null,
            seedIso = null,
            directKernel = null,
            qemuDataDir = File("/vm/qemu"),
            sharedFolderDir = null,
            qmpSocketFile = File("/vm/qmp.sock"),
            serialSocketFile = File("/vm/serial.sock"),
            serialLogFile = File("/vm/serial.log"),
            pidFile = File("/vm/vm.pid"),
        )
    ).joinToString(" ")

    @Test
    fun `the boot never carries a usb-host device, however many are configured`() {
        val withDevices = UsbConfig(listOf(ethernetAdapter, webcam))

        listOf(UsbConfig(), withDevices).forEach { usb ->
            val joined = commandFor(usb)
            assertFalse("usb-host must not be on the command line", joined.contains("usb-host"))
            assertFalse("no fd sets are published up front", joined.contains("-add-fd"))
        }
    }

    @Test
    fun `the guest still gets a controller to plug devices into`() {
        // Hot-plugging a device needs a USB bus to exist already.
        assertTrue(commandFor(UsbConfig()).contains("qemu-xhci"))
    }

    @Test
    fun `configuring a device leaves the rest of the machine alone`() {
        val joined = commandFor(UsbConfig(listOf(ethernetAdapter)))

        assertTrue(joined.contains("virtio-blk-pci,drive=rootdisk"))
        assertTrue(joined.contains("-qmp"))
    }

    @Test
    fun `a device is named the lsusb way`() {
        assertEquals("0bda:8153", ethernetAdapter.idKey)
        // Low ids keep their leading zeros, so ids stay comparable as text.
        assertEquals("0001:000f", UsbDeviceSpec(vendorId = 1, productId = 15).idKey)
    }

    @Test
    fun `ids outside 16 bits are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbDeviceSpec(vendorId = 0x1_0000, productId = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbDeviceSpec(vendorId = 0, productId = -1)
        }
    }

    @Test
    fun `passthrough is off until a device is switched on`() {
        assertFalse(UsbConfig().isEnabled)
        assertTrue(UsbConfig(listOf(ethernetAdapter)).isEnabled)
    }

    @Test
    fun `devices switch on and off independently`() {
        val both = UsbConfig()
            .withDevice(ethernetAdapter, passedThrough = true)
            .withDevice(webcam, passedThrough = true)

        assertTrue(both.isPassedThrough("0bda:8153"))
        assertTrue(both.isPassedThrough("04e8:20d5"))

        val onlyWebcam = both.withDevice(ethernetAdapter, passedThrough = false)
        assertFalse(onlyWebcam.isPassedThrough("0bda:8153"))
        assertTrue(onlyWebcam.isPassedThrough("04e8:20d5"))
    }

    @Test
    fun `switching the same device on twice does not list it twice`() {
        // Android reports whatever label it currently has for a device, so
        // the same device can arrive with a different name than last time.
        val renamed = ethernetAdapter.copy(label = "Realtek adapter")
        val config = UsbConfig()
            .withDevice(ethernetAdapter, passedThrough = true)
            .withDevice(renamed, passedThrough = true)

        assertEquals(1, config.passthroughDevices.size)
        assertEquals("Realtek adapter", config.passthroughDevices.single().label)
    }
}
