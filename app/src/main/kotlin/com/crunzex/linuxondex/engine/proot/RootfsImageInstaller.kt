package com.crunzex.linuxondex.engine.proot

import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Turns an imported rootfs archive (`*.rootfs.tar.gz`) into an extracted
 * directory tree the PRoot engine can run.
 *
 * Extraction is the expensive one-time step of a PRoot desktop — gigabytes
 * and hundreds of thousands of files — so it is stamped: a finished tree
 * records which archive produced it, later boots see the stamp and start in
 * seconds, and a replaced archive triggers a clean re-extraction.
 */
class RootfsImageInstaller(private val paths: VmPaths) {

    /**
     * Returns the ready rootfs directory for [archiveFile], extracting it
     * first if this archive has not been extracted before.
     *
     * [onProgressPercent] is called with 0–100 while extraction runs; a
     * silent multi-minute step reads as a frozen app.
     */
    fun ensureExtracted(
        archiveFile: File,
        onProgressPercent: (Int) -> Unit = {},
    ): File {
        val rootfsDir = paths.prootRootfsDirFor(archiveFile.name)
        val stampFile = rootfsDir.resolve(STAMP_FILE_NAME)
        val installedStamp = stampFile.takeIf(File::isFile)?.readText()
        val reclaimedArchive = readReclaimedArchive(archiveFile)

        if (reclaimedArchive != null) {
            if (installedStamp != reclaimedArchive.stamp) {
                throw LxdError.PayloadCorrupted(
                    "the storage-saving rootfs marker has no matching extracted image; " +
                        "import the original ${archiveFile.name} again"
                )
            }
            deleteRetainedArchiveCopy(archiveFile)
            AppLog.debug(SCOPE, "rootfs already extracted (archive reclaimed): ${rootfsDir.name}")
            return rootfsDir
        }
        if (!archiveFile.exists()) {
            if (installedStamp != null && rootfsDir.isDirectory) {
                deleteRetainedArchiveCopy(archiveFile)
                AppLog.warn(SCOPE, "using extracted rootfs after its imported archive was removed")
                return rootfsDir
            }
            throw LxdError.StorageFailed("rootfs archive is missing: ${archiveFile.name}")
        }

        val expectedStamp = stampValueFor(archiveFile)

        if (installedStamp == expectedStamp) {
            AppLog.debug(SCOPE, "rootfs already extracted (stamp match): ${rootfsDir.name}")
            reclaimImportedArchive(archiveFile, expectedStamp)
            return rootfsDir
        }

        discardPreviousExtraction(rootfsDir)
        requireFreeSpaceFor(archiveFile)
        extractInto(archiveFile, rootfsDir, onProgressPercent)
        writeDefaultDnsConfig(rootfsDir)
        stampFile.writeText(expectedStamp)
        reclaimImportedArchive(archiveFile, expectedStamp)
        return rootfsDir
    }

    /** True when [archiveFile] has a finished extraction (fast start). */
    fun isExtracted(archiveFile: File): Boolean {
        val stampFile = paths.prootRootfsDirFor(archiveFile.name).resolve(STAMP_FILE_NAME)
        val installedStamp = stampFile.takeIf(File::isFile)?.readText() ?: return false
        val reclaimedArchive = readReclaimedArchive(archiveFile)
        return when {
            reclaimedArchive != null -> installedStamp == reclaimedArchive.stamp
            archiveFile.isFile -> installedStamp == stampValueFor(archiveFile)
            else -> true
        }
    }

    /** Removes the extracted tree, e.g. when its archive is deleted. */
    fun deleteExtraction(archiveFile: File) {
        discardPreviousExtraction(paths.prootRootfsDirFor(archiveFile.name))
        deleteRetainedArchiveCopy(archiveFile)
    }

