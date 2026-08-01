package com.crunzex.linuxondex

import android.content.Context
import com.crunzex.linuxondex.about.AboutRepository
import com.crunzex.linuxondex.about.UpdateNotice
import com.crunzex.linuxondex.capability.CapabilityProbe
import com.crunzex.linuxondex.core.DiagnosticsLogExporter
import com.crunzex.linuxondex.engine.qemu.QemuVmEngine
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.terminal.TerminalSession
import com.crunzex.linuxondex.usb.UsbDeviceMonitor
import com.crunzex.linuxondex.usb.UsbPassthroughManager
import com.crunzex.linuxondex.vm.DiskImageManager
import com.crunzex.linuxondex.vm.VmBackupManager
import com.crunzex.linuxondex.vm.IsoRepository
import com.crunzex.linuxondex.vm.PreparedImageRepository
import com.crunzex.linuxondex.vm.VmController
import com.crunzex.linuxondex.vm.VmRepository

/**
 * Hand-rolled dependency container: one instance per process, owned by
 * [LinuxOnDexApp]. Kept deliberately simple instead of a DI framework so the
 * object graph is readable in one file.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val capabilityProbe: CapabilityProbe by lazy { CapabilityProbe(appContext) }

    val vmPaths: VmPaths by lazy { VmPaths(appContext) }

    /** Saves the recent log to Downloads for sharing. */
    val logExporter: DiagnosticsLogExporter by lazy {
        DiagnosticsLogExporter(appContext)
    }

    val payloadInstaller: PayloadInstaller by lazy { PayloadInstaller(appContext, vmPaths) }

    val diskImageManager: DiskImageManager by lazy { DiskImageManager(vmPaths) }

    val vmRepository: VmRepository by lazy { VmRepository(vmPaths) }

    /** Saves the VM disk to a file the user can copy off the phone. */
    val vmBackupManager: VmBackupManager by lazy {
        VmBackupManager(appContext, vmPaths, diskImageManager)
    }

    val isoRepository: IsoRepository by lazy { IsoRepository(appContext, vmPaths) }

    val preparedImageRepository: PreparedImageRepository by lazy {
        PreparedImageRepository(appContext, vmPaths)
    }

    /** Read-only USB listing for the Monitor screen; never touches the VM. */
    val usbDeviceMonitor: UsbDeviceMonitor by lazy { UsbDeviceMonitor(appContext) }

    /** Hands one USB device to the guest while the VM runs. */
    val usbPassthroughManager: UsbPassthroughManager by lazy {
        UsbPassthroughManager(appContext)
    }

    /** Developer profile, installed version and update check for About. */
    val aboutRepository: AboutRepository by lazy { AboutRepository(appContext) }

    /** Remembers which release the user has already been shown. */
    val updateNotice: UpdateNotice by lazy { UpdateNotice(appContext) }

    val vmController: VmController by lazy {
        VmController(
            capabilityProbe = capabilityProbe,
            paths = vmPaths,
            payloadInstaller = payloadInstaller,
            diskManager = diskImageManager,
            repository = vmRepository,
            preparedImages = preparedImageRepository,
            // Opened at boot; a device that is unplugged or not permitted is
            // simply skipped and the VM starts without that one.
            usbDeviceProvider = { config ->
                usbPassthroughManager.openForPassthrough(config.usb.passthroughDevices)
            },
        )
    }

    /**
     * One terminal session per guest console: index 0 is the primary serial
     * console, 1 and 2 are the extra virtio consoles with their own shells.
     *
     * Process-scoped, so a session survives navigation and can be rendered
     * by several windows (in-app screen, popped-out DeX window).
     */
    val terminalSessions: List<TerminalSession> by lazy {
        List(TERMINAL_WINDOW_COUNT) { index ->
            TerminalSession(vmController, consoleIndex = index, appContext = appContext)
        }
    }

    /** The main terminal, shown by the in-app Terminal screen. */
    val terminalSession: TerminalSession get() = terminalSessions.first()

    /**
     * The session for terminal window [index], clamped to the sessions that
     * exist so a stale window intent can never index out of bounds.
     */
    fun terminalSessionAt(index: Int): TerminalSession =
        terminalSessions[index.coerceIn(terminalSessions.indices)]

    companion object {
        /** Primary serial console plus the engine's two extra consoles. */
        const val TERMINAL_WINDOW_COUNT = 1 + QemuVmEngine.EXTRA_CONSOLE_COUNT
    }
}
