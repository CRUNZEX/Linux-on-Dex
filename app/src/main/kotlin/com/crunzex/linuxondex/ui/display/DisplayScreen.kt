package com.crunzex.linuxondex.ui.display

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.display.vnc.RfbClient
import com.crunzex.linuxondex.display.vnc.VncView

/**
 * Graphical display: full-bleed VNC surface over the local QEMU server.
 * On DeX this is effectively a desktop monitor; on the phone, tap/drag/long
 * press emulate the mouse and the soft keyboard types into the guest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayScreen(
    vncPort: Int?,
    /** Null in the two-pane layout, where the menu stays visible beside us. */
    onBack: (() -> Unit)?,
    onOpenInNewWindow: (() -> Unit)? = null,
) {
    var statusText by remember { mutableStateOf("Connecting…") }
    var vncView by remember { mutableStateOf<VncView?>(null) }
    // A desktop guest paints nothing for a while after the display attaches.
    // Without a hint that looks like the app has hung.
    var hasPaintedFrame by remember { mutableStateOf(false) }
    val connectionScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(statusText) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vncView?.let(::toggleSoftKeyboard) }) {
                        Icon(Icons.Filled.Keyboard, contentDescription = "Toggle keyboard")
                    }
                    if (onOpenInNewWindow != null) {
                        IconButton(onClick = onOpenInNewWindow) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = "Open in new window")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        if (vncPort == null) {
            Text(
                "This session has no graphical display.",
                color = Color.White,
                modifier = Modifier.padding(24.dp),
            )
            return@Scaffold
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VncView(context).also { view ->
                        vncView = view
                        connectVnc(
                            view = view,
                            port = vncPort,
                            scope = connectionScope,
                            onStatus = { message -> statusText = message },
                            onFirstFrame = { hasPaintedFrame = true },
                        )
                    }
                },
            )
            if (!hasPaintedFrame) {
                WaitingForGuestDisplay()
            }
        }
        DisposableEffect(Unit) {
            onDispose { connectionScope.cancel() }
        }
    }
}

/**
 * Shown until the guest paints its first frame. A desktop session can take
 * minutes to start under software emulation, and an unexplained black
 * screen is indistinguishable from a crash.
 */
@Composable
private fun WaitingForGuestDisplay() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        Text(
            text = "Waiting for the guest to draw its screen…",
            color = Color.White,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "A desktop can take a few minutes on the software VM",
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun connectVnc(
    view: VncView,
    port: Int,
    scope: CoroutineScope,
    onStatus: (String) -> Unit,
    onFirstFrame: () -> Unit,
) {
    scope.launch {
        try {
            val client = RfbClient(
                host = "127.0.0.1",
                port = port,
                listener = object : RfbClient.Listener {
                    private var resolution = ""

                    override fun onFramebufferReady(bitmap: Bitmap) {
                        view.onFramebufferReady(bitmap)
                        resolution = "${bitmap.width}×${bitmap.height}"
                        view.post { onStatus(resolution) }
                    }

                    override fun onFrameUpdated() {
                        view.post(onFirstFrame)
                        view.onFrameUpdated()
                    }

                    override fun onDisconnected(reason: String) {
                        view.post { onStatus("Disconnected: $reason") }
                    }
                },
            )
            client.connect()
            view.inputTarget = client
            reportStatsWhileConnected(client, scope, onStatus)
            client.runReadLoop() // blocks this coroutine until closed
        } catch (error: Exception) {
            AppLog.warn("DisplayScreen", "VNC connect failed", error)
            view.post { onStatus("Connection failed: ${error.message}") }
        }
    }
}

/**
 * Publishes a live "1280×800 · 30 fps" line once per second while connected,
 * so display performance is visible rather than a matter of opinion.
 *
 * A quiet desktop produces no frames at all (animations and cursor blink
 * are off by design), so zero is reported as "idle" — "0 fps" reads as
 * broken when it actually means "nothing needed redrawing".
 */
private fun reportStatsWhileConnected(
    client: RfbClient,
    scope: CoroutineScope,
    onStatus: (String) -> Unit,
) {
    scope.launch {
        val resolution = "${client.framebufferWidth}×${client.framebufferHeight}"
        while (isActive) {
            delay(STATS_REFRESH_MILLIS)
            val framesPerSecond = client.currentStats().framesPerSecond
            val activity = if (framesPerSecond > 0) "$framesPerSecond fps" else "idle"
            onStatus("$resolution · $activity")
        }
    }
}

private fun toggleSoftKeyboard(view: VncView) {
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    view.requestFocus()
    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

private const val STATS_REFRESH_MILLIS = 1_000L
