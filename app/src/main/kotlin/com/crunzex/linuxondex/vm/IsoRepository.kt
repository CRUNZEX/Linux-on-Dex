package com.crunzex.linuxondex.vm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.File

/** An installer image the user can boot from. */
data class IsoFile(val file: File) {
    val displayName: String get() = file.name
    val sizeMb: Long get() = file.length() shr 20
}

/**
 * Manages installer ISOs. Two sources:
 *  - files imported through the system file picker (copied into app storage)
 *  - files the user drops into Android/data/<pkg>/files/isos over USB/DeX
 */
class IsoRepository(
    private val context: Context,
    private val paths: VmPaths,
) {

    fun listAvailable(): List<IsoFile> {
        val directories = listOfNotNull(paths.isosDir, paths.externalIsosDir)
        return directories
            .flatMap { directory -> directory.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.extension.lowercase() == "iso" }
            .sortedBy { it.name.lowercase() }
            .map(::IsoFile)
    }

    /**
     * Copies the picked document into app storage so QEMU can open it by
     * path.
     *
     * Installer ISOs run to several gigabytes, so this reports progress as
     * it streams: without it the UI looks frozen for minutes. [onProgress]
     * is called with 0.0..1.0, or -1f when the source size is unknown.
     */
    fun importFromDocument(
        documentUri: Uri,
        onProgress: (fraction: Float) -> Unit = {},
    ): IsoFile {
        val fileName = sanitizeFileName(queryDisplayName(documentUri) ?: DEFAULT_ISO_NAME)
        paths.isosDir.mkdirs()
        val destination = uniqueDestination(fileName)
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            val totalBytes = queryDocumentSize(documentUri)
            requireFreeSpaceFor(totalBytes)
            val input = context.contentResolver.openInputStream(documentUri)
                ?: throw LxdError.IsoImportFailed("cannot open $documentUri")
            input.use { source ->
                temporary.outputStream().use { sink ->
                    copyReportingProgress(source, sink, totalBytes, onProgress)
                }
            }
            if (!temporary.renameTo(destination)) {
                throw LxdError.StorageFailed("rename ${temporary.name} → ${destination.name}")
            }
            AppLog.info(SCOPE, "imported ISO ${destination.name} (${destination.length() shr 20} MiB)")
            return IsoFile(destination)
        } catch (error: LxdError) {
            temporary.delete()
            throw error
        } catch (error: Exception) {
            temporary.delete()
            throw LxdError.IsoImportFailed("copy failed: ${error.message}", error)
        }
    }

    fun delete(iso: IsoFile) {
        if (!iso.file.delete()) {
            throw LxdError.StorageFailed("delete ${iso.displayName}")
        }
    }

    /**
     * Streams the ISO across, reporting progress at most every
     * [PROGRESS_REPORT_INTERVAL_BYTES] so the UI updates smoothly without
     * being flooded.
     */
    private fun copyReportingProgress(
        source: java.io.InputStream,
        sink: java.io.OutputStream,
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

    /** Document size in bytes, or 0 when the provider will not say. */
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

    /** Fails early rather than half-copying a multi-gigabyte file. */
    private fun requireFreeSpaceFor(totalBytes: Long) {
        if (totalBytes <= 0) return
        val freeBytes = paths.isosDir.usableSpace
        val requiredBytes = totalBytes + FREE_SPACE_HEADROOM_BYTES
        if (freeBytes < requiredBytes) {
            throw LxdError.IsoImportFailed(
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

    /** Keeps only safe characters; guards against path traversal from names. */
    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val withExtension = if (cleaned.lowercase().endsWith(".iso")) cleaned else "$cleaned.iso"
        return withExtension.take(MAX_FILE_NAME_LENGTH)
    }

    private fun uniqueDestination(fileName: String): File {
        var candidate = paths.isosDir.resolve(fileName)
        var counter = 1
        while (candidate.exists()) {
            val base = fileName.removeSuffix(".iso")
            candidate = paths.isosDir.resolve("$base-$counter.iso")
            counter++
        }
        return candidate
    }

    companion object {
        private const val SCOPE = "IsoRepository"
        private const val DEFAULT_ISO_NAME = "linux.iso"
        private const val MAX_FILE_NAME_LENGTH = 80
        private const val COPY_BUFFER_BYTES = 1 shl 17
        private const val PROGRESS_REPORT_INTERVAL_BYTES = 8L shl 20
        private const val FREE_SPACE_HEADROOM_BYTES = 256L shl 20

        /** Reported when the provider does not disclose the file size. */
        const val UNKNOWN_PROGRESS = -1f
    }
}
