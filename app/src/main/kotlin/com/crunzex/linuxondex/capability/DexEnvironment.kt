package com.crunzex.linuxondex.capability

import android.content.Context
import android.content.res.Configuration
import android.view.Display

/**
 * Live Samsung DeX detection.
 *
 * DeX enter/exit reaches the app as a `uiMode` configuration change, and the
 * activity handles that change itself (no recreate) — so DeX state must be
 * observed *per configuration*, never probed once at startup.
 *
 * No single signal is reliable across One UI versions, so every available
 * source is asked and any positive counts:
 *
 *  1. **`UI_MODE_TYPE_DESK`** — the standard Android signal. Newer One UI
 *     sets it; older releases do not, so it cannot stand alone.
 *  2. **`Configuration.semDesktopModeEnabled`** — Samsung's own field, read
 *     reflectively because it exists only in their framework.
 *  3. **`SemDesktopModeManager`** — Samsung's system service, which knows
 *     DeX is running even when *this* window's configuration does not (dual
 *     mode, where the app stays on the phone while DeX fills the monitor).
 *  4. **A non-default display** — in standalone DeX the window is presented
 *     on the DeX display, so it is simply not on the phone's screen.
 *
 * Every reflective read is guarded: on a non-Samsung device (and on the
 * emulator) the extra sources quietly report false and only the standard
 * ui-mode check applies.
 */
object DexEnvironment {

    /** Pure uiMode check — the documented signal on Android 10+ DeX. */
    fun isDesktopUiMode(uiMode: Int): Boolean =
        uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_DESK

    /**
     * The rule itself, separated from the Android calls so it can be
     * unit-tested: DeX is active when any source says so.
     */
    fun isDesktopActive(
        hasDesktopUiMode: Boolean,
        hasSamsungConfigurationFlag: Boolean,
        hasSamsungServiceFlag: Boolean,
        isOnExternalDisplay: Boolean,
    ): Boolean =
        hasDesktopUiMode ||
            hasSamsungConfigurationFlag ||
            hasSamsungServiceFlag ||
            isOnExternalDisplay

    /**
     * Whether the window described by [configuration] is a DeX session.
     *
     * [context] should be that window's own context (an Activity): the
     * application context carries neither the DeX display nor its
     * configuration, which is exactly why detection used to stick at
     * "Not active".
     */
    fun isDesktopMode(configuration: Configuration, context: Context? = null): Boolean =
        isDesktopActive(
            hasDesktopUiMode = isDesktopUiMode(configuration.uiMode),
            hasSamsungConfigurationFlag = hasSamsungConfigurationFlag(configuration),
            hasSamsungServiceFlag = context?.let(::hasSamsungDesktopService) ?: false,
            isOnExternalDisplay = context?.let(::isPresentedOnExternalDisplay) ?: false,
        )

    /**
     * Samsung's documented field: `Configuration.semDesktopModeEnabled ==
     * Configuration.SEM_DESKTOP_MODE_ENABLED`.
     */
    private fun hasSamsungConfigurationFlag(configuration: Configuration): Boolean = try {
        val configurationClass = configuration.javaClass
        val enabledValue = configurationClass
            .getField("SEM_DESKTOP_MODE_ENABLED")
            .getInt(configurationClass)
        val currentValue = configurationClass
            .getField("semDesktopModeEnabled")
            .getInt(configuration)
        currentValue == enabledValue
    } catch (notSamsung: Exception) {
        false
    }

    /**
     * Samsung's `SemDesktopModeManager`, reached by service name so no
     * Samsung SDK is required.
     */
    private fun hasSamsungDesktopService(context: Context): Boolean = try {
        val manager = context.getSystemService(SAMSUNG_DESKTOP_SERVICE)
        val state = manager?.javaClass?.getMethod("getDesktopModeState")?.invoke(manager)
        state != null && isServiceStateEnabled(state)
    } catch (notSamsung: Exception) {
        false
    }

    /** Compares the reported state against the class's own "enabled" value. */
    private fun isServiceStateEnabled(state: Any): Boolean {
        val stateClass = state.javaClass
        val enabledConstant = runCatching {
            stateClass.getField("ENABLED").getInt(stateClass)
        }.getOrDefault(SAMSUNG_DESKTOP_ENABLED)
        val current = runCatching {
            stateClass.getMethod("getEnabled").invoke(state) as? Int
        }.getOrNull() ?: return false
        return current == enabledConstant
    }

    /**
     * True when this window is presented somewhere other than the phone's
     * own screen, which is what standalone DeX does.
     */
    private fun isPresentedOnExternalDisplay(context: Context): Boolean = try {
        context.display.displayId != Display.DEFAULT_DISPLAY
    } catch (unavailable: Exception) {
        false
    }

    private const val SAMSUNG_DESKTOP_SERVICE = "desktopmode"

    /** Samsung's value for "DeX is on", used if the constant is unreadable. */
    private const val SAMSUNG_DESKTOP_ENABLED = 1
}