    /**
     * Replaces only the app-managed imported archive with a tiny descriptor
     * after extraction. The original document selected by the user is never
     * touched, while the redundant app copy no longer consumes another GB.
     */
    private fun reclaimImportedArchive(archiveFile: File, stamp: String) {
        try {
            if (!isAppManagedArchive(archiveFile) || !archiveFile.isFile) return
            if (readReclaimedArchive(archiveFile) != null) return

            val sourceSizeBytes = archiveFile.length()
            val descriptorFile = descriptorTemporaryFile(archiveFile)
            val retainedArchive = retainedArchiveFile(archiveFile)
            descriptorFile.delete()
            retainedArchive.delete()
            descriptorFile.writeText(reclaimedArchiveText(stamp, sourceSizeBytes))

            if (!archiveFile.renameTo(retainedArchive)) {
                descriptorFile.delete()
                AppLog.warn(SCOPE, "could not reclaim imported archive ${archiveFile.name}")
                return
            }
            if (!descriptorFile.renameTo(archiveFile)) {
                retainedArchive.renameTo(archiveFile)
                descriptorFile.delete()
                AppLog.warn(SCOPE, "could not publish reclaimed rootfs marker ${archiveFile.name}")
                return
            }

            if (retainedArchive.delete()) {
                AppLog.info(
                    SCOPE,
                    "reclaimed ${sourceSizeBytes shr 20} MiB after rootfs extraction",
                )
            } else {
                AppLog.warn(SCOPE, "rootfs is ready but the redundant archive could not be removed")
            }
        } catch (error: Exception) {
            AppLog.warn(SCOPE, "could not reclaim imported archive ${archiveFile.name}", error)
        }
    }

    private fun deleteRetainedArchiveCopy(archiveFile: File) {
        if (!isAppManagedArchive(archiveFile)) return
        val retainedArchive = retainedArchiveFile(archiveFile)
        if (retainedArchive.exists() && !retainedArchive.delete()) {
            AppLog.warn(SCOPE, "could not remove retained archive ${retainedArchive.name}")
        }
        descriptorTemporaryFile(archiveFile).delete()
    }

    private fun isAppManagedArchive(archiveFile: File): Boolean = runCatching {
        val imageDirectory = paths.vmImagesDir?.canonicalFile ?: return@runCatching false
        archiveFile.canonicalFile.parentFile == imageDirectory
    }.getOrDefault(false)

    private fun extractInto(
        archiveFile: File,
        rootfsDir: File,
        onProgressPercent: (Int) -> Unit,
    ) {
        AppLog.info(
            SCOPE,
            "extracting ${archiveFile.name} (${archiveFile.length() shr 20} MiB compressed)",
        )
        val startedAt = System.currentTimeMillis()
        try {
            val rawStream = archiveFile.inputStream().buffered(FILE_BUFFER_BYTES)
            val progressStream =
                ProgressReportingStream(rawStream, archiveFile.length(), onProgressPercent)
            GZIPInputStream(progressStream, GZIP_BUFFER_BYTES).use { archive ->
                TarExtractor.extract(archive, rootfsDir)
            }
        } catch (error: LxdError) {
            discardPreviousExtraction(rootfsDir)
            throw error
        } catch (error: Exception) {
            // A half-extracted tree must never be bootable; remove it so the
            // next attempt starts clean instead of on top of the wreckage.
            discardPreviousExtraction(rootfsDir)
            throw LxdError.PayloadCorrupted(
                "rootfs extraction failed for ${archiveFile.name}", error,
            )
        }
        val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000
        AppLog.info(SCOPE, "rootfs extracted in ${elapsedSeconds}s: ${rootfsDir.name}")
    }

    private fun discardPreviousExtraction(rootfsDir: File) {
        if (!rootfsDir.exists()) return
        AppLog.info(SCOPE, "removing previous extraction: ${rootfsDir.name}")
        if (!rootfsDir.deleteRecursively()) {
            throw LxdError.StorageFailed("could not remove old rootfs ${rootfsDir.name}")
        }
    }

    /**
     * Refuses to start an extraction that would run the phone out of disk.
     * The tree is larger than the archive; the multiplier is deliberately
     * conservative because "no space left" halfway through wastes minutes.
     */
    private fun requireFreeSpaceFor(archiveFile: File) {
        val requiredBytes = archiveFile.length() * EXTRACTED_SIZE_FACTOR +
            ANDROID_STORAGE_RESERVE_BYTES
        val freeBytes = paths.prootImagesDir.apply { mkdirs() }.usableSpace
        if (freeBytes < requiredBytes) {
            throw LxdError.StorageFailed(
                "extracting ${archiveFile.name} needs about ${requiredBytes shr 30} GB free " +
                    "on internal storage, including Android's safety reserve, but only " +
                    "${freeBytes shr 30} GB is available"
            )
        }
    }

