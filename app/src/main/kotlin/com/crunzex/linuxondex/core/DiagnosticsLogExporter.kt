package com.crunzex.linuxondex.core

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the app's recent log to a `.log` file in the phone's Downloads
 * folder, so a problem seen on a real phone can be sent on without adb.
 *
 * Goes through MediaStore because that is the only way an app may add to
 * shared storage on modern Android; the entry stays hidden until the whole
 * file is written, so a truncated log is never handed to anyone.
 */
class DiagnosticsLogExporter(private val context: Context) {

    /**
     * Exports the current log and returns the file name it was saved under.
     * Throws [LxdError.StorageFailed] with a readable message on failure.
     */
    fun exportRecentLog(now: Date = Date()): String {
        val lines = AppLog.recentLines()
        if (lines.isEmpty()) {
            throw LxdError.StorageFailed("there is nothing in the log yet")
        }
        val fileName = logFileName(now)
        val resolver = context.contentResolver
        val details = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, LOG_MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, details)
            ?: throw LxdError.StorageFailed("could not create the log file")

        return try {
            resolver.openOutputStream(target).use { sink ->
                requireNotNull(sink) { "log file could not be opened" }
                sink.write(buildLogDocument(lines).toByteArray(Charsets.UTF_8))
            }
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            AppLog.info(SCOPE, "exported the log as $fileName")
            fileName
        } catch (failure: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw LxdError.StorageFailed("could not save the log: ${failure.message}", failure)
        }
    }

    /**
     * The log with a short header naming the build and device, so a file
     * read days later still says what it came from.
     */
    private fun buildLogDocument(lines: List<String>): String = buildString {
        appendLine("Linux on DeX diagnostics log")
        appendLine("Saved: ${TIMESTAMP_FORMAT.format(Date())}")
        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Lines: ${lines.size}")
        appendLine()
        lines.forEach(::appendLine)
    }

    companion object {
        private const val SCOPE = "LogExport"
        /**
         * Deliberately generic. MediaStore rewrites the file name when the
         * extension does not match the MIME type it was given: asking for
         * "text/plain" turns `…​.log` into `…​.log.txt`. A generic type keeps
         * the name exactly as written.
         */
        private const val LOG_MIME_TYPE = "application/octet-stream"

        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private const val FILE_STAMP_PATTERN = "yyyyMMdd-HHmmss"

        /**
         * The saved name, matching how backups are named so both sort
         * chronologically: `linux-on-dex-20260731-220431.log`.
         */
        fun logFileName(now: Date): String {
            val stamp = SimpleDateFormat(FILE_STAMP_PATTERN, Locale.US).format(now)
            return "linux-on-dex-$stamp.log"
        }
    }
}
