package com.crunzex.linuxondex.engine.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.EngineKind
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.engine.VirtualizationEngine
import com.crunzex.linuxondex.engine.runtime.NativeCommand
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.usb.OpenUsbDevice
import com.crunzex.linuxondex.vm.DiskImageManager
import com.crunzex.linuxondex.vm.PortForwardRule
import com.crunzex.linuxondex.vm.StopReason
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState
import com.crunzex.linuxondex.vm.iso.DirectKernelBoot
import com.crunzex.linuxondex.vm.iso.ExtractedKernel
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Approaches 1 and 2: a full-system QEMU virtual machine, hardware
 * accelerated when [QemuAccelerator.KVM] is possible, software-emulated with
 * [QemuAccelerator.TCG] everywhere else. The accelerator is the only
 * difference, so both engines share this implementation.
 */
class QemuVmEngine(
    private val paths: VmPaths,
    private val payloadInstaller: PayloadInstaller,
    private val diskManager: DiskImageManager,
    private val accelerator: QemuAccelerator,
) : VirtualizationEngine {

    override val kind: EngineKind =
        if (accelerator == QemuAccelerator.KVM) EngineKind.QEMU_KVM else EngineKind.QEMU_TCG

    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    override val state: StateFlow<VmState> = _state.asStateFlow()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val shutdownInitiated = AtomicBoolean(false)

    private var vmProcess: Process? = null
    private var exitWatcher: Job? = null
    private var activeConfig: VmConfig? = null

    /**
     * Opens the USB devices this VM should be given and yields them, still
     * held open. Set by the controller; the default passes nothing so the
     * engine works headless and in tests.
     */
    var usbDeviceProvider: (VmConfig) -> List<OpenUsbDevice> = { emptyList() }

    private fun qmpSocketFile(vmId: String) = paths.socketsDir.resolve("$vmId-qmp.sock")
    private fun serialSocketFile(vmId: String) = paths.socketsDir.resolve("$vmId-serial.sock")

    /** Socket for extra console [index] (0 → guest /dev/hvc0). */
    private fun extraConsoleSocketFile(vmId: String, index: Int) =
        paths.socketsDir.resolve("$vmId-hvc$index.sock")

    private fun extraConsoleSocketFiles(vmId: String): List<File> =
        (0 until EXTRA_CONSOLE_COUNT).map { extraConsoleSocketFile(vmId, it) }
    private fun serialLogFile(vmId: String) = paths.logsDir.resolve("$vmId-serial.log")
    private fun processLogFile(vmId: String) = paths.logsDir.resolve("$vmId-qemu-stderr.log")

    override suspend fun start(config: VmConfig) = lifecycleMutex.withLock {
        check(!_state.value.isBusy && !_state.value.isRunning) {
            "start() called while ${_state.value}"
        }
        withContext(Dispatchers.IO) {
            try {
                startLocked(config)
            } catch (error: Exception) {
                val lxdError = error as? LxdError
                    ?: LxdError.BootFailed("unexpected failure: ${error.message}", error)
                cleanupProcess()
                _state.value = VmState.Failed(lxdError)
                throw lxdError
            }
        }
    }

    private fun startLocked(config: VmConfig) {
        shutdownInitiated.set(false)
        activeConfig = config

        _state.value = VmState.Preparing("Checking virtual machine runtime")
        paths.createRuntimeDirectories()
        payloadInstaller.ensureInstalled()

        _state.value = VmState.Preparing("Preparing disks")
        diskManager.ensureRootDisk(config)
        diskManager.ensureEfiVars(config.id)
        deleteStaleSockets(config.id)
        // Each boot starts a fresh console log; the previous boot's output
        // must never satisfy "is the guest up" checks or confuse the UI.
        serialLogFile(config.id).delete()

        _state.value = VmState.Starting(kind)
        val plan = buildLaunchPlan(config)
        val command = NativeCommand(
            program = paths.qemuSystemBinary,
            arguments = QemuCommandBuilder.build(plan),
            environment = paths.processEnvironment(),
            workingDirectory = paths.vmRootDir,
        )
        val process = command.start(redirectErrorStream = true)
        vmProcess = process
        pumpProcessOutput(process, processLogFile(config.id))
        watchProcessExit(process, config)

        waitForControlChannel(process, config)
        attachUsbDevices(config)
        _state.value = VmState.Running(
            engine = kind,
            vncPort = config.vncPort,
            startedAtMillis = System.currentTimeMillis(),
        )
        AppLog.info(SCOPE, "VM '${config.id}' running via $kind on VNC port ${config.vncPort}")
    }

    /**
     * Plugs the configured USB devices into the guest now that it is up.
     *
     * They are attached after boot rather than named on the command line
     * for two reasons. Android only ever gives an app an already-open
     * descriptor, and the only way to move one into another process is to
     * send it over a socket — which needs QEMU to already be listening. And
     * a device that cannot be claimed then fails on its own instead of
     * taking the whole boot with it, which is exactly what a command-line
     * `usb-host` did.
     *
     * Every failure is contained per device: the VM runs regardless.
     */
    private fun attachUsbDevices(config: VmConfig) {
        val devices = usbDeviceProvider(config)
        if (devices.isEmpty()) return
        try {
            QmpClient.connect(qmpSocketFile(config.id)).use { qmp ->
                devices.forEach { device -> attachOneUsbDevice(qmp, device) }
            }
        } catch (failure: Exception) {
            AppLog.warn(SCOPE, "could not reach QMP to attach USB devices", failure)
        }
    }

    private fun attachOneUsbDevice(qmp: QmpClient, device: OpenUsbDevice) {
        try {
            val fdSetId = qmp.addFileDescriptorToNewSet(device.descriptor.fileDescriptor)
            qmp.attachUsbHostDevice(device.qemuDeviceId, fdSetId)
            AppLog.info(SCOPE, "attached USB ${device.spec.idKey} to the guest")
        } catch (refused: Exception) {
            AppLog.warn(
                SCOPE,
                "the guest refused USB ${device.spec.idKey}; it keeps running without it",
                refused,
            )
        }
    }

    private fun buildLaunchPlan(config: VmConfig): QemuLaunchPlan {
        val installerIso = config.installerIsoPath
            ?.let(::File)
            ?.also { isoFile ->
                if (!isoFile.exists()) {
                    throw LxdError.BootFailed("installer ISO missing: ${isoFile.name}")
                }
            }
        // A ready-made image already contains an installed bootloader, so
        // direct kernel boot only applies to the install-from-ISO path.
        val directKernel = installerIso
            ?.takeIf { config.kernelBoot.enabled && !config.usesPreparedImage }
            ?.let { iso -> prepareDirectKernel(config, iso) }

        return QemuLaunchPlan(
            config = config,
            accelerator = accelerator,
            firmwareCode = paths.firmwareCode,
            efiVarsFile = diskManager.efiVarsFile(config.id),
            rootDiskFile = diskManager.bootDiskFile(config),
            installerIso = installerIso,
            seedIso = resolveSeedIso(config),
            directKernel = directKernel,
            qemuDataDir = paths.qemuDataDir,
            sharedFolderDir = paths.sharedFolderDir?.also { it.mkdirs() },
            qmpSocketFile = qmpSocketFile(config.id),
            serialSocketFile = serialSocketFile(config.id),
            serialLogFile = serialLogFile(config.id),
            pidFile = paths.logsDir.resolve("${config.id}.pid"),
            extraConsoleSockets = extraConsoleSocketFiles(config.id),
        )
    }

    /**
     * Captures the guest framebuffer to a PPM file via QMP.
     *
     * Essential for desktop ISOs whose kernel logs to the graphical console
     * rather than the serial port. Returns null when the VM is not running
     * or QEMU refused the request.
     */
    fun captureScreenshot(): File? {
        val config = activeConfig ?: return null
        if (!_state.value.isRunning) return null
        val outputFile = paths.screenshotsDir
            .also { it.mkdirs() }
            .resolve("${config.id}-${System.currentTimeMillis()}.ppm")
        return try {
            QmpClient.connect(qmpSocketFile(config.id)).use { it.captureScreenshot(outputFile) }
            outputFile.takeIf { it.length() > 0 }
        } catch (error: Exception) {
            AppLog.warn(SCOPE, "screendump failed", error)
            null
        }
    }

    /** QEMU can take a while to create the QMP socket; poll until it accepts. */
    private fun waitForControlChannel(process: Process, config: VmConfig) {
        val socketFile = qmpSocketFile(config.id)
        val deadline = System.currentTimeMillis() + CONTROL_CHANNEL_TIMEOUT_MS
        var lastFailure: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw LxdError.BootFailed(
                    "QEMU exited during startup (code ${process.waitFor()}): " +
                        readLogTail(processLogFile(config.id))
                )
            }
            try {
                QmpClient.connect(socketFile).use { qmp ->
                    val status = qmp.queryStatus()
                    AppLog.info(SCOPE, "QMP up, guest status: $status")
                }
                return
            } catch (failure: Exception) {
                lastFailure = failure
                Thread.sleep(CONTROL_CHANNEL_RETRY_MS)
            }
        }
        throw LxdError.BootFailed(
            "control channel did not come up within ${CONTROL_CHANNEL_TIMEOUT_MS / 1000}s",
            lastFailure,
        )
    }

    override suspend fun stop(gracePeriod: Duration): Unit = lifecycleMutex.withLock {
        val process = vmProcess
        val config = activeConfig
        if (process == null || !process.isAlive || config == null) {
            _state.value = VmState.Stopped(StopReason.USER_REQUESTED)
            return
        }
        withContext(Dispatchers.IO) {
            _state.value = VmState.Stopping
            shutdownInitiated.set(true)

            val cleanPowerdown = requestPowerdownViaQmp(config)
            val exited = cleanPowerdown &&
                process.waitFor(gracePeriod.inWholeSeconds, TimeUnit.SECONDS)

            if (exited) {
                _state.value = VmState.Stopped(StopReason.USER_REQUESTED)
            } else {
                AppLog.warn(SCOPE, "graceful shutdown timed out — forcing")
                process.destroyForcibly()
                process.waitFor(FORCE_KILL_WAIT_SECONDS, TimeUnit.SECONDS)
                _state.value = VmState.Stopped(StopReason.FORCED)
            }
            cleanupProcess()
        }
    }

    override suspend fun forceStop(): Unit = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            shutdownInitiated.set(true)
            vmProcess?.destroyForcibly()
            vmProcess?.waitFor(FORCE_KILL_WAIT_SECONDS, TimeUnit.SECONDS)
            cleanupProcess()
            _state.value = VmState.Stopped(StopReason.FORCED)
        }
    }

    override fun openSerialConsole(): SerialConsoleConnection? = openConsole(index = 0)

    /**
     * Opens console [index]: 0 is the primary serial console, 1..N are the
     * extra virtio consoles (/dev/hvc0, /dev/hvc1 …) that let additional
     * terminal windows run their own shells.
     */
    override fun openConsole(index: Int): SerialConsoleConnection? {
        val config = activeConfig ?: return null
        if (!_state.value.isRunning) return null
        val socketFile = if (index <= 0) {
            serialSocketFile(config.id)
        } else {
            extraConsoleSocketFile(config.id, index - 1)
        }
        return try {
            UnixSocketConsole(socketFile)
        } catch (error: Exception) {
            AppLog.warn(SCOPE, "console $index connect failed: ${error.message}")
            null
        }
    }

    /**
     * Applies a port forward to the RUNNING VM over QMP, so mappings take
     * effect without a reboot. Persisting the rule is the caller's job.
     */
    fun addLivePortForward(rule: PortForwardRule) {
        val config = activeConfig
            ?: throw LxdError.ControlChannelFailed("no running VM to forward into")
        QmpClient.connect(qmpSocketFile(config.id)).use { qmp ->
            qmp.addPortForward(rule.slirpSpecification)
        }
    }

    /** Removes a live forward from the running VM. */
    fun removeLivePortForward(rule: PortForwardRule) {
        val config = activeConfig
            ?: throw LxdError.ControlChannelFailed("no running VM to unforward")
        QmpClient.connect(qmpSocketFile(config.id)).use { qmp ->
            qmp.removePortForward(rule.protocol.qemuName, rule.hostPort)
        }
    }

    /**
     * The QEMU child's pid, so the app can read its /proc usage. QEMU
     * writes it via `-pidfile`; Android's java.lang.Process has no pid
     * accessor, so the file is the reliable source.
     */
    override fun vmProcessId(): Int? {
        if (vmProcess?.isAlive != true) return null
        val config = activeConfig ?: return null
        return try {
            paths.logsDir.resolve("${config.id}.pid").readText().trim().toIntOrNull()
        } catch (unreadable: Exception) {
            null
        }
    }

    /**
     * The cloud-init seed, only while the prepared image still needs it.
     * Re-attaching it after first boot would make cloud-init run again.
     */
    private fun resolveSeedIso(config: VmConfig): File? {
        val prepared = config.preparedImage ?: return null
        if (prepared.firstBootCompleted) return null
        return prepared.seedIsoPath?.let(::File)?.takeIf(File::exists)
    }

    /**
     * Extracts the ISO's kernel so QEMU can boot it directly. Falls back to
     * the ISO's own bootloader when the layout is unfamiliar — an exotic ISO
     * must still boot, just without a custom command line.
     */
    private fun prepareDirectKernel(config: VmConfig, isoFile: File): ExtractedKernel? = try {
        _state.value = VmState.Preparing("Reading kernel from the installer")
        DirectKernelBoot.prepare(
            isoFile = isoFile,
            outputDirectory = paths.diskDirFor(config.id).resolve("boot"),
            extraKernelArguments = config.kernelBoot.extraArguments,
            sendConsoleToSerialPort = config.kernelBoot.consoleOnSerialPort,
        )
    } catch (error: Exception) {
        AppLog.warn(SCOPE, "direct kernel boot unavailable, using the ISO bootloader", error)
        null
    }

    /**
     * Presses keys inside the guest through QMP. Lets the app answer boot
     * menus and send combinations a soft keyboard cannot produce.
     */
    fun sendKeys(vararg qemuKeyCodes: String): Boolean {
        val config = activeConfig ?: return false
        if (!_state.value.isRunning) return false
        return try {
            QmpClient.connect(qmpSocketFile(config.id)).use { it.sendKey(*qemuKeyCodes) }
            true
        } catch (error: Exception) {
            AppLog.warn(SCOPE, "send-key failed", error)
            false
        }
    }

    /** Guest console text captured so far this boot (UEFI + kernel + login). */
    fun readSerialLog(): String {
        val config = activeConfig ?: return ""
        return readLogTail(serialLogFile(config.id), maxBytes = SERIAL_LOG_TAIL_BYTES)
    }

    private fun requestPowerdownViaQmp(config: VmConfig): Boolean = try {
        QmpClient.connect(qmpSocketFile(config.id)).use { it.requestGuestPowerdown() }
        true
    } catch (error: Exception) {
        AppLog.warn(SCOPE, "QMP powerdown failed, will force stop", error)
        false
    }

    private fun pumpProcessOutput(process: Process, logFile: File) {
        engineScope.launch {
            try {
                logFile.outputStream().bufferedWriter().use { sink ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        sink.appendLine(line)
                        sink.flush()
                        if (deservesAttention(line)) {
                            // Warn, not debug: the app's exported log keeps a
                            // bounded history, and this is exactly the line
                            // someone diagnosing a device needs to still find.
                            AppLog.warn(SCOPE, "qemu: $line")
                        } else {
                            AppLog.debug(SCOPE, "qemu: $line")
                        }
                    }
                }
            } catch (_: Exception) {
                // Stream closes when the process dies; nothing to handle.
            }
        }
    }

    private fun watchProcessExit(process: Process, config: VmConfig) {
        exitWatcher = engineScope.launch {
            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            if (shutdownInitiated.get()) return@launch // stop()/forceStop() owns the state
            when {
                _state.value.isRunning && exitCode == 0 ->
                    _state.value = VmState.Stopped(StopReason.GUEST_SHUTDOWN)
                _state.value.isRunning -> _state.value = VmState.Failed(
                    LxdError.BootFailed(
                        "QEMU exited unexpectedly (code $exitCode): " +
                            readLogTail(processLogFile(config.id))
                    )
                )
                // During Starting, waitForControlChannel reports the failure.
            }
        }
    }

    private fun cleanupProcess() {
        exitWatcher?.cancel()
        exitWatcher = null
        vmProcess = null
        activeConfig?.id?.let(::deleteStaleSockets)
    }

    private fun deleteStaleSockets(vmId: String) {
        qmpSocketFile(vmId).delete()
        serialSocketFile(vmId).delete()
        extraConsoleSocketFiles(vmId).forEach { it.delete() }
    }

    private fun readLogTail(logFile: File, maxBytes: Int = ERROR_LOG_TAIL_BYTES): String {
        if (!logFile.exists()) return "(no log)"
        return try {
            val bytes = logFile.readBytes()
            String(bytes.copyOfRange((bytes.size - maxBytes).coerceAtLeast(0), bytes.size))
        } catch (error: Exception) {
            "(log unreadable: ${error.message})"
        }
    }

    private class UnixSocketConsole(socketFile: File) : SerialConsoleConnection {
        private val socket = LocalSocket().apply {
            connect(
                LocalSocketAddress(
                    socketFile.absolutePath,
                    LocalSocketAddress.Namespace.FILESYSTEM,
                )
            )
        }

        override fun read(buffer: ByteArray): Int = socket.inputStream.read(buffer)

        override fun write(data: ByteArray) {
            socket.outputStream.write(data)
            socket.outputStream.flush()
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val SCOPE = "QemuVmEngine"
        private const val CONTROL_CHANNEL_TIMEOUT_MS = 45_000L
        private const val CONTROL_CHANNEL_RETRY_MS = 300L
        private const val FORCE_KILL_WAIT_SECONDS = 5L
        private const val ERROR_LOG_TAIL_BYTES = 2_000
        private const val SERIAL_LOG_TAIL_BYTES = 64_000

        /**
         * Extra virtio consoles beyond the primary serial one, so additional
         * terminal windows get their own shells. Two is plenty for a phone
         * screen and costs nothing when unused.
         */
        const val EXTRA_CONSOLE_COUNT = 2

        /**
         * Words that mark a QEMU line worth keeping in the app's own log.
         *
         * QEMU is quiet when things work and terse when they do not, and its
         * failures — a USB device it could not claim above all — otherwise
         * only reach a file nobody exports. Matching is deliberately loose:
         * a few extra lines cost nothing, a missing one costs a diagnosis.
         */
        private val NOTEWORTHY_QEMU_OUTPUT = listOf(
            "error", "failed", "warning", "cannot", "could not",
            "usb", "libusb", "husb", "fdset",
        )

        internal fun deservesAttention(line: String): Boolean {
            val lowercase = line.lowercase()
            return NOTEWORTHY_QEMU_OUTPUT.any { it in lowercase }
        }
    }
}
