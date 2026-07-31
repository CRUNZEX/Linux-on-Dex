package com.crunzex.linuxondex.ui.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crunzex.linuxondex.monitor.ChargingMonitor
import com.crunzex.linuxondex.monitor.DeviceThermal
import com.crunzex.linuxondex.monitor.NetworkAddresses
import com.crunzex.linuxondex.monitor.PowerReading
import com.crunzex.linuxondex.ui.components.GroupCard
import com.crunzex.linuxondex.ui.components.ListRow
import com.crunzex.linuxondex.ui.components.OneUiCollapsingScaffold
import com.crunzex.linuxondex.ui.components.OneUiMotion
import com.crunzex.linuxondex.ui.components.RowDivider
import com.crunzex.linuxondex.ui.components.SectionCaption
import com.crunzex.linuxondex.ui.components.VerticalSpace
import com.crunzex.linuxondex.ui.main.MainUiState
import com.crunzex.linuxondex.ui.theme.OneUiPalette
import com.crunzex.linuxondex.usb.AttachedUsbDevice
import kotlinx.coroutines.delay

/**
 * Detailed monitor reached from the Home card, framed like a One UI
 * Settings page: collapsing title, history charts for CPU and memory,
 * charging power, temperatures, plugged-in USB devices (view only), and the
 * host/VM network picture.
 *
 * The charts read [MainUiState.resourceHistory], which the view model keeps
 * filling in the background, so they are already populated on arrival and
 * keep growing live.
 */
@Composable
fun MonitorDetailScreen(
    uiState: MainUiState,
    onRefreshUsbDevices: () -> Unit,
    /** Null in the two-pane layout, where the menu stays visible beside us. */
    onBack: (() -> Unit)?,
) {
    // USB devices come and go while the screen is open; re-read on resume
    // and on every plug/unplug broadcast so the list is never stale.
    RefreshUsbDevicesOnPlugEvents(onRefreshUsbDevices)

    val history = uiState.resourceHistory
    val latest = uiState.resourceUsage

    OneUiCollapsingScaffold(
        title = "Monitor",
        subtitle = "What the virtual machine costs the phone",
        onNavigateBack = onBack,
    ) {
        item { SectionCaption("Processor") }
        item {
            MetricChartCard(
                title = "CPU load",
                currentLabel = latest?.let { "${it.cpuPercentOfDevice}%" } ?: "—",
                subtitle = latest?.let {
                    "Share of the phone's whole processor (${it.deviceCoreCount} cores)"
                } ?: "Measured while the VM is running",
                samples = history.map { it.cpuPercentOfDevice.toFloat() },
                maxValue = 100f,
                lineColor = OneUiPalette.Blue,
            )
        }
        item { SectionCaption("Memory") }
        item {
            val peakMb = (history.maxOfOrNull { it.residentMemoryMb } ?: 0).coerceAtLeast(1)
            MetricChartCard(
                title = "Memory in use",
                currentLabel = latest?.let { formatMemory(it.residentMemoryMb) } ?: "—",
                subtitle = "RAM the VM is holding right now",
                samples = history.map { it.residentMemoryMb.toFloat() },
                maxValue = peakMb.toFloat() * HEADROOM_FACTOR,
                lineColor = OneUiPalette.SuccessGreen,
            )
        }
        item { SectionCaption("Power") }
        item { PowerCard() }
        item { SectionCaption("Temperature") }
        item { TemperatureCard() }
        item { SectionCaption("USB devices") }
        item { UsbDevicesCard(uiState.attachedUsbDevices) }
        item { SectionCaption("Network") }
        item { NetworkCard(uiState) }
        item { SectionCaption("Graphics") }
        item { GraphicsCard() }
        item { VerticalSpace(20) }
    }
}

/**
 * Re-probes attached USB devices when this screen resumes and whenever
 * Android broadcasts a USB attach/detach, so the list is never stale.
 */
@Composable
private fun RefreshUsbDevicesOnPlugEvents(onRefreshUsbDevices: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val resumeObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefreshUsbDevices()
        }
        lifecycleOwner.lifecycle.addObserver(resumeObserver)

        val plugReceiver = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context?, intent: Intent?) {
                onRefreshUsbDevices()
            }
        }
        val plugFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            plugReceiver,
            plugFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(resumeObserver)
            runCatching { context.unregisterReceiver(plugReceiver) }
        }
    }
}

