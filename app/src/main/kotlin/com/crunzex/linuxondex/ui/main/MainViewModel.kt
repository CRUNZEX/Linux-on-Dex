package com.crunzex.linuxondex.ui.main

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.crunzex.linuxondex.AppContainer
import com.crunzex.linuxondex.capability.DeviceCapabilities
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.EngineCandidate
import com.crunzex.linuxondex.engine.EngineKind
import com.crunzex.linuxondex.monitor.VmResourceMonitor
import com.crunzex.linuxondex.monitor.VmResourceUsage
import com.crunzex.linuxondex.service.VmService
import com.crunzex.linuxondex.vm.IsoFile
import com.crunzex.linuxondex.vm.PortForwardRule
import com.crunzex.linuxondex.vm.PreparedImage
import com.crunzex.linuxondex.vm.ScreenResolution
import com.crunzex.linuxondex.usb.AttachedUsbDevice
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.VmState

data class MainUiState(
    val vmState: VmState = VmState.Idle,
    val activeEngine: EngineKind? = null,
    val config: VmConfig? = null,
    val capabilities: DeviceCapabilities? = null,
    val engineCandidates: List<EngineCandidate> = emptyList(),
    val availableIsos: List<IsoFile> = emptyList(),
    val rootDiskExists: Boolean = false,
    /** Blocking configuration problems, phrased for the user. */
    val configurationProblems: List<String> = emptyList(),
    /** Ready-made VM images found on the device. */
    val preparedImages: List<PreparedImage> = emptyList(),
    /** Non-null while an ISO import is running: 0.0..1.0, or -1f if unknown. */
    val isoImportProgress: Float? = null,
    /** Non-null while a VM image import is running. */
    val vmImageImportProgress: Float? = null,
    /** One-shot banner message (errors, confirmations). */
    val userMessage: String? = null,
    /** Live host-side cost of the VM; null while it is not measurable. */
    val resourceUsage: VmResourceUsage? = null,
    /** Recent samples for the monitor detail charts, oldest first. */
    val resourceHistory: List<VmResourceUsage> = emptyList(),
    /** USB devices plugged into the phone — a read-only monitor list. */
    val attachedUsbDevices: List<AttachedUsbDevice> = emptyList(),
)

