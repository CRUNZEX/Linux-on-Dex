package com.crunzex.linuxondex.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.vm.UsbDeviceSpec
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** A USB descriptor Android has opened and the app is ready to send to QEMU. */
data class OpenUsbDevice(
    val spec: UsbDeviceSpec,
    val deviceName: String,
    val androidDeviceId: Int,
    val descriptor: ParcelFileDescriptor,
) {
    /** Stable for the lifetime of one Android USB attachment. */
    val qemuDeviceId: String get() = "linuxondex-usb-$androidDeviceId"
}

/** QEMU-side resources created for one passed-through device. */
data class UsbGuestHandle(
    val qemuDeviceId: String,
    val fileDescriptorSetId: Int,
)

/**
 * Narrow boundary between Android USB ownership and the QEMU control socket.
 *
 * Keeping this interface small makes lifecycle and error handling reviewable:
 * [UsbPassthroughManager] owns Android connections, while the VM engine owns
 * QMP. Neither side needs to know the other's implementation details.
 */
interface UsbGuestChannel {
    fun attach(device: OpenUsbDevice): UsbGuestHandle
    fun detach(handle: UsbGuestHandle)
}

/**
 * Hot-plugs selected Android USB devices into a running QEMU guest.
 *
 * Android does not let an unprivileged app open `/dev/bus/usb` by path.
 * [UsbManager.openDevice] is the only supported route: it yields an already
 * open descriptor after explicit user consent. The QEMU channel sends that
 * descriptor with SCM_RIGHTS (`add-fd`) and creates `usb-host` with
 * `hostdevice=/dev/fdset/N`, matching Podroid's proven design.
 *
 * The receiver exists only while a VM session is alive. A selected device is
 * attached whether it was present at boot or plugged in later, and a detach
 * releases both QEMU and Android resources. Failures are isolated per device;
 * USB can never take the VM down with it.
 */
class UsbPassthroughManager(context: Context) {

    private data class ActiveDevice(
        val connection: UsbDeviceConnection,
        val guestHandle: UsbGuestHandle,
        /** The exact VM channel that owns [guestHandle]. */
        val guestChannel: UsbGuestChannel,
    )

