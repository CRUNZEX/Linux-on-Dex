package com.crunzex.linuxondex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crunzex.linuxondex.AppContainer
import com.crunzex.linuxondex.ui.components.OneUiMotion
import com.crunzex.linuxondex.ui.about.AboutScreen
import com.crunzex.linuxondex.ui.diagnostics.DiagnosticsScreen
import com.crunzex.linuxondex.ui.display.DisplayScreen
import com.crunzex.linuxondex.ui.home.HomeScreen
import com.crunzex.linuxondex.ui.main.MainUiState
import com.crunzex.linuxondex.ui.main.MainViewModel
import com.crunzex.linuxondex.ui.monitor.MonitorDetailScreen
import com.crunzex.linuxondex.ui.setup.SetupScreen
import com.crunzex.linuxondex.ui.terminal.TerminalScreen
import com.crunzex.linuxondex.vm.VmState

/** The app's destinations; a plain enum keeps navigation reviewable. */
private enum class Destination(val depth: Int) {
    // depth orders screens so the transition can tell forward from back:
    // Home is the root; every other screen is one level deeper.
    HOME(0),
    SETUP(1),
    CONSOLE(1),
    DISPLAY(1),
    DIAGNOSTICS(1),
    MONITOR(1),
    ABOUT(1),
}

/**
 * Adaptive navigation frame. Phone-width windows navigate screen-by-screen;
 * a tablet, an unfolded foldable or a wide DeX window gets One UI's
 * two-pane Settings layout — the familiar menu on the left, the selected
 * page on the right, defaulting to the virtual machine configuration.
 */
@Composable
fun LinuxOnDexNavHost(container: AppContainer) {
    val mainViewModel: MainViewModel = viewModel(factory = MainViewModel.factory(container))
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }

    // Measure the live window rather than trusting a startup device class:
    // DeX windows and foldables resize while the app is running.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TWO_PANE_MIN_WIDTH_DP.dp) {
            TwoPaneLayout(
                container = container,
                mainViewModel = mainViewModel,
                uiState = uiState,
                destination = destination,
                onNavigate = { destination = it },
            )
        } else {
            SinglePaneLayout(
                container = container,
                mainViewModel = mainViewModel,
                uiState = uiState,
                destination = destination,
                onNavigate = { destination = it },
            )
        }
    }
}

/** Phone layout: one screen at a time with the One UI screen transition. */
@Composable
private fun SinglePaneLayout(
    container: AppContainer,
    mainViewModel: MainViewModel,
    uiState: MainUiState,
    destination: Destination,
    onNavigate: (Destination) -> Unit,
) {
    BackHandler(enabled = destination != Destination.HOME) {
        onNavigate(Destination.HOME)
    }

    // One UI screen transition: the incoming screen slides in from the side
    // and fades, the outgoing one slides the opposite way — direction chosen
    // by relative depth so going back mirrors going forward.
    AnimatedContent(
        targetState = destination,
        label = "screen",
        transitionSpec = {
            val goingForward = targetState.depth >= initialState.depth
            val enterOffset = { width: Int -> if (goingForward) width / SLIDE_DIVISOR else -width / SLIDE_DIVISOR }
            val exitOffset = { width: Int -> if (goingForward) -width / SLIDE_DIVISOR else width / SLIDE_DIVISOR }
            (slideInHorizontally(tween(TRANSITION_MILLIS), enterOffset) +
                fadeIn(tween(TRANSITION_MILLIS))) togetherWith
                (slideOutHorizontally(tween(TRANSITION_MILLIS), exitOffset) +
                    fadeOut(tween(TRANSITION_MILLIS)))
        },
    ) { screen ->
        DestinationPane(
            screen = screen,
            container = container,
            mainViewModel = mainViewModel,
            uiState = uiState,
            isTwoPane = false,
            onNavigate = onNavigate,
        )
    }
}

/**
 * Tablet layout: the Home menu stays put on the left and the selected page
 * fills the right, exactly like One UI Settings on a Galaxy Tab. On launch
 * the right pane shows the virtual machine's hardware configuration.
 */
