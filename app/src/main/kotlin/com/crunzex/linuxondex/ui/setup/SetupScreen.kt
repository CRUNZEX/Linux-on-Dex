package com.crunzex.linuxondex.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.crunzex.linuxondex.ui.components.AnimatedExpand
import com.crunzex.linuxondex.ui.components.CapsuleButton
import com.crunzex.linuxondex.ui.components.ExpanderRow
import com.crunzex.linuxondex.ui.components.ChoiceRow
import com.crunzex.linuxondex.ui.components.GroupCard
import com.crunzex.linuxondex.ui.components.ListRow
import com.crunzex.linuxondex.ui.components.NoticeCard
import com.crunzex.linuxondex.ui.components.OneUiBottomBar
import com.crunzex.linuxondex.ui.components.OneUiCollapsingScaffold
import com.crunzex.linuxondex.ui.components.OpenDocumentAtDownloads
import com.crunzex.linuxondex.ui.components.RowDivider
import com.crunzex.linuxondex.ui.components.SectionCaption
import com.crunzex.linuxondex.ui.components.SliderRow
import com.crunzex.linuxondex.ui.components.StepperRow
import com.crunzex.linuxondex.ui.components.SwitchRow
import com.crunzex.linuxondex.ui.components.TextFieldRow
import com.crunzex.linuxondex.ui.components.VerticalSpace
import com.crunzex.linuxondex.usb.AttachedUsbDevice
import com.crunzex.linuxondex.ui.main.MainUiState
import com.crunzex.linuxondex.vm.BootOrder
import com.crunzex.linuxondex.vm.CpuConfig
import com.crunzex.linuxondex.vm.CpuModel
import com.crunzex.linuxondex.vm.DiskPerformance
import com.crunzex.linuxondex.vm.DiskInterface
import com.crunzex.linuxondex.vm.DisplayAdapter
import com.crunzex.linuxondex.vm.EngineOverride
import com.crunzex.linuxondex.vm.IsoFile
import com.crunzex.linuxondex.vm.KernelBootConfig
import com.crunzex.linuxondex.vm.NetworkConfig
import com.crunzex.linuxondex.vm.PortForwardRule
import com.crunzex.linuxondex.vm.PortProtocol
import com.crunzex.linuxondex.vm.PreparedImage
import com.crunzex.linuxondex.vm.PreparedImageFormat
import com.crunzex.linuxondex.vm.NetworkMode
import com.crunzex.linuxondex.vm.ScreenResolution
import com.crunzex.linuxondex.vm.StorageConfig
import com.crunzex.linuxondex.vm.UsbConfig
import com.crunzex.linuxondex.vm.VmBackup
import com.crunzex.linuxondex.vm.VmConfig

/** Which detail sections are expanded; advanced options stay folded away. */
private data class ExpandedSections(
    val cpu: Boolean = false,
    val storage: Boolean = false,
    val display: Boolean = false,
    val network: Boolean = false,
    val engine: Boolean = false,
)

/**
 * Full VM configuration: media, processor, memory, storage, display,
 * network, shared folder and engine.
 *
 * Simple choices stay visible; every advanced group folds open on demand so
 * the screen reads like One UI Settings rather than a control panel.
 */
