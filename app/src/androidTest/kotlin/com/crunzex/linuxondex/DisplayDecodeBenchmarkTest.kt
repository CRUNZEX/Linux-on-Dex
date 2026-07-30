package com.crunzex.linuxondex

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.crunzex.linuxondex.display.vnc.RfbProtocol
import java.nio.ByteBuffer

/**
 * On-device measurement of the two ways to push a full-screen RAW frame into
 * an Android bitmap, at the real desktop resolution.
 *
 *  - old: allocate an IntArray, decode pixel-by-pixel, then setPixels
 *  - new: copyPixelsFromBuffer straight from the received bytes
 *
 * The new path is what the display now uses for whole-screen redraws (the
 * common GNOME case). This proves on real hardware that it is faster, not
 * just tidier — the whole point of the performance work.
 */
@RunWith(AndroidJUnit4::class)
class DisplayDecodeBenchmarkTest {

    private val width = 1280
    private val height = 800
    private val pixelCount = width * height

    // The client negotiates 16-bit RGB565, so a frame is two bytes per
    // pixel — half what the old 32-bit format moved. Derived from the
    // protocol constant so this benchmark always measures what the app
    // actually does.
    private val frameBytes = pixelCount * RfbProtocol.BYTES_PER_PIXEL

    @Test
    fun wholeFrameBulkCopyBeatsPerPixelDecode() {
        val rawFrame = syntheticFrame()
        // RGB_565 matches the negotiated wire format byte for byte, which is
        // what makes the whole-frame copy a plain memcpy.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        // Warm up both paths so class-loading and JIT do not skew the result.
        repeat(WARMUP_FRAMES) {
            decodeAndSetPixels(rawFrame, bitmap)
            bulkCopy(rawFrame, bitmap)
        }

        val oldNanos = measure { decodeAndSetPixels(rawFrame, bitmap) }
        val newNanos = measure { bulkCopy(rawFrame, bitmap) }

        val oldFps = framesPerSecond(oldNanos)
        val newFps = framesPerSecond(newNanos)
        println("DECODE BENCHMARK ${width}x$height over $MEASURED_FRAMES frames:")
        println("  per-pixel + setPixels : ${oldNanos / 1_000_000}ms total, ~$oldFps fps")
        println("  copyPixelsFromBuffer  : ${newNanos / 1_000_000}ms total, ~$newFps fps")
        println("  speed-up: ${"%.1f".format(oldNanos.toDouble() / newNanos)}x")

        assertTrue(
            "bulk copy ($newFps fps) should beat per-pixel decode ($oldFps fps)",
            newNanos < oldNanos,
        )
    }

    /** The pre-optimisation path: allocate, decode each pixel, upload. */
    private fun decodeAndSetPixels(rawFrame: ByteArray, bitmap: Bitmap) {
        val pixels = RfbProtocol.decodeRawRect(rawFrame, width, height)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /** The optimised whole-frame path: a single native bulk copy. */
    private fun bulkCopy(rawFrame: ByteArray, bitmap: Bitmap) {
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rawFrame, 0, frameBytes))
    }

    private inline fun measure(frame: () -> Unit): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        repeat(MEASURED_FRAMES) { frame() }
        return SystemClock.elapsedRealtimeNanos() - start
    }

    private fun framesPerSecond(totalNanos: Long): Int =
        (MEASURED_FRAMES.toLong() * 1_000_000_000 / totalNanos).toInt()

    /** A frame with varied pixels so decoding cannot be optimised away. */
    private fun syntheticFrame(): ByteArray = ByteArray(frameBytes) { index ->
        (index * 31).toByte()
    }

    companion object {
        private const val WARMUP_FRAMES = 5
        private const val MEASURED_FRAMES = 60
    }
}
