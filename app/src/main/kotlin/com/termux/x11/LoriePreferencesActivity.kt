package com.termux.x11

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.crunzex.linuxondex.ui.display.DisplaySettingsScreen
import com.crunzex.linuxondex.ui.theme.LinuxOnDexTheme

/**
 * The app's One UI replacement for Termux:X11's stock preferences screen.
 *
 * Every code path in the embedded viewer opens settings with an explicit
 * `Intent(context, LoriePreferences.class)` — the extra-keys gear, the
 * "open preferences" user actions and the notification button — so the
 * replacement must own exactly this class name. The stock implementation is
 * removed from the AAR by `tools/strip_embedded_x11_preferences.py`; its
 * `PrefsProto` nested classes remain because the viewer's `Prefs` reads
 * every setting through them.
 *
 * Settings written here apply live: the viewer registers a listener on the
 * same default SharedPreferences file.
 */
class LoriePreferences : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appVersionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        setContent {
            LinuxOnDexTheme {
                DisplaySettingsScreen(
                    appVersionName = appVersionName,
                    displayEngineVersion = BuildConfig.VERSION_NAME,
                    onBack = { finish() },
                )
            }
        }
    }
}