@Composable
fun SetupScreen(
    uiState: MainUiState,
    onImportIso: (android.net.Uri) -> Unit,
    onSelectIso: (IsoFile?) -> Unit,
    onDeleteIso: (IsoFile) -> Unit,
    onUsePreparedImage: (PreparedImage) -> Unit,
    onImportPreparedImage: (android.net.Uri) -> Unit,
    onDeletePreparedImage: (PreparedImage) -> Unit,
    onClearPreparedImage: () -> Unit,
    onUpdateConfig: (VmConfig) -> Unit,
    onApplyDesktopPreset: () -> Unit,
    onAddPortForward: (PortForwardRule) -> Unit,
    onRemovePortForward: (PortForwardRule) -> Unit,
    onSetUsbPassthrough: (AttachedUsbDevice, Boolean) -> Unit,
    onBackupVm: () -> Unit,
    onDownloadBackup: (VmBackup) -> Unit,
    onDeleteBackup: (VmBackup) -> Unit,
    /** Null in the two-pane layout, where the menu stays visible beside us. */
    onBack: (() -> Unit)?,
) {
    // Both pickers open at Downloads: "Recent" is empty for files copied
    // onto the device, which reads as a broken picker.
    val isoPicker = rememberLauncherForActivityResult(
        OpenDocumentAtDownloads()
    ) { uri -> uri?.let(onImportIso) }

    val vmImagePicker = rememberLauncherForActivityResult(
        OpenDocumentAtDownloads()
    ) { uri -> uri?.let(onImportPreparedImage) }

    var expanded by remember { mutableStateOf(ExpandedSections()) }
    val config = uiState.config
    val editable = !uiState.vmState.isRunning && !uiState.vmState.isBusy

    OneUiCollapsingScaffold(
        title = "Virtual machine",
        subtitle = if (editable) "Configure hardware and boot media" else "Read-only while running",
        onNavigateBack = onBack,
        bottomBar = {
            OneUiBottomBar {
                CapsuleButton(
                    text = "Use desktop preset (4 GB · 32 GB disk · 1024 × 600)",
                    onClick = onApplyDesktopPreset,
                    enabled = editable,
                    isPrimary = false,
                )
            }
        },
    ) {
        if (!editable) {
            item {
                NoticeCard(
                    message = "Shut the virtual machine down to change its configuration.",
                    isError = false,
                )
            }
        }
        uiState.configurationProblems.forEach { problem ->
            item { NoticeCard(message = problem, isError = true) }
        }

        item { SectionCaption("Ready-made VM (no installation)") }
        item {
            PreparedImageGroup(
                uiState = uiState,
                enabled = editable,
                onUsePreparedImage = onUsePreparedImage,
                onClearPreparedImage = onClearPreparedImage,
                onDeletePreparedImage = onDeletePreparedImage,
                onPickVmImage = { vmImagePicker.launch(VM_IMAGE_MIME_TYPES) },
            )
        }

        item { SectionCaption("Boot media") }
        item {
            BootMediaGroup(
                uiState = uiState,
                selectedIsoPath = config?.installerIsoPath,
                enabled = editable,
                onSelectIso = onSelectIso,
                onDeleteIso = onDeleteIso,
                onPickNew = { isoPicker.launch(ISO_MIME_TYPES) },
            )
        }

        if (config == null) return@OneUiCollapsingScaffold

        item { SectionCaption("Boot order") }
        item {
            GroupCard {
                BootOrder.entries.forEachIndexed { index, order ->
                    ChoiceRow(
                        title = order.displayName,
                        subtitle = order.description,
                        selected = config.bootOrder == order,
                        enabled = editable,
                        onSelect = { onUpdateConfig(config.copy(bootOrder = order)) },
                    )
                    if (index < BootOrder.entries.lastIndex) RowDivider()
                }
            }
        }

        item { SectionCaption("Kernel boot") }
        item {
            KernelBootGroup(
                config = config,
                enabled = editable,
                onUpdateConfig = onUpdateConfig,
            )
        }

        item { SectionCaption("Processor and memory") }
        item {
            ProcessorGroup(
                config = config,
                deviceTotalRamMb = uiState.capabilities?.totalRamMb ?: 0,
                enabled = editable,
                showCpuModels = expanded.cpu,
                onToggleCpuModels = { expanded = expanded.copy(cpu = !expanded.cpu) },
                onUpdateConfig = onUpdateConfig,
            )
        }

        item { SectionCaption("Storage") }
        item {
            StorageGroup(
                config = config,
                enabled = editable,
                showAdvanced = expanded.storage,
                onToggleAdvanced = { expanded = expanded.copy(storage = !expanded.storage) },
                onUpdateConfig = onUpdateConfig,
            )
        }

        item { SectionCaption("Display") }
        item {
            DisplayGroup(
                config = config,
                enabled = editable,
                showAdvanced = expanded.display,
                onToggleAdvanced = { expanded = expanded.copy(display = !expanded.display) },
                onUpdateConfig = onUpdateConfig,
            )
        }

        item { SectionCaption("Network and sharing") }
        item {
            NetworkGroup(
                config = config,
                enabled = editable,
                showAdvanced = expanded.network,
                onToggleAdvanced = { expanded = expanded.copy(network = !expanded.network) },
                onUpdateConfig = onUpdateConfig,
            )
        }

        item { SectionCaption("Port forwarding") }
        item {
            PortForwardGroup(
                rules = config.network.portForwards,
                // Live add/remove works while running; otherwise it applies
                // at the next boot, so editing is allowed in both states.
                onAddPortForward = onAddPortForward,
                onRemovePortForward = onRemovePortForward,
            )
        }

        item { SectionCaption("USB passthrough (Beta)") }
        item {
            UsbPassthroughGroup(
                attachedDevices = uiState.attachedUsbDevices,
                passedThrough = config.usb,
                enabled = editable,
                onSetPassthrough = onSetUsbPassthrough,
            )
        }

        item { SectionCaption("Backup") }
        item {
            BackupGroup(
                backups = uiState.backups,
                isBackingUp = uiState.isBackingUp,
                imageFormat = config.preparedImage?.format,
                enabled = editable,
                onBackupVm = onBackupVm,
                onDownloadBackup = onDownloadBackup,
                onDeleteBackup = onDeleteBackup,
            )
        }

        item { SectionCaption("Virtualization engine") }
        item {
            EngineGroup(
                config = config,
                enabled = editable,
                showChoices = expanded.engine,
                onToggleChoices = { expanded = expanded.copy(engine = !expanded.engine) },
                onUpdateConfig = onUpdateConfig,
            )
        }
        item { VerticalSpace(16) }
    }
}

