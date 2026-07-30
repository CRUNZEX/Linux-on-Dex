package com.crunzex.linuxondex.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.crunzex.linuxondex.AppContainer
import com.crunzex.linuxondex.LinuxOnDexApp
import com.crunzex.linuxondex.ui.terminal.TerminalScreen
import com.crunzex.linuxondex.ui.theme.LinuxOnDexTheme

/**
 * A terminal in its own task, so DeX (and freeform windowing) shows it as a
 * separate resizable window next to the main app and the display.
 *
 * Several can be open at once: each window carries its own console index and
 * therefore its own independent shell in the guest — window 1 uses the
 * primary serial console, later windows use the extra virtio consoles
 * (/dev/hvc0, /dev/hvc1).
 */
class TerminalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LinuxOnDexApp).container
        val windowIndex = intent.getIntExtra(EXTRA_WINDOW_INDEX, 0)

        setContent {
            LinuxOnDexTheme {
                TerminalScreen(
                    session = container.terminalSessionAt(windowIndex),
                    windowLabel = windowLabelFor(windowIndex),
                    onBack = { finish() },
                    onOpenInNewWindow = { openNextWindow(container, windowIndex) },
                )
            }
        }
    }

    /** Opens the next console's window, wrapping back to the first. */
    private fun openNextWindow(container: AppContainer, currentIndex: Int) {
        val nextIndex = (currentIndex + 1) % container.terminalSessions.size
        startActivity(launchIntent(this, nextIndex))
    }

    companion object {
        private const val EXTRA_WINDOW_INDEX = "window_index"

        /** Terminal 1 is the primary console; later ones name their device. */
        fun windowLabelFor(windowIndex: Int): String =
            if (windowIndex == 0) {
                "Terminal 1"
            } else {
                "Terminal ${windowIndex + 1} · hvc${windowIndex - 1}"
            }

        /**
         * Intent for the terminal window showing console [windowIndex].
         *
         * NEW_DOCUMENT with a per-index data URI makes Android treat each
         * index as its own document — hence its own DeX window — while
         * relaunching the same index reuses that window instead of stacking
         * duplicates.
         */
        fun launchIntent(context: Context, windowIndex: Int = 0): Intent =
            Intent(context, TerminalActivity::class.java)
                .putExtra(EXTRA_WINDOW_INDEX, windowIndex)
                .setData(Uri.parse("linuxondex://terminal/$windowIndex"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}
