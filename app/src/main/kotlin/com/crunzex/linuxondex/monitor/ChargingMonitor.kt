package com.crunzex.linuxondex.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/**
 * A snapshot of the phone's power situation for the Monitor screen: whether
 * it is charging, from what, at how many watts, and the battery level.
 */
data class PowerReading(
    val isCharging: Boolean,
    /** 0–100, or null when Android does not report a level. */
    val batteryPercent: Int?,
    /** "AC adapter", "USB port", "Wireless pad", or null when unplugged. */
    val powerSourceName: String?,
    /**
     * Power flowing right now, in watts: into the battery while charging,
     * out of it while running unplugged. Null when not measurable.
     */
    val watts: Float?,
) {
    companion object {
        /**
         * Watts from the two numbers Android exposes: battery current in
         * microamps (sign varies by vendor, so magnitude is used) and
         * battery voltage in millivolts. Returns null for the sentinel and
         * garbage values some kernels report instead of a measurement.
         */
        fun wattsFrom(currentMicroAmps: Int?, voltageMilliVolts: Int): Float? {
            if (currentMicroAmps == null || currentMicroAmps == Int.MIN_VALUE) return null
            if (currentMicroAmps == 0 || voltageMilliVolts <= 0) return null
            val amps = abs(currentMicroAmps.toFloat()) / MICROAMPS_PER_AMP
            val volts = voltageMilliVolts.toFloat() / MILLIVOLTS_PER_VOLT
            val watts = amps * volts
            return watts.takeIf { it <= PLAUSIBLE_MAX_WATTS }
        }

        private const val MICROAMPS_PER_AMP = 1_000_000f
        private const val MILLIVOLTS_PER_VOLT = 1_000f

        /** Above any real phone charger; larger values are sensor garbage. */
        const val PLAUSIBLE_MAX_WATTS = 150f
    }
}

/**
 * Reads the phone's charging state from the sticky battery broadcast and
 * [BatteryManager]. Every read is defensive: anything missing or unreadable
 * degrades to nulls, never an exception on the UI path.
 */
class ChargingMonitor(private val context: Context) {

    fun read(): PowerReading = try {
        readUnguarded()
    } catch (unreadable: Exception) {
        PowerReading(isCharging = false, batteryPercent = null, powerSourceName = null, watts = null)
    }

    private fun readUnguarded(): PowerReading {
        val batteryIntent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryManager =
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pluggedSource = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val voltageMilliVolts = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val currentMicroAmps =
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        return PowerReading(
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            batteryPercent = batteryPercent(batteryIntent),
            powerSourceName = powerSourceName(pluggedSource),
            watts = PowerReading.wattsFrom(currentMicroAmps, voltageMilliVolts),
        )
    }

    private fun batteryPercent(batteryIntent: Intent?): Int? {
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return null
        return (level * 100) / scale
    }

    private fun powerSourceName(pluggedSource: Int): String? = when (pluggedSource) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC adapter"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB port"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless pad"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
        else -> null
    }
}
