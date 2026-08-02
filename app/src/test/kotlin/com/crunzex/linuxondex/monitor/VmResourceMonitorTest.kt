package com.crunzex.linuxondex.monitor

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VmResourceMonitorTest {

    // ---- Parsers -------------------------------------------------------------

    @Test
    fun `stat parser sums user and system ticks`() {
        // Fields after comm: state ppid pgrp sid tty tpgid flags minflt
        // cminflt majflt cmajflt utime stime …
        val line = "4242 (qemu-system-aar) S 1 4242 0 0 -1 4194560 " +
            "999 0 12 0 5000 1500 0 0 20 0 8 0 12345 999424 5000 " +
            "18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0"

        assertEquals(6_500L, ProcFileParser.cpuTicksFromStatLine(line))
    }

    @Test
    fun `stat parser survives parentheses and spaces in the process name`() {
        // proc(5): only the LAST ')' safely ends the comm field.
        val line = "77 (fun (name) x) R 1 77 0 0 -1 0 0 0 0 0 250 750 0 0 20 0 1 0 1 1 1"

        assertEquals(1_000L, ProcFileParser.cpuTicksFromStatLine(line))
    }

    @Test
    fun `malformed stat lines yield null instead of garbage`() {
        assertNull(ProcFileParser.cpuTicksFromStatLine("no parens here"))
        assertNull(ProcFileParser.cpuTicksFromStatLine("1 (x) S"))
    }

    @Test
    fun `status parser reads the resident set line`() {
        val status = """
            Name:   qemu-system-aar
            VmPeak:  5000000 kB
            VmRSS:   2097152 kB
            Threads: 9
        """.trimIndent()

        assertEquals(2_097_152L, ProcFileParser.residentKbFromStatus(status))
        assertNull(ProcFileParser.residentKbFromStatus("Name: x\nThreads: 2"))
    }

    // ---- Monitor flow ----------------------------------------------------------

    /** Deterministic harness: scripted clock, ticks and memory readings. */
    private class Harness(
        private val tickReadings: List<Long>,
        residentKb: Long = 1_048_576, // 1 GB
    ) {
        private var sampleIndex = 0
        private var clockMillis = 0L
        private val statusText = "VmRSS:\t$residentKb kB\n"

        val monitor = VmResourceMonitor(
            vmProcessId = { 4242 },
            readProcFile = { path ->
                when {
                    path.endsWith("/stat") -> {
                        val ticks = tickReadings[sampleIndex.coerceAtMost(tickReadings.lastIndex)]
                        sampleIndex++
                        "4242 (qemu) S 1 0 0 0 -1 0 0 0 0 0 $ticks 0 0 0 20 0 8 0 1 1 1"
                    }
                    path.endsWith("/status") -> statusText
                    else -> null
                }
            },
            monotonicMillis = { clockMillis.also { clockMillis += 2_000 } },
            deviceCoreCount = 8,
            clockTicksPerSecond = 100,
        )
    }

    @Test
    fun `computes device-wide cpu percent from tick deltas`() = runTest {
        // 400 ticks in 2 s = 4 s of CPU over 2 s of wall = 2 cores = 25% of 8.
        val harness = Harness(tickReadings = listOf(1_000, 1_400))

        val usage = harness.monitor.usage(pollIntervalMillis = 1).first { it != null }!!

        assertEquals(25, usage.cpuPercentOfDevice)
        assertEquals(1_024, usage.residentMemoryMb)
        assertEquals(8, usage.deviceCoreCount)
    }

    @Test
    fun `emits null when there is no process to measure`() = runTest {
        val monitor = VmResourceMonitor(
            vmProcessId = { null },
            readProcFile = { null },
            monotonicMillis = { 0 },
            deviceCoreCount = 8,
        )

        val first = monitor.usage(pollIntervalMillis = 1).take(2).toList()

        assertEquals(listOf(null, null), first)
    }
}