/**
 * VM→host port forwards. Each connection to localhost:<host port> on the
 * phone reaches the guest, so a web server or SSH inside the VM becomes
 * reachable from Android at 127.0.0.1. Rules can be added while the VM runs
 * and take effect immediately.
 */
@Composable
private fun PortForwardGroup(
    rules: List<PortForwardRule>,
    onAddPortForward: (PortForwardRule) -> Unit,
    onRemovePortForward: (PortForwardRule) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    GroupCard {
        if (rules.isEmpty()) {
            ListRow(
                title = "No forwards",
                subtitle = "Reach guest services from Android at 127.0.0.1",
                value = "",
            )
        } else {
            rules.forEachIndexed { index, rule ->
                ListRow(
                    title = rule.displayText,
                    subtitle = "Tap to remove",
                    trailing = {
                        IconButton(onClick = { onRemovePortForward(rule) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove forward")
                        }
                    },
                    modifier = Modifier.clickable { onRemovePortForward(rule) },
                )
                if (index < rules.lastIndex) RowDivider()
            }
        }
        RowDivider()
        ListRow(
            title = "Add port forward",
            subtitle = "localhost port → VM port",
            trailing = { Icon(Icons.Filled.Add, contentDescription = null) },
            modifier = Modifier.clickable { showAddDialog = true },
        )
    }
    if (showAddDialog) {
        PortForwardDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { rule ->
                onAddPortForward(rule)
                showAddDialog = false
            },
        )
    }
}

/**
 * Hands plugged-in devices to the guest, one switch each.
 *
 * Every device switched on appears inside the VM as though it were plugged
 * straight into it, from the moment the guest finishes booting. Any number
 * can be on at once: each is handed over as its own open descriptor rather
 * than competing for a single slot.
 */
@Composable
private fun UsbPassthroughGroup(
    attachedDevices: List<AttachedUsbDevice>,
    passedThrough: UsbConfig,
    enabled: Boolean,
    onSetPassthrough: (AttachedUsbDevice, Boolean) -> Unit,
) {
    GroupCard {
        if (attachedDevices.isEmpty()) {
            ListRow(
                title = "No USB devices connected",
                subtitle = "Plug in a drive, webcam, network adapter or anything else",
                value = "",
            )
            return@GroupCard
        }
        attachedDevices.forEachIndexed { index, device ->
            if (index > 0) RowDivider()
            SwitchRow(
                title = device.name,
                subtitle = "${device.kind.displayName} · ${device.idText}",
                checked = passedThrough.isPassedThrough(device.idText),
                enabled = enabled,
                onCheckedChange = { turnOn -> onSetPassthrough(device, turnOn) },
            )
        }
    }
}

