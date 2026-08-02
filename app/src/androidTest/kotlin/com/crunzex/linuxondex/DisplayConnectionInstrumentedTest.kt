package com.crunzex.linuxondex.ui.display

import android.graphics.Bitmap
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crunzex.linuxondex.display.vnc.ActiveConnectionOwner
import com.crunzex.linuxondex.display.vnc.RfbClient
import com.crunzex.linuxondex.display.vnc.RfbProtocol
import com.crunzex.linuxondex.display.vnc.VncView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercises the same socket, reconnect, view-binding, and input path as DisplayScreen. */
@RunWith(AndroidJUnit4::class)
class DisplayConnectionInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun externalClipboardTrafficReconnectsAndDexClickControlsGuest() {
        FakeReconnectServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val connectionOwner = ActiveConnectionOwner<RfbClient>()
            val frameLatch = CountDownLatch(EXPECTED_FRAME_COUNT)
            val statusMessages = mutableListOf<String>()
            lateinit var view: VncView

            instrumentation.runOnMainSync {
                view = VncView(instrumentation.targetContext).apply {
                    layout(0, 0, FRAME_WIDTH, FRAME_HEIGHT)
                }
                connectVnc(
                    view = view,
                    port = server.port,
                    scope = scope,
                    connectionOwner = connectionOwner,
                    onStatus = { message -> statusMessages += message },
                    onFirstFrame = frameLatch::countDown,
                )
            }