/**
 * A metric with its animated current value and a filled area chart of recent
 * history, styled like One UI's usage graphs: a rounded line with a soft
 * gradient fill and a faint baseline grid.
 */
@Composable
private fun MetricChartCard(
    title: String,
    currentLabel: String,
    subtitle: String,
    samples: List<Float>,
    maxValue: Float,
    lineColor: Color,
) {
    GroupCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedValueText(text = currentLabel, color = lineColor)
        }
        val gridColor = MaterialTheme.colorScheme.outlineVariant
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT_DP.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            drawBaselineGrid(gridColor)
            drawHistory(samples, maxValue, lineColor)
        }
    }
}

/**
 * One UI value treatment: when the number changes it slides gently upward
 * and cross-fades, instead of snapping.
 */
@Composable
private fun AnimatedValueText(text: String, color: Color) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(OneUiMotion.settleSpec()) +
                slideInVertically(OneUiMotion.settleSpec()) { height -> height / 2 })
                .togetherWith(
                    fadeOut(OneUiMotion.settleSpec()) +
                        slideOutVertically(OneUiMotion.settleSpec()) { height -> -height / 2 }
                )
        },
        label = "metric-value",
    ) { valueText ->
        Text(
            text = valueText,
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

private fun DrawScope.drawBaselineGrid(gridColor: Color) {
    val lines = 4
    repeat(lines + 1) { index ->
        val y = size.height * index / lines
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
    }
}

/** Draws the sample line plus a gradient fill from the line down to the axis. */
private fun DrawScope.drawHistory(samples: List<Float>, maxValue: Float, lineColor: Color) {
    if (samples.size < 2 || maxValue <= 0f) return
    val stepX = size.width / (samples.size - 1)
    fun pointAt(index: Int): Offset {
        val normalized = (samples[index] / maxValue).coerceIn(0f, 1f)
        return Offset(index * stepX, size.height * (1f - normalized))
    }

    val linePath = Path().apply {
        moveTo(0f, pointAt(0).y)
        for (index in 1 until samples.size) lineTo(index * stepX, pointAt(index).y)
    }
    val fillPath = Path().apply {
        addPath(linePath)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f)),
        ),
    )
    drawPath(path = linePath, color = lineColor, style = Stroke(width = CHART_LINE_WIDTH))
}

