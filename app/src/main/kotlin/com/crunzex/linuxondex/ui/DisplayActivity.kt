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
 * QEMU opens its own RFB connection. PRoot hands off to the embedded native
 * X11 activity, whose content is a SurfaceView.
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
                    displayEndpoint = (vmState as? VmState.Running)?.displayEndpoint,
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
