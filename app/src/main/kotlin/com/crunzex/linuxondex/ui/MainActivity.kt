package com.crunzex.linuxondex.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.crunzex.linuxondex.LinuxOnDexApp
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.ui.theme.LinuxOnDexTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.info(SCOPE, "notification permission granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermissionOnce()
        val container = (application as LinuxOnDexApp).container
        setContent {
            LinuxOnDexTheme {
                LinuxOnDexNavHost(container)
            }
        }
    }

    /** The VM runs in a foreground service; its notification is the user's
     *  only handle on it once the activity is backgrounded. */
    private fun askForNotificationPermissionOnce() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // DeX enter/exit arrives as a uiMode configuration change; screens
        // refresh their capability snapshot through the view model on resume.
    }

    companion object {
        private const val SCOPE = "MainActivity"
    }
}
