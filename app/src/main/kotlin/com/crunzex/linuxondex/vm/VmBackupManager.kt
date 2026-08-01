package com.crunzex.linuxondex.vm

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.runtime.NativeCommand
import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved copy of a virtual machine's disk. */
data class VmBackup(
    val file: File,
    val sizeMb: Long,
    val createdAtMillis: Long,
) {
    val displayName: String get() = file.name
}

/**
 * Saves the virtual machine's disk to a file the user can copy off the
 * phone, and lists what has already been saved.
 *
 * A backup keeps the form of what it copied. A disk image is rewritten with
 * `qemu-img convert`, which drops the blocks the guest has discarded — so the
 * backup is usually far smaller than the live disk — and proves the source is
 * a readable qcow2 on the way past. A container archive is copied as it is,
 * because it is already compressed and is the very file an import expects.
 *
 * Either way the name keeps its suffix and gains a timestamp, so a backup can
 * be imported straight back without being renamed.
 *
 * Backups land in the app's own folder on shared storage, which is visible
 * over USB and to the Files app without any permission.
 */
class VmBackupManager(
    private val context: Context,
    private val paths: VmPaths,
    private val diskManager: DiskImageManager,
) {

    /** Where finished backups are written; created on demand. */
    fun backupDirectory(): File {
        val shared = context.getExternalFilesDir(BACKUP_FOLDER)
        val directory = shared ?: File(context.filesDir, BACKUP_FOLDER)
        if (!directory.exists() && !directory.mkdirs()) {
            throw LxdError.StorageFailed("could not create ${directory.absolutePath}")
        }
        return directory
    }

    /** Existing backups, newest first. */
    fun listBackups(): List<VmBackup> = try {
        backupDirectory().listFiles()
            .orEmpty()
            // Both kinds of image count: a container archive is as much a
            // backup as a disk, and listing only disks would hide half of them.
            .filter { it.isFile && isBackupFile(it.name) }
            .map { VmBackup(it, it.length() shr 20, it.lastModified()) }
            .sortedByDescending { it.createdAtMillis }
    } catch (unreadable: Exception) {
        AppLog.warn(SCOPE, "listing backups failed", unreadable)
        emptyList()
    }

    /**
     * Copies [config]'s disk into a timestamped file and returns it.
     *
     * The VM must be shut down: copying a disk while the guest is writing
     * to it captures a torn image that may not boot. Callers check the VM
     * state first; this repeats the check that the file exists at all.
     */
    fun createBackup(config: VmConfig, now: Date = Date()): VmBackup {
        // Whatever this VM actually boots: a ready-made image, or its own disk.
        val source = diskManager.bootDiskFile(config)
        if (!source.isFile) {
            throw LxdError.StorageFailed("this virtual machine has nothing to back up yet")
        }
        val destination = File(backupDirectory(), backupFileName(source.name, now))

        AppLog.info(SCOPE, "backing up ${source.name} -> ${destination.name}")
        try {
            // A backup has to stay the kind of thing it was: a disk image is
            // rewritten by qemu-img, a container archive is copied. Running
            // qemu-img over an archive would fail, and a plain copy of a disk
            // would forfeit the shrinking a rewrite gives.
            when (PreparedImageFormat.fromFileName(source.name)) {
                PreparedImageFormat.PROOT_ROOTFS -> copyArchive(source, destination)
                else -> convertDiskImage(source, destination)
            }
        } catch (failure: Exception) {
            destination.delete()
            throw failure as? LxdError
                ?: LxdError.StorageFailed("backup failed: ${failure.message}", failure)
        }

        if (!destination.isFile || destination.length() == 0L) {
            destination.delete()
            throw LxdError.StorageFailed("backup produced no file")
        }
        return VmBackup(destination, destination.length() shr 20, destination.lastModified())
    }

    /**
     * Rewrites a disk image with `qemu-img convert`.
     *
     * Not a plain copy: the rewrite drops the blocks the guest has discarded,
     * so a backup is usually far smaller than the live disk, and reading the
     * source through qemu-img proves it is a valid image on the way past.
     */
    private fun convertDiskImage(source: File, destination: File) {
        val qemuImg = NativeCommand(
            program = paths.qemuImgBinary,
            arguments = listOf(
                "convert",
                "-O", DISK_EXTENSION,
                source.absolutePath,
                destination.absolutePath,
            ),
            environment = paths.processEnvironment(),
        )
        val result = qemuImg.runAndCaptureOutput(timeoutSeconds = BACKUP_TIMEOUT_SECONDS)
        if (!result.isSuccess) {
            throw LxdError.StorageFailed("backup failed: ${result.output.take(200)}")
        }
    }

    /**
     * Copies a container archive byte for byte.
     *
     * There is nothing to rewrite: the archive is already compressed, and it
     * is the exact file the app extracts a container from, so a copy of it
     * restores by being imported again like any other image.
     */
    private fun copyArchive(source: File, destination: File) {
        requireFreeSpaceFor(destination, source.length())
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output, COPY_BUFFER_BYTES)
            }
        }
    }

    /** Refuses a copy that would fill the phone rather than failing part-way. */
    private fun requireFreeSpaceFor(destination: File, requiredBytes: Long) {
        val availableBytes = destination.parentFile?.usableSpace ?: return
        if (availableBytes < requiredBytes + FREE_SPACE_HEADROOM_BYTES) {
            throw LxdError.StorageFailed(
                "needs ${(requiredBytes shr 20)} MB free but only " +
                    "${availableBytes shr 20} MB is available"
            )
        }
    }

    /**
     * Copies a backup into the phone's public Downloads folder, where every
     * file manager and a USB cable can reach it.
     *
     * Written through MediaStore rather than straight to a path: that is the
     * only way an app may add to shared storage on modern Android, and it
     * also makes the file appear in Downloads immediately. The entry stays
     * "pending" until the copy finishes, so a half-written image is never
     * offered to the user.
     *
     * Returns the visible file name; throws [LxdError.StorageFailed] with a
     * readable message on any failure, having cleaned up the partial entry.
     */
    fun exportToDownloads(backup: VmBackup, onProgress: (Float) -> Unit = {}): String {
        if (!backup.file.isFile) {
            throw LxdError.StorageFailed("${backup.displayName} is no longer there")
        }
        val resolver = context.contentResolver
        val details = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, backup.file.name)
            put(MediaStore.Downloads.MIME_TYPE, DISK_MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Hidden from other apps until the bytes are all there.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, details)
            ?: throw LxdError.StorageFailed("could not create the download entry")

        try {
            val totalBytes = backup.file.length().coerceAtLeast(1)
            resolver.openOutputStream(target).use { sink ->
                requireNotNull(sink) { "download entry could not be opened" }
                backup.file.inputStream().use { source ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        copied += read
                        onProgress(copied.toFloat() / totalBytes)
                    }
                }
            }
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            AppLog.info(SCOPE, "exported ${backup.displayName} to Downloads")
            return backup.file.name
        } catch (failure: Exception) {
            // Never leave a half-written file claiming to be a VM image.
            runCatching { resolver.delete(target, null, null) }
            throw LxdError.StorageFailed(
                "could not save to Downloads: ${failure.message}",
                failure,
            )
        }
    }

    fun delete(backup: VmBackup) {
        if (backup.file.exists() && !backup.file.delete()) {
            throw LxdError.StorageFailed("could not delete ${backup.displayName}")
        }
    }

    companion object {
        private const val SCOPE = "VmBackup"
        private const val BACKUP_FOLDER = "vm-backups"
        private const val DISK_EXTENSION = "qcow2"
        private const val BACKUP_TIMEOUT_SECONDS = 30 * 60L
        private const val DISK_MIME_TYPE = "application/octet-stream"
        private const val COPY_BUFFER_BYTES = 1 shl 20
        private const val FREE_SPACE_HEADROOM_BYTES = 128L shl 20

        /** Stamp format: sorts chronologically as plain text. */
        private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"

        /**
         * The backup's name: the source image's name with the local date and
         * time inserted before its extension, keeping the extension it had.
         *
         * ```
         * linux-on-dex-alpine-3.22.0-arm64.qcow2
         *     -> linux-on-dex-alpine-3.22.0-arm64-20260801-221023.qcow2
         * linux-on-dex-debian-13-proot-arm64.rootfs.tar.gz
         *     -> linux-on-dex-debian-13-proot-arm64-20260801-221023.rootfs.tar.gz
         * ```
         *
         * Keeping the suffix is not cosmetic: it is what tells the app, and
         * the user, which kind of image the file is. A container archive
         * renamed `.qcow2` would be offered as a bootable disk and fail.
         *
         * Pure and separately tested — the name is what the user sees and
         * sorts by, so it must not drift.
         */
        fun backupFileName(sourceFileName: String, now: Date): String {
            val stamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(now)
            val suffix = backupSuffixOf(sourceFileName)
            val base = sourceFileName.removeSuffix(suffix)
            return "$base-$stamp$suffix"
        }

        /**
         * The part of a name that says what the file is: the whole
         * `.rootfs.tar.gz` for a container, otherwise the last extension.
         *
         * A name with no extension is treated as a disk image, because that
         * is what it is — the VM's own root disk, whose file name the user
         * never sees.
         */
        private fun backupSuffixOf(fileName: String): String {
            if (PreparedImageFormat.fromFileName(fileName) == PreparedImageFormat.PROOT_ROOTFS) {
                return PreparedImageFormat.ROOTFS_ARCHIVE_SUFFIX
            }
            val lastDot = fileName.lastIndexOf('.')
            return if (lastDot > 0) fileName.substring(lastDot) else ".$DISK_EXTENSION"
        }

        /** True when this file is one of the kinds a backup can be. */
        fun isBackupFile(fileName: String): Boolean =
            PreparedImageFormat.fromFileName(fileName) != null
    }
}