@Composable
private fun TwoPaneLayout(
    container: AppContainer,
    mainViewModel: MainViewModel,
    uiState: MainUiState,
    destination: Destination,
    onNavigate: (Destination) -> Unit,
) {
    // Home is a full screen on the phone; here it is the always-visible
    // menu, so the detail pane falls back to the configuration page.
    val paneDestination =
        if (destination == Destination.HOME) Destination.SETUP else destination

    BackHandler(enabled = paneDestination != Destination.SETUP) {
        onNavigate(Destination.SETUP)
    }

    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .width(MENU_PANE_WIDTH_DP.dp)
                .fillMaxHeight()
        ) {
            HomeScreen(
                uiState = uiState,
                onStartVm = mainViewModel::startVm,
                onStopVm = mainViewModel::stopVm,
                onOpenSetup = { onNavigate(Destination.SETUP) },
                onOpenConsole = { onNavigate(Destination.CONSOLE) },
                onOpenDisplay = { onNavigate(Destination.DISPLAY) },
                onOpenDiagnostics = { onNavigate(Destination.DIAGNOSTICS) },
                onOpenMonitor = { onNavigate(Destination.MONITOR) },
                onOpenAbout = { onNavigate(Destination.ABOUT) },
                onMessageShown = mainViewModel::clearMessage,
            )
        }
        VerticalDivider(
            thickness = 0.7.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Box(Modifier.fillMaxHeight().weight(1f)) {
            // The menu never moves, so the detail switches with One UI's
            // gentle rise-and-fade instead of a whole-screen slide.
            AnimatedContent(
                targetState = paneDestination,
                label = "detail-pane",
                transitionSpec = {
                    (fadeIn(OneUiMotion.settleSpec()) +
                        slideInVertically(OneUiMotion.settleSpec()) { height ->
                            height / DETAIL_RISE_DIVISOR
                        }) togetherWith fadeOut(tween(DETAIL_FADE_OUT_MILLIS))
                },
            ) { screen ->
                DestinationPane(
                    screen = screen,
                    container = container,
                    mainViewModel = mainViewModel,
                    uiState = uiState,
                    isTwoPane = true,
                    onNavigate = onNavigate,
                )
            }
        }
    }
}

/** Renders one destination, adapted to whether the menu is visible beside it. */
@Composable
private fun DestinationPane(
    screen: Destination,
    container: AppContainer,
    mainViewModel: MainViewModel,
    uiState: MainUiState,
    isTwoPane: Boolean,
    onNavigate: (Destination) -> Unit,
) {
    // With the menu on screen a back arrow would only duplicate it, so
    // detail screens hide theirs in the two-pane layout.
    val onBack: (() -> Unit)? =
        if (isTwoPane) null else ({ onNavigate(Destination.HOME) })

    // Where a pop-out returns this window: the phone goes Home, the tablet
    // keeps its frame and falls back to the configuration pane.
    val afterPopOut = if (isTwoPane) Destination.SETUP else Destination.HOME

    when (screen) {
        Destination.HOME -> HomeScreen(
            uiState = uiState,
            onStartVm = mainViewModel::startVm,
            onStopVm = mainViewModel::stopVm,
            onOpenSetup = { onNavigate(Destination.SETUP) },
            onOpenConsole = { onNavigate(Destination.CONSOLE) },
            onOpenDisplay = { onNavigate(Destination.DISPLAY) },
            onOpenDiagnostics = { onNavigate(Destination.DIAGNOSTICS) },
            onOpenMonitor = { onNavigate(Destination.MONITOR) },
            onOpenAbout = { onNavigate(Destination.ABOUT) },
            onMessageShown = mainViewModel::clearMessage,
        )

        Destination.SETUP -> SetupScreen(
            uiState = uiState,
            onImportIso = mainViewModel::importIso,
            onSelectIso = mainViewModel::selectIso,
            onDeleteIso = mainViewModel::deleteIso,
            onUsePreparedImage = mainViewModel::usePreparedImage,
            onImportPreparedImage = mainViewModel::importPreparedImage,
            onDeletePreparedImage = mainViewModel::deletePreparedImage,
            onClearPreparedImage = mainViewModel::clearPreparedImage,
            onUpdateConfig = mainViewModel::updateConfig,
            onApplyDesktopPreset = mainViewModel::applyDesktopPreset,
            onAddPortForward = mainViewModel::addPortForward,
            onRemovePortForward = mainViewModel::removePortForward,
            onBack = onBack,
        )

        Destination.CONSOLE -> {
            val context = LocalContext.current
            TerminalScreen(
                session = container.terminalSession,
                onBack = onBack,
                onOpenInNewWindow = {
                    // Pop the terminal out as its own DeX window and hand
                    // this pane back so the two are not duplicated.
                    context.startActivity(TerminalActivity.launchIntent(context))
                    onNavigate(afterPopOut)
                },
            )
        }

        Destination.DISPLAY -> {
            val context = LocalContext.current
            DisplayScreen(
                vncPort = (uiState.vmState as? VmState.Running)?.vncPort,
                onBack = onBack,
                onOpenInNewWindow = {
                    context.startActivity(DisplayActivity.launchIntent(context))
                    onNavigate(afterPopOut)
                },
            )
        }

        Destination.DIAGNOSTICS -> DiagnosticsScreen(
            uiState = uiState,
            onBack = onBack,
        )

        Destination.MONITOR -> MonitorDetailScreen(
            uiState = uiState,
            onRefreshUsbDevices = mainViewModel::refreshUsbDevices,
            onBack = onBack,
        )

        Destination.ABOUT -> AboutScreen(
            repository = container.aboutRepository,
            onBack = onBack,
        )
    }
}

private const val TRANSITION_MILLIS = 300
private const val SLIDE_DIVISOR = 5

/** Material's "expanded" width class — tablets, unfolded foldables, DeX. */
private const val TWO_PANE_MIN_WIDTH_DP = 840

/** Width of the pinned menu pane, sized like One UI Settings on a tablet. */
private const val MENU_PANE_WIDTH_DP = 375

/** The detail rises a fraction of its height while it fades in. */
private const val DETAIL_RISE_DIVISOR = 24
private const val DETAIL_FADE_OUT_MILLIS = 150
