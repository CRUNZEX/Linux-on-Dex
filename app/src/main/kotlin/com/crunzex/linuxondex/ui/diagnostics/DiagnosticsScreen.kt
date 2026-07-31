package com.crunzex.linuxondex.ui.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crunzex.linuxondex.capability.KvmAccess
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.engine.EngineAvailability
import com.crunzex.linuxondex.ui.components.GroupCard
import com.crunzex.linuxondex.ui.components.ListRow
import com.crunzex.linuxondex.ui.components.RowDivider
import com.crunzex.linuxondex.ui.components.SectionCaption
import com.crunzex.linuxondex.ui.components.VerticalSpace
import com.crunzex.linuxondex.ui.main.MainUiState
import com.crunzex.linuxondex.ui.theme.OneUiPalette

/**
 * Transparency screen: what the device allows, which engine gets used and —
 * crucially — the exact reason anything is unavailable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    uiState: MainUiState,
    onExportLog: () -> Unit,
    /** Null in the two-pane layout, where the menu stays visible beside us. */
    onBack: (() -> Unit)?,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionCaption("Device capabilities") }
            item { CapabilitiesGroup(uiState) }
            item { SectionCaption("Display & GPU") }
            item { DisplayPathGroup() }
            item { SectionCaption("Engines (preferred first)") }
            item { EnginesGroup(uiState) }
            item { SectionCaption("Recent app log") }
            item { LogCard(onExportLog = onExportLog) }
            item { VerticalSpace(28) }
        }
    }
}

@Composable
private fun CapabilitiesGroup(uiState: MainUiState) {
    val caps = uiState.capabilities
    GroupCard {
        ListRow(title = "Device", value = caps?.deviceModel ?: "…")
        RowDivider()
        ListRow(title = "Android API level", value = caps?.apiLevel?.toString() ?: "…")
        RowDivider()
        ListRow(
            title = "ARM64 processor",
            value = caps?.isArm64.asYesNo(),
        )
        RowDivider()
        ListRow(
            title = "KVM hardware virtualization",
            subtitle = "/dev/kvm reachable by apps",
            value = when (caps?.kvm) {
                KvmAccess.USABLE -> "Usable"
                KvmAccess.DENIED -> "Blocked by firmware"
                KvmAccess.ABSENT -> "Not present"
                null -> "…"
            },
            valueColor = if (caps?.kvm == KvmAccess.USABLE) OneUiPalette.SuccessGreen
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        ListRow(
            title = "Virtualization framework (AVF)",
            subtitle = "Reserved for future firmware support",
            value = caps?.hasVirtualizationFramework.asYesNo(),
        )
        RowDivider()
        ListRow(title = "Process spawning", value = caps?.canForkExec.asYesNo())
        RowDivider()
        ListRow(title = "QEMU runtime packaged", value = caps?.qemuPayloadPresent.asYesNo())
        RowDivider()
        ListRow(title = "PRoot runtime packaged", value = caps?.prootPayloadPresent.asYesNo())
        RowDivider()
        ListRow(title = "Device memory", value = caps?.let { "${it.totalRamMb} MB" } ?: "…")
    }
}

/**
 * Where the phone's GPU is and is not in play — stated plainly so "make it
 * use the GPU" has a truthful answer. App-side drawing (VNC surface,
 * terminal glyphs) runs on the device GPU through hardware-accelerated
 * canvases. The *guest's* 3D cannot: stock Samsung firmware gives untrusted
 * apps no KVM and no GPU render nodes, so the guest renders GL in software
 * (llvmpipe) and this app tunes the desktop images accordingly.
 */
@Composable
private fun DisplayPathGroup() {
    val view = androidx.compose.ui.platform.LocalView.current
    val hardwareCanvas = view.isHardwareAccelerated
    GroupCard {
        ListRow(
            title = "App rendering",
            subtitle = "Display and terminal draw on the device GPU",
            value = if (hardwareCanvas) "GPU (hardware canvas)" else "Software",
            valueColor = if (hardwareCanvas) OneUiPalette.SuccessGreen
            else MaterialTheme.colorScheme.error,
        )
        RowDivider()
        ListRow(
            title = "Guest 3D acceleration",
            // Measured, not assumed: the packaged QEMU offers only
            // virtio-gpu-pci (no virtio-gpu-gl-pci) and no egl-headless
            // display, so there is no path from guest GL to the phone GPU.
            // Enabling it needs a QEMU rebuilt against virglrenderer, not a
            // setting — see the notes in the repository README.
            subtitle = "The packaged VM runtime has no 3D device, so guest " +
                "graphics are drawn by the CPU. Lower the resolution for speed.",
            value = "Software (llvmpipe)",
        )
    }
}

@Composable
private fun EnginesGroup(uiState: MainUiState) {
    GroupCard {
        uiState.engineCandidates.forEachIndexed { index, candidate ->
            val verdict = candidate.availability
            ListRow(
                title = "${index + 1}. ${candidate.kind.displayName}",
                subtitle = when (verdict) {
                    is EngineAvailability.Available -> candidate.kind.shortDescription
                    is EngineAvailability.Unavailable -> verdict.reason
                },
                value = if (verdict.isAvailable) "Ready" else "Unavailable",
                valueColor = if (verdict.isAvailable) OneUiPalette.SuccessGreen
                else MaterialTheme.colorScheme.error,
            )
            if (index < uiState.engineCandidates.lastIndex) RowDivider()
        }
    }
}

@Composable
private fun LogCard(onExportLog: () -> Unit) {
    GroupCard {
        ListRow(
            title = "Save log to Downloads",
            subtitle = "A .log file you can send on, named with the date and time",
            trailing = { Icon(Icons.Filled.Download, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onExportLog),
        )
        RowDivider()
        Text(
            text = AppLog.recentLines().takeLast(40).joinToString("\n").ifEmpty { "(empty)" },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}

private fun Boolean?.asYesNo(): String = when (this) {
    true -> "Yes"
    false -> "No"
    null -> "…"
}