    private val appContext = context.applicationContext
    private val usbManager: UsbManager? =
        appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val operationMutex = Mutex()
    private val activeDevices = ConcurrentHashMap<String, ActiveDevice>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            AppLog.error(SCOPE, "USB passthrough operation failed", error)
        }
    )

    @Volatile
    private var sessionStarted = false

    @Volatile
    private var selectedDevices: List<UsbDeviceSpec> = emptyList()

    @Volatile
    private var guestChannel: UsbGuestChannel? = null

    /**
     * Changes for every VM session. A blocking QMP attach can finish after
     * stop/start has selected another VM; the token makes that late result
     * release itself instead of entering the new session.
     */
    @Volatile
    private var activeSessionId: Long = NO_SESSION

    private var nextSessionId: Long = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(
                UsbManager.EXTRA_DEVICE,
                UsbDevice::class.java,
            ) ?: return
            when (intent.action) {
                PERMISSION_ACTION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        attachIfSelected(device)
                    } else {
                        AppLog.warn(SCOPE, "USB permission denied for ${device.deviceName}")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> requestAndAttachIfSelected(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> detach(device.deviceName)
            }
        }
    }

    /** True when Android has already granted access to the currently attached device. */
    fun hasPermission(spec: UsbDeviceSpec): Boolean =
        matchingDevices(spec).any { device -> usbManager?.hasPermission(device) == true }

    /** True when at least one device with this vendor/product pair is attached. */
    fun isAttached(spec: UsbDeviceSpec): Boolean = matchingDevices(spec).isNotEmpty()

    /**
     * Requests Android's consent dialog. The PendingIntent must be mutable:
     * Android fills in EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED before it is
     * delivered, and an immutable intent silently loses that result on modern
     * Android releases.
     */
    fun requestPermission(spec: UsbDeviceSpec) {
        matchingDevices(spec).forEach(::requestPermission)
    }

    /**
     * Starts one VM-scoped hot-plug session and scans devices already present.
     * Calling this twice replaces the old session without leaking handles.
     */
    fun startSession(specs: List<UsbDeviceSpec>, channel: UsbGuestChannel) {
        stopSession()
        if (specs.isEmpty()) return
        if (usbManager == null) {
            AppLog.warn(SCOPE, "USB service unavailable; VM starts without passthrough")
            return
        }

        selectedDevices = specs.distinctBy(UsbDeviceSpec::idKey)
        guestChannel = channel
        activeSessionId = ++nextSessionId
        val filter = IntentFilter().apply {
            addAction(PERMISSION_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sessionStarted = true

        val attached = usbManager.deviceList.values.toList()
        AppLog.info(SCOPE, "USB session armed; scanning ${attached.size} device(s)")
        attached.forEach(::requestAndAttachIfSelected)
    }

    /** Releases all Android handles. Safe after a crash and safe to call repeatedly. */
    fun stopSession() {
        if (!sessionStarted && activeDevices.isEmpty() && guestChannel == null) return
        sessionStarted = false
        activeSessionId = NO_SESSION
        runCatching { appContext.unregisterReceiver(receiver) }
        scope.coroutineContext.cancelChildren()

        val entries = activeDevices.values.toList()
        activeDevices.clear()
        entries.forEach { entry ->
            runCatching { entry.guestChannel.detach(entry.guestHandle) }
                .onFailure { AppLog.warn(SCOPE, "releasing guest USB resources failed", it) }
            runCatching { entry.connection.close() }
                .onFailure { AppLog.warn(SCOPE, "closing a USB connection failed", it) }
        }
        selectedDevices = emptyList()
        guestChannel = null
        AppLog.info(SCOPE, "USB session disarmed; released ${entries.size} device(s)")
    }

    private fun requestAndAttachIfSelected(device: UsbDevice) {
        if (!sessionStarted || selectedSpecFor(device) == null) return
        if (usbManager?.hasPermission(device) == true) {
            attachIfSelected(device)
        } else {
            requestPermission(device)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: return
        if (manager.hasPermission(device)) {
            if (sessionStarted) attachIfSelected(device)
            return
        }
        val permissionResult = PendingIntent.getBroadcast(
            appContext,
            device.deviceId,
            Intent(PERMISSION_ACTION).setPackage(appContext.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.requestPermission(device, permissionResult)
    }

    private fun attachIfSelected(device: UsbDevice) {
        val requestedSessionId = activeSessionId
        if (requestedSessionId == NO_SESSION) return
        scope.launch {
            operationMutex.withLock {
                if (!isCurrentSession(requestedSessionId) ||
                    activeDevices.containsKey(device.deviceName)
                ) {
                    return@withLock
                }
                val spec = selectedSpecFor(device) ?: return@withLock
                val manager = usbManager ?: return@withLock
                val channel = guestChannel ?: return@withLock
                if (!manager.hasPermission(device)) return@withLock

                val connection = manager.openDevice(device)
                if (connection == null) {
                    AppLog.warn(SCOPE, "Android could not open ${device.deviceName}")
                    return@withLock
                }

                // fromFd() duplicates the UsbDeviceConnection descriptor. QMP
                // duplicates it again into QEMU via SCM_RIGHTS, so this local
                // duplicate can close immediately after attach succeeds.
                val descriptor = ParcelFileDescriptor.fromFd(connection.fileDescriptor)
                try {
                    val openDevice = OpenUsbDevice(
                        spec = spec,
                        deviceName = device.deviceName,
                        androidDeviceId = device.deviceId,
                        descriptor = descriptor,
                    )
                    val handle = channel.attach(openDevice)
                    if (!isCurrentSession(requestedSessionId)) {
                        runCatching { channel.detach(handle) }
                        connection.close()
                        return@withLock
                    }
                    activeDevices[device.deviceName] = ActiveDevice(
                        connection = connection,
                        guestHandle = handle,
                        guestChannel = channel,
                    )
                    AppLog.info(SCOPE, "passed ${spec.idKey} to the guest as ${handle.qemuDeviceId}")
                } catch (error: Exception) {
                    connection.close()
                    AppLog.warn(SCOPE, "passing ${spec.idKey} to the guest failed", error)
                } finally {
                    runCatching { descriptor.close() }
                }
            }
        }
    }

    private fun detach(deviceName: String) {
        scope.launch {
            operationMutex.withLock {
                val active = activeDevices.remove(deviceName) ?: return@withLock
                runCatching { active.guestChannel.detach(active.guestHandle) }
                    .onFailure { AppLog.warn(SCOPE, "guest USB detach failed for $deviceName", it) }
                runCatching { active.connection.close() }
                AppLog.info(SCOPE, "released USB device $deviceName")
            }
        }
    }

    private fun matchingDevices(spec: UsbDeviceSpec): List<UsbDevice> =
        usbManager?.deviceList?.values.orEmpty().filter { device ->
            device.vendorId == spec.vendorId && device.productId == spec.productId
        }

    private fun selectedSpecFor(device: UsbDevice): UsbDeviceSpec? =
        selectedDevices.firstOrNull { spec ->
            spec.vendorId == device.vendorId && spec.productId == device.productId
        }

    private fun isCurrentSession(sessionId: Long): Boolean =
        sessionStarted && sessionId == activeSessionId

    companion object {
        private const val SCOPE = "UsbPassthrough"
        private const val NO_SESSION = 0L
        const val PERMISSION_ACTION = "com.crunzex.linuxondex.USB_PERMISSION"
    }
}
