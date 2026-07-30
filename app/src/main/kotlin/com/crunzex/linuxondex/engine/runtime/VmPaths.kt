package com.crunzex.linuxondex.engine.runtime

import android.content.Context
import java.io.File

/**
 * Single source of truth for every file and directory the VM runtime touches.
 *
 * Layout:
 * ```
 * <nativeLibraryDir>/                     executables + shared libs (read-only,
 *                                         the only exec()-able location)
 * <filesDir>/vm/
 *   qemu/                                 firmware + keymaps (-L data dir)
 *   disks/<vm>/root.qcow2                 guest disks
 *   disks/<vm>/efi-vars.fd                per-VM UEFI variable store
 *   isos/                                 imported installer ISOs
 *   proot-rootfs/                         extracted Alpine rootfs (fallback 3)
 *   sockets/                              QMP + serial unix sockets
 *   logs/                                 per-boot QEMU logs
 * <cacheDir>/vm-tmp/                      TMPDIR for spawned processes
 * ```
 */
class VmPaths(context: Context) {

    val nativeLibraryDir: File = File(context.applicationInfo.nativeLibraryDir)

    val qemuSystemBinary: File = nativeLibraryDir.resolve("libqemu-system-aarch64.so")
    val qemuImgBinary: File = nativeLibraryDir.resolve("libqemu-img.so")
    val prootBinary: File = nativeLibraryDir.resolve("libproot.so")
    val prootLoaderBinary: File = nativeLibraryDir.resolve("libproot-loader.so")

    val vmRootDir: File = context.filesDir.resolve("vm")
    val qemuDataDir: File = vmRootDir.resolve("qemu")
    val firmwareCode: File = qemuDataDir.resolve("edk2-aarch64-code.fd")
    val firmwareVarsTemplate: File = qemuDataDir.resolve("edk2-arm-vars.fd")
    val disksDir: File = vmRootDir.resolve("disks")
    val isosDir: File = vmRootDir.resolve("isos")
    val prootRootfsDir: File = vmRootDir.resolve("proot-rootfs")
    val socketsDir: File = vmRootDir.resolve("sockets")
    val logsDir: File = vmRootDir.resolve("logs")
    val tmpDir: File = context.cacheDir.resolve("vm-tmp")

    /** ISOs the user dropped into Android/data/<pkg>/files/isos via USB/DeX. */
    val externalIsosDir: File? = context.getExternalFilesDir("isos")

    /**
     * Ready-made VM images pushed by the user over USB/DeX. External storage
     * so a file manager or adb can reach it.
     */
    val vmImagesDir: File? = context.getExternalFilesDir("vm-images")

    /**
     * Folder shared with the guest over 9p. Lives in external app storage so
     * the user can reach it from Android's Files app and over USB/DeX.
     */
    val sharedFolderDir: File? = context.getExternalFilesDir("shared")

    /** Screenshots captured from the guest framebuffer via QMP. */
    val screenshotsDir: File = vmRootDir.resolve("screenshots")

    fun diskDirFor(vmId: String): File = disksDir.resolve(vmId)

    fun createRuntimeDirectories() {
        listOf(
            vmRootDir, qemuDataDir, disksDir, isosDir, prootRootfsDir,
            socketsDir, logsDir, tmpDir, screenshotsDir,
        ).forEach { directory -> directory.mkdirs() }
        sharedFolderDir?.mkdirs()
        vmImagesDir?.mkdirs()
    }

    /**
     * Environment for every process we spawn: the linker must resolve our
     * renamed libraries from the native-library dir, and nothing may default
     * to Termux-era paths.
     */
    fun processEnvironment(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to nativeLibraryDir.absolutePath,
        "TMPDIR" to tmpDir.absolutePath,
        "HOME" to vmRootDir.absolutePath,
    )
}