            try {
                val paintedBothConnections = frameLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertNull("fake RFB server failed before both frames: ${server.failure.get()}", server.failure.get())
                assertTrue(
                    "Display did not paint before and after reconnect; statuses=$statusMessages",
                    paintedBothConnections,
                )
                dispatchDexPrimaryClick(view, x = 23f, y = 31f)
                assertTrue(
                    "reconnected RFB server received no DeX click",
                    server.pointerLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                assertEquals(listOf(1, 0), server.pointerButtonMasks)
                assertNull("fake RFB server failed", server.failure.get())
            } finally {
                connectionOwner.close()
                scope.cancel()
            }
        }
    }

    private fun dispatchDexPrimaryClick(view: VncView, x: Float, y: Float) {
        val down = mouseEvent(MotionEvent.ACTION_DOWN, x, y)
        val up = mouseEvent(MotionEvent.ACTION_UP, x, y)
        try {
            instrumentation.runOnMainSync {
                assertTrue(view.onTouchEvent(down))
                assertTrue(view.onTouchEvent(up))
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /** Samsung DeX may omit BUTTON_PRIMARY here; the view must infer it. */
    private fun mouseEvent(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 1L, action, x, y, 0).apply {
            source = InputDevice.SOURCE_MOUSE
        }

    private class FakeReconnectServer : AutoCloseable {
        // The production client is deliberately IPv4 loopback-only. Android's
        // getLoopbackAddress() may return ::1, which is a different listener.
        private val serverSocket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val port: Int = serverSocket.localPort
        val pointerLatch = CountDownLatch(EXPECTED_POINTER_EVENT_COUNT)
        val pointerButtonMasks = mutableListOf<Int>()
        val failure = AtomicReference<Throwable?>(null)

        private val worker = thread(start = true, name = "fake-rfb-reconnect-server") {
            runCatching {
                serveFirstConnection()
                serveSecondConnection()
            }.onFailure(failure::set)
        }

        private fun serveFirstConnection() {
            serverSocket.accept().use { socket ->
                val streams = completeHandshake(socket)
                // Negative length denotes extended clipboard. The first byte
                // is deliberately 101 ('e'), reproducing the reported bogus
                // message when a client fails to drain the payload.
                streams.output.writeByte(RfbProtocol.SERVER_CUT_TEXT)
                streams.output.write(byteArrayOf(0, 0, 0))
                streams.output.writeInt(-EXTENDED_CLIPBOARD_PAYLOAD.size)
                streams.output.write(EXTENDED_CLIPBOARD_PAYLOAD)
                sendAlphaCursor(streams.output)
                sendRawFrame(streams.output)
                streams.output.flush()
            }
        }

        private fun serveSecondConnection() {
            serverSocket.accept().use { socket ->
                val streams = completeHandshake(socket)
                sendRawFrame(streams.output)
                streams.output.flush()
                while (pointerButtonMasks.size < EXPECTED_POINTER_EVENT_COUNT) {
                    when (val messageType = streams.input.readUnsignedByte()) {
                        RfbProtocol.CLIENT_FRAMEBUFFER_UPDATE_REQUEST -> discardFully(streams.input, 9)
                        RfbProtocol.CLIENT_POINTER_EVENT -> {
                            pointerButtonMasks += streams.input.readUnsignedByte()
                            discardFully(streams.input, 4)
                            pointerLatch.countDown()
                        }
                        RfbProtocol.CLIENT_KEY_EVENT -> discardFully(streams.input, 7)
                        else -> throw AssertionError("unexpected client message $messageType")
                    }
                }
            }
        }

        private fun completeHandshake(socket: Socket): RfbStreams {
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())

            output.write(RfbProtocol.PROTOCOL_VERSION.toByteArray())
            output.flush()
            assertEquals(RfbProtocol.PROTOCOL_VERSION, String(readFully(input, 12)))

            output.writeByte(1)
            output.writeByte(RfbProtocol.SECURITY_TYPE_NONE)
            output.flush()
            assertEquals(RfbProtocol.SECURITY_TYPE_NONE, input.readUnsignedByte())
            output.writeInt(0)
            output.flush()

            assertEquals("client must request a shared session", 1, input.readUnsignedByte())
            sendServerInit(output)
            output.flush()
            readClientSetup(input)
            return RfbStreams(input, output)
        }

        private fun readClientSetup(input: DataInputStream) {
            var framebufferRequested = false
            var alphaCursorRequested = false
            while (!framebufferRequested) {
                when (val messageType = input.readUnsignedByte()) {
                    RfbProtocol.CLIENT_SET_PIXEL_FORMAT -> discardFully(input, 19)
                    RfbProtocol.CLIENT_SET_ENCODINGS -> {
                        input.readUnsignedByte() // padding
                        val encodingCount = input.readUnsignedShort()
                        repeat(encodingCount) {
                            if (input.readInt() == RfbProtocol.ENCODING_ALPHA_CURSOR) {
                                alphaCursorRequested = true
                            }
                        }
                    }
                    RfbProtocol.CLIENT_FRAMEBUFFER_UPDATE_REQUEST -> {
                        discardFully(input, 9)
                        framebufferRequested = true
                    }
                    else -> throw AssertionError("unexpected setup message $messageType")
                }
            }
            assertTrue("client must request QEMU's safe alpha cursor", alphaCursorRequested)
        }

        private fun sendServerInit(output: DataOutputStream) {
            output.writeShort(FRAME_WIDTH)
            output.writeShort(FRAME_HEIGHT)
            output.write(
                byteArrayOf(
                    32, 24, 0, 1,
                    0, -1, 0, -1, 0, -1,
                    16, 8, 0, 0, 0, 0,
                ),
            )
            val name = "Linux on DeX test".toByteArray()
            output.writeInt(name.size)
            output.write(name)
        }

        private fun sendRawFrame(output: DataOutputStream) {
            output.writeByte(RfbProtocol.SERVER_FRAMEBUFFER_UPDATE)
            output.writeByte(0)
            output.writeShort(1)
            output.writeShort(0)
            output.writeShort(0)
            output.writeShort(FRAME_WIDTH)
            output.writeShort(FRAME_HEIGHT)
            output.writeInt(RfbProtocol.ENCODING_RAW)
            repeat(FRAME_WIDTH * FRAME_HEIGHT) {
                output.writeByte(0x10)
                output.writeByte(0x84)
            }
        }

        private fun sendAlphaCursor(output: DataOutputStream) {
            output.writeByte(RfbProtocol.SERVER_FRAMEBUFFER_UPDATE)
            output.writeByte(0)
            output.writeShort(1)
            output.writeShort(1) // hotspot x
            output.writeShort(2) // hotspot y
            output.writeShort(CURSOR_WIDTH)
            output.writeShort(CURSOR_HEIGHT)
            output.writeInt(RfbProtocol.ENCODING_ALPHA_CURSOR)
            output.writeInt(RfbProtocol.ENCODING_RAW)
            repeat(CURSOR_WIDTH * CURSOR_HEIGHT) {
                // Begin each pixel with the byte from the real failure. If the
                // client under-reads this payload, it will reproduce message 16.
                output.writeInt(0x10FFFFFF)
            }
        }

        override fun close() {
            runCatching { serverSocket.close() }
            worker.join(WORKER_JOIN_MILLIS)
        }

        private data class RfbStreams(
            val input: DataInputStream,
            val output: DataOutputStream,
        )
    }

    companion object {
        private const val FRAME_WIDTH = 64
        private const val FRAME_HEIGHT = 48
        private const val EXPECTED_FRAME_COUNT = 3
        private const val EXPECTED_POINTER_EVENT_COUNT = 2
        private const val CURSOR_WIDTH = 16
        private const val CURSOR_HEIGHT = 16
        private const val TEST_TIMEOUT_SECONDS = 10L
        private const val SOCKET_TIMEOUT_MILLIS = 10_000
        private const val WORKER_JOIN_MILLIS = 2_000L
        private val EXTENDED_CLIPBOARD_PAYLOAD = byteArrayOf(101, 120, 116, 101, 110, 100, 101, 100)

        private fun readFully(input: DataInputStream, byteCount: Int): ByteArray =
            ByteArray(byteCount).also(input::readFully)

        private fun discardFully(input: DataInputStream, byteCount: Int) {
            val bytes = ByteArray(byteCount)
            input.readFully(bytes)
        }
    }
}
