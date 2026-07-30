package com.crunzex.linuxondex.vm

import kotlinx.serialization.Serializable

/**
 * Complete, user-editable definition of one virtual machine.
 *
 * Grouped into small sub-configs so each area (CPU, storage, display,
 * network) validates itself and maps 1:1 onto a settings section in the UI.
 * Persisted as JSON by [VmRepository]; consumed by the engines.
 */
@Serializable
data class VmConfig(
    val id: String,
    val name: String,
    val cpu: CpuConfig = CpuConfig(),
    val memoryMb: Int = 2048,
    val storage: StorageConfig = StorageConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val network: NetworkConfig = NetworkConfig(),
    val sharedFolder: SharedFolderConfig = SharedFolderConfig(),
    /** Absolute path of the installer ISO; null once installation finished. */
    val installerIsoPath: String? = null,
    /**
     * A ready-made disk imported instead of installing from an ISO. When
     * set, the VM boots this image directly and no installer runs.
     */
    val preparedImage: PreparedImageConfig? = null,
    val bootOrder: BootOrder = BootOrder.INSTALLER_FIRST,
    val kernelBoot: KernelBootConfig = KernelBootConfig(),
    /** Pin a specific engine instead of automatic best-available selection. */
    val engineOverride: EngineOverride = EngineOverride.AUTO,
) {
    init {
        require(id.matches(SAFE_ID_REGEX)) { "invalid vm id: $id" }
        require(memoryMb in MIN_MEMORY_MB..MAX_MEMORY_MB) {
            "memoryMb out of range: $memoryMb"
        }
    }

    val vncPort: Int get() = VNC_BASE_PORT + display.vncDisplayNumber

    /** True when this VM runs a ready-made image rather than an installer. */
    val usesPreparedImage: Boolean get() = preparedImage != null

    /** True when the installer ISO should be attached and bootable. */
    val bootsFromInstaller: Boolean
        get() = !usesPreparedImage &&
            installerIsoPath != null &&
            bootOrder == BootOrder.INSTALLER_FIRST

    /**
     * Problems that should block starting the VM, phrased for the user.
     * Empty list means the configuration is startable.
     */
    fun validationProblems(deviceTotalRamMb: Int): List<String> = buildList {
        if (deviceTotalRamMb > 0) {
            val safeCeiling = maxSafeGuestMemoryMb(deviceTotalRamMb)
            if (memoryMb > safeCeiling) {
                add(
                    "Memory ($memoryMb MB) leaves too little for Android; " +
                        "keep it at or below $safeCeiling MB"
                )
            }
        }
        if (!usesPreparedImage &&
            installerIsoPath == null &&
            bootOrder == BootOrder.INSTALLER_FIRST
        ) {
            add("Boot order is set to installer first, but no ISO is selected")
        }
        if (cpu.model == CpuModel.HOST && engineOverride == EngineOverride.FORCE_TCG) {
            add("CPU model \"host\" needs hardware virtualization; pick \"max\" for the software VM")
        }
        if (network.sshPortForward != null && network.mode == NetworkMode.DISABLED) {
            add("Port forwarding needs networking enabled")
        }
    }

    companion object {
        private val SAFE_ID_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,40}$")

        const val VNC_BASE_PORT = 5900
        const val MIN_MEMORY_MB = 512
        const val MAX_MEMORY_MB = 32 * 1024

        /** Android needs headroom; never hand the guest more than this. */
        fun maxSafeGuestMemoryMb(deviceTotalRamMb: Int): Int =
            (deviceTotalRamMb - ANDROID_RESERVED_RAM_MB).coerceAtLeast(MIN_MEMORY_MB)

        private const val ANDROID_RESERVED_RAM_MB = 2048

        /**
         * Defaults scaled to the device: roughly a third of RAM (capped at
         * 4 GB), and all cores but two. Guest rendering is software
         * (llvmpipe scales with threads), so vCPUs are the main smoothness
         * lever; keeping two big cores back leaves Android responsive.
         */
        fun createDefault(totalDeviceRamMb: Int, availableCpuCores: Int): VmConfig {
            val guestMemoryMb = (totalDeviceRamMb / 3)
                .coerceIn(MIN_MEMORY_MB, 4 * 1024)
                .coerceAtMost(maxSafeGuestMemoryMb(totalDeviceRamMb))
            return VmConfig(
                id = "primary",
                name = "Linux VM",
                cpu = CpuConfig(coreCount = defaultCoreCount(availableCpuCores)),
                memoryMb = guestMemoryMb,
            )
        }

        /** All but two device cores, within [2, 6]. */
        fun defaultCoreCount(availableCpuCores: Int): Int =
            (availableCpuCores - 2).coerceIn(2, 6)

        /**
         * A desktop distribution ISO (Ubuntu, Fedora Workstation…) needs far
         * more than the conservative default: a 100+ MB initrd, a squashfs
         * overlay and a full desktop session.
         */
        const val RECOMMENDED_DESKTOP_MEMORY_MB = 4096
        const val RECOMMENDED_DESKTOP_DISK_GB = 32
    }
}

