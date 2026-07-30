package com.crunzex.linuxondex.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

/**
 * A temperature reading in Celsius, labelled for people rather than kernels:
 * [label] says what part of the phone it is, [sensorName] keeps the raw zone
 * name for the curious.
 */
data class TemperatureReading(
    val label: String,
    val celsius: Float,
    val sensorName: String? = null,
)

/**
 * Best-effort device temperatures for the monitor. Android exposes no
 * general thermal API to apps, so this reads the two sources that are
 * reachable without privilege: the battery's reported temperature (always
 * available via the sticky battery broadcast) and any CPU thermal zones the
 * kernel leaves world-readable (many devices do, some do not).
 *
 * Every read is defensive — a missing or unreadable source is simply
 * omitted, never an error.
 */
class DeviceThermal(private val context: Context) {

    fun readAll(): List<TemperatureReading> = listOfNotNull(
        batteryTemperature(),
        hottestProcessorReading(cpuZoneTemperatures()),
    )

    /** Battery temperature from the sticky broadcast; tenths of a degree. */
    private fun batteryTemperature(): TemperatureReading? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val tenthsCelsius = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenthsCelsius == Int.MIN_VALUE) return null
        return TemperatureReading("Battery", tenthsCelsius / 10f)
    }

    /**
     * Scans /sys/class/thermal for CPU-ish zones that report a live,
     * plausible temperature. Values are millidegrees.
     */
    private fun cpuZoneTemperatures(): List<TemperatureReading> = try {
        File(THERMAL_ROOT).listFiles { file -> file.name.startsWith("thermal_zone") }
            .orEmpty()
            .mapNotNull(::readZone)
            .filter { it.celsius in PLAUSIBLE_MIN_CELSIUS..PLAUSIBLE_MAX_CELSIUS }
    } catch (unreadable: Exception) {
        emptyList()
    }

    private fun readZone(zone: File): TemperatureReading? {
        val zoneType = File(zone, "type").readTextOrNull()?.trim() ?: return null
        if (!zoneTypeReportsLiveTemperature(zoneType)) return null
        val milliCelsius = File(zone, "temp").readTextOrNull()?.trim()?.toLongOrNull() ?: return null
        return TemperatureReading(
            label = PROCESSOR_SENSOR_LABEL,
            celsius = milliCelsius / 1000f,
            sensorName = prettyZoneName(zoneType),
        )
    }

    private fun prettyZoneName(rawType: String): String =
        rawType.replace('_', ' ').replace('-', ' ').trim()

    private fun File.readTextOrNull(): String? = try {
        readText()
    } catch (unreadable: Exception) {
        null
    }

    companion object {
        const val PROCESSOR_SENSOR_LABEL = "Processor sensor"

        /**
         * Whether a thermal zone measures the processor, as opposed to
         * reporting a configuration value.
         *
         * "trip" zones expose a shutdown *threshold* (a constant like
         * 105 °C), not a reading — on Galaxy hardware they sit at the top of
         * every hottest-first sort and drown out the one real sensor.
         */
        fun zoneTypeReportsLiveTemperature(zoneType: String): Boolean {
            if (zoneType.contains("trip", ignoreCase = true)) return false
            return zoneType.contains("cpu", ignoreCase = true) ||
                zoneType.contains("soc", ignoreCase = true) ||
                zoneType.contains("tsens", ignoreCase = true)
        }

        /**
         * The one processor row the monitor shows: devices expose many
         * near-identical CPU zones, and a single hottest value is what the
         * user can act on.
         */
        fun hottestProcessorReading(
            zoneReadings: List<TemperatureReading>,
        ): TemperatureReading? = zoneReadings.maxByOrNull { it.celsius }

        /** One plain word saying whether a temperature is anything to mind. */
        fun warmthWord(celsius: Float): String = when {
            celsius >= HOT_CELSIUS -> "Hot"
            celsius >= WARM_CELSIUS -> "Warm"
            else -> "Normal"
        }

        const val WARM_CELSIUS = 45f
        const val HOT_CELSIUS = 60f

        private const val THERMAL_ROOT = "/sys/class/thermal"
        private const val PLAUSIBLE_MIN_CELSIUS = 5f
        private const val PLAUSIBLE_MAX_CELSIUS = 120f
    }
}