/**
 * Saved copies of the VM disk. Backing up needs the VM shut down: copying a
 * disk the guest is writing to captures an image that may not boot.
 */
@Composable
private fun BackupGroup(
    backups: List<VmBackup>,
    isBackingUp: Boolean,
    /** What the VM boots, so the row can name the file it will produce. */
    imageFormat: PreparedImageFormat?,
    enabled: Boolean,
    onBackupVm: () -> Unit,
    onDownloadBackup: (VmBackup) -> Unit,
    onDeleteBackup: (VmBackup) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<VmBackup?>(null) }
    GroupCard {
        ListRow(
            title = if (isBackingUp) "Backing up…" else "Back up this VM now",
            subtitle = if (isBackingUp) {
                describeBackupInProgress(imageFormat)
            } else {
                // Naming the actual suffix matters: a backup keeps the form of
                // what it copied, and the user looks for that file by name.
                "Saves a ${backupSuffixLabel(imageFormat)} named with today's date and time"
            },
            enabled = enabled && !isBackingUp,
            leading = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(enabled = enabled && !isBackingUp, onClick = onBackupVm),
        )
        backups.forEach { backup ->
            RowDivider()
            ListRow(
                title = backup.displayName,
                subtitle = "${backup.sizeMb} MB",
                trailing = {
                    Row {
                        IconButton(
                            onClick = { onDownloadBackup(backup) },
                            enabled = !isBackingUp,
                        ) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = "Save ${backup.displayName} to Downloads",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { pendingDelete = backup }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete ${backup.displayName}",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        }
    }
    pendingDelete?.let { backup ->
        DeleteConfirmDialog(
            itemName = backup.displayName,
            onConfirm = { onDeleteBackup(backup); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Small dialog collecting a protocol + host/guest port for a new forward. */
@Composable
private fun PortForwardDialog(
    onDismiss: () -> Unit,
    onConfirm: (PortForwardRule) -> Unit,
) {
    var protocol by remember { mutableStateOf(PortProtocol.TCP) }
    var hostPortText by remember { mutableStateOf("8080") }
    var guestPortText by remember { mutableStateOf("80") }
    var bindAllInterfaces by remember { mutableStateOf(false) }

    val rule = runCatching {
        PortForwardRule(
            protocol = protocol,
            hostPort = hostPortText.toInt(),
            guestPort = guestPortText.toInt(),
            bindAllInterfaces = bindAllInterfaces,
        )
    }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add port forward") },
        text = {
            Column {
                Row {
                    PortProtocol.entries.forEach { candidate ->
                        FilterChip(
                            selected = protocol == candidate,
                            onClick = { protocol = candidate },
                            label = { Text(candidate.displayName) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = hostPortText,
                    onValueChange = { hostPortText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Host port (localhost)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = guestPortText,
                    onValueChange = { guestPortText = it.filter(Char::isDigit).take(5) },
                    label = { Text("VM port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.padding(top = 8.dp),
                )
                SwitchRow(
                    title = "Reachable from other devices",
                    subtitle = if (bindAllInterfaces) {
                        "Binds 0.0.0.0 — anyone on this network can connect"
                    } else {
                        "Binds 127.0.0.1 — this phone only"
                    },
                    checked = bindAllInterfaces,
                    onCheckedChange = { bindAllInterfaces = it },
                )
                if (rule == null) {
                    Text(
                        text = "Host port must be 1024–65535; VM port 1–65535.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = rule != null, onClick = { rule?.let(onConfirm) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Ready-made images boot an already-installed system, so choosing one
 * replaces the whole install-from-ISO workflow.
 */
@Composable
private fun PreparedImageGroup(
    uiState: MainUiState,
    enabled: Boolean,
    onUsePreparedImage: (PreparedImage) -> Unit,
    onClearPreparedImage: () -> Unit,
    onDeletePreparedImage: (PreparedImage) -> Unit,
    onPickVmImage: () -> Unit,
) {
    val selectedPath = uiState.config?.preparedImage?.diskImagePath
    val isImporting = uiState.vmImageImportProgress != null
    val canEdit = enabled && !isImporting
    var pendingDelete by remember { mutableStateOf<PreparedImage?>(null) }

    GroupCard {
        uiState.preparedImages.forEach { image ->
            SelectableDeletableRow(
                title = image.displayName,
                subtitle = "${image.sizeMb} MB · " + describePreparedImage(image),
                selected = image.diskFile.absolutePath == selectedPath,
                enabled = canEdit,
                onSelect = { onUsePreparedImage(image) },
                onDelete = { pendingDelete = image },
            )
            RowDivider()
        }
        if (selectedPath != null) {
            ChoiceRow(
                title = "Install from an ISO instead",
                subtitle = "Stop using the ready-made image",
                selected = false,
                enabled = canEdit,
                onSelect = onClearPreparedImage,
            )
            RowDivider()
        }
        ListRow(
            title = if (isImporting) "Importing VM image…" else "Import VM image…",
            subtitle = if (isImporting) {
                describeImportProgress(uiState.vmImageImportProgress)
            } else {
                "Pick a .qcow2 disk or a .rootfs.tar.gz desktop container"
            },
            enabled = canEdit,
            leading = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(enabled = canEdit, onClick = onPickVmImage),
        )
    }
    pendingDelete?.let { image ->
        DeleteConfirmDialog(
            itemName = image.displayName,
            onConfirm = { onDeletePreparedImage(image); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * A choice row with a trailing delete button, for lists whose items can be
 * both selected and removed (ready-made images, imported ISOs). The row
 * body selects; the trash icon deletes.
 */
@Composable
private fun SelectableDeletableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    ChoiceRow(
        title = title,
        subtitle = subtitle,
        selected = selected,
        enabled = enabled,
        onSelect = onSelect,
        trailing = {
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete $title",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
private fun DeleteConfirmDialog(itemName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $itemName?") },
        text = { Text("This permanently removes the file from the phone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The file extension a backup of this kind of image will carry. */
private fun backupSuffixLabel(imageFormat: PreparedImageFormat?): String =
    when (imageFormat) {
        PreparedImageFormat.PROOT_ROOTFS -> ".rootfs.tar.gz"
        else -> ".qcow2"
    }

/**
 * A disk image is rewritten and usually comes out smaller; a container
 * archive is copied as it is, so promising compaction would be wrong.
 */
private fun describeBackupInProgress(imageFormat: PreparedImageFormat?): String =
    when (imageFormat) {
        PreparedImageFormat.PROOT_ROOTFS -> "Copying the container archive; this can take a while"
        else -> "Writing a compacted copy; this can take a while"
    }

/** The second line of an image row: what this file is, at a glance. */
private fun describePreparedImage(image: PreparedImage): String = when (image.format) {
    // Deliberately not "desktop": the same format carries console-only
    // containers, and which one this is cannot be known until it is
    // unpacked. Claiming a desktop that never appears is worse than saying
    // less.
    PreparedImageFormat.PROOT_ROOTFS ->
        "Linux container — runs at native speed, no emulation"
    PreparedImageFormat.QCOW2_DISK ->
        if (image.seedFile != null) "includes first-boot setup" else "no setup file"
}

private fun describeImportProgress(progress: Float?): String = when {
    progress == null -> ""
    progress < 0f -> "Copying…"
    else -> "${(progress * 100).toInt()}% copied"
}


@Composable
private fun BootMediaGroup(
    uiState: MainUiState,
    selectedIsoPath: String?,
    enabled: Boolean,
    onSelectIso: (IsoFile?) -> Unit,
    onDeleteIso: (IsoFile) -> Unit,
    onPickNew: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<IsoFile?>(null) }
    GroupCard {
        uiState.availableIsos.forEach { iso ->
            SelectableDeletableRow(
                title = iso.displayName,
                subtitle = "${iso.sizeMb} MB",
                selected = iso.file.absolutePath == selectedIsoPath,
                enabled = enabled,
                onSelect = { onSelectIso(iso) },
                onDelete = { pendingDelete = iso },
            )
            RowDivider()
        }
        if (selectedIsoPath != null) {
            ChoiceRow(
                title = "No installer",
                subtitle = "Detach the ISO",
                selected = false,
                enabled = enabled,
                onSelect = { onSelectIso(null) },
            )
            RowDivider()
        }
        ListRow(
            title = "Import ISO file…",
            subtitle = "Copy an installer image into the app",
            enabled = enabled,
            leading = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(enabled = enabled, onClick = onPickNew),
        )
    }
    pendingDelete?.let { iso ->
        DeleteConfirmDialog(
            itemName = iso.displayName,
            onConfirm = { onDeleteIso(iso); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * Direct kernel boot and its command line. This is what makes an installer
 * ISO adjustable: without it the ISO's own bootloader dictates everything.
 */
@Composable
private fun KernelBootGroup(
    config: VmConfig,
    enabled: Boolean,
    onUpdateConfig: (VmConfig) -> Unit,
) {
    val kernelBoot = config.kernelBoot
    GroupCard {
        SwitchRow(
            title = "Direct kernel boot",
            subtitle = "Skip the installer's bootloader so the options below apply",
            checked = kernelBoot.enabled,
            enabled = enabled,
            onCheckedChange = { turnOn ->
                onUpdateConfig(config.copy(kernelBoot = kernelBoot.copy(enabled = turnOn)))
            },
        )
        RowDivider()
        SwitchRow(
            title = "Console on serial port",
            subtitle = "Show the guest's boot messages in the Terminal screen",
            checked = kernelBoot.consoleOnSerialPort,
            enabled = enabled && kernelBoot.enabled,
            onCheckedChange = { turnOn ->
                onUpdateConfig(
                    config.copy(kernelBoot = kernelBoot.copy(consoleOnSerialPort = turnOn))
                )
            },
        )
        RowDivider()
        TextFieldRow(
            title = "Extra kernel arguments",
            placeholder = "nomodeset systemd.mask=some.service",
            value = kernelBoot.extraArguments,
            enabled = enabled && kernelBoot.enabled,
            onValueChange = { text ->
                val cleaned = text.replace(Regex("[\\r\\n]"), "")
                    .take(KernelBootConfig.MAX_ARGUMENTS_LENGTH)
                onUpdateConfig(
                    config.copy(kernelBoot = kernelBoot.copy(extraArguments = cleaned))
                )
            },
        )
    }
}

@Composable
private fun ProcessorGroup(
    config: VmConfig,
    deviceTotalRamMb: Int,
    enabled: Boolean,
    showCpuModels: Boolean,
    onToggleCpuModels: () -> Unit,
    onUpdateConfig: (VmConfig) -> Unit,
) {
    val maxMemoryMb = if (deviceTotalRamMb > 0) {
        VmConfig.maxSafeGuestMemoryMb(deviceTotalRamMb)
    } else {
        VmConfig.MAX_MEMORY_MB
    }
    GroupCard {
        StepperRow(
            title = "Processor cores",
            subtitle = "More cores speed up the guest but compete with Android",
            value = "${config.cpu.coreCount} vCPU",
            enabled = enabled,
            canDecrease = config.cpu.coreCount > CpuConfig.MIN_CORES,
            canIncrease = config.cpu.coreCount < MAX_SELECTABLE_CORES,
            onStep = { direction ->
                val next = (config.cpu.coreCount + direction)
                    .coerceIn(CpuConfig.MIN_CORES, MAX_SELECTABLE_CORES)
                onUpdateConfig(config.copy(cpu = config.cpu.copy(coreCount = next)))
            },
        )
        RowDivider()
        SliderRow(
            title = "Memory",
            subtitle = "Desktop distributions need 4 GB or more",
            valueLabel = "${config.memoryMb} MB",
            value = config.memoryMb.toFloat(),
            valueRange = VmConfig.MIN_MEMORY_MB.toFloat()..maxMemoryMb.toFloat(),
            enabled = enabled,
            onValueChange = { raw ->
                val snapped = (raw / MEMORY_STEP_MB).toInt() * MEMORY_STEP_MB
                onUpdateConfig(
                    config.copy(
                        memoryMb = snapped.coerceIn(VmConfig.MIN_MEMORY_MB, maxMemoryMb)
                    )
                )
            },
        )
        RowDivider()
        ExpanderRow(
            title = "CPU model",
            subtitle = config.cpu.model.description,
            value = config.cpu.model.displayName,
            expanded = showCpuModels,
            enabled = enabled,
            onToggle = onToggleCpuModels,
        )
        AnimatedExpand(expanded = showCpuModels) {
            CpuModel.entries.forEach { model ->
                RowDivider()
                ChoiceRow(
                    title = model.displayName,
                    subtitle = model.description,
                    selected = config.cpu.model == model,
                    enabled = enabled,
                    onSelect = { onUpdateConfig(config.copy(cpu = config.cpu.copy(model = model))) },
                )
            }
        }
    }
}

@Composable
private fun StorageGroup(
    config: VmConfig,
    enabled: Boolean,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onUpdateConfig: (VmConfig) -> Unit,
) {
    GroupCard {
        StepperRow(
            title = "Disk size",
            subtitle = "Grows on demand; applies to newly created disks",
            value = "${config.storage.diskSizeGb} GB",
            enabled = enabled,
            canDecrease = config.storage.diskSizeGb > StorageConfig.MIN_DISK_GB,
            canIncrease = config.storage.diskSizeGb < StorageConfig.MAX_DISK_GB,
            onStep = { direction ->
                val next = (config.storage.diskSizeGb + direction * DISK_STEP_GB)
                    .coerceIn(StorageConfig.MIN_DISK_GB, StorageConfig.MAX_DISK_GB)
                onUpdateConfig(config.copy(storage = config.storage.copy(diskSizeGb = next)))
            },
        )
        RowDivider()
        ExpanderRow(
            title = "Advanced storage",
            subtitle = "${config.storage.diskInterface.displayName} · " +
                config.storage.performance.displayName,
            expanded = showAdvanced,
            enabled = enabled,
            onToggle = onToggleAdvanced,
        )
        AnimatedExpand(expanded = showAdvanced) {
            RowDivider()
            SectionCaption("Disk interface")
            DiskInterface.entries.forEach { option ->
                ChoiceRow(
                    title = option.displayName,
                    subtitle = option.description,
                    selected = config.storage.diskInterface == option,
                    enabled = enabled,
                    onSelect = {
                        onUpdateConfig(
                            config.copy(storage = config.storage.copy(diskInterface = option))
                        )
                    },
                )
            }
            RowDivider()
            SectionCaption("Disk performance")
            DiskPerformance.entries.forEach { option ->
                ChoiceRow(
                    title = option.displayName,
                    subtitle = option.description,
                    selected = config.storage.performance == option,
                    enabled = enabled,
                    onSelect = {
                        onUpdateConfig(
                            config.copy(storage = config.storage.copy(performance = option))
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DisplayGroup(
    config: VmConfig,
    enabled: Boolean,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onUpdateConfig: (VmConfig) -> Unit,
) {
    GroupCard {
        ExpanderRow(
            title = "Resolution",
            subtitle = "Applies at the next start",
            value = config.display.resolution.displayName,
            expanded = showAdvanced,
            enabled = enabled,
            onToggle = onToggleAdvanced,
        )
        AnimatedExpand(expanded = showAdvanced) {
            ScreenResolution.entries.forEach { option ->
                RowDivider()
                ChoiceRow(
                    title = option.displayName,
                    selected = config.display.resolution == option,
                    enabled = enabled && config.display.adapter.supportsCustomResolution,
                    onSelect = {
                        onUpdateConfig(
                            config.copy(display = config.display.copy(resolution = option))
                        )
                    },
                )
            }
            RowDivider()
            SectionCaption("Graphics adapter")
            DisplayAdapter.entries.forEach { option ->
                ChoiceRow(
                    title = option.displayName,
                    subtitle = option.description,
                    selected = config.display.adapter == option,
                    enabled = enabled,
                    onSelect = {
                        onUpdateConfig(
                            config.copy(display = config.display.copy(adapter = option))
                        )
                    },
                )
            }
        }
        RowDivider()
        ListRow(
            title = "QEMU RFB display number",
            subtitle = "QEMU/VNC fallback listens on 127.0.0.1:${config.vncPort}",
            value = config.display.vncDisplayNumber.toString(),
        )
    }
}

@Composable
private fun NetworkGroup(
    config: VmConfig,
    enabled: Boolean,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onUpdateConfig: (VmConfig) -> Unit,
) {
    val network = config.network
    GroupCard {
        SwitchRow(
            title = "Networking",
            subtitle = network.mode.description,
            checked = network.mode == NetworkMode.USER,
            enabled = enabled,
            onCheckedChange = { turnOn ->
                val mode = if (turnOn) NetworkMode.USER else NetworkMode.DISABLED
                onUpdateConfig(
                    config.copy(
                        network = network.copy(
                            mode = mode,
                            sshPortForward = if (turnOn) network.sshPortForward else null,
                        )
                    )
                )
            },
        )
        RowDivider()
        SwitchRow(
            title = "SSH port forwarding",
            subtitle = network.sshPortForward
                ?.let { "Reach the guest at 127.0.0.1:$it" }
                ?: "Expose the guest's SSH port on Android",
            checked = network.sshPortForward != null,
            enabled = enabled && network.mode == NetworkMode.USER,
            onCheckedChange = { turnOn ->
                onUpdateConfig(
                    config.copy(
                        network = network.copy(
                            sshPortForward =
                                if (turnOn) NetworkConfig.DEFAULT_SSH_PORT else null
                        )
                    )
                )
            },
        )
        RowDivider()
        SwitchRow(
            title = "Shared folder",
            subtitle = "mount -t 9p -o trans=virtio ${config.sharedFolder.mountTag} /mnt",
            checked = config.sharedFolder.enabled,
            enabled = enabled,
            onCheckedChange = { turnOn ->
                onUpdateConfig(
                    config.copy(sharedFolder = config.sharedFolder.copy(enabled = turnOn))
                )
            },
        )
    }
}

@Composable
private fun EngineGroup(
    config: VmConfig,
    enabled: Boolean,
    showChoices: Boolean,
    onToggleChoices: () -> Unit,
    onUpdateConfig: (VmConfig) -> Unit,
) {
    // A rootfs image can only run under PRoot, so the image, not the
    // override, decides — and the row says so instead of pretending the
    // choice below still applies.
    val imagePinsEngine = config.runsInProotContainer
    GroupCard {
        ExpanderRow(
            title = "Engine selection",
            value = if (imagePinsEngine) {
                "PRoot container — set by the selected desktop image"
            } else {
                config.engineOverride.displayName
            },
            expanded = showChoices,
            enabled = enabled,
            onToggle = onToggleChoices,
        )
        AnimatedExpand(expanded = showChoices) {
            if (imagePinsEngine) {
                RowDivider()
                ListRow(
                    title = "This image runs on PRoot",
                    subtitle = "Desktop container images always use the native-speed " +
                        "container. The choice below applies when a disk image or " +
                        "ISO is selected instead.",
                    enabled = enabled,
                )
            }
            EngineOverride.entries.forEach { option ->
                RowDivider()
                ChoiceRow(
                    title = option.displayName,
                    subtitle = option.description,
                    selected = config.engineOverride == option,
                    enabled = enabled,
                    onSelect = { onUpdateConfig(config.copy(engineOverride = option)) },
                )
            }
        }
    }
}

private const val MEMORY_STEP_MB = 256
private const val DISK_STEP_GB = 8
private const val MAX_SELECTABLE_CORES = 8
private val VM_IMAGE_MIME_TYPES = arrayOf(
    "application/octet-stream",
    "application/x-qemu-disk",
    "*/*",
)
private val ISO_MIME_TYPES = arrayOf(
    "application/x-iso9660-image",
    "application/octet-stream",
    "*/*",
)