// ---- CPU --------------------------------------------------------------------

@Serializable
data class CpuConfig(
    val coreCount: Int = 2,
    val model: CpuModel = CpuModel.MAX,
) {
    init {
        require(coreCount in MIN_CORES..MAX_CORES) { "coreCount out of range: $coreCount" }
    }

    companion object {
        const val MIN_CORES = 1
        const val MAX_CORES = 16
    }
}

/** ARM64 CPU models the bundled QEMU offers. */
@Serializable
enum class CpuModel(
    val qemuName: String,
    val displayName: String,
    val description: String,
    /** "host" only exists when a hardware accelerator is in use. */
    val requiresHardwareVirtualization: Boolean = false,
) {
    MAX("max", "Maximum features", "All features QEMU can emulate (recommended)"),
    HOST("host", "Host passthrough", "Mirror the physical CPU", requiresHardwareVirtualization = true),
    NEOVERSE_N1("neoverse-n1", "Neoverse N1", "Server-class ARMv8.2 profile"),
    CORTEX_A710("cortex-a710", "Cortex-A710", "Modern big core (ARMv9)"),
    CORTEX_A76("cortex-a76", "Cortex-A76", "Widely compatible big core"),
    CORTEX_A53("cortex-a53", "Cortex-A53", "Minimal ARMv8.0 baseline"),
}

// ---- Storage ----------------------------------------------------------------

@Serializable
data class StorageConfig(
    val diskSizeGb: Int = 16,
    val cacheMode: DiskCacheMode = DiskCacheMode.WRITEBACK,
    val diskInterface: DiskInterface = DiskInterface.VIRTIO_BLK,
) {
    init {
        require(diskSizeGb in MIN_DISK_GB..MAX_DISK_GB) {
            "diskSizeGb out of range: $diskSizeGb"
        }
    }

    companion object {
        const val MIN_DISK_GB = 4
        const val MAX_DISK_GB = 512
    }
}

@Serializable
enum class DiskCacheMode(
    val qemuValue: String,
    val displayName: String,
    val description: String,
) {
    WRITEBACK("writeback", "Write-back", "Fastest; small risk on sudden power loss"),
    WRITETHROUGH("writethrough", "Write-through", "Safest, noticeably slower"),
    NONE("none", "No host cache", "Bypass Android's page cache"),
}

@Serializable
enum class DiskInterface(
    val displayName: String,
    val description: String,
) {
    VIRTIO_BLK("VirtIO block", "Lowest overhead (recommended)"),
    VIRTIO_SCSI("VirtIO SCSI", "Adds TRIM/UNMAP and hot-plug support"),
}

// ---- Display ----------------------------------------------------------------

