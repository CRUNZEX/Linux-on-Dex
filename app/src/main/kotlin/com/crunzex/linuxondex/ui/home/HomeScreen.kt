package com.crunzex.linuxondex.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.crunzex.linuxondex.engine.EngineKind
import com.crunzex.linuxondex.monitor.VmResourceUsage
import com.crunzex.linuxondex.ui.components.CapsuleButton
import com.crunzex.linuxondex.ui.components.GroupCard
import com.crunzex.linuxondex.ui.components.ListRow
import com.crunzex.linuxondex.ui.components.NoticeCard
import com.crunzex.linuxondex.ui.components.OneUiBottomBar
import com.crunzex.linuxondex.ui.components.OneUiCollapsingScaffold
import com.crunzex.linuxondex.ui.components.RowDivider
import com.crunzex.linuxondex.ui.components.SectionCaption
import com.crunzex.linuxondex.ui.components.rememberDexModeActive
import com.crunzex.linuxondex.ui.components.StatusDot
import com.crunzex.linuxondex.ui.components.VerticalSpace
import com.crunzex.linuxondex.ui.main.MainUiState
import com.crunzex.linuxondex.ui.theme.OneUiPalette
import com.crunzex.linuxondex.vm.VmState
import java.io.File

/**
 * Landing screen: One UI collapsing title, VM status card, grouped settings
 * rows, and the primary action pinned to the bottom within thumb reach.
 */
@Composable
fun HomeScreen(
    uiState: MainUiState,
    onStartVm: () -> Unit,
    onStopVm: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenMonitor: () -> Unit,
    onOpenAbout: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val vmState = uiState.vmState

    OneUiCollapsingScaffold(
        title = "Linux on DeX",
        subtitle = "Full Linux virtual machine on your Galaxy",
        actions = {
            OverflowMenu(
                hasUnseenUpdate = uiState.hasUnseenUpdate,
                onOpenSetup = onOpenSetup,
                onOpenAbout = onOpenAbout,
            )
        },
        bottomBar = {
            OneUiBottomBar {
                TransientMessage(uiState.userMessage, onMessageShown)
                if (vmState.isRunning) {
                    CapsuleButton(
                        text = "Shut down",
                        onClick = onStopVm,
                        isPrimary = false,
                        leadingIcon = { Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)) },
                    )
                } else {
                    CapsuleButton(
                        text = when {
                            uiState.config?.usesPreparedImage == true -> "Start Linux"
                            uiState.rootDiskExists -> "Start"
                            else -> "Install Linux"
                        },
                        onClick = onStartVm,
                        enabled = !vmState.isBusy && uiState.canBoot,
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp)) },
                    )
                }
            }
        },
    ) {
        item { VmStatusCard(uiState) }
        item { SectionCaption("Resource monitor") }
        item {
            ResourceMonitorGroup(
                usage = uiState.resourceUsage.takeIf { uiState.vmState.isRunning },
                onOpenMonitor = onOpenMonitor,
            )
        }
        uiState.isoImportProgress?.let { progress ->
            item { IsoImportProgressCard(progress) }
        }
        uiState.configurationProblems.forEach { problem ->
            item { NoticeCard(message = problem, isError = true) }
        }
        if (uiState.showQuickStart) {
            item { SectionCaption("Quick start") }
            item { QuickStartCard(uiState, onOpenSetup) }
        }
        item { SectionCaption("Storage and media") }
        item { StorageGroup(uiState, onOpenSetup) }
        item { SectionCaption("Session") }
        item { SessionGroup(uiState, onOpenConsole, onOpenDisplay) }
        item { SectionCaption("Environment") }
        item { EnvironmentGroup(uiState, onOpenDiagnostics) }
        item { VerticalSpace(12) }
    }
}

/**
 * One UI's overflow menu: the three vertical dots at the end of the app bar,
 * opening a rounded card of text-only entries.
 *
 * Settings lives here now rather than as its own icon, which is where One UI
 * 8.5 puts a screen's actions once there is more than one.
 */
@Composable
private fun OverflowMenu(
    hasUnseenUpdate: Boolean,
    onOpenSetup: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        // One UI marks the menu itself, then the entry inside it, so the
        // user can follow the dot to whatever is new.
        if (hasUnseenUpdate) {
            UpdateDot(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = MENU_DOT_INSET_DP.dp, end = MENU_DOT_INSET_DP.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            offset = DpOffset(x = (-8).dp, y = 0.dp),
        ) {
            OverflowMenuItem(text = "Settings") {
                expanded = false
                onOpenSetup()
            }
            OverflowMenuItem(text = "About this app", showUpdateDot = hasUnseenUpdate) {
                expanded = false
                onOpenAbout()
            }
        }
    }
}

