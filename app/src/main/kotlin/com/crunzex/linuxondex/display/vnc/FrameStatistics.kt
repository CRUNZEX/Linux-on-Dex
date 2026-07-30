package com.crunzex.linuxondex.display.vnc

/** A point-in-time view of display throughput, for the UI and for tests. */
data class FrameStats(
    val framesPerSecond: Int,
    val kilobitsPerSecond: Int,
)

/**
 * Rolling frames-per-second and bandwidth counter for the VNC display.
 *
 * "Is it laggy?" is otherwise a matter of opinion; this turns it into a
 * number the Display screen can show and a test can assert on. It measures
 * over a fixed window so a brief stall does not swing the reading wildly.
 *
 * Not thread-safe: the read loop is the only writer, and it snapshots for
 * the UI thread through [snapshot].
 */
class FrameStatistics(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {

    // -1 marks "no frame recorded yet"; 0 is a valid timestamp, so it cannot
    // be the sentinel or a window starting at t=0 would never close.
    private var windowStartMillis = UNSTARTED
    private var framesInWindow = 0
    private var bytesInWindow = 0L

    @Volatile
    private var latest = FrameStats(framesPerSecond = 0, kilobitsPerSecond = 0)

    @Volatile
    private var lastFrameMillis = UNSTARTED

    /**
     * Records one decoded frame of [frameBytes]. [nowMillis] is passed in so
     * the logic stays pure and testable — no hidden clock.
     */
    fun recordFrame(frameBytes: Int, nowMillis: Long) {
        if (windowStartMillis == UNSTARTED) windowStartMillis = nowMillis
        lastFrameMillis = nowMillis

        // Close the window first, using only the frames that fell inside it,
        // then count the current frame into the fresh window. Counting the
        // boundary frame into the window it closes would inflate the rate by
        // one frame each second.
        val elapsed = nowMillis - windowStartMillis
        if (elapsed >= windowMillis && framesInWindow > 0) {
            latest = FrameStats(
                framesPerSecond = ((framesInWindow * MILLIS_PER_SECOND) / elapsed).toInt(),
                kilobitsPerSecond = ((bytesInWindow * BITS_PER_BYTE) / elapsed).toInt(),
            )
            windowStartMillis = nowMillis
            framesInWindow = 0
            bytesInWindow = 0
        }

        framesInWindow++
        bytesInWindow += frameBytes
    }

    /** Latest completed measurement; safe to read from another thread. */
    fun snapshot(): FrameStats = latest

    /**
     * Like [snapshot], but reports zero once no frame has arrived for a
     * whole window — otherwise a desktop that went quiet would keep showing
     * its last busy reading forever.
     */
    fun snapshot(nowMillis: Long): FrameStats =
        if (lastFrameMillis == UNSTARTED || nowMillis - lastFrameMillis >= windowMillis) {
            FrameStats(framesPerSecond = 0, kilobitsPerSecond = 0)
        } else {
            latest
        }

    companion object {
        private const val UNSTARTED = -1L
        private const val DEFAULT_WINDOW_MILLIS = 1_000L
        private const val MILLIS_PER_SECOND = 1_000L
        // kilobits: bytes*8 gives bits, and dividing by elapsed-ms already
        // scales by 1/1000, so the result is bits/ms == kilobits/s.
        private const val BITS_PER_BYTE = 8L
    }
}
