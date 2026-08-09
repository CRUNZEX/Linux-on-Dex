package com.crunzex.linuxondex.engine.proot

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * First-run settings for the embedded Termux:X11 viewer.
 *
 * The viewer reads its configuration from this app's default
 * SharedPreferences. Its shipped defaults are tuned for Termux power users —
 * trackpad-style touch, where a tap clicks at the *pointer* rather than at
 * the finger — so a first-time user taps the desktop, nothing visible
 * happens, and the display reads as broken.
 *
 * These defaults are written once, before the viewer first starts. Keys the
 * user has since changed through the viewer's own settings screen are never
 * overwritten.
 */
object EmbeddedX11ViewerDefaults {

    /** Taps click where the finger is; a two-finger tap is a right click. */
    private const val TOUCH_MODE_SIMULATED_TOUCH = "2"

    private val firstRunDefaults = mapOf(
        "touchMode" to TOUCH_MODE_SIMULATED_TOUCH,
    )

    fun ensureApplied(context: Context) {
        ensureApplied(PreferenceManager.getDefaultSharedPreferences(context))
    }

    /** Separated from [Context] so the merge rule stays unit-testable. */
    fun ensureApplied(preferences: SharedPreferences) {
        val missingDefaults = firstRunDefaults.filterKeys { key -> !preferences.contains(key) }
        if (missingDefaults.isEmpty()) return
        val editor = preferences.edit()
        missingDefaults.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }
}
