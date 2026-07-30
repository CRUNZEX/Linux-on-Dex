package com.crunzex.linuxondex.engine.qemu

import com.crunzex.linuxondex.vm.BootOrder
import com.crunzex.linuxondex.vm.CpuModel
import com.crunzex.linuxondex.vm.DiskInterface
import com.crunzex.linuxondex.vm.DisplayAdapter
import com.crunzex.linuxondex.vm.NetworkMode
import com.crunzex.linuxondex.vm.VmConfig
import com.crunzex.linuxondex.vm.iso.ExtractedKernel
import java.io.File

/** Which QEMU accelerator to use — the only difference between engines 1 and 2. */
enum class QemuAccelerator {
    /** Approach 1: hardware virtualization through /dev/kvm. */
    KVM,

    /** Approach 2: software emulation, one guest vCPU per host thread. */
    TCG;

    /**
     * Builds the `-accel` value for a guest of [guestMemoryMb].
     *
     * TCG translates guest code into host code and caches the result. The
     * default cache is small, so a desktop guest evicts translations
     * constantly and re-translates the same hot code. Enlarging it is the
     * single cheapest speed-up available without hardware virtualization.
     */
    fun toQemuArgument(guestMemoryMb: Int): String = when (this) {
        KVM -> "kvm"
        TCG -> "tcg,thread=multi,tb-size=${translationCacheMb(guestMemoryMb)}"
    }

    companion object {
        const val MIN_TRANSLATION_CACHE_MB = 128
        // A desktop's code working set (shell + toolkit + browser) blows
        // through 512 MB and re-translates constantly. Guest memory is
        // already capped against device RAM, so a quarter of it stays safe.
        const val MAX_TRANSLATION_CACHE_MB = 1024

        /**
         * A quarter of guest memory, clamped: enough for a desktop's working
         * set without taking RAM that Android needs back.
         */
        fun translationCacheMb(guestMemoryMb: Int): Int =
            (guestMemoryMb / 4).coerceIn(MIN_TRANSLATION_CACHE_MB, MAX_TRANSLATION_CACHE_MB)
    }
}

/** Everything path-like the command needs, resolved by the engine. */
data class QemuLaunchPlan(
    val config: VmConfig,
    val accelerator: QemuAccelerator,
    val firmwareCode: File,
    val efiVarsFile: File,
    val rootDiskFile: File,
    val installerIso: File?,
    /** cloud-init seed for a ready-made image's first boot; else null. */
    val seedIso: File?,
    /** Kernel/initrd extracted from the ISO; null when booting normally. */
    val directKernel: ExtractedKernel?,
    val qemuDataDir: File,
    val sharedFolderDir: File?,
    val qmpSocketFile: File,
    val serialSocketFile: File,
    /** Everything the guest writes to its console, from power-on. */
    val serialLogFile: File,
    val pidFile: File,
    /**
     * Sockets backing the guest's extra virtio consoles (/dev/hvc0, hvc1…),
     * one per additional terminal window the app can open.
     */
    val extraConsoleSockets: List<File> = emptyList(),
)

/**
 * Builds the qemu-system-aarch64 argument list from a [QemuLaunchPlan].
 *
 * Pure — no filesystem or Android calls — so the exact command line is
 * asserted in unit tests. Each private helper contributes one device group.
 */
object QemuCommandBuilder {

    fun build(plan: QemuLaunchPlan): List<String> = buildList {
        addAll(machineAndCpuArguments(plan))
        addAll(firmwareArguments(plan))
        addAll(rootDiskArguments(plan))
        addAll(installerIsoArguments(plan))
        addAll(seedIsoArguments(plan))
        addAll(directKernelArguments(plan))
        addAll(displayArguments(plan))
        addAll(inputDeviceArguments())
        addAll(networkArguments(plan))
        addAll(sharedFolderArguments(plan))
        addAll(controlChannelArguments(plan))
    }

