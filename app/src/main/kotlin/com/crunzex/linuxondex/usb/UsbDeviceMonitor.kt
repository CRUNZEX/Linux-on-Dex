package com.crunzex.linuxondex.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.crunzex.linuxondex.core.AppLog

/**
 * What kind of USB device is plugged in, for the read-only monitor list.
 *
 * Nothing here configures the virtual machine. Devices stay connected to
 * Android; the list only explains what each one is and how it already
 * benefits the VM (network and input travel through Android by design).
 */
enum class UsbDeviceKind(val displayName: String, val description: String) {
    STORAGE("Drive", "Storage mounted by Android"),
    NETWORK("Network adapter", "The VM shares Android's connection automatically"),
    INPUT("Keyboard / mouse", "Input reaches the VM through its windows"),
    OTHER("Device", "Connected to Android");

    companion object {
        /**
         * Classifies a device from its USB class codes — the device-level
         * class plus every interface class, in that order. Storage wins over
         * the rest because a drive with an extra HID interface (a button, a
         * status LED) is still a drive to the user.
         */
        fun fromUsbClassCodes(classCodes: List<Int>): UsbDeviceKind = when {
            UsbConstants.USB_CLASS_MASS_STORAGE in classCodes -> STORAGE
            UsbConstants.USB_CLASS_COMM in classCodes ||
                UsbConstants.USB_CLASS_CDC_DATA in classCodes -> NETWORK
            UsbConstants.USB_CLASS_HID in classCodes -> INPUT
            else -> OTHER
        }
    }
}

/** One plugged-in device as the monitor shows it. */
data class AttachedUsbDevice(
    val vendorId: Int,
    val productId: Int,
    /** Human name, e.g. "SanDisk Ultra". */
    val name: String,
    val kind: UsbDeviceKind,
) {
    init {
        require(vendorId in 0..MAX_USB_ID) { "vendorId out of range: $vendorId" }
        require(productId in 0..MAX_USB_ID) { "productId out of range: $productId" }
    }

    /** The vendor:product pair as shown by lsusb, e.g. "090c:1000". */
    val idText: String get() = "%04x:%04x".format(vendorId, productId)

    companion object {
        const val MAX_USB_ID = 0xFFFF
    }
}

/**
 * Read-only view of what is plugged into the phone's USB port, shown on the
 * Monitor screen.
 *
 * This deliberately replaces the earlier passthrough/mount features: nothing
 * is ever attached to or detached from the virtual machine, so no USB device
 * can affect whether the VM boots. Every probe is defensive — a missing
 * service or an unreadable device yields an empty list, never an exception.
 */
class UsbDeviceMonitor(context: Context) {

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /**
     * Devices currently plugged in, de-duplicated by ID pair. A composite
     * device (a dock, a hub) can appear once per interface in Android's
     * list; the user should see one row per physical device.
     */
    fun attachedDevices(): List<AttachedUsbDevice> = try {
        usbManager?.deviceList?.values.orEmpty()
            .distinctBy { device -> "%04x:%04x".format(device.vendorId, device.productId) }
            .map { device ->
                val kind = UsbDeviceKind.fromUsbClassCodes(classCodesOf(device))
                AttachedUsbDevice(
                    vendorId = device.vendorId,
                    productId = device.productId,
                    name = device.productName?.takeIf { it.isNotBlank() }
                        ?: "USB ${kind.displayName.lowercase()}",
                    kind = kind,
                )
            }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
    } catch (unreadable: Exception) {
        AppLog.warn(SCOPE, "listing USB devices failed", unreadable)
        emptyList()
    }

    /**
     * The device class plus every interface class. Composite devices report
     * class 0 at the device level and describe themselves per interface, so
     * both have to be considered to recognise a drive or a network adapter.
     */
    private fun classCodesOf(device: UsbDevice): List<Int> = buildList {
        add(device.deviceClass)
        for (index in 0 until device.interfaceCount) {
            add(device.getInterface(index).interfaceClass)
        }
    }

    companion object {
        private const val SCOPE = "UsbMonitor"
    }
}
