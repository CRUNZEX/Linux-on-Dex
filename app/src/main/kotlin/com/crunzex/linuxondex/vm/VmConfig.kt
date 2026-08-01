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
    val usb: UsbConfig = UsbConfig(),
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

    /**
     * True when the selected image is a rootfs archive, which only the PRoot
     * engine can run. The artifact decides the engine: QEMU cannot boot a
     * directory tree any more than PRoot can boot a qcow2.
     */
    val runsInProotContainer: Boolean
        get() = preparedImage?.format == PreparedImageFormat.PROOT_ROOTFS

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

        /**
         * The most memory the guest may be given: the device's size less the
         * 2 GB Android keeps for itself. A 12 GB phone therefore offers
         * 10240 MB.
         *
         * The device size is taken as the capacity it was built with, not
         * the figure the kernel reports: a 12 GB phone reports about
         * 11122 MB, the rest having been carved out for the kernel, the
         * modem and other firmware before Linux ever saw it. Subtracting
         * from the reported number would quietly offer 9074 MB on a phone
         * the user knows has 12 GB. See [fittedMemoryMb].
         *
         * Handing out more than the kernel reports is safe in the way that
         * matters: this is a ceiling on a *setting*, and a guest only
         * occupies the memory it actually touches — a VM given 10 GB that
         * uses 2 GB costs 2 GB.
         */
        fun maxSafeGuestMemoryMb(deviceTotalRamMb: Int): Int {
            val physicalRamMb = fittedMemoryMb(deviceTotalRamMb)
            return (physicalRamMb - ANDROID_RESERVED_RAM_MB).coerceAtLeast(MIN_MEMORY_MB)
        }

        /**
         * How much memory the device is actually fitted with, given what the
         * kernel reports.
         *
         * Rounding up to the next whole gigabyte is not enough: a 12 GB phone
         * reports about 11122 MB, which is 10.9 GB, and would round to 11 GB —
         * still short of the 12 GB on the box. Memory is only sold in a
         * handful of sizes, so the reported figure is matched to the smallest
         * one that could contain it.
         */
        private fun fittedMemoryMb(reportedRamMb: Int): Int {
            if (reportedRamMb <= 0) return 0
            return FITTED_MEMORY_SIZES_MB.firstOrNull { size -> size >= reportedRamMb }
                ?: reportedRamMb
        }

        /** The memory capacities phones are actually built with, ascending. */
        private val FITTED_MEMORY_SIZES_MB = listOf(
            2 * 1024, 3 * 1024, 4 * 1024, 6 * 1024, 8 * 1024, 10 * 1024,
            12 * 1024, 16 * 1024, 18 * 1024, 24 * 1024, 32 * 1024,
        )

        private const val ANDROID_RESERVED_RAM_MB = 2048

        /**
         * What a new VM starts with: 4 vCPUs and 4 GB.
         *
         * A fixed pair rather than a fraction of the device, because these
         * are the numbers a desktop distribution actually wants, and every
         * phone this app supports can spare them. Both are still clamped to
         * what the device has, so a smaller or older device gets something
         * that works instead of something that cannot start.
         */
        fun createDefault(totalDeviceRamMb: Int, availableCpuCores: Int): VmConfig = VmConfig(
            id = "primary",
            name = "Linux VM",
            cpu = CpuConfig(coreCount = defaultCoreCount(availableCpuCores)),
            memoryMb = defaultMemoryMb(totalDeviceRamMb),
        )

        /** The default guest size, 4 GB, lowered if the device cannot spare it. */
        fun defaultMemoryMb(totalDeviceRamMb: Int): Int {
            if (totalDeviceRamMb <= 0) return DEFAULT_MEMORY_MB
            return DEFAULT_MEMORY_MB
                .coerceAtMost(maxSafeGuestMemoryMb(totalDeviceRamMb))
                .coerceAtLeast(MIN_MEMORY_MB)
        }

        /** Four vCPUs, but never more than the device physically has. */
        fun defaultCoreCount(availableCpuCores: Int): Int =
            if (availableCpuCores <= 0) DEFAULT_CORE_COUNT
            else DEFAULT_CORE_COUNT.coerceAtMost(availableCpuCores)
                .coerceIn(CpuConfig.MIN_CORES, CpuConfig.MAX_CORES)

        const val DEFAULT_CORE_COUNT = 4
        const val DEFAULT_MEMORY_MB = 4096

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
    val diskInterface: DiskInterface = DiskInterface.VIRTIO_BLK,
    /**
     * How hard the disk path is tuned; see [DiskPerformance]. Defaults to
     * the fastest setting, because emulated storage is what makes a
     * software VM feel slow and a phone VM is rebuilt far more easily
     * than it is waited on. Users who keep irreplaceable work inside the
     * guest can move it down a notch.
     */
    val performance: DiskPerformance = DiskPerformance.MAXIMUM,
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