/** Charging wattage and battery level, refreshed while the screen is open. */
@Composable
private fun PowerCard() {
    val context = LocalContext.current
    val power by produceState(initialValue = ChargingMonitor(context).read()) {
        while (true) {
            delay(POWER_REFRESH_MILLIS)
            value = ChargingMonitor(context).read()
        }
    }
    GroupCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Charging power",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = powerSubtitle(power),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedValueText(
                text = powerWattsLabel(power),
                color = if (power.isCharging) {
                    OneUiPalette.Blue
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun powerSubtitle(power: PowerReading): String {
    val source = power.powerSourceName ?: "Not plugged in"
    val battery = power.batteryPercent?.let { "Battery $it%" }
    return listOfNotNull(source, battery).joinToString(" · ")
}

private fun powerWattsLabel(power: PowerReading): String = when {
    power.watts == null -> if (power.isCharging) "Charging" else "—"
    power.isCharging -> "%.1f W in".format(power.watts)
    else -> "%.1f W used".format(power.watts)
}

@Composable
private fun TemperatureCard() {
    val context = LocalContext.current
    // Re-read every few seconds while the screen is open; thermal state
    // drifts slowly, so a leisurely cadence is plenty.
    val readings by produceState(initialValue = DeviceThermal(context).readAll()) {
        while (true) {
            delay(TEMPERATURE_REFRESH_MILLIS)
            value = DeviceThermal(context).readAll()
        }
    }
    GroupCard {
        if (readings.isEmpty()) {
            ListRow(
                title = "Temperature",
                subtitle = "No readable sensors on this device",
                value = "—",
            )
            return@GroupCard
        }
        readings.forEachIndexed { index, reading ->
            ListRow(
                title = reading.label,
                subtitle = listOfNotNull(
                    DeviceThermal.warmthWord(reading.celsius),
                    reading.sensorName?.let { "Sensor: $it" },
                ).joinToString(" · "),
                value = "%.1f °C".format(reading.celsius),
                valueColor = temperatureColor(reading.celsius),
            )
            if (index < readings.lastIndex) RowDivider()
        }
    }
}

/**
 * What is plugged into the USB port right now.
 *
 * A plain list: handing a device to the guest is a configuration choice and
 * lives in the virtual machine's own settings, not here.
 */
@Composable
private fun UsbDevicesCard(devices: List<AttachedUsbDevice>) {
    GroupCard {
        if (devices.isEmpty()) {
            ListRow(
                title = "Nothing connected",
                subtitle = "USB devices you plug in are listed here",
                value = "",
            )
        } else {
            devices.forEachIndexed { index, device ->
                ListRow(
                    title = device.name,
                    subtitle = "${device.kind.description} · ${device.idText}",
                    value = device.kind.displayName,
                )
                if (index < devices.lastIndex) RowDivider()
            }
        }
    }
}

@Composable
private fun NetworkCard(uiState: MainUiState) {
    val hostAddresses by produceState(initialValue = NetworkAddresses.hostAddresses()) {
        while (true) {
            delay(NETWORK_REFRESH_MILLIS)
            value = NetworkAddresses.hostAddresses()
        }
    }
    val forwards = uiState.config?.network?.portForwards.orEmpty()
    val vmNetwork = NetworkAddresses.vmNetwork(forwards)

    GroupCard {
        ListRow(title = "Host (Android)", subtitle = "This phone's addresses", value = "")
        if (hostAddresses.isEmpty()) {
            RowDivider()
            ListRow(title = "No active interfaces", value = "—")
        } else {
            hostAddresses.forEach { host ->
                RowDivider()
                ListRow(
                    title = host.interfaceName,
                    subtitle = if (host.isIpv4) "IPv4" else "IPv6",
                    value = host.address,
                )
            }
        }
        RowDivider()
        ListRow(
            title = "VM (guest)",
            subtitle = "Address inside the virtual machine",
            value = vmNetwork.guestIpAddress,
            valueColor = OneUiPalette.Blue,
        )
        RowDivider()
        ListRow(
            title = "Host from the VM",
            subtitle = "Reach Android services at this address from inside",
            value = vmNetwork.hostReachableFromGuest,
        )
        RowDivider()
        ListRow(title = "VM DNS", value = vmNetwork.guestDnsServer)
        if (forwards.isEmpty()) {
            RowDivider()
            ListRow(
                title = "Forwarded ports",
                subtitle = "Add rules in VM settings to reach guest services",
                value = "None",
            )
        } else {
            forwards.forEach { rule ->
                RowDivider()
                MonoRow(rule.displayText)
            }
        }
    }
}

@Composable
private fun GraphicsCard() {
    GroupCard {
        ListRow(
            title = "VM screen drawing",
            subtitle = "Drawn by the emulated CPU — the VM has no phone GPU inside",
            value = "Software",
        )
        RowDivider()
        ListRow(
            title = "This app's windows",
            subtitle = "The Terminal and Display windows draw with the phone GPU",
            value = "Phone GPU",
            valueColor = OneUiPalette.SuccessGreen,
        )
    }
}

@Composable
private fun MonoRow(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

private fun temperatureColor(celsius: Float): Color = when {
    celsius >= DeviceThermal.HOT_CELSIUS -> OneUiPalette.ErrorRed
    celsius >= DeviceThermal.WARM_CELSIUS -> OneUiPalette.WarningOrange
    else -> OneUiPalette.SuccessGreen
}

private fun formatMemory(megabytes: Int): String =
    if (megabytes >= 1024) "%.1f GB".format(megabytes / 1024f) else "$megabytes MB"

private const val CHART_HEIGHT_DP = 120
private const val CHART_LINE_WIDTH = 4f
private const val HEADROOM_FACTOR = 1.15f
private const val POWER_REFRESH_MILLIS = 2_000L
private const val TEMPERATURE_REFRESH_MILLIS = 3_000L
private const val NETWORK_REFRESH_MILLIS = 5_000L
