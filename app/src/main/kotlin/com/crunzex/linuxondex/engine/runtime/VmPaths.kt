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
 *   proot-images/<image>/                 extracted desktop rootfs images
 *   proot-shm/                            bound as /dev/shm in PRoot guests
 *   sockets/                              QMP + serial unix sockets
 *   logs/                                 per-boot QEMU logs
 * <cacheDir>/vm-tmp/                      TMPDIR for spawned processes
 * ```
 */
class VmPaths(
    val nativeLibraryDir: File,
    filesDir: File,
    cacheDir: File,
    externalFilesDir: (kind: String) -> File?,
) {
    constructor(context: Context) : this(
        nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        externalFilesDir = { kind -> context.getExternalFilesDir(kind) },
    )

    val qemuSystemBinary: File = nativeLibraryDir.resolve("libqemu-system-aarch64.so")
    val qemuImgBinary: File = nativeLibraryDir.resolve("libqemu-img.so")
    val prootBinary: File = nativeLibraryDir.resolve("libproot.so")
    val prootLoaderBinary: File = nativeLibraryDir.resolve("libproot-loader.so")
    val virglRendererBinary: File = nativeLibraryDir.resolve("libvirgl-test-server-android.so")

    val vmRootDir: File = filesDir.resolve("vm")
    val qemuDataDir: File = vmRootDir.resolve("qemu")
    val firmwareCode: File = qemuDataDir.resolve("edk2-aarch64-code.fd")
    val firmwareVarsTemplate: File = qemuDataDir.resolve("edk2-arm-vars.fd")
    val disksDir: File = vmRootDir.resolve("disks")
    val isosDir: File = vmRootDir.resolve("isos")
    val prootRootfsDir: File = vmRootDir.resolve("proot-rootfs")

    /**
     * Extracted rootfs images, one directory per imported archive. They must
     * live on internal storage: external app storage is FUSE-backed and
     * refuses the symlinks every Linux rootfs is full of.
     */
    val prootImagesDir: File = vmRootDir.resolve("proot-images")

    /**
     * Bound into PRoot guests as /dev/shm, which Android does not provide.
     * X11 clients use it for shared-memory frames; without it every GNOME
     * app falls back to pushing pixels through the X socket.
     */
    val prootSharedMemoryDir: File = vmRootDir.resolve("proot-shm")

    val socketsDir: File = vmRootDir.resolve("sockets")
    val virglSocket: File = socketsDir.resolve("virgl-renderer.sock")
    val logsDir: File = vmRootDir.resolve("logs")
    val tmpDir: File = cacheDir.resolve("vm-tmp")

    /** ISOs the user dropped into Android/data/<pkg>/files/isos via USB/DeX. */
    val externalIsosDir: File? = externalFilesDir("isos")

    /**
     * Ready-made VM images pushed by the user over USB/DeX. External storage
     * so a file manager or adb can reach it.
     */
    val vmImagesDir: File? = externalFilesDir("vm-images")

    /**
     * Folder shared with the guest over 9p. Lives in external app storage so
     * the user can reach it from Android's Files app and over USB/DeX.
     */
    val sharedFolderDir: File? = externalFilesDir("shared")

    /** Screenshots captured from the guest framebuffer via QMP. */
    val screenshotsDir: File = vmRootDir.resolve("screenshots")

    fun diskDirFor(vmId: String): File = disksDir.resolve(vmId)

    /**
     * Where the rootfs inside [archiveFileName] is extracted to: one
     * directory per archive, always a direct child of [prootImagesDir].
     *
     * The name is sanitized rather than trusted. A name made only of dots
     * would otherwise resolve to the images directory itself — and this
     * directory gets deleted and recreated, which would take every other
     * extracted image with it.
     */
    fun prootRootfsDirFor(archiveFileName: String): File {
        val sanitized = archiveFileName
            .removeSuffix(ROOTFS_ARCHIVE_SUFFIX)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeName = sanitized
            .takeUnless { it.isEmpty() || it.all { character -> character == '.' } }
            ?: FALLBACK_ROOTFS_DIR_NAME
        return prootImagesDir.resolve(safeName)
    }

    fun createRuntimeDirectories() {
        listOf(
            vmRootDir, qemuDataDir, disksDir, isosDir, prootRootfsDir,
            prootImagesDir, prootSharedMemoryDir,
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

    companion object {
        private const val ROOTFS_ARCHIVE_SUFFIX = ".rootfs.tar.gz"
        private const val FALLBACK_ROOTFS_DIR_NAME = "rootfs"
    }
}
