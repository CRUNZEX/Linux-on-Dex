package com.crunzex.linuxondex.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.crunzex.linuxondex.capability.DexEnvironment

/**
 * Whether this window is currently part of a Samsung DeX desktop session.
 *
 * Reads [LocalConfiguration], which Compose refreshes on every configuration
 * change — including the `uiMode` change DeX docking sends to an activity
 * that handles its own config changes. That makes the value live: dock the
 * phone and it flips without an activity restart.
 */
@Composable
fun rememberDexModeActive(): Boolean {
    val configuration = LocalConfiguration.current
    return remember(configuration) { DexEnvironment.isDesktopMode(configuration) }
}
