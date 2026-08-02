package com.crunzex.linuxondex.display.vnc

import android.graphics.Bitmap
import android.os.SystemClock
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.core.LxdError
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Minimal RFB 3.8 client for QEMU's loopback VNC server: security type
 * "None", RAW + CopyRect + DesktopSize encodings. Local-only by design —
 * this client must never be pointed at a remote host (no encryption).
 *
 * Pixels are negotiated as 16-bit RGB565 (see [RfbProtocol.clientPixelFormat]):
 * half the bytes of 32bpp for the GPU-less guest to render and ship, and an
 * exact match for the [Bitmap.Config.RGB_565] framebuffer we blit into.
 *
 * Threading: [connect] performs the handshake, then [runReadLoop] blocks on
 * the caller's (IO) thread. Input senders are internally synchronized and
 * callable from any thread.
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
) : VncInputSink, AutoCloseable {

    interface Listener {
        /** New framebuffer geometry; the given bitmap replaces any previous one. */
        fun onFramebufferReady(bitmap: Bitmap)

        /** Some region of the bitmap changed. */
        fun onFrameUpdated()

        fun onDisconnected(reason: String)
    }

    @Volatile
    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private val writeLock = Any()
    /** Preserves input order without ever performing socket I/O on Android's UI thread. */
    private val inputWriter = Executors.newSingleThreadExecutor { task ->
        Thread(task, "rfb-input-$port").apply { isDaemon = true }
    }

    @Volatile
    private var framebuffer: Bitmap? = null

    @Volatile
    private var closed = false

    /** Reused across frames so decoding a busy desktop does not allocate. */
    private var rawByteScratch = ByteArray(0)
    private var rawByteBuffer = ByteBuffer.wrap(rawByteScratch)
    private var pixelIntScratch = IntArray(0)

    private val statistics = FrameStatistics()

    override val framebufferWidth: Int get() = framebuffer?.width ?: 0
    override val framebufferHeight: Int get() = framebuffer?.height ?: 0

    /** Latest throughput measurement, safe to read from the UI thread. */
    fun currentStats(): FrameStats = statistics.snapshot(monotonicMillis())

    fun connect() {
        try {
            if (closed) throw IOException("client was closed before connect")
            val connectingSocket = Socket()
            socket = connectingSocket
            if (closed) throw IOException("client closed while connecting")

            connectingSocket.tcpNoDelay = true
            connectingSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            input = DataInputStream(connectingSocket.inputStream.buffered(INPUT_BUFFER_BYTES))
            output = DataOutputStream(connectingSocket.outputStream.buffered())

            negotiateVersion()
            negotiateSecurity()
            exchangeInitMessages()
            sendPixelFormat()
            sendSupportedEncodings()
            requestFramebufferUpdate(incremental = false)
        } catch (error: LxdError) {
            close()
            throw error
        } catch (error: Exception) {
            close()
            throw LxdError.DisplayConnectFailed("$host:$port — ${error.message}", error)
        }
    }

    /** Blocks handling server messages until disconnect or [close]. */
    fun runReadLoop() {
        try {
            while (!closed) {
                when (val messageType = input.readUnsignedByte()) {
                    RfbProtocol.SERVER_FRAMEBUFFER_UPDATE -> handleFramebufferUpdate()
                    RfbProtocol.SERVER_SET_COLOURMAP -> skipColourMap()
                    RfbProtocol.SERVER_BELL -> Unit
                    RfbProtocol.SERVER_CUT_TEXT -> skipCutText()
                    RfbProtocol.SERVER_END_CONTINUOUS_UPDATES -> Unit
                    RfbProtocol.SERVER_FENCE -> skipFence()
                    else -> throw IOException("unknown server message $messageType")
                }
            }
        } catch (error: Exception) {
            if (!closed) {
                AppLog.warn(SCOPE, "read loop ended", error)
                listener.onDisconnected(error.message ?: "connection lost")
            }
        } finally {
            close()
        }
    }

    // ---- Handshake ---------------------------------------------------------

    private fun negotiateVersion() {
        val serverVersion = ByteArray(12).also { input.readFully(it) }
        AppLog.debug(SCOPE, "server version: ${String(serverVersion).trim()}")
        output.write(RfbProtocol.PROTOCOL_VERSION.toByteArray())
        output.flush()
    }

    private fun negotiateSecurity() {
        val typeCount = input.readUnsignedByte()
        if (typeCount == 0) {
            throw IOException("server refused connection: ${readErrorReason()}")
        }
        val offeredTypes = ByteArray(typeCount).also { input.readFully(it) }
        if (offeredTypes.none { it.toInt() == RfbProtocol.SECURITY_TYPE_NONE }) {
            throw IOException("server requires authentication; loopback should offer None")
        }
        output.writeByte(RfbProtocol.SECURITY_TYPE_NONE)
        output.flush()
        val securityResult = input.readInt()
        if (securityResult != 0) {
            throw IOException("security handshake failed: ${readErrorReason()}")
        }
    }

    private fun exchangeInitMessages() {
        output.writeByte(1) // shared session
        output.flush()
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        discardFully(16) // server pixel format — we override it anyway
        val nameLength = input.readInt()
        requirePayloadLength("desktop name", nameLength, MAX_DESKTOP_NAME_BYTES)
        discardFully(nameLength)
        replaceFramebuffer(width, height)
        AppLog.info(SCOPE, "framebuffer ${width}x$height")
    }

    private fun sendPixelFormat() = sendMessage {
        output.writeByte(RfbProtocol.CLIENT_SET_PIXEL_FORMAT)
        output.write(ByteArray(3))
        output.write(RfbProtocol.clientPixelFormat())
    }

    private fun sendSupportedEncodings() = sendMessage {
        val encodings = intArrayOf(
            RfbProtocol.ENCODING_COPY_RECT,
            RfbProtocol.ENCODING_RAW,
            RfbProtocol.ENCODING_DESKTOP_SIZE,
            // Prefer QEMU's fixed 32-bit cursor payload. QEMU 10.2 truncates
            // RichCursor data after a 16-bit pixel-format negotiation, which
            // corrupts the next RFB message when a guest application changes
            // its pointer. TigerVNC ignores this extension and continues with
            // the compatible RichCursor request below.
            RfbProtocol.ENCODING_ALPHA_CURSOR,
            // Keeps the guest's pointer out of the picture: see
            // [RfbProtocol.ENCODING_CURSOR].
            RfbProtocol.ENCODING_CURSOR,
        )
        output.writeByte(RfbProtocol.CLIENT_SET_ENCODINGS)
        output.writeByte(0)
        output.writeShort(encodings.size)
        encodings.forEach(output::writeInt)
    }

    fun requestFramebufferUpdate(incremental: Boolean) {
        val bitmap = framebuffer ?: return
        sendMessage {
            output.writeByte(RfbProtocol.CLIENT_FRAMEBUFFER_UPDATE_REQUEST)
            output.writeByte(if (incremental) 1 else 0)
            output.writeShort(0)
            output.writeShort(0)
            output.writeShort(bitmap.width)
            output.writeShort(bitmap.height)
        }
    }

    // ---- Server messages ---------------------------------------------------

    private fun handleFramebufferUpdate() {
        discardFully(1) // padding
        val rectangleCount = input.readUnsignedShort()

        var frameBytes = 0
        repeat(rectangleCount) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            frameBytes += when (val encoding = input.readInt()) {
                RfbProtocol.ENCODING_RAW -> applyRawRect(x, y, width, height)
                RfbProtocol.ENCODING_COPY_RECT -> applyCopyRect(x, y, width, height)
                RfbProtocol.ENCODING_DESKTOP_SIZE -> { replaceFramebuffer(width, height); 0 }
                RfbProtocol.ENCODING_ALPHA_CURSOR -> discardAlphaCursor(width, height)
                RfbProtocol.ENCODING_CURSOR -> discardCursorSprite(x, y, width, height)
                else -> throw IOException("server sent unrequested encoding $encoding")
            }
        }
        statistics.recordFrame(frameBytes, monotonicMillis())
        listener.onFrameUpdated()

        // Do not write before consuming the complete frame. A server may send
        // its final update and close immediately; an eager request then fails
        // with Broken pipe and used to discard the valid pixels already in our
        // input buffer. Delivery comes first, followed by exactly one request.
        requestFramebufferUpdate(incremental = true)
    }

    /**
     * Consumes a pointer-sprite rectangle and draws nothing.
     *
     * The sprite is requested precisely so the server stops drawing the
     * pointer into the framebuffer; the app then deliberately does not draw
     * it either. Touch input acts where the finger lands, so a second,
     * lagging arrow on screen would only ever be misleading.
     *
     * The payload must still be read in full: its length is implied by the
     * rectangle size rather than stated, so leaving bytes behind would
     * desynchronise every following rectangle.
     */
    private fun discardCursorSprite(x: Int, y: Int, width: Int, height: Int): Int {
        val byteCount = RfbProtocol.cursorRectangleByteCount(width, height)
        if (byteCount > 0) input.readFully(rawScratch(byteCount), 0, byteCount)
        return 0 // not a frame update: nothing on screen changed
    }

    /**
     * Consumes QEMU's alpha-cursor extension without drawing a second pointer.
     * The nested encoding word must be RAW; accepting another value would make
     * its payload length unknowable and silently desynchronise the connection.
     */
    private fun discardAlphaCursor(width: Int, height: Int): Int {
        val nestedEncoding = input.readInt()
        if (nestedEncoding != RfbProtocol.ENCODING_RAW) {
            throw IOException("unsupported alpha cursor encoding $nestedEncoding")
        }
        val pixelByteCount = RfbProtocol.alphaCursorRectangleByteCount(width, height) -
            Int.SIZE_BYTES
        if (pixelByteCount > 0) {
            input.readFully(rawScratch(pixelByteCount), 0, pixelByteCount)
        }
        return 0
    }

    /** Reads and applies one RAW rect; returns the pixel-byte count read. */
    private fun applyRawRect(x: Int, y: Int, width: Int, height: Int): Int {
        if (width == 0 || height == 0) return 0
        val bitmap = framebuffer ?: throw IOException("rect before framebuffer init")
        val byteCount = width * height * PIXEL_BYTES
        val rawBytes = rawScratch(byteCount)
        input.readFully(rawBytes, 0, byteCount)

        synchronized(bitmap) {
            if (coversWholeFramebuffer(bitmap, x, y, width, height) &&
                fillsBitmapExactly(bitmap, byteCount)
            ) {
                // Whole-screen redraw (the common GNOME case): RGB565 on the
                // wire is byte-for-byte an RGB_565 bitmap, so this is a single
                // native memcpy — no per-pixel Kotlin loop and no int buffer.
                bitmap.copyPixelsFromBuffer(rawBuffer(byteCount))
            } else {
                val pixels = pixelScratch(width * height)
                RfbProtocol.decodeRawRectInto(rawBytes, pixels, width * height)
                bitmap.setPixels(pixels, 0, width, x, y, width, height)
            }
        }
        return byteCount
    }

    private fun coversWholeFramebuffer(
        bitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean = x == 0 && y == 0 && width == bitmap.width && height == bitmap.height

    /**
     * Guards the bulk-copy path: the bytes we received must exactly fill the
     * bitmap's own storage.
     *
     * The memcpy is blind to pixel meaning, so if the negotiated format and
     * the bitmap config ever disagreed on bytes-per-pixel the user would see
     * a silently garbled frame. Checking instead costs one comparison per
     * frame and degrades to the per-pixel path, which is self-describing.
     */
    private fun fillsBitmapExactly(bitmap: Bitmap, byteCount: Int): Boolean =
        byteCount == bitmap.byteCount

    /** Grows and reuses the raw-byte scratch so decoding never allocates. */
    private fun rawScratch(byteCount: Int): ByteArray {
        if (rawByteScratch.size < byteCount) {
            rawByteScratch = ByteArray(byteCount)
            rawByteBuffer = ByteBuffer.wrap(rawByteScratch)
        }
        return rawByteScratch
    }

    /** Rewinds the wrapper reused by whole-frame RGB565 copies. */
    private fun rawBuffer(byteCount: Int): ByteBuffer {
        rawScratch(byteCount)
        rawByteBuffer.clear()
        rawByteBuffer.limit(byteCount)
        return rawByteBuffer
    }

    private fun pixelScratch(pixelCount: Int): IntArray {
        if (pixelIntScratch.size < pixelCount) {
            pixelIntScratch = IntArray(pixelCount)
        }
        return pixelIntScratch
    }

    /**
     * Reads and applies one CopyRect; returns its 4-byte source header size.
     *
     * `getPixels`/`setPixels` round-trip an RGB_565 bitmap losslessly (Android
     * expands and re-packs each channel with the same bit replication), so a
     * scrolled region survives the trip through ARGB ints unchanged.
     */
    private fun applyCopyRect(x: Int, y: Int, width: Int, height: Int): Int {
        val bitmap = framebuffer ?: throw IOException("rect before framebuffer init")
        val sourceX = input.readUnsignedShort()
        val sourceY = input.readUnsignedShort()
        val pixels = pixelScratch(width * height)
        synchronized(bitmap) {
            bitmap.getPixels(pixels, 0, width, sourceX, sourceY, width, height)
            bitmap.setPixels(pixels, 0, width, x, y, width, height)
        }
        return COPY_RECT_HEADER_BYTES
    }

    private fun replaceFramebuffer(width: Int, height: Int) {
        // RGB_565 mirrors the pixel format we negotiate, so a whole-screen
        // update lands here as a straight memcpy. It also halves the bitmap's
        // own footprint (2 MB rather than 4 MB at 1280x800).
        //
        // The ARGB_8888 version needed setHasAlpha(false) so the bulk copy
        // did not produce a fully transparent surface. RGB_565 has no alpha
        // channel to misinterpret — it is opaque by construction — so that
        // call is gone rather than left as a no-op that implies otherwise.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        framebuffer = bitmap
        listener.onFramebufferReady(bitmap)
    }

    private fun skipColourMap() {
        discardFully(3)
        val colourCount = input.readUnsignedShort()
        discardFully(colourCount * COLOUR_MAP_BYTES_PER_ENTRY)
    }

    private fun skipCutText() {
        discardFully(3)
        val encodedLength = input.readInt()
        // TigerVNC/QEMU extended clipboard messages encode their payload
        // length as a negative signed value. A previous external viewer can
        // make the server announce clipboard state to the local connection;
        // treating that negative value as "skip nothing" leaves payload
        // bytes in the stream and the next ASCII byte becomes a bogus server
        // message (the observed value 101 is 'e'). We do not consume the
        // clipboard, but we must always consume its complete wire payload.
        val payloadLength = if (encodedLength < 0) {
            -encodedLength.toLong()
        } else {
            encodedLength.toLong()
        }
        if (payloadLength > MAX_CLIPBOARD_BYTES) {
            throw IOException("server clipboard payload too large: $payloadLength bytes")
        }
        discardFully(payloadLength.toInt())
    }

    /** Fence is optional RFB flow-control traffic; ignoring its body corrupts the stream. */
    private fun skipFence() {
        discardFully(3) // padding
        input.readInt() // flags
        val payloadLength = input.readUnsignedByte()
        if (payloadLength > MAX_FENCE_BYTES) {
            throw IOException("server fence payload too large: $payloadLength bytes")
        }
        discardFully(payloadLength)
    }

    /** Reads rather than skips: InputStream.skip is explicitly allowed to stop early. */
    private fun discardFully(byteCount: Int) {
        requirePayloadLength("RFB payload", byteCount, MAX_DISCARDABLE_BYTES)
        var remaining = byteCount
        while (remaining > 0) {
            val chunkBytes = minOf(remaining, discardScratch.size)
            input.readFully(discardScratch, 0, chunkBytes)
            remaining -= chunkBytes
        }
    }

    private fun requirePayloadLength(description: String, byteCount: Int, maximum: Int) {
        if (byteCount !in 0..maximum) {
            throw IOException("invalid $description length: $byteCount")
        }
    }

    private fun readErrorReason(): String {
        val length = input.readInt().coerceIn(0, 1024)
        val reason = ByteArray(length).also { input.readFully(it) }
        return String(reason)
    }

    // ---- Input -------------------------------------------------------------

    /** [buttonMask]: bit0 = left, bit1 = middle, bit2 = right, bits 3/4 = wheel. */
    override fun sendPointerEvent(x: Int, y: Int, buttonMask: Int): Boolean =
        enqueueInputMessage("pointer event") {
            sendMessage {
                output.writeByte(RfbProtocol.CLIENT_POINTER_EVENT)
                output.writeByte(buttonMask)
                output.writeShort(clampCoordinate(x, framebufferWidth))
                output.writeShort(clampCoordinate(y, framebufferHeight))
            }
        }

    override fun sendKeyEvent(keysym: Int, isDown: Boolean): Boolean =
        enqueueInputMessage("key event") {
            sendMessage {
                output.writeByte(RfbProtocol.CLIENT_KEY_EVENT)
                output.writeByte(if (isDown) 1 else 0)
                output.writeShort(0)
                output.writeInt(keysym)
            }
        }

    private fun enqueueInputMessage(description: String, send: () -> Unit): Boolean {
        if (closed) return false
        return try {
            inputWriter.execute {
                if (closed) return@execute
                try {
                    send()
                } catch (error: Exception) {
                    AppLog.warn(SCOPE, "failed to send $description", error)
                    close()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun clampCoordinate(coordinate: Int, extent: Int): Int =
        if (extent > 0) coordinate.coerceIn(0, extent - 1) else 0

    private inline fun sendMessage(write: () -> Unit) {
        synchronized(writeLock) {
            write()
            output.flush()
        }
    }

    /** Monotonic clock for stats; unaffected by wall-clock changes. */
    private fun monotonicMillis(): Long = SystemClock.elapsedRealtime()

    override fun close() {
        closed = true
        inputWriter.shutdownNow()
        runCatching { socket?.close() }
    }

    companion object {
        private const val SCOPE = "RfbClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val INPUT_BUFFER_BYTES = 1 shl 16
        private const val MAX_DESKTOP_NAME_BYTES = 1 shl 20
        private const val MAX_CLIPBOARD_BYTES = 32L shl 20
        private const val MAX_DISCARDABLE_BYTES = 32 shl 20
        private const val MAX_FENCE_BYTES = 64
        private const val COLOUR_MAP_BYTES_PER_ENTRY = 6
        private const val DISCARD_BUFFER_BYTES = 8 * 1024

        /**
         * Derived from the format we negotiate rather than restated, so the
         * two can never drift apart. Currently 2 — RGB565.
         */
        private const val PIXEL_BYTES = RfbProtocol.BYTES_PER_PIXEL

        /** The src-x/src-y header of a CopyRect. Unrelated to [PIXEL_BYTES]. */
        private const val COPY_RECT_HEADER_BYTES = 4
    }

    /** Fixed-size buffer used by [discardFully] for allocation-free draining. */
    private val discardScratch = ByteArray(DISCARD_BUFFER_BYTES)
}
