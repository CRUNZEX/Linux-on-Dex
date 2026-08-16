package com.crunzex.linuxondex.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.crunzex.linuxondex.capability.CapabilityProbe
import com.crunzex.linuxondex.capability.DeviceCapabilities
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.EngineAvailability
import com.crunzex.linuxondex.engine.EngineCandidate
import com.crunzex.linuxondex.engine.EngineKind
import com.crunzex.linuxondex.engine.EngineSelector
import com.crunzex.linuxondex.engine.SerialConsoleConnection
import com.crunzex.linuxondex.engine.VirtualizationEngine
import com.crunzex.linuxondex.engine.proot.ProotEngine
import com.crunzex.linuxondex.engine.proot.NativeX11Server
import com.crunzex.linuxondex.engine.proot.RootfsImageInstaller
import com.crunzex.linuxondex.engine.qemu.QemuAccelerator
import com.crunzex.linuxondex.engine.qemu.QemuVmEngine
import com.crunzex.linuxondex.engine.runtime.GuestProcessReaper
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.usb.UsbPassthroughManager
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The single app-facing entry point for VM lifecycle:
 * capability probing → engine choice (with the user's override) → start/stop,
 * while mirroring the active engine's state for the UI.
 */
class VmController(
    private val capabilityProbe: CapabilityProbe,
    private val paths: VmPaths,
    private val payloadInstaller: PayloadInstaller,
    private val nativeX11Server: NativeX11Server,
    private val diskManager: DiskImageManager,
    private val repository: VmRepository,
    private val preparedImages: PreparedImageRepository,
    private val usbPassthroughManager: UsbPassthroughManager? = null,
    /** The "GPU-accelerated desktop" setting, read fresh at each boot. */
    private val desktopGpuPreference: () -> Boolean = { true },
    /** Lets app layers mark a fresh session /tmp before the guest starts. */
    private val onGuestTmpPrepared: () -> Unit = {},
) {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val rootfsInstaller = RootfsImageInstaller(paths)
    private val guestProcessReaper = GuestProcessReaper(paths)

    private val _vmState = MutableStateFlow<VmState>(VmState.Idle)
    val vmState: StateFlow<VmState> = _vmState.asStateFlow()

    private val _activeEngineKind = MutableStateFlow<EngineKind?>(null)
    val activeEngineKind: StateFlow<EngineKind?> = _activeEngineKind.asStateFlow()

    private var activeEngine: VirtualizationEngine? = null
    private var stateMirror: Job? = null

    /** Fresh capability snapshot + ranked engine verdicts for diagnostics UI. */
    fun probeCapabilities(): Pair<DeviceCapabilities, List<EngineCandidate>> {
        val capabilities = capabilityProbe.probe()
        return capabilities to EngineSelector.rank(capabilities)
    }

    fun loadPrimaryConfig(): VmConfig {
        val capabilities = capabilityProbe.probe()
        return repository.loadOrCreatePrimary(
            totalDeviceRamMb = capabilities.totalRamMb,
            availableCpuCores = Runtime.getRuntime().availableProcessors(),
        )
    }

    fun updatePrimaryConfig(config: VmConfig) {
        repository.save(config)
    }

    /**
     * Selects a ready-made image and makes it the VM's boot disk. Clears any
     * installer ISO, since a prepared image needs no installation.
     */
    fun usePreparedImage(image: PreparedImage) {
        val config = loadPrimaryConfig()
        repository.save(
            config.copy(
                preparedImage = preparedImages.toConfig(image),
                installerIsoPath = null,
                bootOrder = BootOrder.DISK_FIRST,
            )
        )
        AppLog.info(SCOPE, "prepared image selected: ${image.displayName}")
    }

    /** Returns to the install-from-ISO workflow. */
    fun clearPreparedImage() {
        val config = loadPrimaryConfig()
        repository.save(config.copy(preparedImage = null))
    }

    fun listPreparedImages(): List<PreparedImage> = preparedImages.listAvailable()

    /**
     * True when [config]'s container image is already unpacked, so the next
     * start skips extraction. False for disk images, which never extract.
     */
    fun isContainerImageReady(config: VmConfig): Boolean {
        if (!config.runsInProotContainer) return false
        val archive = config.preparedImage?.diskImagePath?.let(::File) ?: return false
        return rootfsInstaller.isExtracted(archive)
    }

    /**
     * Imports a disk image picked in the file manager and selects it, so the
     * user never has to copy files onto the device by hand.
     */
    fun importPreparedImage(
        documentUri: android.net.Uri,
        onProgress: (Float) -> Unit,
    ): PreparedImage {
        val imported = preparedImages.importFromDocument(
            documentUri = documentUri,
            onProgress = onProgress,
        )
        usePreparedImage(imported)
        return imported
    }

    suspend fun startPrimaryVm() {
        val config = loadPrimaryConfig()
        lifecycleMutex.withLock {
            check(!_vmState.value.isBusy && !_vmState.value.isRunning) {
                "VM already ${_vmState.value}"
            }
            // A guest from an earlier session can outlive both its engine and
            // the app, and it keeps the display port bound. Left in place it
            // makes every later start fail with "address already in use" —
            // the failure that used to leave force-stopping the app as the
            // only way out.
            guestProcessReaper.killAllGuestProcesses()
            if (!paths.resetTransientRuntimeDirectory()) {
                throw LxdError.StorageFailed("resetting the temporary VM cache")
            }
            val engine = createEngineFor(config)
            attachEngine(engine)
            AppLog.info(SCOPE, "starting '${config.id}' with ${engine.kind}")
            engine.start(config)
        }
    }

    /**
     * Asks the guest to power down, allowing the time this engine actually
     * needs. Rushing it would force-kill a guest mid-shutdown.
     */
    suspend fun stopVm() {
        lifecycleMutex.withLock {
            val engine = activeEngine
            if (engine == null) {
                // No engine to ask, yet a guest may still be running — the
                // app can be restarted while its previous session lives on.
                // Stop must always end in Stopped, or the UI offers a Stop
                // button that does nothing and a Start that cannot succeed.
                stopWithoutEngine()
                return
            }
            engine.stop(gracePeriod = engine.kind.shutdownGraceSeconds.seconds)
        }
    }

    /** Last-resort stop: kill whatever is left and report a stopped VM. */
    private fun stopWithoutEngine() {
        val killedCount = guestProcessReaper.killAllGuestProcesses()
        AppLog.info(SCOPE, "stop requested with no active engine; killed $killedCount process(es)")
        _vmState.value = VmState.Stopped(StopReason.FORCED)
    }

    /**
     * Records that a ready-made image has finished its one-time setup, so
     * later boots skip the cloud-init seed and start straight away.
     *
     * Detected from the guest's own console: cloud-init prints its final
     * message and a login prompt appears.
     */
    fun markFirstBootCompletedIfReady() {
        val config = repository.load(VmRepository.PRIMARY_VM_ID) ?: return
        val prepared = config.preparedImage ?: return
        if (prepared.firstBootCompleted) return
        if (!hasReachedLoginPrompt()) return

        repository.save(
            config.copy(preparedImage = prepared.copy(firstBootCompleted = true))
        )
        AppLog.info(SCOPE, "first boot complete; later starts will skip the seed")
    }

    /** True when the guest console shows cloud-init done or a login prompt. */
    private fun hasReachedLoginPrompt(): Boolean {
        val console = readBootLog()
        return FIRST_BOOT_COMPLETE_MARKERS.any { marker ->
            console.contains(marker, ignoreCase = true)
        }
    }

    suspend fun forceStopVm() {
        lifecycleMutex.withLock {
            val engine = activeEngine
            if (engine == null) {
                stopWithoutEngine()
                return
            }
            engine.forceStop()
            // The engine kills what it started; this catches anything that
            // outlived it, so a forced stop always leaves a clean slate.
            guestProcessReaper.killAllGuestProcesses()
        }
    }

    fun openSerialConsole(): SerialConsoleConnection? = activeEngine?.openSerialConsole()

    /** Opens console [index] — one per terminal window. */
    fun openConsole(index: Int): SerialConsoleConnection? = activeEngine?.openConsole(index)

    /** Pid of the running VM process, for the resource monitor. */
    fun vmProcessId(): Int? = activeEngine?.vmProcessId()

    /**
     * Applies a forward to the running VM immediately. No-op (but not an
     * error) when the VM is stopped — the rule then simply takes effect at
     * the next boot through the command line.
     */
    fun applyLivePortForward(rule: PortForwardRule) {
        (activeEngine as? QemuVmEngine)
            ?.takeIf { vmState.value.isRunning }
            ?.addLivePortForward(rule)
    }

    /** Removes a forward from the running VM immediately; no-op if stopped. */
    fun removeLivePortForward(rule: PortForwardRule) {
        (activeEngine as? QemuVmEngine)
            ?.takeIf { vmState.value.isRunning }
            ?.removeLivePortForward(rule)
    }

    fun readBootLog(): String = (activeEngine as? QemuVmEngine)?.readSerialLog() ?: ""

    /**
     * Engine choice. A rootfs image decides for itself — only PRoot can run
     * a directory tree, whatever the override says. Otherwise honour the
     * user's explicit override, then fall back to the best available engine.
     * Fails with the collected per-engine reasons so the user sees exactly
     * why nothing can run.
     */
    private fun createEngineFor(config: VmConfig): VirtualizationEngine {
        val capabilities = capabilityProbe.probe()
        if (config.runsInProotContainer) {
            requireProotUsable(capabilities)
            return instantiate(EngineKind.PROOT)
        }
        val chosenKind = when (config.engineOverride) {
            EngineOverride.FORCE_KVM -> EngineKind.QEMU_KVM
            EngineOverride.FORCE_TCG -> EngineKind.QEMU_TCG
            EngineOverride.FORCE_PROOT -> EngineKind.PROOT
            EngineOverride.AUTO -> EngineSelector.selectBest(capabilities)?.kind
                ?: throw LxdError.NoEngineAvailable(describeUnavailability(capabilities))
        }
        return instantiate(chosenKind)
    }

    private fun requireProotUsable(capabilities: DeviceCapabilities) {
        val verdict = EngineSelector.rank(capabilities)
            .first { it.kind == EngineKind.PROOT }
            .availability
        if (verdict is EngineAvailability.Unavailable) {
            throw LxdError.NoEngineAvailable(
                "the selected image needs the PRoot container, " +
                    "which cannot run here: ${verdict.reason}"
            )
        }
    }

    private fun instantiate(kind: EngineKind): VirtualizationEngine = when (kind) {
        EngineKind.QEMU_KVM ->
            qemuEngine(QemuAccelerator.KVM)
        EngineKind.QEMU_TCG ->
            qemuEngine(QemuAccelerator.TCG)
        EngineKind.PROOT ->
            ProotEngine(
                paths = paths,
                payloadInstaller = payloadInstaller,
                nativeX11Server = nativeX11Server,
                desktopGpuPreference = desktopGpuPreference,
                onGuestTmpPrepared = onGuestTmpPrepared,
            )
    }

    private fun qemuEngine(accelerator: QemuAccelerator): QemuVmEngine =
        QemuVmEngine(
            paths = paths,
            payloadInstaller = payloadInstaller,
            diskManager = diskManager,
            accelerator = accelerator,
            usbPassthroughManager = usbPassthroughManager,
        )

    private fun attachEngine(engine: VirtualizationEngine) {
        stateMirror?.cancel()
        activeEngine = engine
        _activeEngineKind.value = engine.kind
        stateMirror = controllerScope.launch {
            engine.state.collect { engineState -> _vmState.value = engineState }
        }
    }

    private fun describeUnavailability(capabilities: DeviceCapabilities): String =
        EngineSelector.rank(capabilities).joinToString("; ") { candidate ->
            val reason = when (val verdict = candidate.availability) {
                is EngineAvailability.Unavailable -> verdict.reason
                EngineAvailability.Available -> "available"
            }
            "${candidate.kind.name}: $reason"
        }

    companion object {
        private const val SCOPE = "VmController"

        /**
         * Console text proving a prepared image finished configuring itself:
         * the seed's final message, or any login prompt.
         */
        private val FIRST_BOOT_COMPLETE_MARKERS = listOf(
            "Linux on DeX is ready",
            "login:",
            "Cloud-init.*finished",
        )
    }
}