/**
 * Presentation state for the home/setup/diagnostics screens. All heavy work
 * is delegated to [com.crunzex.linuxondex.vm.VmController] off the main
 * thread; every user action funnels errors into [MainUiState.userMessage].
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** Signals MainActivity to start/stop the foreground VM service. */
    val vmState: StateFlow<VmState> get() = container.vmController.vmState

    init {
        observeVmState()
        observeResourceUsage()
        refresh()
    }

    /**
     * Streams the VM's host-side CPU/RAM while it runs. The monitor emits
     * null when there is no process to measure, which clears the card.
     */
    private fun observeResourceUsage() {
        val monitor = VmResourceMonitor(
            vmProcessId = { container.vmController.vmProcessId() },
        )
        viewModelScope.launch(Dispatchers.IO) {
            monitor.usage().collect { usage ->
                _uiState.update { state ->
                    state.copy(
                        resourceUsage = usage,
                        // Keep a bounded history so the detail screen can
                        // chart it; clear it when the VM stops measuring.
                        resourceHistory = if (usage == null) {
                            emptyList()
                        } else {
                            (state.resourceHistory + usage).takeLast(RESOURCE_HISTORY_SAMPLES)
                        },
                    )
                }
            }
        }
    }

    private fun observeVmState() {
        viewModelScope.launch {
            var previousState: VmState? = null
            container.vmController.vmState.collect { newState ->
                _uiState.update {
                    it.copy(
                        vmState = newState,
                        activeEngine = container.vmController.activeEngineKind.value,
                    )
                }
                // Reaching Running (disk created) or leaving it changes what
                // the home rows should show — refresh the snapshot once.
                val crossedLifecycleEdge =
                    (newState is VmState.Running) != (previousState is VmState.Running)
                if (crossedLifecycleEdge) refresh()
                // Leaving Running is when the boot log is complete enough to
                // tell whether a prepared image finished configuring itself.
                if (previousState is VmState.Running && newState !is VmState.Running) {
                    runCatching { container.vmController.markFirstBootCompletedIfReady() }
                }
                previousState = newState
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (capabilities, candidates) = container.vmController.probeCapabilities()
                val config = container.vmController.loadPrimaryConfig()
                _uiState.update {
                    it.copy(
                        capabilities = capabilities,
                        engineCandidates = candidates,
                        config = config,
                        availableIsos = container.isoRepository.listAvailable(),
                        rootDiskExists = container.diskImageManager.rootDiskExists(config.id),
                        configurationProblems =
                            config.validationProblems(capabilities.totalRamMb),
                        preparedImages = container.vmController.listPreparedImages(),
                        attachedUsbDevices = container.usbDeviceMonitor.attachedDevices(),
                    )
                }
            } catch (error: Exception) {
                AppLog.error(SCOPE, "refresh failed", error)
                postMessage(describe(error))
            }
        }
    }

    /**
     * The VM must outlive the activity, so start/stop go through the
     * foreground service; it drives VmController and owns the wake lock.
     */
    fun startVm() {
        val state = _uiState.value.vmState
        if (state.isBusy || state.isRunning) return
        // A new boot means a new console: clear the terminal so its replay
        // of the boot log starts from this boot, not the previous one.
        container.terminalSession.prepareForNewBoot()
        VmService.requestStart(container.appContext)
    }

    fun stopVm() {
        VmService.requestStop(container.appContext)
    }

    /**
     * Imports an ISO in the background, publishing progress so the UI can
     * show it. A multi-gigabyte copy otherwise looks like a frozen app.
     */
    fun importIso(documentUri: Uri) {
        if (_uiState.value.isoImportProgress != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isoImportProgress = 0f) }
            try {
                val imported = container.isoRepository.importFromDocument(documentUri) { fraction ->
                    _uiState.update { it.copy(isoImportProgress = fraction) }
                }
                postMessage("Imported ${imported.displayName}")
                refresh()
            } catch (error: Exception) {
                AppLog.error(SCOPE, "ISO import failed", error)
                postMessage(describe(error))
            } finally {
                _uiState.update { it.copy(isoImportProgress = null) }
            }
        }
    }

    /**
     * Imports a ready-made disk image chosen in the file picker. The seed
     * that configures the dex account is generated here, so the user only
     * has to pick the disk.
     */
    fun importPreparedImage(documentUri: Uri) {
        if (_uiState.value.vmImageImportProgress != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(vmImageImportProgress = 0f) }
            try {
                val imported = container.vmController.importPreparedImage(documentUri) { fraction ->
                    _uiState.update { it.copy(vmImageImportProgress = fraction) }
                }
                postMessage("${imported.displayName} is ready to start")
                refresh()
            } catch (error: Exception) {
                AppLog.error(SCOPE, "VM image import failed", error)
                postMessage(describe(error))
            } finally {
                _uiState.update { it.copy(vmImageImportProgress = null) }
            }
        }
    }

    /** Switches the VM to a ready-made image — the no-install fast path. */
    fun usePreparedImage(image: PreparedImage) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.vmController.usePreparedImage(image)
                postMessage("${image.displayName} is ready to start")
                refresh()
            } catch (error: Exception) {
                AppLog.error(SCOPE, "selecting prepared image failed", error)
                postMessage(describe(error))
            }
        }
    }

    fun clearPreparedImage() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.vmController.clearPreparedImage() }
            refresh()
        }
    }

    /**
     * Deletes a ready-made image (and its paired seed) from storage. If the
     * current config was booting it, that selection is cleared first so the
     * VM never points at a file that no longer exists.
     */
    fun deletePreparedImage(image: PreparedImage) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val selectedPath = _uiState.value.config?.preparedImage?.diskImagePath
                if (selectedPath == image.diskFile.absolutePath) {
                    container.vmController.clearPreparedImage()
                }
                container.preparedImageRepository.delete(image)
                postMessage("Deleted ${image.displayName}")
                refresh()
            } catch (error: Exception) {
                AppLog.error(SCOPE, "deleting prepared image failed", error)
                postMessage(describe(error))
            }
        }
    }

    fun selectIso(iso: IsoFile?) = updateConfig { config ->
        config.copy(installerIsoPath = iso?.file?.absolutePath)
    }

    /**
     * Deletes an imported installer ISO. Clears it from the config first if
     * it was the selected installer, so no stale path survives.
     */
    fun deleteIso(iso: IsoFile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_uiState.value.config?.installerIsoPath == iso.file.absolutePath) {
                    withContext(Dispatchers.Main.immediate) { selectIso(null) }
                }
                container.isoRepository.delete(iso)
                postMessage("Deleted ${iso.displayName}")
                refresh()
            } catch (error: Exception) {
                AppLog.error(SCOPE, "deleting ISO failed", error)
                postMessage(describe(error))
            }
        }
    }

    /** Persists a fully-formed configuration edited in the settings screen. */
    fun updateConfig(updated: VmConfig) = updateConfig { updated }

    /**
     * Raises resources to what a desktop distribution actually needs — the
     * conservative default is sized for a headless server guest.
     */
    /**
     * Adds a port forward: persisted for future boots and, when the VM is
     * already running, applied live over QMP so it works right away.
     */
    fun addPortForward(rule: PortForwardRule) {
        val current = _uiState.value.config ?: return
        val duplicate = current.network.portForwards.any {
            it.protocol == rule.protocol && it.hostPort == rule.hostPort
        }
        if (duplicate) {
            _uiState.update { it.copy(userMessage = "localhost:${rule.hostPort} is already forwarded") }
            return
        }
        updateConfig { config ->
            config.copy(network = config.network.copy(portForwards = config.network.portForwards + rule))
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.vmController.applyLivePortForward(rule)
            } catch (error: Exception) {
                AppLog.warn(SCOPE, "live hostfwd add failed", error)
                _uiState.update {
                    it.copy(userMessage = "Forward saved, but applying it live failed — restart the VM")
                }
            }
        }
    }

    fun removePortForward(rule: PortForwardRule) {
        updateConfig { config ->
            config.copy(network = config.network.copy(portForwards = config.network.portForwards - rule))
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.vmController.removeLivePortForward(rule)
            } catch (error: Exception) {
                AppLog.warn(SCOPE, "live hostfwd remove failed", error)
            }
        }
    }

    /**
     * Re-reads plugged-in USB devices for the Monitor screen's read-only
     * list; called when that screen resumes and on plug/unplug broadcasts.
     */
    fun refreshUsbDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            val attached = container.usbDeviceMonitor.attachedDevices()
            _uiState.update { it.copy(attachedUsbDevices = attached) }
        }
    }

    fun applyDesktopPreset() = updateConfig { config ->
        val deviceRamMb = _uiState.value.capabilities?.totalRamMb ?: 0
        val targetMemoryMb = if (deviceRamMb > 0) {
            VmConfig.RECOMMENDED_DESKTOP_MEMORY_MB
                .coerceAtMost(VmConfig.maxSafeGuestMemoryMb(deviceRamMb))
        } else {
            VmConfig.RECOMMENDED_DESKTOP_MEMORY_MB
        }
        val deviceCores = Runtime.getRuntime().availableProcessors()
        config.copy(
            memoryMb = targetMemoryMb,
            // Software rendering scales with vCPUs — give the desktop the
            // full default allocation, not just a minimum of four.
            cpu = config.cpu.copy(
                coreCount = config.cpu.coreCount
                    .coerceAtLeast(VmConfig.defaultCoreCount(deviceCores))
            ),
            storage = config.storage.copy(
                diskSizeGb = config.storage.diskSizeGb
                    .coerceAtLeast(VmConfig.RECOMMENDED_DESKTOP_DISK_GB)
            ),
            // Resolution is the strongest FPS lever there is: with no GPU
            // for the guest, every pixel is drawn by the emulated CPU and
            // then shipped over VNC, so cost scales linearly with pixel
            // count. 1024×600 is 40% fewer pixels than 1280×800 and less
            // than a third of 1080p. Raise it in Display settings once the
            // desktop feels comfortable.
            display = config.display.copy(resolution = ScreenResolution.HD_1024_600),
        )
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    private fun updateConfig(transform: (VmConfig) -> VmConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = container.vmController.loadPrimaryConfig()
                val updated = transform(current)
                container.vmController.updatePrimaryConfig(updated)
                refresh()
            } catch (error: Exception) {
                AppLog.error(SCOPE, "config update failed", error)
                postMessage(describe(error))
            }
        }
    }

    private suspend fun postMessage(message: String) = withContext(Dispatchers.Main) {
        _uiState.update { it.copy(userMessage = message) }
    }

    private fun describe(error: Exception): String = when (error) {
        is LxdError -> error.userMessage
        is IllegalStateException -> error.message ?: "Invalid operation"
        else -> "Unexpected error: ${error.message}"
    }

    companion object {
        private const val SCOPE = "MainViewModel"

        /** ~2 minutes of history at the 2 s sample interval. */
        private const val RESOURCE_HISTORY_SAMPLES = 60

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(container) as T
            }
    }
}
