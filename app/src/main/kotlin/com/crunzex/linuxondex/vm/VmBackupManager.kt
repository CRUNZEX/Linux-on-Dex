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
 * The copy is made with `qemu-img convert`, not a plain file copy: it
 * rewrites the image without the blocks the guest has discarded, so a
 * backup is usually far smaller than the live disk, and it verifies the
 * source is a readable qcow2 on the way through.
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
            .filter { it.isFile && it.extension.equals(DISK_EXTENSION, ignoreCase = true) }
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
            throw LxdError.StorageFailed("this virtual machine has no disk to back up yet")
        }
        val destination = File(backupDirectory(), backupFileName(source.name, now))

        AppLog.info(SCOPE, "backing up ${source.name} -> ${destination.name}")
        val qemuImg = NativeCommand(
            program = paths.qemuImgBinary,
            arguments = listOf(
                "convert",
                "-O", "qcow2",
                source.absolutePath,
                destination.absolutePath,
            ),
            environment = paths.processEnvironment(),
        )
        val result = qemuImg.runAndCaptureOutput(timeoutSeconds = BACKUP_TIMEOUT_SECONDS)
        if (!result.isSuccess) {
            destination.delete()
            throw LxdError.StorageFailed("backup failed: ${result.output.take(200)}")
        }
        if (!destination.isFile || destination.length() == 0L) {
            destination.delete()
            throw LxdError.StorageFailed("backup produced no file")
        }
        return VmBackup(destination, destination.length() shr 20, destination.lastModified())
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

        /** Stamp format: sorts chronologically as plain text. */
        private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"

        /**
         * The backup's name: the source image's name with the local date and
         * time appended, e.g.
         * `linux-on-dex-alpine-3.22.0-arm64-20260731-220431.qcow2`.
         *
         * Pure and separately tested — the name is what the user sees and
         * sorts by, so it must not drift.
         */
        fun backupFileName(sourceFileName: String, now: Date): String {
            val stamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(now)
            val base = sourceFileName.removeSuffix(".$DISK_EXTENSION")
            return "$base-$stamp.$DISK_EXTENSION"
        }
    }
}
