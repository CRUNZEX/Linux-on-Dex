package com.crunzex.linuxondex.ui.display

import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.crunzex.linuxondex.display.settings.DisplaySessionPreferences
import com.crunzex.linuxondex.display.settings.RenderQuality
import com.crunzex.linuxondex.display.settings.RenderResolutionChoice
import com.crunzex.linuxondex.display.settings.RenderResolutionMapping
import com.crunzex.linuxondex.ui.components.AnimatedExpand
import com.crunzex.linuxondex.ui.components.ChoiceRow
import com.crunzex.linuxondex.ui.components.ExpanderRow
import com.crunzex.linuxondex.ui.components.GroupCard
import com.crunzex.linuxondex.ui.components.ListRow
import com.crunzex.linuxondex.ui.components.OneUiCollapsingScaffold
import com.crunzex.linuxondex.ui.components.RowDivider
import com.crunzex.linuxondex.ui.components.SectionCaption
import com.crunzex.linuxondex.ui.components.SwitchRow
import com.crunzex.linuxondex.ui.components.VerticalSpace

/**
 * One UI settings for the Linux display, shared by both transports: the
 * native X11 window and the VNC view read the same preference keys, so a
 * change here applies to whichever display is on screen — live, no restart.
 *
 * Rows that only affect native X11 sessions say so in their subtitle
 * instead of hiding, so the screen looks identical wherever it is opened.
 */
