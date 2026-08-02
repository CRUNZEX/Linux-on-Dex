package com.crunzex.linuxondex.vm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.proot.RootfsImageInstaller
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
    /** What the file is, deciding which engine boots it. */
    val format: PreparedImageFormat =
        PreparedImageFormat.fromFileName(diskFile.name) ?: PreparedImageFormat.QCOW2_DISK

    val displayName: String get() = prettifyFileName(diskFile.name)
    val sizeMb: Long get() = diskFile.length() shr 20

    /** "linux-on-dex-ubuntu-24.04-arm64.qcow2" -> "Ubuntu 24.04 arm64". */
    private fun prettifyFileName(fileName: String): String = fileName
        .removeSuffix(PreparedImageFormat.ROOTFS_ARCHIVE_SUFFIX)
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
    private val rootfsInstaller by lazy { RootfsImageInstaller(paths) }

    fun listAvailable(): List<PreparedImage> {
        val directory = paths.vmImagesDir ?: return emptyList()
        if (!directory.exists()) return emptyList()

        val files = directory.listFiles().orEmpty().filter(File::isFile)
        return files
            .filter { PreparedImageFormat.fromFileName(it.name) != null }
            .sortedBy { it.name.lowercase() }
            .map { imageFile ->
                val image = PreparedImage(imageFile, seedFile = null)
                // Only disks boot through cloud-init; a rootfs needs no seed.
                if (image.format == PreparedImageFormat.QCOW2_DISK) {
                    image.copy(seedFile = findSeedFor(imageFile, files))
                } else {
                    image
                }
            }
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
        val format = PreparedImageFormat.fromFileName(destination.name)
            ?: PreparedImageFormat.QCOW2_DISK
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
            requireLooksLikeImage(temporary, format)
            if (!temporary.renameTo(destination)) {
                throw LxdError.StorageFailed("rename ${temporary.name} → ${destination.name}")
            }

            // Only disks boot through cloud-init; a rootfs archive carries
            // its whole configuration inside and needs no seed.
            val seedFile = if (format == PreparedImageFormat.QCOW2_DISK) {
                CloudInitSeedBuilder.build(
                    outputFile = seedFileFor(destination),
                    username = username,
                    password = password,
                )
            } else {
                null
            }
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
        if (image.format == PreparedImageFormat.PROOT_ROOTFS) {
            // The extracted tree is gigabytes; deleting the archive without
            // it would silently keep paying for an image that is gone.
            rootfsInstaller.deleteExtraction(image.diskFile)
        }
    }

    /** Builds the config a VM needs to boot [image] with no installer. */
    fun toConfig(image: PreparedImage): PreparedImageConfig = PreparedImageConfig(
        displayName = image.displayName,
        diskImagePath = image.diskFile.absolutePath,
        seedIsoPath = image.seedFile?.absolutePath,
        format = image.format,
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
     * images start with the magic "QFI\xFB"; rootfs archives are gzip
     * streams; raw .img disks have no signature and pass on size alone.
     */
    private fun requireLooksLikeImage(file: File, format: PreparedImageFormat) {
        if (file.length() < MIN_PLAUSIBLE_IMAGE_BYTES) {
            throw LxdError.StorageFailed(
                "the selected file is only ${file.length() shr 10} KB — too small " +
                    "to be a virtual machine image"
            )
        }
        val header = ByteArray(MAGIC_HEADER_BYTES)
        file.inputStream().use { it.read(header) }
        when (format) {
            PreparedImageFormat.QCOW2_DISK -> {
                val isQcow2 = header.copyOf(QCOW2_MAGIC.size).contentEquals(QCOW2_MAGIC)
                if (!isQcow2 && file.extension.lowercase() == "qcow2") {
                    throw LxdError.StorageFailed(
                        "the selected file is named .qcow2 but is not a qcow2 image"
                    )
                }
            }
            PreparedImageFormat.PROOT_ROOTFS -> {
                val isGzip = header.copyOf(GZIP_MAGIC.size).contentEquals(GZIP_MAGIC)
                if (!isGzip) {
                    throw LxdError.StorageFailed(
                        "the selected file is named ${PreparedImageFormat.ROOTFS_ARCHIVE_SUFFIX} " +
                            "but is not a gzip archive"
                    )
                }
            }
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
        val named = if (PreparedImageFormat.fromFileName(cleaned) != null) {
            cleaned
        } else {
            "$cleaned.qcow2"
        }
        // The suffix decides the format, so truncation must spare it.
        return if (named.length <= MAX_FILE_NAME_LENGTH) named else {
            val suffix = knownSuffixOf(named)
            named.take(MAX_FILE_NAME_LENGTH - suffix.length) + suffix
        }
    }

    private fun uniqueDestination(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        var counter = 1
        while (candidate.exists()) {
            // Number before the whole suffix: "x-1.rootfs.tar.gz", never
            // "x.rootfs.tar-1.gz" (which would no longer read as a rootfs).
            val suffix = knownSuffixOf(fileName)
            val base = fileName.removeSuffix(suffix)
            candidate = File(directory, "$base-$counter$suffix")
            counter++
        }
        return candidate
    }

    /** The format-bearing suffix, e.g. ".rootfs.tar.gz" or ".qcow2". */
    private fun knownSuffixOf(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(PreparedImageFormat.ROOTFS_ARCHIVE_SUFFIX) ->
                PreparedImageFormat.ROOTFS_ARCHIVE_SUFFIX
            lower.contains('.') -> "." + fileName.substringAfterLast('.')
            else -> ""
        }
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

        private val QCOW2_MAGIC = byteArrayOf(0x51, 0x46, 0x49, 0xFB.toByte()) // "QFI\xFB"
        private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())
        private const val MAGIC_HEADER_BYTES = 4

        /** Reported when the provider does not disclose the file size. */
        const val UNKNOWN_PROGRESS = -1f
    }
}
