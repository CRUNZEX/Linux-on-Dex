package com.crunzex.linuxondex.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crunzex.linuxondex.LinuxOnDexApp
import com.crunzex.linuxondex.ui.display.DisplayScreen
import com.crunzex.linuxondex.ui.theme.LinuxOnDexTheme
import com.crunzex.linuxondex.vm.VmState

/**
 * The graphical display as its own task: on DeX this is the "monitor"
 * window, draggable and resizable independently of the terminal.
 *
 * Opens its own VNC connection — QEMU's server accepts several clients, so
 * this and the in-app display can even run side by side.
 */
class DisplayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LinuxOnDexApp).container
        setContent {
            LinuxOnDexTheme {
                val vmState by container.vmController.vmState.collectAsStateWithLifecycle()
                DisplayScreen(
                    vncPort = (vmState as? VmState.Running)?.vncPort,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        fun launchIntent(context: Context): Intent =
            Intent(context, DisplayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
