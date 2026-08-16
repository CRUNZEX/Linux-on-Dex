package com.crunzex.linuxondex.display.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * One place for every display-session setting, shared by both display
 * transports. The embedded X11 viewer reads most of these keys directly (its
 * `Prefs` names are kept verbatim and it live-reloads on change), and the
 * app's own VNC view reads the same keys so one screen configures both.
 */
object DisplayPreferenceKeys {
    // Keys owned by the embedded Termux:X11 viewer — names must not change.
    const val RESOLUTION_MODE = "displayResolutionMode"
    const val SCALE_PERCENT = "displayScale"
    const val RESOLUTION_EXACT = "displayResolutionExact"
    const val FILTERING_MODE = "displayFilteringMode"
    const val STRETCH_TO_FILL = "displayStretch"
    const val TOUCH_MODE = "touchMode"
    const val DEX_META_KEY_CAPTURE = "dexMetaKeyCapture"
    const val SHOW_EXTRA_KEYS_BAR = "showAdditionalKbd"
    const val SHOW_IME_WITH_HARDWARE_KEYBOARD = "showIMEWhileExternalConnected"
    const val SCREEN_IDLE_TIMEOUT = "screenIdleTimeout"

    // Keys owned by this app.
    const val SUSTAINED_PERFORMANCE = "displaySustainedPerformance"
    const val PREVENT_TEARING = "displayPreventTearing"
    const val GPU_ACCELERATED_DESKTOP = "displayGpuAcceleratedDesktop"

    const val FILTERING_BILINEAR = "bilinear"
    const val FILTERING_NEAREST = "nearest"
    const val IDLE_TIMEOUT_NEVER = "never"
    const val IDLE_TIMEOUT_SYSTEM = "system"
}

/**
 * The render-quality presets offered in Display settings.
 *
 * Rendering fewer pixels is the one honest lever this pipeline has for
 * animation smoothness: the guest desktop blits complete frames into a
 * buffer the renderer samples, and the shorter that blit, the smaller the
 * window in which a half-drawn frame can be shown (the visible "tearing")
 * — and the higher the frame rate on a software-rendered desktop.
 *
 * [viewerScalePercent] uses the embedded viewer's semantics, which are a
 * zoom factor: the X screen is `surface × 100 / scale`, so *larger* values
 * mean *fewer* rendered pixels (150 → ⅔ per axis, 200 → ½ per axis).
 */
enum class RenderQuality(
    val label: String,
    val viewerScalePercent: Int,
) {
    SHARPEST("Sharpest", 100),
    BALANCED("Balanced", 150),
    SMOOTHEST("Smoothest", 200);

    companion object {
        fun fromViewerScalePercent(scalePercent: Int): RenderQuality? =
            entries.firstOrNull { it.viewerScalePercent == scalePercent }
    }
}

/** What the resolution-related keys currently describe, as one value. */
sealed interface RenderResolutionChoice {
    data class Preset(val quality: RenderQuality) : RenderResolutionChoice
    data class ExactResolution(val geometryText: String) : RenderResolutionChoice
}

/**
 * Pure mapping between the viewer's three resolution keys and the single
 * choice the settings screen presents. Kept free of Android types so the
 * rules are unit-testable.
 */
object RenderResolutionMapping {

    const val MODE_NATIVE = "native"
    const val MODE_SCALED = "scaled"
    const val MODE_EXACT = "exact"

    /**
     * Exact modes offered by the embedded viewer that make sense here, up
     * to 2K-class panels. Values must stay within the viewer's own
     * displayResolution array.
     */
    val EXACT_RESOLUTIONS = listOf(
        "800x600", "1024x768", "1280x720", "1280x1024",
        "1366x768", "1600x1200", "1920x1080", "1920x1200",
        "2048x1536", "2560x1440", "2560x1600",
    )

    fun choiceFrom(mode: String?, scalePercent: Int, exact: String?): RenderResolutionChoice =
        when (mode) {
            MODE_SCALED ->
                RenderResolutionChoice.Preset(
                    RenderQuality.fromViewerScalePercent(scalePercent) ?: RenderQuality.BALANCED
                )
            MODE_EXACT ->
                RenderResolutionChoice.ExactResolution(exact ?: EXACT_RESOLUTIONS.first())
            else -> RenderResolutionChoice.Preset(RenderQuality.SHARPEST)
        }

    /** The key writes that make the viewer render the given choice. */
    fun writesFor(choice: RenderResolutionChoice): Map<String, Any> = when (choice) {
        is RenderResolutionChoice.Preset -> when (choice.quality) {
            RenderQuality.SHARPEST -> mapOf(
                DisplayPreferenceKeys.RESOLUTION_MODE to MODE_NATIVE,
                DisplayPreferenceKeys.SCALE_PERCENT to RenderQuality.SHARPEST.viewerScalePercent,
            )
            else -> mapOf(
                DisplayPreferenceKeys.RESOLUTION_MODE to MODE_SCALED,
                DisplayPreferenceKeys.SCALE_PERCENT to choice.quality.viewerScalePercent,
            )
        }
        is RenderResolutionChoice.ExactResolution -> mapOf(
            DisplayPreferenceKeys.RESOLUTION_MODE to MODE_EXACT,
            DisplayPreferenceKeys.RESOLUTION_EXACT to choice.geometryText,
        )
    }

    fun describe(choice: RenderResolutionChoice): String = when (choice) {
        is RenderResolutionChoice.Preset -> when (choice.quality) {
            RenderQuality.SHARPEST -> "Sharpest (native)"
            RenderQuality.BALANCED -> "Balanced (⅔ resolution)"
            RenderQuality.SMOOTHEST -> "Smoothest (½ resolution)"
        }
        is RenderResolutionChoice.ExactResolution -> choice.geometryText
    }
}