@Composable
fun DisplaySettingsScreen(
    appVersionName: String,
    displayEngineVersion: String,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val preferences = remember { DisplaySessionPreferences(context) }

    var resolutionChoice by remember { mutableStateOf(preferences.resolutionChoice()) }
    var smoothScaling by remember { mutableStateOf(preferences.smoothScaling) }
    var stretchToFill by remember { mutableStateOf(preferences.stretchToFill) }
    var touchMode by remember { mutableStateOf(preferences.touchMode) }
    var captureDexMeta by remember { mutableStateOf(preferences.captureDexMetaKey) }
    var showExtraKeys by remember { mutableStateOf(preferences.showExtraKeysBar) }
    var showImeWithKeyboard by remember {
        mutableStateOf(preferences.showImeWithHardwareKeyboard)
    }
    var keepScreenAwake by remember { mutableStateOf(preferences.keepScreenAwake) }
    var sustainedPerformance by remember { mutableStateOf(preferences.sustainedPerformance) }
    var preventTearing by remember { mutableStateOf(preferences.preventTearing) }

    var qualityExpanded by remember { mutableStateOf(false) }
    var exactExpanded by remember { mutableStateOf(false) }
    var touchModeExpanded by remember { mutableStateOf(false) }

    val sustainedPerformanceSupported = remember {
        runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isSustainedPerformanceModeSupported == true
        }.getOrDefault(false)
    }

    OneUiCollapsingScaffold(
        title = "Display settings",
        subtitle = "Applies to the Linux screen while it runs",
        onNavigateBack = onBack,
    ) {
        item { SectionCaption("Motion and picture") }
        item {
            GroupCard {
                ExpanderRow(
                    title = "Render quality",
                    value = RenderResolutionMapping.describe(resolutionChoice),
                    subtitle = "Lower quality renders fewer pixels: animations run " +
                        "smoother and tearing artifacts shrink",
                    expanded = qualityExpanded,
                    onToggle = { qualityExpanded = !qualityExpanded },
                )
                AnimatedExpand(qualityExpanded) {
                    RenderQuality.entries.forEach { quality ->
                        ChoiceRow(
                            title = RenderResolutionMapping.describe(
                                RenderResolutionChoice.Preset(quality)
                            ),
                            subtitle = when (quality) {
                                RenderQuality.SHARPEST -> "Full window resolution"
                                RenderQuality.BALANCED -> "Best mix of clarity and speed"
                                RenderQuality.SMOOTHEST -> "Fastest desktop, softest image"
                            },
                            selected = resolutionChoice ==
                                RenderResolutionChoice.Preset(quality),
                            onSelect = {
                                val choice = RenderResolutionChoice.Preset(quality)
                                preferences.setResolutionChoice(choice)
                                resolutionChoice = choice
                            },
                        )
                    }
                }
                RowDivider()
                ExpanderRow(
                    title = "Exact resolution",
                    value = (resolutionChoice as? RenderResolutionChoice.ExactResolution)
                        ?.geometryText ?: "Off",
                    subtitle = "Fix the Linux screen to one size · Native X11 sessions",
                    expanded = exactExpanded,
                    onToggle = { exactExpanded = !exactExpanded },
                )
                AnimatedExpand(exactExpanded) {
                    RenderResolutionMapping.EXACT_RESOLUTIONS.forEach { geometry ->
                        ChoiceRow(
                            title = geometry,
                            selected = resolutionChoice ==
                                RenderResolutionChoice.ExactResolution(geometry),
                            onSelect = {
                                val choice = RenderResolutionChoice.ExactResolution(geometry)
                                preferences.setResolutionChoice(choice)
                                resolutionChoice = choice
                            },
                        )
                    }
                }
                RowDivider()
                SwitchRow(
                    title = "Prevent tearing in animations",
                    subtitle = "Present whole frames (flip) instead of copying a " +
                        "frame that is still being drawn — applies at the next " +
                        "session start · Native X11 sessions",
                    checked = preventTearing,
                    onCheckedChange = {
                        preferences.preventTearing = it
                        preventTearing = it
                    },
                )
                RowDivider()
                SwitchRow(
                    title = "Smooth scaling",
                    subtitle = "Bilinear filtering when the image is not shown 1:1",
                    checked = smoothScaling,
                    onCheckedChange = {
                        preferences.smoothScaling = it
                        smoothScaling = it
                    },
                )
                RowDivider()
                SwitchRow(
                    title = "Stretch to fill window",
                    subtitle = "Ignore aspect ratio instead of letterboxing",
                    checked = stretchToFill,
                    onCheckedChange = {
                        preferences.stretchToFill = it
                        stretchToFill = it
                    },
                )
            }
        }

        item { SectionCaption("Performance") }
        item {
            GroupCard {
                SwitchRow(
                    title = "Keep screen awake",
                    subtitle = "Never dim or lock while the Linux display is open",
                    checked = keepScreenAwake,
                    onCheckedChange = {
                        preferences.keepScreenAwake = it
                        keepScreenAwake = it
                    },
                )
                RowDivider()
                SwitchRow(
                    title = "Consistent performance mode",
                    subtitle = if (sustainedPerformanceSupported) {
                        "Steady sustainable speed instead of short bursts — " +
                            "takes effect when a display window opens"
                    } else {
                        "Not supported on this device"
                    },
                    enabled = sustainedPerformanceSupported,
                    checked = sustainedPerformance && sustainedPerformanceSupported,
                    onCheckedChange = {
                        preferences.sustainedPerformance = it
                        sustainedPerformance = it
                    },
                )
            }
        }

        item { SectionCaption("Input") }
        item {
            GroupCard {
                ExpanderRow(
                    title = "Touch input",
                    value = touchModeLabel(touchMode),
                    subtitle = "Native X11 sessions",
                    expanded = touchModeExpanded,
                    onToggle = { touchModeExpanded = !touchModeExpanded },
                )
                AnimatedExpand(touchModeExpanded) {
                    TOUCH_MODES.forEach { (value, label, description) ->
                        ChoiceRow(
                            title = label,
                            subtitle = description,
                            selected = touchMode == value,
                            onSelect = {
                                preferences.touchMode = value
                                touchMode = value
                            },
                        )
                    }
                }
                RowDivider()
                SwitchRow(
                    title = "Send Meta key to Linux",
                    subtitle = "Capture the Meta/Windows key on DeX keyboards · " +
                        "Native X11 sessions",
                    checked = captureDexMeta,
                    onCheckedChange = {
                        preferences.captureDexMetaKey = it
                        captureDexMeta = it
                    },
                )
            }
        }

        item { SectionCaption("Keyboard") }
        item {
            GroupCard {
                SwitchRow(
                    title = "Extra keys bar",
                    subtitle = "Esc, Ctrl, arrows and friends under the display · " +
                        "Native X11 sessions",
                    checked = showExtraKeys,
                    onCheckedChange = {
                        preferences.showExtraKeysBar = it
                        showExtraKeys = it
                    },
                )
                RowDivider()
                SwitchRow(
                    title = "Soft keyboard with hardware keyboard",
                    subtitle = "Keep the on-screen keyboard available while a " +
                        "physical keyboard is connected · Native X11 sessions",
                    checked = showImeWithKeyboard,
                    onCheckedChange = {
                        preferences.showImeWithHardwareKeyboard = it
                        showImeWithKeyboard = it
                    },
                )
            }
        }

        item { SectionCaption("About") }
        item {
            GroupCard {
                ListRow(title = "App version", value = appVersionName)
                RowDivider()
                ListRow(title = "Display engine", value = displayEngineVersion)
                RowDivider()
                ListRow(title = "Android", value = "API ${Build.VERSION.SDK_INT}")
            }
        }
        item { VerticalSpace(24) }
    }
}

private fun touchModeLabel(mode: String): String =
    TOUCH_MODES.firstOrNull { it.first == mode }?.second ?: "Simulated touchscreen"

/** value → label → what it means; values are the viewer's own. */
private val TOUCH_MODES = listOf(
    Triple("1", "Trackpad", "Drag to move the pointer, tap to click"),
    Triple("2", "Simulated touchscreen", "Taps click where the finger is"),
    Triple("3", "Direct touch", "Raw touch events go straight to Linux"),
)
