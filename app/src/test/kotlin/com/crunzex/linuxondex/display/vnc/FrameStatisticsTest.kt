package com.crunzex.linuxondex.display.vnc

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStatisticsTest {

    @Test
    fun `reports zero before a window has elapsed`() {
        val stats = FrameStatistics(windowMillis = 1_000)

        stats.recordFrame(frameBytes = 1000, nowMillis = 0)
        stats.recordFrame(frameBytes = 1000, nowMillis = 500)

        assertEquals(0, stats.snapshot().framesPerSecond)
    }

    @Test
    fun `computes fps over a completed window`() {
        val stats = FrameStatistics(windowMillis = 1_000)

        // 30 frames spread across exactly one second.
        for (frame in 0 until 30) {
            stats.recordFrame(frameBytes = 0, nowMillis = frame * 33L)
        }
        // Cross the window boundary to publish the measurement.
        stats.recordFrame(frameBytes = 0, nowMillis = 1_000)

        assertEquals(30, stats.snapshot().framesPerSecond)
    }

    @Test
    fun `computes bandwidth in kilobits per second`() {
        val stats = FrameStatistics(windowMillis = 1_000)

        // 125,000 bytes in one second = 1,000 kbit/s.
        stats.recordFrame(frameBytes = 125_000, nowMillis = 0)
        stats.recordFrame(frameBytes = 0, nowMillis = 1_000)

        assertEquals(1_000, stats.snapshot().kilobitsPerSecond)
    }

    @Test
    fun `stale reading drops to zero once frames stop arriving`() {
        val stats = FrameStatistics(windowMillis = 1_000)

        repeat(30) { stats.recordFrame(0, it * 33L) }
        stats.recordFrame(frameBytes = 0, nowMillis = 1_000) // publishes 30 fps

        // Still fresh: half a window later the reading stands.
        assertEquals(30, stats.snapshot(nowMillis = 1_400).framesPerSecond)
        // Quiet for a full window: an idle desktop must read as idle.
        assertEquals(0, stats.snapshot(nowMillis = 2_100).framesPerSecond)
    }

    @Test
    fun `resets counters between windows`() {
        val stats = FrameStatistics(windowMillis = 1_000)

        repeat(10) { stats.recordFrame(0, it * 100L) }
        stats.recordFrame(0, 1_000)      // publishes ~10 fps
        val first = stats.snapshot().framesPerSecond

        // A quieter second window: only 2 frames.
        stats.recordFrame(0, 1_500)
        stats.recordFrame(0, 2_000)
        val second = stats.snapshot().framesPerSecond

        assertEquals(10, first)
        assertEquals(2, second)
    }
}
