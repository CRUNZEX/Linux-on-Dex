package com.crunzex.linuxondex.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Monitor screen's numbers and words come from these pure functions:
 * charging watts from Android's raw current/voltage, and the plain-language
 * temperature labels.
 */
class PowerAndThermalTest {

    // ---- Charging watts ------------------------------------------------------

    @Test
    fun `watts are current times voltage in sensible units`() {
        // 1.5 A at 5 V is a typical slow charger: 7.5 W.
        val watts = PowerReading.wattsFrom(
            currentMicroAmps = 1_500_000,
            voltageMilliVolts = 5_000,
        )

        assertEquals(7.5f, watts!!, 0.001f)
    }

    @Test
    fun `discharge current is negative but the magnitude is what matters`() {
        val watts = PowerReading.wattsFrom(
            currentMicroAmps = -2_000_000,
            voltageMilliVolts = 4_000,
        )

        assertEquals(8.0f, watts!!, 0.001f)
    }

    @Test
    fun `sentinel and empty measurements yield no reading`() {
        // BatteryManager reports Int.MIN_VALUE when the kernel has no data.
        assertNull(PowerReading.wattsFrom(Int.MIN_VALUE, 5_000))
        assertNull(PowerReading.wattsFrom(null, 5_000))
        assertNull(PowerReading.wattsFrom(0, 5_000))
        assertNull(PowerReading.wattsFrom(1_500_000, 0))
        assertNull(PowerReading.wattsFrom(1_500_000, -1))
    }

    @Test
    fun `sensor garbage beyond any real charger is discarded`() {
        // Some kernels report milliamps in the microamp field; the resulting
        // "thousands of watts" must not reach the screen.
        assertNull(
            PowerReading.wattsFrom(
                currentMicroAmps = Int.MAX_VALUE,
                voltageMilliVolts = 5_000,
            )
        )
    }

    // ---- Temperature zones -----------------------------------------------------

    @Test
    fun `trip-point zones are configuration, not measurements, and are dropped`() {
        // Galaxy hardware exposes "cpu-hw-trip" zones that always report the
        // 105 degree shutdown threshold; showing them as readings was a bug.
        assertFalse(DeviceThermal.zoneTypeReportsLiveTemperature("cpu-hw-trip-0"))
        assertFalse(DeviceThermal.zoneTypeReportsLiveTemperature("cpu_hw_trip_1"))
        assertFalse(DeviceThermal.zoneTypeReportsLiveTemperature("soc-trip-point"))
    }

    @Test
    fun `real processor zones are recognised across vendors`() {
        assertTrue(DeviceThermal.zoneTypeReportsLiveTemperature("cpu-0-5-1"))
        assertTrue(DeviceThermal.zoneTypeReportsLiveTemperature("cpuss-max"))
        assertTrue(DeviceThermal.zoneTypeReportsLiveTemperature("soc_thermal"))
        assertTrue(DeviceThermal.zoneTypeReportsLiveTemperature("tsens0"))
        assertFalse(DeviceThermal.zoneTypeReportsLiveTemperature("battery"))
    }

    @Test
    fun `only the single hottest processor zone is shown`() {
        val readings = listOf(
            TemperatureReading(DeviceThermal.PROCESSOR_SENSOR_LABEL, 41.2f, "cpu-0-0-0"),
            TemperatureReading(DeviceThermal.PROCESSOR_SENSOR_LABEL, 45.3f, "cpu 0 5 1"),
            TemperatureReading(DeviceThermal.PROCESSOR_SENSOR_LABEL, 39.9f, "cpuss-2"),
        )

        val shown = DeviceThermal.hottestProcessorReading(readings)

        assertEquals("Processor sensor", shown?.label)
        assertEquals(45.3f, shown!!.celsius, 0.001f)
        assertEquals("cpu 0 5 1", shown.sensorName)
        assertNull(DeviceThermal.hottestProcessorReading(emptyList()))
    }

    @Test
    fun `warmth is described in one plain word`() {
        assertEquals("Normal", DeviceThermal.warmthWord(30f))
        assertEquals("Normal", DeviceThermal.warmthWord(DeviceThermal.WARM_CELSIUS - 0.1f))
        assertEquals("Warm", DeviceThermal.warmthWord(DeviceThermal.WARM_CELSIUS))
        assertEquals("Warm", DeviceThermal.warmthWord(DeviceThermal.HOT_CELSIUS - 0.1f))
        assertEquals("Hot", DeviceThermal.warmthWord(DeviceThermal.HOT_CELSIUS))
    }
}