    private fun machineAndCpuArguments(plan: QemuLaunchPlan): List<String> {
        val config = plan.config
        // "host" is only meaningful with a hardware accelerator; fall back so
        // a stale config can never make the software VM fail to launch.
        val cpuModel =
            if (config.cpu.model.requiresHardwareVirtualization &&
                plan.accelerator != QemuAccelerator.KVM
            ) {
                CpuModel.MAX
            } else {
                config.cpu.model
            }
        return listOf(
            "-name", config.name,
            "-machine", "virt,gic-version=3",
            "-accel", plan.accelerator.toQemuArgument(config.memoryMb),
            "-cpu", cpuModel.qemuName,
            "-smp", config.cpu.coreCount.toString(),
            "-m", config.memoryMb.toString(),
            // Deterministic device set; nothing implicit.
            "-nodefaults",
            "-L", plan.qemuDataDir.absolutePath,
            "-rtc", "base=utc",
            // Entropy: lets the guest kernel boot without waiting for RNG.
            "-device", "virtio-rng-pci",
        )
    }

    /** UEFI: read-only code flash plus this VM's writable variable store. */
    private fun firmwareArguments(plan: QemuLaunchPlan): List<String> = listOf(
        "-drive", "if=pflash,format=raw,readonly=on,file=${plan.firmwareCode.absolutePath}",
        "-drive", "if=pflash,format=raw,file=${plan.efiVarsFile.absolutePath}",
    )

    private fun rootDiskArguments(plan: QemuLaunchPlan): List<String> {
        val storage = plan.config.storage
        val bootIndex = if (plan.config.bootsFromInstaller) 1 else 0
        val backend = "if=none,id=rootdisk,format=qcow2," +
            "cache=${storage.cacheMode.qemuValue},discard=unmap," +
            "file=${plan.rootDiskFile.absolutePath}"
        return when (storage.diskInterface) {
            DiskInterface.VIRTIO_BLK -> listOf(
                "-drive", backend,
                "-device", "virtio-blk-pci,drive=rootdisk,bootindex=$bootIndex",
            )
            DiskInterface.VIRTIO_SCSI -> listOf(
                "-drive", backend,
                "-device", "virtio-scsi-pci,id=scsi-root",
                "-device", "scsi-hd,bus=scsi-root.0,drive=rootdisk,bootindex=$bootIndex",
            )
        }
    }

    /**
     * The installer stays attached whenever an ISO is selected so the guest
     * can still reach it; only [BootOrder] decides what boots first.
     */
    private fun installerIsoArguments(plan: QemuLaunchPlan): List<String> {
        val iso = plan.installerIso ?: return emptyList()
        val bootIndex = if (plan.config.bootOrder == BootOrder.INSTALLER_FIRST) 0 else 1
        return listOf(
            "-drive", "if=none,id=installcd,format=raw,readonly=on,file=${iso.absolutePath}",
            "-device", "virtio-scsi-pci,id=scsi-cd",
            "-device", "scsi-cd,bus=scsi-cd.0,drive=installcd,bootindex=$bootIndex",
        )
    }

    /**
     * Boots the kernel QEMU was handed rather than the ISO's bootloader.
     * The ISO stays attached: the initrd still mounts it to find the root
     * filesystem.
     */
    private fun directKernelArguments(plan: QemuLaunchPlan): List<String> {
        val kernel = plan.directKernel ?: return emptyList()
        return listOf(
            "-kernel", kernel.kernelFile.absolutePath,
            "-initrd", kernel.initrdFile.absolutePath,
            "-append", kernel.kernelCommandLine,
        )
    }

    /**
     * cloud-init's NoCloud seed, attached read-only. It is only needed until
     * the image's first boot has configured itself, so the engine stops
     * passing it afterwards.
     */
    private fun seedIsoArguments(plan: QemuLaunchPlan): List<String> {
        val seed = plan.seedIso ?: return emptyList()
        return listOf(
            "-drive", "if=none,id=cloudinitseed,format=raw,readonly=on,file=${seed.absolutePath}",
            "-device", "virtio-blk-pci,drive=cloudinitseed",
        )
    }

