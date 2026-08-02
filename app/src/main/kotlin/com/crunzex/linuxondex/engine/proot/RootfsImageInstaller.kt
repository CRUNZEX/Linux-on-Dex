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
        if (!archiveFile.exists()) {
            throw LxdError.StorageFailed("rootfs archive is missing: ${archiveFile.name}")
        }
        val rootfsDir = paths.prootRootfsDirFor(archiveFile.name)
        val stampFile = rootfsDir.resolve(STAMP_FILE_NAME)
        val expectedStamp = stampValueFor(archiveFile)

        if (stampFile.takeIf(File::exists)?.readText() == expectedStamp) {
            AppLog.debug(SCOPE, "rootfs already extracted (stamp match): ${rootfsDir.name}")
            return rootfsDir
        }

        discardPreviousExtraction(rootfsDir)
        requireFreeSpaceFor(archiveFile)
        extractInto(archiveFile, rootfsDir, onProgressPercent)
        writeDefaultDnsConfig(rootfsDir)
        stampFile.writeText(expectedStamp)
        return rootfsDir
    }

    /** True when [archiveFile] has a finished extraction (fast start). */
    fun isExtracted(archiveFile: File): Boolean {
        val stampFile = paths.prootRootfsDirFor(archiveFile.name).resolve(STAMP_FILE_NAME)
        return stampFile.takeIf(File::exists)?.readText() == stampValueFor(archiveFile)
    }

    /** Removes the extracted tree, e.g. when its archive is deleted. */
    fun deleteExtraction(archiveFile: File) {
        discardPreviousExtraction(paths.prootRootfsDirFor(archiveFile.name))
    }

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
        val requiredBytes = archiveFile.length() * EXTRACTED_SIZE_FACTOR
        val freeBytes = paths.prootImagesDir.apply { mkdirs() }.usableSpace
        if (freeBytes < requiredBytes) {
            throw LxdError.StorageFailed(
                "extracting ${archiveFile.name} needs about ${requiredBytes shr 30} GB free " +
                    "on internal storage but only ${freeBytes shr 30} GB is available"
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

        /** What proves a tree came from exactly this archive file. */
        fun stampValueFor(archiveFile: File): String =
            stampValue(archiveFile.name, archiveFile.length(), archiveFile.lastModified())

        /** Pure form for tests: name, size and mtime identify an archive. */
        fun stampValue(name: String, sizeBytes: Long, modifiedAtMillis: Long): String =
            "$name:$sizeBytes:$modifiedAtMillis"
    }
}