/**
 * How aggressively the guest's disk is tuned.
 *
 * Emulated storage is the slowest part of a software VM: every guest write
 * crosses qcow2, the app sandbox and the phone's own filesystem. These
 * profiles trade durability for speed in the three steps that actually
 * matter, so the user picks an outcome rather than five separate knobs.
 *
 * [flushesToDisk] is what separates them: a profile that stops honouring
 * the guest's flush requests is dramatically faster, and loses recent
 * writes if the phone dies mid-run.
 */
@Serializable
enum class DiskPerformance(
    val displayName: String,
    val description: String,
    /** The `cache=` mode QEMU is given. */
    val qemuCacheMode: String,
    /** Whether guest flushes reach the phone's storage. */
    val flushesToDisk: Boolean,
    /** Run disk work on its own thread instead of the main loop. */
    val usesDedicatedIoThread: Boolean,
) {
    MAXIMUM(
        "Maximum speed (default)",
        "Ignores flush requests — much faster for installs, updates and " +
            "everyday use. Shut the VM down cleanly; a crash or battery " +
            "pull can corrupt the disk.",
        qemuCacheMode = "unsafe",
        flushesToDisk = false,
        usesDedicatedIoThread = true,
    ),
    FAST(
        "Balanced",
        "Write-back caching on its own I/O thread; safe on a clean shutdown",
        qemuCacheMode = "writeback",
        flushesToDisk = true,
        usesDedicatedIoThread = true,
    ),
    SAFEST(
        "Safest",
        "Every write reaches storage before the guest continues; slowest",
        qemuCacheMode = "writethrough",
        flushesToDisk = true,
        usesDedicatedIoThread = false,
    ),
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
    /**
     * True publishes the port on every interface (0.0.0.0) so other devices
     * on the same network can reach the guest service; false keeps it on
     * this phone only. Off by default, because opening a port to the local
     * network is a decision the user should make deliberately.
     */
    val bindAllInterfaces: Boolean = false,
) {
    init {
        require(hostPort in NetworkConfig.MIN_HOST_PORT..NetworkConfig.MAX_PORT) {
            "hostPort out of range: $hostPort (apps may only bind ${NetworkConfig.MIN_HOST_PORT}+)"
        }
        require(guestPort in 1..NetworkConfig.MAX_PORT) {
            "guestPort out of range: $guestPort"
        }
    }

    /** The address slirp binds the host side to. */
    val bindAddress: String
        get() = if (bindAllInterfaces) ALL_INTERFACES else LOCALHOST

    /** The exact slirp specification QEMU consumes, e.g. `tcp:127.0.0.1:8080-:80`. */
    val slirpSpecification: String
        get() = "${protocol.qemuName}:$bindAddress:$hostPort-:$guestPort"

    val displayText: String
        get() {
            val host = if (bindAllInterfaces) "all interfaces" else "localhost"
            return "${protocol.displayName} $host:$hostPort → VM:$guestPort"
        }

    companion object {
        const val LOCALHOST = "127.0.0.1"
        const val ALL_INTERFACES = "0.0.0.0"
    }
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

// ---- USB --------------------------------------------------------------------

/**
 * A USB device, identified the way QEMU matches one: by its vendor and
 * product ids, which stay the same across unplugging and replugging.
 */
@Serializable
data class UsbDeviceSpec(
    val vendorId: Int,
    val productId: Int,
    /** Human name for the UI, e.g. "Realtek USB Ethernet". */
    val label: String = "",
) {
    init {
        require(vendorId in 0..MAX_USB_ID) { "vendorId out of range: $vendorId" }
        require(productId in 0..MAX_USB_ID) { "productId out of range: $productId" }
    }

    /** The lsusb-style pair, e.g. "0bda:8153". */
    val idKey: String get() = "%04x:%04x".format(vendorId, productId)

    companion object {
        const val MAX_USB_ID = 0xFFFF
    }
}

/**
 * USB passthrough: every device switched on here is handed to the guest,
 * and Android gives it up for as long as the VM runs.
 *
 * Any number of devices may be passed at once. Each one reaches QEMU as its
 * own already-open descriptor, so devices no longer compete for a single
 * slot the way they did while passthrough went through one environment
 * variable.
 */
@Serializable
data class UsbConfig(
    val passthroughDevices: List<UsbDeviceSpec> = emptyList(),
) {
    val isEnabled: Boolean get() = passthroughDevices.isNotEmpty()

    /** True when the device with this id is switched on. */
    fun isPassedThrough(idKey: String): Boolean =
        passthroughDevices.any { it.idKey == idKey }

    /**
     * The same configuration with [device] switched on or off.
     *
     * Matching is by id rather than by whole value: the label follows
     * whatever Android reports for the device this time, and a renamed
     * device must not silently become a second entry.
     */
    fun withDevice(device: UsbDeviceSpec, passedThrough: Boolean): UsbConfig {
        val others = passthroughDevices.filterNot { it.idKey == device.idKey }
        return copy(
            passthroughDevices = if (passedThrough) others + device else others,
        )
    }
}

// ---- Boot -------------------------------------------------------------------

@Serializable
enum class BootOrder(val displayName: String, val description: String) {
    INSTALLER_FIRST("Installer first", "Boot the ISO — use while installing"),
    DISK_FIRST("Hard disk first", "Boot the installed system"),
}

/**
 * What kind of artifact a ready-made image is — which decides the engine
 * that runs it. A qcow2 is a block device only QEMU can boot; a rootfs
 * archive is a directory tree only PRoot can run. The two are not
 * interchangeable, so the format travels with the image instead of being
 * guessed at boot time.
 */
@Serializable
enum class PreparedImageFormat(val displayName: String) {
    /** A qcow2/raw disk image, booted as a full virtual machine. */
    QCOW2_DISK("Virtual machine disk"),

    /**
     * A Linux root filesystem archive, run through PRoot's syscall
     * translation at native CPU speed — no emulation, which is what makes
     * a desktop usable on devices where /dev/kvm is denied.
     */
    PROOT_ROOTFS("Native container (PRoot)");

    companion object {
        /** Marks a rootfs archive: `<name>.rootfs.tar.gz`. */
        const val ROOTFS_ARCHIVE_SUFFIX = ".rootfs.tar.gz"

        private val DISK_IMAGE_EXTENSIONS = setOf("qcow2", "img")

        /** The format a file name announces, or null for unrelated files. */
        fun fromFileName(fileName: String): PreparedImageFormat? {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(ROOTFS_ARCHIVE_SUFFIX) -> PROOT_ROOTFS
                lower.substringAfterLast('.') in DISK_IMAGE_EXTENSIONS -> QCOW2_DISK
                else -> null
            }
        }
    }
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
    /**
     * Absolute path of the image inside app storage: a qcow2/raw disk, or —
     * when [format] is [PreparedImageFormat.PROOT_ROOTFS] — a rootfs archive.
     */
    val diskImagePath: String,
    /** Absolute path of the cloud-init seed ISO, if the image needs one. */
    val seedIsoPath: String? = null,
    /** Credentials to show the user; never used to authenticate anything. */
    val username: String = DEFAULT_USERNAME,
    val password: String = DEFAULT_PASSWORD,
    /** Cloud-init only needs the seed on the very first boot. */
    val firstBootCompleted: Boolean = false,
    /** Defaults to a disk so configs saved before this field existed still load. */
    val format: PreparedImageFormat = PreparedImageFormat.QCOW2_DISK,
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
enum class EngineOverride(
    val displayName: String,
    val description: String,
) {
    AUTO(
        "Automatic (recommended)",
        "Best available: hardware VM, then software VM, then PRoot",
    ),
    FORCE_KVM(
        "Force hardware VM",
        "Needs /dev/kvm, which stock Samsung firmware denies to apps",
    ),
    FORCE_TCG(
        "Force software VM",
        "Full virtual machine under emulation — boots any ARM64 distro",
    ),
    FORCE_PROOT(
        "Force PRoot container",
        "No VM: runs Linux at native speed — the smooth choice for desktops",
    ),
}