    private fun displayArguments(plan: QemuLaunchPlan): List<String> {
        val display = plan.config.display
        val adapterArgument = if (display.adapter.supportsCustomResolution) {
            "${display.adapter.qemuDevice},edid=on," +
                "xres=${display.resolution.widthPx},yres=${display.resolution.heightPx}"
        } else {
            display.adapter.qemuDevice
        }
        return listOf(
            "-device", adapterArgument,
            // Local-only VNC server; never bound to a routable address.
            "-vnc", "127.0.0.1:${display.vncDisplayNumber}",
            "-display", "none",
        )
    }

    /** usb-tablet reports absolute coordinates, which VNC pointers need. */
    private fun inputDeviceArguments(): List<String> = listOf(
        "-device", "qemu-xhci,id=usb",
        "-device", "usb-kbd",
        "-device", "usb-tablet",
    )

    private fun networkArguments(plan: QemuLaunchPlan): List<String> {
        val network = plan.config.network
        if (network.mode == NetworkMode.DISABLED) return emptyList()
        val forwards = buildList {
            network.sshPortForward?.let { add("tcp:127.0.0.1:$it-:22") }
            network.portForwards.forEach { add(it.slirpSpecification) }
        }.joinToString("") { ",hostfwd=$it" }
        // ipv6=off: user-mode IPv6 has no route out of the app sandbox, so
        // every AAAA lookup would wait for a multi-second timeout before
        // IPv4 succeeds. Disabling it makes name resolution feel instant.
        return listOf(
            "-netdev", "user,id=net0,ipv6=off$forwards",
            "-device", "virtio-net-pci,netdev=net0",
        )
    }

    /**
     * 9p share of an Android-visible folder. `mapped-file` keeps guest
     * ownership/permission metadata in sidecar files, so it works on app
     * storage without root or xattr support.
     */
    private fun sharedFolderArguments(plan: QemuLaunchPlan): List<String> {
        val shared = plan.config.sharedFolder
        val directory = plan.sharedFolderDir
        if (!shared.enabled || directory == null) return emptyList()
        return listOf(
            "-fsdev", "local,id=shared0,path=${directory.absolutePath}," +
                "security_model=mapped-file",
            "-device", "virtio-9p-pci,fsdev=shared0,mount_tag=${shared.mountTag}",
        )
    }

    /**
     * QMP for control, plus a serial chardev that mirrors all guest console
     * output into a log file so nothing printed before the terminal UI
     * attaches is lost.
     */
    private fun controlChannelArguments(plan: QemuLaunchPlan): List<String> = listOf(
        "-qmp", "unix:${plan.qmpSocketFile.absolutePath},server=on,wait=off",
        "-chardev", "socket,id=serial0,path=${plan.serialSocketFile.absolutePath}," +
            "server=on,wait=off,logfile=${plan.serialLogFile.absolutePath},logappend=on",
        "-serial", "chardev:serial0",
        "-monitor", "none",
        "-pidfile", plan.pidFile.absolutePath,
    ) + extraConsoleArguments(plan)

    /**
     * Extra virtio consoles, one per [QemuLaunchPlan.extraConsoleSockets].
     *
     * These appear in the guest as /dev/hvc0, /dev/hvc1 … so a second (and
     * third) terminal window can each own a genuinely independent shell
     * instead of mirroring the one serial console. The images enable a getty
     * on them; a guest without one simply shows an empty terminal, which the
     * UI explains rather than failing.
     */
    private fun extraConsoleArguments(plan: QemuLaunchPlan): List<String> {
        if (plan.extraConsoleSockets.isEmpty()) return emptyList()
        return buildList {
            add("-device")
            add("virtio-serial-pci,id=virtio-serial0")
            plan.extraConsoleSockets.forEachIndexed { index, socketFile ->
                val chardevId = "hvc$index"
                add("-chardev")
                add("socket,id=$chardevId,path=${socketFile.absolutePath},server=on,wait=off")
                add("-device")
                add("virtconsole,chardev=$chardevId,id=console$index")
            }
        }
    }
}
