package com.crunzex.linuxondex.ui.display

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.engine.proot.EmbeddedX11ViewerDefaults
import com.crunzex.linuxondex.display.vnc.ActiveConnectionOwner
import com.crunzex.linuxondex.display.vnc.RfbClient
import com.crunzex.linuxondex.display.vnc.VncView
import com.crunzex.linuxondex.vm.DisplayEndpoint

/**
 * Routes QEMU/legacy RFB sessions to [VncView] and native PRoot X11 sessions
 * to the embedded Lorie SurfaceView activity.
 */
@Composable
fun DisplayScreen(
    displayEndpoint: DisplayEndpoint?,
    /** Null in the two-pane layout, where the menu stays visible beside us. */
    onBack: (() -> Unit)?,
    onOpenInNewWindow: (() -> Unit)? = null,
) {
    when (displayEndpoint) {
        is DisplayEndpoint.Rfb -> RfbDisplayScreen(
            vncPort = displayEndpoint.port,
            onBack = onBack,
            onOpenInNewWindow = onOpenInNewWindow,
        )
        is DisplayEndpoint.NativeX11 -> NativeX11DisplayScreen(onBack)
        null -> NoGraphicalDisplayScreen(onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RfbDisplayScreen(
    vncPort: Int,
    onBack: (() -> Unit)?,
    onOpenInNewWindow: (() -> Unit)?,
) {
    var statusText by remember { mutableStateOf("Connecting…") }
    var vncView by remember { mutableStateOf<VncView?>(null) }
    // A desktop guest paints nothing for a while after the display attaches.
    // Without a hint that looks like the app has hung.
    var hasPaintedFrame by remember { mutableStateOf(false) }
    val connectionScope = remember(vncPort) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    val connectionOwner = remember(vncPort) { ActiveConnectionOwner<RfbClient>() }

    DisposableEffect(connectionScope, connectionOwner) {
        onDispose {
            // A blocking socket read ignores coroutine cancellation. Close the
            // connection first so the worker can actually leave its read loop.
            connectionOwner.close()
            connectionScope.cancel()
        }
    }

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
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open in new window",
                            )
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
                            connectionOwner = connectionOwner,
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeX11DisplayScreen(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    var launchError by remember { mutableStateOf<String?>(null) }
    val openDisplay = {
        launchError = runCatching {
            EmbeddedX11ViewerDefaults.ensureApplied(context)
            context.startActivity(
                Intent(context, com.termux.x11.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.exceptionOrNull()?.message
    }
    LaunchedEffect(Unit) { openDisplay() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Native X11 display") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                launchError ?: "The PRoot desktop is open in its native X11 window.",
                color = Color.White,
            )
            Button(onClick = openDisplay, modifier = Modifier.padding(top = 16.dp)) {
                Text("Open X11 display")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoGraphicalDisplayScreen(onBack: (() -> Unit)?) {
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Display") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Text(
            "This session has no graphical display.",
            color = Color.White,
            modifier = Modifier.padding(padding).padding(24.dp),
        )
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

@VisibleForTesting
fun connectVnc(
    view: VncView,
    port: Int,
    scope: CoroutineScope,
    connectionOwner: ActiveConnectionOwner<RfbClient>,
    onStatus: (String) -> Unit,
    onFirstFrame: () -> Unit,
) {
    scope.launch {
        while (isActive) {
            var client: RfbClient? = null
            var statisticsJob: Job? = null
            try {
                val newClient = RfbClient(
                host = "127.0.0.1",
                port = port,
                listener = object : RfbClient.Listener {
                    private var resolution = ""

                    override fun onFramebufferReady(bitmap: Bitmap) {
                        view.onFramebufferReady(bitmap)
                        resolution = "${bitmap.width}×${bitmap.height}"
                        dispatchToDisplayThread { onStatus(resolution) }
                    }

                    override fun onFrameUpdated() {
                        dispatchToDisplayThread(onFirstFrame)
                        view.onFrameUpdated()
                    }

                    override fun onDisconnected(reason: String) {
                        dispatchToDisplayThread { onStatus("Disconnected: $reason") }
                    }
                },
            )
                client = newClient
                if (!connectionOwner.attach(newClient)) return@launch

                newClient.connect()
                dispatchToDisplayThread { view.bindInputTarget(newClient) }
                statisticsJob = reportStatsWhileConnected(newClient, scope, view, onStatus)
                newClient.runReadLoop() // blocks this coroutine until closed
            } catch (error: Exception) {
                AppLog.warn("DisplayScreen", "VNC connect failed", error)
                dispatchToDisplayThread { onStatus("Connection interrupted · reconnecting…") }
            } finally {
                statisticsJob?.cancel()
                client?.let { completedClient ->
                    connectionOwner.detach(completedClient)
                    completedClient.close()
                    dispatchToDisplayThread { view.unbindInputTarget(completedClient) }
                }
            }
            if (!isActive) break
            dispatchToDisplayThread { onStatus("Reconnecting display…") }
            delay(RECONNECT_DELAY_MILLIS)
        }
    }
}

/**
 * Publishes a live "1280×800 · 30 fps" line once per second while connected,
 * so display performance is visible rather than a matter of opinion.
 *
 * A quiet desktop produces no frames at all (animations and cursor blink
 * are off by design), so zero is reported as "ready" — "0 fps" reads as
 * broken when it actually means "nothing needed redrawing".
 */
private fun reportStatsWhileConnected(
    client: RfbClient,
    scope: CoroutineScope,
    view: VncView,
    onStatus: (String) -> Unit,
) = scope.launch {
        val resolution = "${client.framebufferWidth}×${client.framebufferHeight}"
        while (isActive) {
            delay(STATS_REFRESH_MILLIS)
            val framesPerSecond = client.currentStats().framesPerSecond
            val activity = if (framesPerSecond > 0) "$framesPerSecond fps" else "ready"
            dispatchToDisplayThread { onStatus("$resolution · $activity") }
        }
}

/** UI callbacks must not depend on the VNC view already being attached. */
private fun dispatchToDisplayThread(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        action()
    } else {
        DISPLAY_HANDLER.post(action)
    }
}

private fun toggleSoftKeyboard(view: VncView) {
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    view.requestFocus()
    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

private const val STATS_REFRESH_MILLIS = 1_000L
private const val RECONNECT_DELAY_MILLIS = 500L
private val DISPLAY_HANDLER = Handler(Looper.getMainLooper())
