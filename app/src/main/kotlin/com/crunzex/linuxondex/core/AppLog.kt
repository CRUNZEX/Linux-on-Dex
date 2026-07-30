package com.crunzex.linuxondex.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Central logger: writes to logcat and keeps a bounded in-memory history so
 * the app can show its own diagnostics screen (essential when debugging a VM
 * boot on a physical phone without adb).
 */
object AppLog {

    private const val LOGCAT_TAG = "LinuxOnDex"
    private const val MAX_HISTORY_LINES = 800

    private val history = ArrayDeque<String>(MAX_HISTORY_LINES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun debug(scope: String, message: String) {
        Log.d(LOGCAT_TAG, "[$scope] $message")
        remember("D", scope, message)
    }

    fun info(scope: String, message: String) {
        Log.i(LOGCAT_TAG, "[$scope] $message")
        remember("I", scope, message)
    }

    fun warn(scope: String, message: String, error: Throwable? = null) {
        Log.w(LOGCAT_TAG, "[$scope] $message", error)
        remember("W", scope, describe(message, error))
    }

    fun error(scope: String, message: String, error: Throwable? = null) {
        Log.e(LOGCAT_TAG, "[$scope] $message", error)
        remember("E", scope, describe(message, error))
    }

    /** Snapshot of recent log lines, oldest first. */
    fun recentLines(): List<String> = synchronized(history) { history.toList() }

    private fun describe(message: String, error: Throwable?): String =
        if (error == null) message else "$message (${error.javaClass.simpleName}: ${error.message})"

    private fun remember(level: String, scope: String, message: String) {
        val line = "${timeFormat.format(Date())} $level/$scope: $message"
        synchronized(history) {
            if (history.size >= MAX_HISTORY_LINES) history.removeFirst()
            history.addLast(line)
        }
    }
}
