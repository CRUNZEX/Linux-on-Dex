package com.crunzex.linuxondex.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.vm.UsbDeviceSpec

/** A device this app holds open, ready to be handed to the guest. */
data class OpenUsbDevice(
    val spec: UsbDeviceSpec,
    /** Sent to QEMU over the monitor socket; closed when the VM stops. */
    val descriptor: ParcelFileDescriptor,
) {
    /** Identifies the device inside QEMU, e.g. "usbdev-0bda-8153". */
    val qemuDeviceId: String get() = "usbdev-${spec.idKey.replace(':', '-')}"
}

/**
 * Hands real USB devices to the guest.
 *
 * Android never lets an app open a USB device by path; it opens the device
 * itself and returns an already-open file descriptor. That descriptor is the
 * only handle that exists, so passthrough is really a question of getting it
 * into QEMU — which the engine does by sending it over the monitor socket
 * once the guest is up.
 *
 * The connections are owned here rather than by the engine: they must
 * outlive the call that starts the VM, and be closed exactly once when it
 * stops so Android gets the devices back.
 */
class UsbPassthroughManager(private val context: Context) {

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val openDevices = mutableListOf<Pair<UsbDeviceConnection, ParcelFileDescriptor>>()

    /** True when Android has already granted this app access to [spec]. */
    fun hasPermission(spec: UsbDeviceSpec): Boolean =
        findDevice(spec)?.let { usbManager?.hasPermission(it) } == true

    /** True when the device is plugged in right now. */
    fun isAttached(spec: UsbDeviceSpec): Boolean = findDevice(spec) != null

    /**
     * Asks Android to show its permission dialog for [spec]. The answer
     * arrives as a broadcast the caller does not have to handle: the next
     * VM start simply checks [hasPermission] again.
     */
    fun requestPermission(spec: UsbDeviceSpec) {
        val device = findDevice(spec) ?: return
        val manager = usbManager ?: return
        if (manager.hasPermission(device)) return
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(PERMISSION_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.requestPermission(device, intent)
    }

    /**
     * Opens every device in [specs] and returns those that could be opened.
     *
     * A device that is unplugged, refused or cannot be opened is skipped
     * rather than failing the boot: "start without that one" is always a
     * better outcome than a VM that will not start. An empty list is
     * therefore a perfectly normal answer.
     */
    fun openForPassthrough(specs: List<UsbDeviceSpec>): List<OpenUsbDevice> {
        releaseDevices()
        if (specs.isEmpty()) return emptyList()
        val manager = usbManager ?: return emptyList()
        return specs.mapNotNull { spec -> openDevice(manager, spec) }
    }

    /** Gives every device back to Android; safe to call more than once. */
    fun releaseDevices() {
        openDevices.forEach { (connection, descriptor) ->
            runCatching { descriptor.close() }
                .onFailure { AppLog.warn(SCOPE, "closing a USB descriptor failed", it) }
            runCatching { connection.close() }
                .onFailure { AppLog.warn(SCOPE, "closing a USB connection failed", it) }
        }
        openDevices.clear()
    }

    private fun openDevice(manager: UsbManager, spec: UsbDeviceSpec): OpenUsbDevice? {
        val device = findDevice(spec) ?: run {
            AppLog.warn(SCOPE, "${spec.idKey} is not plugged in; starting without it")
            return null
        }
        if (!manager.hasPermission(device)) {
            AppLog.warn(SCOPE, "no permission for ${spec.idKey}; starting without it")
            return null
        }
        return try {
            val connection = manager.openDevice(device) ?: run {
                AppLog.warn(SCOPE, "could not open ${spec.idKey}; starting without it")
                return null
            }
            // A duplicate, so the descriptor sent to QEMU has a lifetime this
            // class controls rather than one tied to the connection object.
            val descriptor = ParcelFileDescriptor.fromFd(connection.fileDescriptor)
            openDevices.add(connection to descriptor)
            AppLog.info(SCOPE, "holding ${spec.idKey} open for the guest")
            OpenUsbDevice(spec, descriptor)
        } catch (refused: Exception) {
            AppLog.warn(SCOPE, "opening ${spec.idKey} failed", refused)
            null
        }
    }

    private fun findDevice(spec: UsbDeviceSpec): UsbDevice? =
        usbManager?.deviceList?.values?.firstOrNull { device ->
            device.vendorId == spec.vendorId && device.productId == spec.productId
        }

    companion object {
        private const val SCOPE = "UsbPassthrough"

        /** Broadcast Android answers permission requests on. */
        const val PERMISSION_ACTION = "com.crunzex.linuxondex.USB_PERMISSION"
    }
}
