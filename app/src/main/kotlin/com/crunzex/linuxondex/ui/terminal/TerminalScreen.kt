package com.crunzex.linuxondex.ui.terminal

import android.content.ClipData
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crunzex.linuxondex.terminal.TerminalSession
import kotlinx.coroutines.launch

/**
 * Full terminal over the guest serial console: a real emulator surface, a
 * shortcut bar for keys soft keyboards lack, pinch/buttons for font size —
 * the iTerm experience, sized for a phone or a DeX window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    session: TerminalSession,
    /** Null in the two-pane layout, where the menu stays visible beside us. */
    onBack: (() -> Unit)?,
    onOpenInNewWindow: (() -> Unit)? = null,
    /** Names this window when several terminals are open, e.g. "Terminal 2". */
    windowLabel: String = "Terminal",
) {
    val status by session.statusLine.collectAsStateWithLifecycle()
    val guestTitle by session.title.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val clipboardScope = rememberCoroutineScope()

    var fontSizeSp by rememberSaveable { mutableFloatStateOf(TerminalCanvasView.DEFAULT_FONT_SIZE_SP) }
    var ctrlLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }
    var terminalView by remember { mutableStateOf<TerminalCanvasView?>(null) }

    LaunchedEffect(Unit) { session.start() }

    // Keep the view in sync with Compose-owned state.
    LaunchedEffect(fontSizeSp) { terminalView?.setFontSize(fontSizeSp) }
    LaunchedEffect(ctrlLatched, altLatched) {
        terminalView?.setModifierLatch(ctrlLatched, altLatched)
    }

    Scaffold(
        containerColor = TerminalChrome,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = guestTitle.ifEmpty { "$windowLabel — $status" },
                        fontSize = 16.sp,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    // Serial consoles never learn about window resizes on
                    // their own; this types `stty rows … cols …` for the
                    // user. Meaningful at an idle shell prompt.
                    IconButton(onClick = { session.syncGuestSizeToScreen() }) {
                        Icon(
                            Icons.Filled.AspectRatio,
                            contentDescription = "Match VM size to this window",
                        )
                    }
                    IconButton(onClick = {
                        clipboardScope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText(
                                        "Terminal screen",
                                        session.screen.screenText(),
                                    )
                                )
                            )
                        }
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy screen text")
                    }
                    IconButton(onClick = {
                        clipboardScope.launch {
                            clipboard.getClipEntry()?.clipData
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                ?.let(session::paste)
                        }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
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
                    containerColor = TerminalChrome,
                    titleContentColor = TerminalChromeText,
                    navigationIconContentColor = TerminalChromeText,
                    actionIconContentColor = TerminalChromeText,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { context ->
                    TerminalCanvasView(context).also { view ->
                        terminalView = view
                        view.setFontSize(fontSizeSp)
                        view.host = object : TerminalCanvasView.Host {
                            override fun onGridSizeChanged(columns: Int, rows: Int) = Unit

                            override fun onFontSizeChanged(sizeSp: Float) {
                                fontSizeSp = sizeSp
                            }

                            override fun onModifierLatchConsumed() {
                                ctrlLatched = false
                                altLatched = false
                            }
                        }
                        view.attach(session)
                    }
                },
            )
            TerminalShortcutBar(
                ctrlLatched = ctrlLatched,
                altLatched = altLatched,
                onToggleCtrl = { ctrlLatched = !ctrlLatched },
                onToggleAlt = { altLatched = !altLatched },
                onSendKeyCode = { keyCode -> terminalView?.sendSpecialKey(keyCode) },
                onSendBytes = session::sendBytes,
                onFontSmaller = { fontSizeSp = (fontSizeSp - FONT_STEP_SP).coerceAtLeast(TerminalCanvasView.MIN_FONT_SIZE_SP) },
                onFontLarger = { fontSizeSp = (fontSizeSp + FONT_STEP_SP).coerceAtMost(TerminalCanvasView.MAX_FONT_SIZE_SP) },
            )
        }
    }
}

/**
 * The keys a soft keyboard does not have, one tap away — Esc/Tab/arrows,
 * latching Ctrl and Alt, the classic control combos, and font size.
 */
@Composable
private fun TerminalShortcutBar(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendKeyCode: (Int) -> Unit,
    onSendBytes: (ByteArray) -> Unit,
    onFontSmaller: () -> Unit,
    onFontLarger: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(TerminalChrome)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        ShortcutChip("Esc") { onSendBytes(byteArrayOf(0x1B)) }
        ShortcutChip("Tab") { onSendBytes(byteArrayOf(0x09)) }
        ShortcutChip("Ctrl", highlighted = ctrlLatched, onClick = onToggleCtrl)
        ShortcutChip("Alt", highlighted = altLatched, onClick = onToggleAlt)
        ShortcutChip("←") { onSendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
        ShortcutChip("↓") { onSendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
        ShortcutChip("↑") { onSendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
        ShortcutChip("→") { onSendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
        ShortcutChip("^C") { onSendBytes(byteArrayOf(0x03)) }
        ShortcutChip("^D") { onSendBytes(byteArrayOf(0x04)) }
        ShortcutChip("^L") { onSendBytes(byteArrayOf(0x0C)) }
        ShortcutChip("^Z") { onSendBytes(byteArrayOf(0x1A)) }
        ShortcutChip("PgUp") { onSendKeyCode(KeyEvent.KEYCODE_PAGE_UP) }
        ShortcutChip("PgDn") { onSendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN) }
        ShortcutChip("A−", onClick = onFontSmaller)
        ShortcutChip("A+", onClick = onFontLarger)
    }
}

@Composable
private fun ShortcutChip(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (highlighted) ChipHighlight else ChipBackground,
        contentColor = if (highlighted) Color.Black else TerminalChromeText,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier.padding(horizontal = 3.dp),
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private const val FONT_STEP_SP = 2f
private val TerminalChrome = Color(0xFF101014)
private val TerminalChromeText = Color(0xFFD6E4D8)
private val ChipBackground = Color(0xFF232329)
private val ChipHighlight = Color(0xFF8AB4F8)
