package com.crunzex.linuxondex.monitor

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * What the virtual machine currently costs the phone, sampled from the
 * host side — the numbers the user feels, not what the guest believes.
 */
data class VmResourceUsage(
    /** Whole-device CPU share, 0-100, across all cores together. */
    val cpuPercentOfDevice: Int,
    /** Resident memory of the VM process, in megabytes. */
    val residentMemoryMb: Int,
    /** Device core count the percentage is normalized against. */
    val deviceCoreCount: Int,
)

/**
 * Pure text parsers for the two /proc files the monitor reads. Split from
 * the polling loop so the fiddly parts are unit-testable with fixtures.
 */
object ProcFileParser {

    /**
     * Total CPU ticks (user + system) from a `/proc/<pid>/stat` line.
     *
     * The second field, `comm`, may contain spaces and parentheses; per
     * proc(5) the only safe anchor is the *last* ')' in the line, after
     * which fields are strictly space-separated (utime and stime are the
     * 12th and 13th after it).
     */
    fun cpuTicksFromStatLine(statLine: String): Long? {
        val afterComm = statLine.lastIndexOf(')')
        if (afterComm < 0) return null
        val fields = statLine.substring(afterComm + 1).trim().split(' ')
        val userTicks = fields.getOrNull(UTIME_FIELD_AFTER_COMM)?.toLongOrNull() ?: return null
        val systemTicks = fields.getOrNull(STIME_FIELD_AFTER_COMM)?.toLongOrNull() ?: return null
        return userTicks + systemTicks
    }

    /** Resident set size in kilobytes from `/proc/<pid>/status` content. */
    fun residentKbFromStatus(statusText: String): Long? =
        statusText.lineSequence()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()

    // stat(5): after "comm)" the fields are state(1) … utime(12) stime(13).
    private const val UTIME_FIELD_AFTER_COMM = 11
    private const val STIME_FIELD_AFTER_COMM = 12
}

/**
 * Samples the VM process's CPU and memory every couple of seconds by
 * reading its /proc entries — permitted because QEMU is this app's own
 * child process (same uid).
 *
 * Emits null whenever there is nothing to measure (VM not running, process
 * just exited), so the UI can dismiss the card instead of freezing numbers.
 */
class VmResourceMonitor(
    private val vmProcessId: () -> Int?,
    private val readProcFile: (path: String) -> String? = ::readFileOrNull,
    private val monotonicMillis: () -> Long = System::nanoTime.let { nano ->
        { nano() / 1_000_000 }
    },
    private val deviceCoreCount: Int = Runtime.getRuntime().availableProcessors(),
    private val clockTicksPerSecond: Long = ANDROID_CLOCK_TICKS_PER_SECOND,
) {

    fun usage(pollIntervalMillis: Long = DEFAULT_POLL_MILLIS): Flow<VmResourceUsage?> = flow {
        var previousTicks: Long? = null
        var previousAtMillis = 0L

        while (true) {
            val sample = samplePid()
            if (sample == null) {
                previousTicks = null
                emit(null)
            } else {
                val (ticks, residentKb) = sample
                val nowMillis = monotonicMillis()
                val lastTicks = previousTicks
                if (lastTicks != null && nowMillis > previousAtMillis) {
                    emit(
                        buildUsage(
                            deltaTicks = (ticks - lastTicks).coerceAtLeast(0),
                            deltaMillis = nowMillis - previousAtMillis,
                            residentKb = residentKb,
                        )
                    )
                }
                previousTicks = ticks
                previousAtMillis = nowMillis
            }
            delay(pollIntervalMillis)
        }
    }

    /** Reads both proc files for the current pid; null when unavailable. */
    private fun samplePid(): Pair<Long, Long>? {
        val pid = vmProcessId() ?: return null
        val statLine = readProcFile("/proc/$pid/stat") ?: return null
        val statusText = readProcFile("/proc/$pid/status") ?: return null
        val ticks = ProcFileParser.cpuTicksFromStatLine(statLine) ?: return null
        val residentKb = ProcFileParser.residentKbFromStatus(statusText) ?: return null
        return ticks to residentKb
    }

    private fun buildUsage(deltaTicks: Long, deltaMillis: Long, residentKb: Long): VmResourceUsage {
        val cpuSeconds = deltaTicks.toDouble() / clockTicksPerSecond
        val wallSeconds = deltaMillis.toDouble() / 1_000
        val percentOfDevice =
            (cpuSeconds / wallSeconds / deviceCoreCount * 100).toInt().coerceIn(0, 100)
        return VmResourceUsage(
            cpuPercentOfDevice = percentOfDevice,
            residentMemoryMb = (residentKb / 1024).toInt(),
            deviceCoreCount = deviceCoreCount,
        )
    }

    companion object {
        const val DEFAULT_POLL_MILLIS = 2_000L

        /** USER_HZ is 100 on every Android kernel configuration. */
        private const val ANDROID_CLOCK_TICKS_PER_SECOND = 100L

        private fun readFileOrNull(path: String): String? = try {
            File(path).readText()
        } catch (unreadable: Exception) {
            null
        }
    }
}