@Serializable
data class DisplayConfig(
    val adapter: DisplayAdapter = DisplayAdapter.VIRTIO_GPU,
    val resolution: ScreenResolution = ScreenResolution.WXGA_1280_800,
    /** VNC server listens on 127.0.0.1:(5900 + this). */
    val vncDisplayNumber: Int = 1,
) {
    init {
        require(vncDisplayNumber in 0..99) {
            "vncDisplayNumber out of range: $vncDisplayNumber"
        }
    }
}

@Serializable
enum class DisplayAdapter(
    val qemuDevice: String,
    val displayName: String,
    val description: String,
    /** ramfb is a plain framebuffer on the sysbus, not a PCI device. */
    val supportsCustomResolution: Boolean = true,
) {
    VIRTIO_GPU("virtio-gpu-pci", "VirtIO GPU", "Best performance and resolution control"),
    VGA("VGA", "Standard VGA", "Maximum compatibility with older guests", supportsCustomResolution = false),
    RAMFB("ramfb", "Simple framebuffer", "Minimal device for text-mode guests", supportsCustomResolution = false),
}

@Serializable
enum class ScreenResolution(val widthPx: Int, val heightPx: Int, val displayName: String) {
    SVGA_800_600(800, 600, "800 × 600"),
    // Under software rendering, frame cost scales with pixel count: this is
    // 40% fewer pixels than 1280x800 and a quarter of 1080p, which is the
    // difference between a sluggish desktop and a usable one.
    HD_1024_600(1024, 600, "1024 × 600 (fastest)"),
    WXGA_1280_800(1280, 800, "1280 × 800"),
    HD_1366_768(1366, 768, "1366 × 768"),
    // The desktop sweet spot without a GPU: 42% fewer pixels than 1080p,
    // still comfortable on a DeX monitor.
    HD_PLUS_1600_900(1600, 900, "1600 × 900 (smooth)"),
    FHD_1920_1080(1920, 1080, "1920 × 1080 (DeX)"),
    QHD_2560_1440(2560, 1440, "2560 × 1440"),
}

// ---- Network ----------------------------------------------------------------

/**
 * One forwarded port: connections to 127.0.0.1:[hostPort] on the phone are
 * delivered to [guestPort] inside the VM.
 */
@Serializable
data class PortForwardRule(
    val protocol: PortProtocol = PortProtocol.TCP,
    val hostPort: Int,
    val guestPort: Int,
) {
    init {
        require(hostPort in NetworkConfig.MIN_HOST_PORT..NetworkConfig.MAX_PORT) {
            "hostPort out of range: $hostPort (apps may only bind ${NetworkConfig.MIN_HOST_PORT}+)"
        }
        require(guestPort in 1..NetworkConfig.MAX_PORT) {
            "guestPort out of range: $guestPort"
        }
    }

    /** The exact slirp specification QEMU consumes, e.g. `tcp:127.0.0.1:8080-:80`. */
    val slirpSpecification: String
        get() = "${protocol.qemuName}:127.0.0.1:$hostPort-:$guestPort"

    val displayText: String
        get() = "${protocol.displayName} localhost:$hostPort → VM:$guestPort"
}

@Serializable
enum class PortProtocol(val qemuName: String, val displayName: String) {
    TCP("tcp", "TCP"),
    UDP("udp", "UDP"),
}

@Serializable
data class NetworkConfig(
    val mode: NetworkMode = NetworkMode.USER,
    /** Forward 127.0.0.1:<port> on Android to port 22 in the guest. */
    val sshPortForward: Int? = null,
    /** User-defined forwards, applied at boot and live over QMP. */
    val portForwards: List<PortForwardRule> = emptyList(),
) {
    init {
        require(sshPortForward == null || sshPortForward in MIN_HOST_PORT..MAX_PORT) {
            "sshPortForward out of range: $sshPortForward"
        }
        val hostPorts = portForwards.map { "${it.protocol}:${it.hostPort}" } +
            listOfNotNull(sshPortForward?.let { "TCP:$it" })
        require(hostPorts.size == hostPorts.toSet().size) {
            "duplicate host ports in port forwards"
        }
    }

    companion object {
        /** Ports below 1024 are privileged and unusable from an app. */
        const val MIN_HOST_PORT = 1024
        const val MAX_PORT = 65535
        const val DEFAULT_SSH_PORT = 2222
    }
}