/**
 * Thin, typed access to the shared preference file. All writes go through
 * here so the two display transports can never disagree about a key name.
 */
class DisplaySessionPreferences(private val preferences: SharedPreferences) {

    constructor(context: Context) : this(
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    )

    fun resolutionChoice(): RenderResolutionChoice = RenderResolutionMapping.choiceFrom(
        mode = preferences.getString(DisplayPreferenceKeys.RESOLUTION_MODE, null),
        scalePercent = preferences.getInt(DisplayPreferenceKeys.SCALE_PERCENT, 100),
        exact = preferences.getString(DisplayPreferenceKeys.RESOLUTION_EXACT, null),
    )

    fun setResolutionChoice(choice: RenderResolutionChoice) {
        val editor = preferences.edit()
        RenderResolutionMapping.writesFor(choice).forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                else -> error("unsupported preference type for $key")
            }
        }
        editor.apply()
    }

    var smoothScaling: Boolean
        get() = preferences.getString(
            DisplayPreferenceKeys.FILTERING_MODE,
            DisplayPreferenceKeys.FILTERING_NEAREST,
        ) == DisplayPreferenceKeys.FILTERING_BILINEAR
        set(enabled) {
            preferences.edit().putString(
                DisplayPreferenceKeys.FILTERING_MODE,
                if (enabled) {
                    DisplayPreferenceKeys.FILTERING_BILINEAR
                } else {
                    DisplayPreferenceKeys.FILTERING_NEAREST
                },
            ).apply()
        }

    var stretchToFill: Boolean
        get() = preferences.getBoolean(DisplayPreferenceKeys.STRETCH_TO_FILL, false)
        set(enabled) {
            preferences.edit().putBoolean(DisplayPreferenceKeys.STRETCH_TO_FILL, enabled).apply()
        }

    var touchMode: String
        get() = preferences.getString(DisplayPreferenceKeys.TOUCH_MODE, "2") ?: "2"
        set(mode) {
            preferences.edit().putString(DisplayPreferenceKeys.TOUCH_MODE, mode).apply()
        }

    var captureDexMetaKey: Boolean
        get() = preferences.getBoolean(DisplayPreferenceKeys.DEX_META_KEY_CAPTURE, false)
        set(enabled) {
            preferences.edit()
                .putBoolean(DisplayPreferenceKeys.DEX_META_KEY_CAPTURE, enabled).apply()
        }

    var showExtraKeysBar: Boolean
        get() = preferences.getBoolean(DisplayPreferenceKeys.SHOW_EXTRA_KEYS_BAR, true)
        set(enabled) {
            preferences.edit()
                .putBoolean(DisplayPreferenceKeys.SHOW_EXTRA_KEYS_BAR, enabled).apply()
        }

    var showImeWithHardwareKeyboard: Boolean
        get() = preferences.getBoolean(
            DisplayPreferenceKeys.SHOW_IME_WITH_HARDWARE_KEYBOARD, true
        )
        set(enabled) {
            preferences.edit()
                .putBoolean(DisplayPreferenceKeys.SHOW_IME_WITH_HARDWARE_KEYBOARD, enabled)
                .apply()
        }

    var keepScreenAwake: Boolean
        get() = preferences.getString(
            DisplayPreferenceKeys.SCREEN_IDLE_TIMEOUT,
            DisplayPreferenceKeys.IDLE_TIMEOUT_SYSTEM,
        ) == DisplayPreferenceKeys.IDLE_TIMEOUT_NEVER
        set(enabled) {
            preferences.edit().putString(
                DisplayPreferenceKeys.SCREEN_IDLE_TIMEOUT,
                if (enabled) {
                    DisplayPreferenceKeys.IDLE_TIMEOUT_NEVER
                } else {
                    DisplayPreferenceKeys.IDLE_TIMEOUT_SYSTEM
                },
            ).apply()
        }

    var sustainedPerformance: Boolean
        get() = preferences.getBoolean(DisplayPreferenceKeys.SUSTAINED_PERFORMANCE, false)
        set(enabled) {
            preferences.edit()
                .putBoolean(DisplayPreferenceKeys.SUSTAINED_PERFORMANCE, enabled).apply()
        }

    /**
     * Lets the X server flip whole presented frames instead of copying out
     * of a buffer the guest is still drawing into — the mechanism behind
     * animation tearing. Read at X-server start, so it applies to the next
     * session.
     */
    var preventTearing: Boolean
        get() = preferences.getBoolean(DisplayPreferenceKeys.PREVENT_TEARING, true)
        set(enabled) {
            preferences.edit()
                .putBoolean(DisplayPreferenceKeys.PREVENT_TEARING, enabled).apply()
        }

    /**
     * Whether the next PRoot desktop session may render on the device GPU
     * through the app's graphics bridge. The engine still verifies the
     * bridge end-to-end each boot and falls back to the CPU renderer by
     * itself, so this switch only expresses intent.
     *
     * Off by default: on the real devices tested, whole-desktop rendering
     * through the bridge is bounded by per-frame transfers (a Note 10 Lite
     * never finished painting its first frame; a Tab S9 lost GNOME to a
     * driver SIGSEGV), while the CPU renderer holds the desktop up
     * reliably. Per-application acceleration via `dex-gpu` is separate and
     * unaffected.
     */
    var gpuAcceleratedDesktop: Boolean
        get() = preferences.getBoolean(DisplayPreferenceKeys.GPU_ACCELERATED_DESKTOP, false)
        set(enabled) {
            preferences.edit()
                .putBoolean(DisplayPreferenceKeys.GPU_ACCELERATED_DESKTOP, enabled).apply()
        }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