@Composable
private fun OverflowMenuItem(
    text: String,
    showUpdateDot: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (showUpdateDot) {
                    Spacer(Modifier.width(8.dp))
                    UpdateDot()
                }
            }
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
    )
}

/**
 * One UI's "new" marker: a small solid orange circle, no number. Purely
 * decorative, so it carries no content description — the row it sits on
 * already says what it refers to.
 */
@Composable
private fun UpdateDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(UPDATE_DOT_SIZE_DP.dp)
            .background(OneUiPalette.BadgeOrange, CircleShape)
    )
}

@Composable
private fun VmStatusCard(uiState: MainUiState) {
    val vmState = uiState.vmState
    GroupCard {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = vmState.statusColor())
                Spacer(Modifier.width(10.dp))
                Text(
                    text = uiState.config?.name ?: "Linux VM",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                if (vmState.isBusy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Text(
                text = vmState.describe(uiState.activeEngine),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            uiState.config?.let { config ->
                Text(
                    text = "${config.cpu.coreCount} vCPU · ${config.memoryMb} MB · " +
                        "${config.storage.diskSizeGb} GB · ${config.display.resolution.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (vmState is VmState.Failed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = vmState.error.userMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Offers the no-install path when a ready-made image is present, and shows
 * its sign-in details so the user is not left guessing at the login prompt.
 */
@Composable
private fun QuickStartCard(uiState: MainUiState, onOpenSetup: () -> Unit) {
    val prepared = uiState.config?.preparedImage
    GroupCard {
        if (prepared == null) {
            ListRow(
                title = "Ready-made VM available",
                subtitle = "${uiState.preparedImages.size} image(s) found — " +
                    "start Linux without installing",
                leading = { Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary) },
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(onClick = onOpenSetup),
            )
        } else {
            ListRow(
                title = prepared.displayName,
                subtitle = if (prepared.firstBootCompleted) {
                    "Configured — starts straight to a login prompt"
                } else {
                    "First start runs a one-time setup"
                },
                leading = { Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary) },
            )
            RowDivider()
            ListRow(
                title = "Sign in",
                subtitle = "Password: ${prepared.password} · passwordless sudo",
                value = prepared.username,
                valueColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Keeps a multi-gigabyte ISO copy visibly alive instead of looking frozen. */
@Composable
private fun IsoImportProgressCard(progress: Float) {
    GroupCard {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "Importing ISO…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (progress >= 0f) {
                    "${(progress * 100).toInt()}% copied — you can keep using the app"
                } else {
                    "Copying — you can keep using the app"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (progress >= 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StorageGroup(uiState: MainUiState, onOpenSetup: () -> Unit) {
    GroupCard {
        ListRow(
            title = "Installer ISO",
            subtitle = uiState.config?.installerIsoPath?.let { File(it).name }
                ?: "None selected — tap to choose",
            leading = { Icon(Icons.Filled.Album, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            modifier = Modifier.clickable(onClick = onOpenSetup),
        )
        RowDivider()
        ListRow(
            title = "Root disk",
            subtitle = if (uiState.rootDiskExists) "Created" else "Will be created on first start",
            value = uiState.config?.let { "${it.storage.diskSizeGb} GB" },
        )
        RowDivider()
        ListRow(
            title = "Shared folder",
            subtitle = if (uiState.config?.sharedFolder?.enabled == true) {
                "Mount in the guest with tag \"${uiState.config.sharedFolder.mountTag}\""
            } else {
                "Off"
            },
            leading = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            modifier = Modifier.clickable(onClick = onOpenSetup),
        )
    }
}

@Composable
private fun SessionGroup(
    uiState: MainUiState,
    onOpenConsole: () -> Unit,
    onOpenDisplay: () -> Unit,
) {
    val running = uiState.vmState.isRunning
    GroupCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onOpenConsole,
                enabled = running,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Terminal, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Terminal")
            }
            OutlinedButton(
                onClick = onOpenDisplay,
                enabled = running,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Monitor, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Display")
            }
        }
    }
}

/**
 * What the VM costs the phone right now, measured host-side from the QEMU
 * process — the numbers the user actually feels. The live rows appear only
 * while the VM runs; the monitor page itself (temperatures, power, USB
 * devices, network) is always a tap away.
 */
@Composable
private fun ResourceMonitorGroup(usage: VmResourceUsage?, onOpenMonitor: () -> Unit) {
    GroupCard {
        if (usage != null) {
            ListRow(
                title = "CPU",
                subtitle = "Whole device, ${usage.deviceCoreCount} cores",
                value = "${usage.cpuPercentOfDevice}%",
                valueColor = when {
                    usage.cpuPercentOfDevice >= CPU_HEAVY_PERCENT ->
                        MaterialTheme.colorScheme.error
                    else -> OneUiPalette.SuccessGreen
                },
            )
            RowDivider()
            ListRow(
                title = "Memory",
                subtitle = "VM process resident set",
                value = formatMemoryMb(usage.residentMemoryMb),
            )
            RowDivider()
        }
        ListRow(
            title = "Monitor",
            subtitle = "Charts, power, temperature and USB devices",
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            modifier = Modifier.clickable(onClick = onOpenMonitor),
        )
    }
}

private fun formatMemoryMb(megabytes: Int): String =
    if (megabytes >= 1024) "%.1f GB".format(megabytes / 1024f) else "$megabytes MB"

private const val CPU_HEAVY_PERCENT = 85

@Composable
private fun EnvironmentGroup(uiState: MainUiState, onOpenDiagnostics: () -> Unit) {
    val bestEngine = uiState.engineCandidates.firstOrNull { it.availability.isAvailable }
    GroupCard {
        ListRow(
            title = "Virtualization engine",
            subtitle = bestEngine?.kind?.shortDescription ?: "None available",
            value = bestEngine?.kind?.displayName ?: "—",
            valueColor = if (bestEngine != null) OneUiPalette.SuccessGreen
            else MaterialTheme.colorScheme.error,
        )
        RowDivider()
        // Live per-configuration check: DeX docking arrives as a uiMode
        // configuration change, so this recomposes the moment it happens —
        // the startup capability snapshot would stay stale forever.
        val dexActive = rememberDexModeActive()
        ListRow(
            title = "Samsung DeX",
            subtitle = if (dexActive) "Terminal and Display open as windows"
            else "Full-screen desktop when docked",
            value = if (dexActive) "Active" else "Not active",
            valueColor = if (dexActive) OneUiPalette.SuccessGreen
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        ListRow(
            title = "Diagnostics",
            subtitle = "Device capabilities and engine details",
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            modifier = Modifier.clickable(onClick = onOpenDiagnostics),
        )
    }
}

/** Lightweight banner shown above the pinned action, then auto-dismissed. */
@Composable
private fun TransientMessage(message: String?, onShown: () -> Unit) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(MESSAGE_VISIBLE_MILLIS)
        onShown()
    }
    Snackbar(Modifier.padding(bottom = 4.dp)) { Text(message) }
}

private val MainUiState.canBoot: Boolean
    get() = configurationProblems.isEmpty() &&
        (config?.usesPreparedImage == true || rootDiskExists || config?.installerIsoPath != null)

/** Show the quick-start section while it can still save the user an install. */
private val MainUiState.showQuickStart: Boolean
    get() = preparedImages.isNotEmpty() || config?.usesPreparedImage == true

@Composable
private fun VmState.statusColor(): Color = when (this) {
    is VmState.Running -> OneUiPalette.SuccessGreen
    is VmState.Failed -> MaterialTheme.colorScheme.error
    is VmState.Preparing, is VmState.Starting, VmState.Stopping -> OneUiPalette.WarningOrange
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun VmState.describe(engine: EngineKind?): String = when (this) {
    VmState.Idle -> "Powered off"
    is VmState.Preparing -> stepDescription
    is VmState.Starting -> "Starting via ${this.engine.displayName}…"
    is VmState.Running -> "Running · ${this.engine.displayName}"
    VmState.Stopping -> "Shutting down…"
    is VmState.Stopped -> "Powered off"
    is VmState.Failed -> "Stopped with an error"
}

private const val MESSAGE_VISIBLE_MILLIS = 4_000L

/** Diameter of the One UI "new" dot. */
private const val UPDATE_DOT_SIZE_DP = 7

/** Keeps the dot on the icon's corner rather than the ripple's edge. */
private const val MENU_DOT_INSET_DP = 12
