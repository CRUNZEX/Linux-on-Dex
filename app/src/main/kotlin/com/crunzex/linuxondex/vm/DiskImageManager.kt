package com.crunzex.linuxondex.vm

import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.runtime.NativeCommand
import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File

/**
 * Owns the per-VM disk directory: the qcow2 root disk (created with the
 * bundled qemu-img) and the writable copy of the UEFI variable store.
 */
class DiskImageManager(private val paths: VmPaths) {

    fun rootDiskFile(vmId: String): File = paths.diskDirFor(vmId).resolve(ROOT_DISK_NAME)

    /**
     * The disk this VM actually boots: a ready-made image when one is
     * imported, otherwise the qcow2 this app creates and installs into.
     */
    fun bootDiskFile(config: VmConfig): File =
        config.preparedImage?.diskImagePath?.let(::File) ?: rootDiskFile(config.id)

    fun efiVarsFile(vmId: String): File = paths.diskDirFor(vmId).resolve(EFI_VARS_NAME)

    fun rootDiskExists(vmId: String): Boolean = rootDiskFile(vmId).exists()

    /**
     * Creates the root disk if missing. qcow2 grows lazily, so this is fast.
     * A ready-made image is already a disk, so nothing is created for it.
     */
    fun ensureRootDisk(config: VmConfig) {
        if (config.usesPreparedImage) return
        val diskFile = rootDiskFile(config.id)
        if (diskFile.exists()) return
        diskFile.parentFile?.mkdirs()

        val result = NativeCommand(
            program = paths.qemuImgBinary,
            arguments = listOf(
                "create", "-f", "qcow2",
                diskFile.absolutePath,
                "${config.storage.diskSizeGb}G",
            ),
            environment = paths.processEnvironment(),
        ).runAndCaptureOutput()
        result.requireSuccess("qemu-img create")

        AppLog.info(SCOPE, "created root disk ${diskFile.name} (${config.storage.diskSizeGb} GiB) for '${config.id}'")
    }

    /**
     * Each VM needs its own writable UEFI variable store, seeded from the
     * read-only template shipped with the firmware.
     */
    fun ensureEfiVars(vmId: String) {
        val varsFile = efiVarsFile(vmId)
        if (varsFile.exists()) return
        val template = paths.firmwareVarsTemplate
        if (!template.exists()) {
            throw LxdError.PayloadMissing(template.name)
        }
        varsFile.parentFile?.mkdirs()
        try {
            template.copyTo(varsFile)
        } catch (error: Exception) {
            throw LxdError.StorageFailed("copy EFI vars template for '$vmId'", error)
        }
    }

    /** Deletes all disks for a VM — used by "reset VM" in settings. */
    fun deleteDisks(vmId: String) {
        val diskDir = paths.diskDirFor(vmId)
        if (diskDir.exists() && !diskDir.deleteRecursively()) {
            throw LxdError.StorageFailed("delete disk directory for '$vmId'")
        }
        AppLog.info(SCOPE, "deleted disks for '$vmId'")
    }

    companion object {
        private const val SCOPE = "DiskImageManager"
        private const val ROOT_DISK_NAME = "root.qcow2"
        private const val EFI_VARS_NAME = "efi-vars.fd"
    }
}