    /**
     * PRoot guests share Android's network directly but have no DHCP to
     * populate the resolver; Android itself has no /etc/resolv.conf to
     * inherit. Public resolvers work on any network.
     */
    private fun writeDefaultDnsConfig(rootfsDir: File) {
        runCatching {
            val resolvConf = rootfsDir.resolve("etc/resolv.conf")
            resolvConf.parentFile?.mkdirs()
            // The rootfs may ship resolv.conf as a dangling symlink into
            // /run; replace it with a real file.
            resolvConf.delete()
            resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
    }

    /** Progress by compressed bytes read — monotonic and cheap. */
    private class ProgressReportingStream(
        delegate: InputStream,
        private val totalBytes: Long,
        private val onProgressPercent: (Int) -> Unit,
    ) : FilterInputStream(delegate) {
        private var consumedBytes = 0L
        private var lastReportedPercent = -1

        override fun read(): Int =
            super.read().also { if (it >= 0) count(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count(it) }

        private fun count(bytes: Int) {
            consumedBytes += bytes
            if (totalBytes <= 0) return
            val percent = ((consumedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            if (percent != lastReportedPercent) {
                lastReportedPercent = percent
                onProgressPercent(percent)
            }
        }
    }

    companion object {
        private const val SCOPE = "RootfsImageInstaller"
        private const val STAMP_FILE_NAME = ".linux-on-dex-rootfs-stamp"
        private const val FILE_BUFFER_BYTES = 1 shl 18
        private const val GZIP_BUFFER_BYTES = 1 shl 16
        private const val EXTRACTED_SIZE_FACTOR = 4L
        private const val ANDROID_STORAGE_RESERVE_BYTES = 2L * 1024 * 1024 * 1024
        private const val RECLAIMED_ARCHIVE_MAGIC = "linux-on-dex-reclaimed-rootfs-v1"
        private const val MAX_RECLAIMED_DESCRIPTOR_BYTES = 4L * 1024
        private const val RETAINED_ARCHIVE_SUFFIX = ".compressed-reclaim"
        private const val DESCRIPTOR_TEMPORARY_SUFFIX = ".descriptor.part"

        data class ReclaimedArchive(
            val stamp: String,
            val sourceSizeBytes: Long,
        )

        /** What proves a tree came from exactly this archive file. */
        fun stampValueFor(archiveFile: File): String =
            readReclaimedArchive(archiveFile)?.stamp
                ?: stampValue(archiveFile.name, archiveFile.length(), archiveFile.lastModified())

        /** Original compressed size, even after the app copy was reclaimed. */
        fun sourceSizeBytes(archiveFile: File): Long =
            readReclaimedArchive(archiveFile)?.sourceSizeBytes ?: archiveFile.length()

        fun isReclaimedArchive(archiveFile: File): Boolean =
            readReclaimedArchive(archiveFile) != null

        internal fun reclaimedArchiveText(stamp: String, sourceSizeBytes: Long): String =
            "$RECLAIMED_ARCHIVE_MAGIC\n$sourceSizeBytes\n$stamp\n"

        internal fun readReclaimedArchive(archiveFile: File): ReclaimedArchive? {
            if (!archiveFile.isFile || archiveFile.length() > MAX_RECLAIMED_DESCRIPTOR_BYTES) {
                return null
            }
            return runCatching {
                val lines = archiveFile.readLines()
                if (lines.size < 3 || lines[0] != RECLAIMED_ARCHIVE_MAGIC) {
                    null
                } else {
                    val sourceSizeBytes = lines[1].toLongOrNull()?.takeIf { it > 0 }
                    val stamp = lines[2].takeIf(String::isNotBlank)
                    if (sourceSizeBytes != null && stamp != null) {
                        ReclaimedArchive(stamp, sourceSizeBytes)
                    } else {
                        null
                    }
                }
            }.getOrNull()
        }

        private fun retainedArchiveFile(archiveFile: File): File =
            File(archiveFile.parentFile, ".${archiveFile.name}$RETAINED_ARCHIVE_SUFFIX")

        private fun descriptorTemporaryFile(archiveFile: File): File =
            File(archiveFile.parentFile, ".${archiveFile.name}$DESCRIPTOR_TEMPORARY_SUFFIX")

        /** Pure form for tests: name, size and mtime identify an archive. */
        fun stampValue(name: String, sizeBytes: Long, modifiedAtMillis: Long): String =
            "$name:$sizeBytes:$modifiedAtMillis"
    }
}
