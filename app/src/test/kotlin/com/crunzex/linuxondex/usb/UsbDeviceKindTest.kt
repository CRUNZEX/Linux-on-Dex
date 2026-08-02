package com.crunzex.linuxondex.usb

import android.hardware.usb.UsbConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * USB devices are only *observed*, never attached to the VM. These tests pin
 * the classification the Monitor list shows for each kind of device.
 */
class UsbDeviceKindTest {

    @Test
    fun `a mass storage class means a drive`() {
        assertEquals(
            UsbDeviceKind.STORAGE,
            UsbDeviceKind.fromUsbClassCodes(listOf(UsbConstants.USB_CLASS_MASS_STORAGE)),
        )
    }

    @Test
    fun `composite devices report class 0 at device level and are classified by interface`() {
        // Real drives commonly enumerate as [0 (per-interface), 8 (storage)].
        assertEquals(
            UsbDeviceKind.STORAGE,
            UsbDeviceKind.fromUsbClassCodes(
                listOf(UsbConstants.USB_CLASS_PER_INTERFACE, UsbConstants.USB_CLASS_MASS_STORAGE)
            ),
        )
    }

    @Test
    fun `a drive with an extra HID interface is still a drive`() {
        assertEquals(
            UsbDeviceKind.STORAGE,
            UsbDeviceKind.fromUsbClassCodes(
                listOf(UsbConstants.USB_CLASS_HID, UsbConstants.USB_CLASS_MASS_STORAGE)
            ),
        )
    }

    @Test
    fun `communication classes mean a network adapter`() {
        assertEquals(
            UsbDeviceKind.NETWORK,
            UsbDeviceKind.fromUsbClassCodes(listOf(UsbConstants.USB_CLASS_COMM)),
        )
        assertEquals(
            UsbDeviceKind.NETWORK,
            UsbDeviceKind.fromUsbClassCodes(listOf(UsbConstants.USB_CLASS_CDC_DATA)),
        )
    }

    @Test
    fun `a HID class alone means keyboard or mouse`() {
        assertEquals(
            UsbDeviceKind.INPUT,
            UsbDeviceKind.fromUsbClassCodes(listOf(UsbConstants.USB_CLASS_HID)),
        )
    }

    @Test
    fun `anything unrecognised is just a device`() {
        assertEquals(
            UsbDeviceKind.OTHER,
            UsbDeviceKind.fromUsbClassCodes(listOf(UsbConstants.USB_CLASS_VENDOR_SPEC)),
        )
        assertEquals(UsbDeviceKind.OTHER, UsbDeviceKind.fromUsbClassCodes(emptyList()))
    }

    @Test
    fun `the id pair is shown in lsusb form`() {
        val device = AttachedUsbDevice(
            vendorId = 0x090c,
            productId = 0x1000,
            name = "USB drive",
            kind = UsbDeviceKind.STORAGE,
        )

        assertEquals("090c:1000", device.idText)
    }

    @Test
    fun `usb ids outside 16 bits are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AttachedUsbDevice(
                vendorId = 0x1_0000,
                productId = 0,
                name = "broken",
                kind = UsbDeviceKind.OTHER,
            )
        }
    }
}
