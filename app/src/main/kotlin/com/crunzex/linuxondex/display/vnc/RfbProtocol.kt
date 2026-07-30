package com.crunzex.linuxondex.display.vnc

/**
 * Pure helpers for the tiny subset of the RFB 3.8 protocol we speak with
 * QEMU's built-in VNC server. Split from the socket client so pixel decoding
 * is unit-testable.
 */
object RfbProtocol {

    const val PROTOCOL_VERSION = "RFB 003.008\n"

    const val SECURITY_TYPE_NONE = 1

    // Server → client message types.
    const val SERVER_FRAMEBUFFER_UPDATE = 0
    const val SERVER_SET_COLOURMAP = 1
    const val SERVER_BELL = 2
    const val SERVER_CUT_TEXT = 3

    // Client → server message types.
    const val CLIENT_SET_PIXEL_FORMAT = 0
    const val CLIENT_SET_ENCODINGS = 2
    const val CLIENT_FRAMEBUFFER_UPDATE_REQUEST = 3
    const val CLIENT_KEY_EVENT = 4
    const val CLIENT_POINTER_EVENT = 5

    // Encodings.
    const val ENCODING_RAW = 0
    const val ENCODING_COPY_RECT = 1
    const val ENCODING_DESKTOP_SIZE = -223

    /**
     * Wire size of one received pixel, and the single source of truth for
     * every buffer that holds pixel data.
     *
     * Kept next to [clientPixelFormat] deliberately: the two must always
     * agree, and a stale bytes-per-pixel constant anywhere on the frame path
     * means buffer overruns or garbled frames.
     */
    const val BYTES_PER_PIXEL = 2

    /**
     * The exact 16-byte PIXEL_FORMAT block we request: 16bpp RGB565, true
     * colour, little endian, red shift 11 / green 5 / blue 0.
     *
     * WHY 16bpp: the guest has no GPU, so its desktop is rendered pixel by
     * pixel on the emulated CPU, then copied through QEMU's VNC server and
     * across loopback into this app. Every one of those stages is linear in
     * bytes-per-pixel, so asking for 16bpp instead of 32bpp roughly halves
     * the whole per-frame cost at once — a full-screen 1280x800 update drops
     * from 4 MB to 2 MB.
     *
     * WHY RGB565 specifically: this is byte-for-byte Android's
     * [android.graphics.Bitmap.Config.RGB_565] layout on a little-endian
     * device — one 16-bit little-endian word per pixel, red in bits 11..15,
     * green in 5..10, blue in 0..4. Received bytes can therefore be blitted
     * straight into the framebuffer bitmap with no per-pixel conversion at
     * all. That 1:1 match is also why we ask for little-endian pixels rather
     * than the big-endian byte order the rest of RFB uses.
     *
     * Note the max fields are U16 and, unlike the pixel data, always travel
     * big-endian per the RFB spec — hence the leading zero in each pair.
     */
    fun clientPixelFormat(): ByteArray = byteArrayOf(
        16,            // bits-per-pixel
        16,            // depth
        0,             // big-endian flag: 0 = little-endian pixel words
        1,             // true-colour flag
        0, 31,         // red max (u16)   — 5 bits
        0, 63,         // green max       — 6 bits
        0, 31,         // blue max        — 5 bits
        11,            // red shift
        5,             // green shift
        0,             // blue shift
        0, 0, 0,       // padding
    )

    /**
     * Converts one RAW rectangle (little-endian RGB565 per pixel, top-down
     * rows) into opaque ARGB ints ready for `Bitmap.setPixels`.
     */
    fun decodeRawRect(pixelBytes: ByteArray, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        decodeRawRectInto(pixelBytes, pixels, width * height)
        return pixels
    }

    /**
     * Decodes [pixelCount] RGB565 pixels of a RAW rect into a caller-owned
     * array of opaque ARGB ints.
     *
     * Widening to ARGB looks wasteful next to the raw memcpy a whole-screen
     * update gets, but `Bitmap.setPixels` only ever accepts ARGB_8888 ints —
     * even for an RGB_565 bitmap, which re-packs them itself. Partial rects
     * are small by definition (a menu, a blinking caret), so paying for the
     * expansion there is cheaper and far simpler than the alternatives, and
     * it keeps this file free of Android graphics types and therefore
     * unit-testable on a plain JVM. Full frames bypass it entirely; see
     * `RfbClient.applyRawRect`.
     *
     * The array is reused across frames so a busy desktop does not allocate
     * (and later garbage-collect) megabytes every frame, which is the
     * difference between smooth and stuttery under load.
     */
    fun decodeRawRectInto(pixelBytes: ByteArray, into: IntArray, pixelCount: Int) {
        val requiredBytes = pixelCount * BYTES_PER_PIXEL
        require(pixelBytes.size >= requiredBytes) {
            "raw rect too short: ${pixelBytes.size} bytes for $pixelCount pixels " +
                "(need $requiredBytes)"
        }
        require(into.size >= pixelCount) {
            "destination too small: ${into.size} for $pixelCount pixels"
        }
        var byteIndex = 0
        for (pixelIndex in 0 until pixelCount) {
            val lowByte = pixelBytes[byteIndex].toInt() and 0xFF
            val highByte = pixelBytes[byteIndex + 1].toInt() and 0xFF
            into[pixelIndex] = expandRgb565ToArgb((highByte shl 8) or lowByte)
            byteIndex += BYTES_PER_PIXEL
        }
    }

    /**
     * Widens one RGB565 word to an opaque ARGB int.
     *
     * Each channel is scaled by replicating its high bits into the vacated
     * low bits instead of shifting alone, so the full source range maps onto
     * the full destination range: 5-bit 31 becomes 255, not 248. A plain
     * shift would render pure white as 0xF8FCF8 and give every bright
     * surface a visible tint.
     */
    private fun expandRgb565ToArgb(pixel: Int): Int {
        val red5 = (pixel ushr 11) and 0x1F
        val green6 = (pixel ushr 5) and 0x3F
        val blue5 = pixel and 0x1F
        val red8 = (red5 shl 3) or (red5 ushr 2)
        val green8 = (green6 shl 2) or (green6 ushr 4)
        val blue8 = (blue5 shl 3) or (blue5 ushr 2)
        return ALPHA_OPAQUE or (red8 shl 16) or (green8 shl 8) or blue8
    }

    private const val ALPHA_OPAQUE = 0xFF shl 24
}
