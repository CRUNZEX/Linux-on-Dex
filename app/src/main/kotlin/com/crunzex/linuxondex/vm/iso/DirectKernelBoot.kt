package com.crunzex.linuxondex.vm.iso

import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import java.io.File

/** Kernel, initrd and command line extracted from an installer ISO. */
data class ExtractedKernel(
    val kernelFile: File,
    val initrdFile: File,
    val kernelCommandLine: String,
)

/**
 * Prepares an installer ISO for direct kernel boot: QEMU loads the kernel
 * and initrd itself instead of running the ISO's bootloader.
 *
 * Three concrete wins over booting the ISO normally:
 *
 *  - **The kernel command line becomes editable.** Installer ISOs hard-code
 *    theirs, so without this there is no way to mask a service that
 *    misbehaves on emulated hardware, or to redirect the console.
 *  - **The console can be sent to the serial port.** Desktop ISOs use
 *    `console=tty0`, which leaves the Terminal screen blank for the whole
 *    boot.
 *  - **The bootloader's menu timeout disappears**, which is 30 seconds on
 *    a stock Ubuntu ISO.
 */
object DirectKernelBoot {

    /**
     * Layouts used by the installer families we support. The first entry
     * whose kernel and initrd both exist wins.
     */
    private data class IsoLayout(
        val familyName: String,
        val kernelPath: String,
        val initrdPath: String,
        /** Arguments this family needs to find its root filesystem. */
        val requiredArguments: String,
    )

    private val KNOWN_LAYOUTS = listOf(
        IsoLayout("Ubuntu/Debian live", "/casper/vmlinuz", "/casper/initrd", "boot=casper"),
        IsoLayout("Debian installer", "/install.a64/vmlinuz", "/install.a64/initrd.gz", ""),
        IsoLayout("Fedora/RHEL", "/images/pxeboot/vmlinuz", "/images/pxeboot/initrd.img", ""),
        IsoLayout("Arch/generic", "/arch/boot/aarch64/vmlinuz", "/arch/boot/aarch64/initramfs", ""),
    )

    /**
     * Guest services that only exist for real Qualcomm hardware and fail in
     * a restart loop on QEMU's `virt` board, flooding the console and
     * burning emulated CPU that the rest of the boot needs.
     */
    private val MASKED_GUEST_SERVICES = listOf("pd-mapper.service", "qrtr-ns.service")

    /** True when this ISO has a layout we know how to boot directly. */
    fun isSupported(isoFile: File): Boolean = runCatching {
        Iso9660Reader(isoFile).use { reader -> findLayout(reader) != null }
    }.getOrDefault(false)

    /**
     * Extracts the kernel and initrd into [outputDirectory], reusing files
     * from a previous run when their size already matches.
     */
    fun prepare(
        isoFile: File,
        outputDirectory: File,
        extraKernelArguments: String,
        sendConsoleToSerialPort: Boolean,
    ): ExtractedKernel {
        outputDirectory.mkdirs()
        return Iso9660Reader(isoFile).use { reader ->
            val layout = findLayout(reader)
                ?: throw LxdError.BootFailed(
                    "${isoFile.name} has no kernel layout this app recognises; " +
                        "turn off direct kernel boot to use the ISO's own bootloader"
                )
            val kernelEntry = reader.findFile(layout.kernelPath)!!
            val initrdEntry = reader.findFile(layout.initrdPath)!!

            val kernelFile = outputDirectory.resolve("vmlinuz")
            val initrdFile = outputDirectory.resolve("initrd")
            extractIfStale(reader, kernelEntry, kernelFile)
            extractIfStale(reader, initrdEntry, initrdFile)

            val commandLine = buildCommandLine(
                layout = layout,
                extraArguments = extraKernelArguments,
                sendConsoleToSerialPort = sendConsoleToSerialPort,
            )
            AppLog.info(
                SCOPE,
                "direct boot ready (${layout.familyName}): " +
                    "kernel ${kernelFile.length() shr 20} MiB, " +
                    "initrd ${initrdFile.length() shr 20} MiB, cmdline: $commandLine",
            )
            ExtractedKernel(kernelFile, initrdFile, commandLine)
        }
    }

    private fun findLayout(reader: Iso9660Reader): IsoLayout? = KNOWN_LAYOUTS.firstOrNull { layout ->
        reader.findFile(layout.kernelPath) != null && reader.findFile(layout.initrdPath) != null
    }

    private fun extractIfStale(reader: Iso9660Reader, entry: IsoEntry, destination: File) {
        if (destination.exists() && destination.length() == entry.sizeBytes) return
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            temporary.outputStream().buffered().use { output -> reader.extract(entry, output) }
            if (!temporary.renameTo(destination)) {
                throw LxdError.StorageFailed("rename ${temporary.name} → ${destination.name}")
            }
        } catch (error: LxdError) {
            temporary.delete()
            throw error
        } catch (error: Exception) {
            temporary.delete()
            throw LxdError.StorageFailed("extracting ${entry.name} from the ISO", error)
        }
    }

    /**
     * Assembles the command line. Order matters: family arguments first,
     * then our fixes, then the user's, so the user can always override.
     */
    fun buildCommandLine(
        familyArguments: String,
        extraArguments: String,
        sendConsoleToSerialPort: Boolean,
    ): String = buildList {
        if (familyArguments.isNotBlank()) add(familyArguments)
        MASKED_GUEST_SERVICES.forEach { service -> add("systemd.mask=$service") }
        if (sendConsoleToSerialPort) {
            // tty0 keeps the graphical console working for the VNC display.
            add("console=tty0")
            add("console=ttyAMA0,115200")
        }
        if (extraArguments.isNotBlank()) add(extraArguments.trim())
    }.joinToString(" ")

    private fun buildCommandLine(
        layout: IsoLayout,
        extraArguments: String,
        sendConsoleToSerialPort: Boolean,
    ): String = buildCommandLine(
        familyArguments = layout.requiredArguments,
        extraArguments = extraArguments,
        sendConsoleToSerialPort = sendConsoleToSerialPort,
    )

    private const val SCOPE = "DirectKernelBoot"
}