@Serializable
enum class NetworkMode(val displayName: String, val description: String) {
    USER("Shared with Android", "Outbound internet through the phone (no setup needed)"),
    DISABLED("Disabled", "Fully offline guest"),
}

// ---- Shared folder ----------------------------------------------------------

@Serializable
data class SharedFolderConfig(
    val enabled: Boolean = false,
    /** Guest-side mount tag: `mount -t 9p -o trans=virtio <tag> /mnt`. */
    val mountTag: String = DEFAULT_MOUNT_TAG,
) {
    init {
        require(mountTag.matches(SAFE_TAG_REGEX)) { "invalid mount tag: $mountTag" }
    }

    companion object {
        const val DEFAULT_MOUNT_TAG = "android"
        private val SAFE_TAG_REGEX = Regex("^[A-Za-z0-9_-]{1,31}$")
    }
}

// ---- Boot -------------------------------------------------------------------

@Serializable
enum class BootOrder(val displayName: String, val description: String) {
    INSTALLER_FIRST("Installer first", "Boot the ISO — use while installing"),
    DISK_FIRST("Hard disk first", "Boot the installed system"),
}

/**
 * A ready-made VM: an already-installed disk image, plus the optional
 * cloud-init seed that configures its first boot.
 *
 * This is the fast path — no installer, no live session, straight to a login
 * prompt — and the only practical way to get a desktop distribution running
 * quickly under software emulation.
 */
@Serializable
data class PreparedImageConfig(
    /** Name shown in the UI, e.g. "Ubuntu 24.04 (ready-made)". */
    val displayName: String,
    /** Absolute path of the qcow2 root disk inside app storage. */
    val diskImagePath: String,
    /** Absolute path of the cloud-init seed ISO, if the image needs one. */
    val seedIsoPath: String? = null,
    /** Credentials to show the user; never used to authenticate anything. */
    val username: String = DEFAULT_USERNAME,
    val password: String = DEFAULT_PASSWORD,
    /** Cloud-init only needs the seed on the very first boot. */
    val firstBootCompleted: Boolean = false,
) {
    companion object {
        const val DEFAULT_USERNAME = "dex"
        const val DEFAULT_PASSWORD = "dex"
    }
}

/**
 * Direct kernel boot: QEMU loads the kernel and initrd straight out of the
 * ISO instead of running the ISO's bootloader.
 *
 * Installer ISOs hard-code their kernel command line, so this is the only
 * way to redirect the console to the serial port or to mask a guest service
 * that misbehaves on emulated hardware.
 */
@Serializable
data class KernelBootConfig(
    val enabled: Boolean = true,
    /** Also send the guest console to the serial port, for the Terminal screen. */
    val consoleOnSerialPort: Boolean = true,
    /** Appended last, so it overrides anything the app adds. */
    val extraArguments: String = "",
) {
    init {
        require(extraArguments.length <= MAX_ARGUMENTS_LENGTH) {
            "kernel arguments too long: ${extraArguments.length}"
        }
        require(extraArguments.none { it == '\n' || it == '\r' }) {
            "kernel arguments must be a single line"
        }
    }

    companion object {
        const val MAX_ARGUMENTS_LENGTH = 512
    }
}

@Serializable
enum class EngineOverride(val displayName: String) {
    AUTO("Automatic (recommended)"),
    FORCE_KVM("Force hardware VM"),
    FORCE_TCG("Force software VM"),
    FORCE_PROOT("Force compatibility container"),
}
