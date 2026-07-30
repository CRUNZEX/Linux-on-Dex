package com.crunzex.linuxondex.capability

import android.content.res.Configuration

/**
 * Live Samsung DeX detection.
 *
 * DeX enter/exit reaches the app as a `uiMode` configuration change, and the
 * activity handles that change itself (no recreate) — so DeX state must be
 * *observed per configuration*, never probed once at startup. The one-shot
 * probe reads the application context, whose configuration does not track
 * the DeX display, which is exactly why "Not active" used to stick.
 */
object DexEnvironment {

    /** Pure uiMode check — the documented signal on Android 10+ DeX. */
    fun isDesktopUiMode(uiMode: Int): Boolean =
        uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_DESK

    /**
     * Full check for one window's [configuration]: the standard DESK ui-mode
     * plus Samsung's own configuration field, which also reports DeX running
     * on the *other* display in dual mode.
     */
    fun isDesktopMode(configuration: Configuration): Boolean =
        isDesktopUiMode(configuration.uiMode) || hasSamsungDesktopFlag(configuration)

    /**
     * Samsung's documented detection: `Configuration.semDesktopModeEnabled ==
     * SEM_DESKTOP_MODE_ENABLED`. Reflection because these fields only exist
     * in Samsung's framework; anywhere else this quietly reports false.
     */
    private fun hasSamsungDesktopFlag(configuration: Configuration): Boolean = try {
        val configurationClass = configuration.javaClass
        val enabledValue = configurationClass
            .getField("SEM_DESKTOP_MODE_ENABLED")
            .getInt(configurationClass)
        val currentValue = configurationClass
            .getField("semDesktopModeEnabled")
            .getInt(configuration)
        currentValue == enabledValue
    } catch (fieldMissing: NoSuchFieldException) {
        false
    } catch (notReadable: Exception) {
        false
    }
}
