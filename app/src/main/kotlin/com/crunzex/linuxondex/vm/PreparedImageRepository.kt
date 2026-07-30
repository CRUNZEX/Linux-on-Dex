package com.crunzex.linuxondex.vm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.iso.CloudInitSeedBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A ready-made VM image found on the device, ready to boot without installing. */
data class PreparedImage(
    val diskFile: File,
    val seedFile: File?,
) {
    val displayName: String get() = prettifyFileName(diskFile.name)
    val sizeMb: Long get() = diskFile.length() shr 20

    /** "linux-on-dex-ubuntu-24.04-arm64.qcow2" -> "Ubuntu 24.04 arm64". */
    private fun prettifyFileName(fileName: String): String = fileName
        .removeSuffix(".qcow2")
        .removeSuffix(".img")
        .removePrefix("linux-on-dex-")
        .split('-')
        .joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) }
}

/**
 * Manages ready-made VM images: the ones already on the device, and the ones
 * the user imports through the file picker.
 *
 * Images live in external app storage so they can also be pushed over
 * USB/DeX: `Android/data/com.crunzex.linuxondex/files/vm-images/`
 */
class PreparedImageRepository(
    private val context: Context,
    private val paths: VmPaths,
) {

    fun listAvailable(): List<PreparedImage> {
        val directory = paths.vmImagesDir ?: return emptyList()
        if (!directory.exists()) return emptyList()

        val files = directory.listFiles().orEmpty().filter(File::isFile)
        return files
            .filter { it.extension.lowercase() in DISK_EXTENSIONS }
            .sortedBy { it.name.lowercase() }
            .map { diskFile -> PreparedImage(diskFile, findSeedFor(diskFile, files)) }
    }

    /**
     * Copies a disk image chosen in the file picker into app storage and
     * generates its cloud-init seed, so importing is a single pick.
     *
     * Reports progress (0.0..1.0) because these images are hundreds of
     * megabytes and a silent copy looks like a frozen app.
     */
    fun importFromDocument(
        documentUri: Uri,
        username: String = PreparedImageConfig.DEFAULT_USERNAME,
        password: String = PreparedImageConfig.DEFAULT_PASSWORD,
        onProgress: (fraction: Float) -> Unit = {},
    ): PreparedImage {
        val directory = paths.vmImagesDir
            ?: throw LxdError.StorageFailed("external storage is unavailable")
        directory.mkdirs()

        val fileName = sanitizeFileName(queryDisplayName(documentUri) ?: DEFAULT_IMAGE_NAME)
        val destination = uniqueDestination(directory, fileName)
        val temporary = File(directory, destination.name + ".part")

        try {
            val totalBytes = queryDocumentSize(documentUri)
            requireFreeSpaceFor(directory, totalBytes)
            val input = context.contentResolver.openInputStream(documentUri)
                ?: throw LxdError.StorageFailed("cannot open the selected file")
            input.use { source ->
                temporary.outputStream().use { sink ->
                    copyReportingProgress(source, sink, totalBytes, onProgress)
                }
            }
            requireLooksLikeDiskImage(temporary)
            if (!temporary.renameTo(destination)) {
                throw LxdError.StorageFailed("rename ${temporary.name} → ${destination.name}")
            }

            val seedFile = CloudInitSeedBuilder.build(
                outputFile = seedFileFor(destination),
                username = username,
                password = password,
            )
            AppLog.info(SCOPE, "imported ${destination.name} (${destination.length() shr 20} MiB)")
            return PreparedImage(destination, seedFile)
        } catch (error: LxdError) {
            temporary.delete()
            throw error
        } catch (error: Exception) {
            temporary.delete()
            throw LxdError.StorageFailed("importing the VM image", error)
        }
    }

    fun delete(image: PreparedImage) {
        image.seedFile?.takeIf { it.name.startsWith(image.diskFile.nameWithoutExtension) }?.delete()
        if (!image.diskFile.delete()) {
            throw LxdError.StorageFailed("delete ${image.displayName}")
        }
    }

    /** Builds the config a VM needs to boot [image] with no installer. */
    fun toConfig(image: PreparedImage): PreparedImageConfig = PreparedImageConfig(
        displayName = image.displayName,
        diskImagePath = image.diskFile.absolutePath,
        seedIsoPath = image.seedFile?.absolutePath,
    )

    /**
     * A disk's own seed if one was generated for it, otherwise any generic
     * seed sitting in the folder — which is what the offline build tool
     * produces alongside its image.
     */
    private fun findSeedFor(diskFile: File, candidates: List<File>): File? {
        val ownSeed = seedFileFor(diskFile)
        if (ownSeed.exists()) return ownSeed
        return candidates.firstOrNull { it.name.endsWith(GENERIC_SEED_SUFFIX) }
    }

    private fun seedFileFor(diskFile: File): File =
        File(diskFile.parentFile, "${diskFile.nameWithoutExtension}$SEED_SUFFIX")

    /**
     * Rejects an obviously wrong pick before it becomes a failed boot. qcow2
     * images start with the magic "QFI\xFB"; raw images have no signature, so
     * they are accepted on size alone.
     */
    private fun requireLooksLikeDiskImage(file: File) {
        if (file.length() < MIN_PLAUSIBLE_IMAGE_BYTES) {
            throw LxdError.StorageFailed(
                "the selected file is only ${file.length() shr 10} KB — too small " +
                    "to be a virtual machine disk"
            )
        }
        val header = ByteArray(QCOW2_MAGIC.size)
        file.inputStream().use { it.read(header) }
        val isQcow2 = header.contentEquals(QCOW2_MAGIC)
        if (!isQcow2 && file.extension.lowercase() == "qcow2") {
            throw LxdError.StorageFailed(
                "the selected file is named .qcow2 but is not a qcow2 image"
            )
        }
    }

    private fun copyReportingProgress(
        source: InputStream,
        sink: OutputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copiedBytes = 0L
        var nextReportAt = PROGRESS_REPORT_INTERVAL_BYTES

        onProgress(if (totalBytes > 0) 0f else UNKNOWN_PROGRESS)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            sink.write(buffer, 0, read)
            copiedBytes += read
            if (copiedBytes >= nextReportAt) {
                nextReportAt = copiedBytes + PROGRESS_REPORT_INTERVAL_BYTES
                onProgress(
                    if (totalBytes > 0) {
                        (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else {
                        UNKNOWN_PROGRESS
                    }
                )
            }
        }
        onProgress(1f)
    }

    private fun requireFreeSpaceFor(directory: File, totalBytes: Long) {
        if (totalBytes <= 0) return
        val requiredBytes = totalBytes + FREE_SPACE_HEADROOM_BYTES
        val freeBytes = directory.usableSpace
        if (freeBytes < requiredBytes) {
            throw LxdError.StorageFailed(
                "needs ${requiredBytes shr 20} MB free but only ${freeBytes shr 20} MB " +
                    "is available"
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
        }
    }.getOrNull()

    private fun queryDocumentSize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeColumn >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeColumn)) {
                cursor.getLong(sizeColumn)
            } else {
                0L
            }
        } ?: 0L
    }.getOrDefault(0L)

    /** Keeps only safe characters; guards against path traversal from names. */
    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val hasKnownExtension = DISK_EXTENSIONS.any { cleaned.lowercase().endsWith(".$it") }
        val named = if (hasKnownExtension) cleaned else "$cleaned.qcow2"
        return named.take(MAX_FILE_NAME_LENGTH)
    }

    private fun uniqueDestination(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        var counter = 1
        while (candidate.exists()) {
            val base = fileName.substringBeforeLast('.')
            val extension = fileName.substringAfterLast('.', "qcow2")
            candidate = File(directory, "$base-$counter.$extension")
            counter++
        }
        return candidate
    }

    companion object {
        private const val SCOPE = "PreparedImageRepository"
        private const val SEED_SUFFIX = "-seed.iso"
        private const val GENERIC_SEED_SUFFIX = "seed.iso"
        private const val DEFAULT_IMAGE_NAME = "linux-vm.qcow2"
        private const val MAX_FILE_NAME_LENGTH = 80
        private const val COPY_BUFFER_BYTES = 1 shl 17
        private const val PROGRESS_REPORT_INTERVAL_BYTES = 8L shl 20
        private const val FREE_SPACE_HEADROOM_BYTES = 512L shl 20
        private const val MIN_PLAUSIBLE_IMAGE_BYTES = 1L shl 20

        private val DISK_EXTENSIONS = setOf("qcow2", "img")
        private val QCOW2_MAGIC = byteArrayOf(0x51, 0x46, 0x49, 0xFB.toByte()) // "QFI\xFB"

        /** Reported when the provider does not disclose the file size. */
        const val UNKNOWN_PROGRESS = -1f
    }
}
