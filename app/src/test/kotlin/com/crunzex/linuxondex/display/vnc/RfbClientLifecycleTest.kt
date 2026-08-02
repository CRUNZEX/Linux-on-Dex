package com.crunzex.linuxondex.display.vnc

import android.graphics.Bitmap
import com.crunzex.linuxondex.core.LxdError
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RfbClientLifecycleTest {

    @Test
    fun `client closed before connect cannot open a late socket`() {
        val client = RfbClient(
            host = "127.0.0.1",
            port = 1,
            listener = NoOpListener,
        )

        client.close()

        val error = assertThrows(LxdError.DisplayConnectFailed::class.java) {
            client.connect()
        }
        assertTrue(error.message.orEmpty().contains("closed before connect"))
    }

    private object NoOpListener : RfbClient.Listener {
        override fun onFramebufferReady(bitmap: Bitmap) = Unit
        override fun onFrameUpdated() = Unit
        override fun onDisconnected(reason: String) = Unit
    }
}
