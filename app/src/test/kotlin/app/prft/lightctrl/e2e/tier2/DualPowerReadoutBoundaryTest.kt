package app.prft.lightctrl.e2e.tier2

import app.prft.lightctrl.domain.PowerScaleConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 — Feature 9: Dual Power Readout Boundary Tests.
 *
 * Verifies:
 * 1. Sub-1.0 remote power floor (0.1..0.9 f-stops) calculation and fraction formatting.
 * 2. Missing, zero, or negative maxWattSeconds graceful fallback to scale & fraction.
 * 3. Out-of-bounds power clamping for negative, zero, and overflow tenth-stops.
 * 4. Precision rounding boundaries for integer, 1-decimal, and stripped trailing zero cases.
 * 5. High-wattage studio packs (2400 Ws Pro-11) and invalid parameter validation.
 * 6. Strict monotonic increase across all 100 tenth-stop energy levels.
 */
class DualPowerReadoutBoundaryTest {

    @Test
    fun `test 1 Connect Pro remote power floor 0_1 fraction and Ws math`() {
        val maxWs = 500

        // 0.1 power level (1 tenth-stop): 500 * 2^(0.1 - 10.0) = 500 * 2^(-9.9) ~= 0.52336 Ws
        assertEquals(0.52336, PowerScaleConverter.calculateWattSeconds(maxWs, 1), 0.001)
        assertEquals("0.5 Ws", PowerScaleConverter.formatWattSeconds(maxWs, 1))
        assertEquals("1/512 - 0.9 EV", PowerScaleConverter.formatPowerFraction(1))
        assertEquals("0.1", PowerScaleConverter.formatScale(1))
        assertEquals("0.1 (0.5 Ws · 1/512 - 0.9 EV)", PowerScaleConverter.formatDualPowerReadout(1, maxWs))

        // 0.5 power level (5 tenth-stops)
        assertEquals("1/512 - 0.5 EV", PowerScaleConverter.formatPowerFraction(5))
        assertEquals("0.5", PowerScaleConverter.formatScale(5))
    }

    @Test
    fun `test 2 Unknown device or fixture without maxWattSeconds graceful fallback`() {
        // Null maxWs fallback
        assertEquals("7.0 (1/8)", PowerScaleConverter.formatDualPowerReadout(70, null))
        assertEquals("8.3 (1/4 + 0.3 EV)", PowerScaleConverter.formatDualPowerReadout(83, null))
        assertEquals("0.1 (1/512 - 0.9 EV)", PowerScaleConverter.formatDualPowerReadout(1, null))

        // Non-positive maxWs fallback
        assertEquals("7.0 (1/8)", PowerScaleConverter.formatDualPowerReadout(70, 0))
        assertEquals("7.0 (1/8)", PowerScaleConverter.formatDualPowerReadout(70, -500))
    }

    @Test
    fun `test 3 Clamping of negative, zero, and overflow tenthStops`() {
        // Clamping to floor 1 (0.1 stops)
        assertEquals(0.52336, PowerScaleConverter.calculateWattSeconds(500, -10), 0.001)
        assertEquals(0.52336, PowerScaleConverter.calculateWattSeconds(500, 0), 0.001)
        assertEquals("1/512 - 0.9 EV", PowerScaleConverter.formatPowerFraction(-5))
        assertEquals("0.1", PowerScaleConverter.formatScale(-5))

        // Clamping to ceiling 100 (10.0 stops)
        assertEquals(500.0, PowerScaleConverter.calculateWattSeconds(500, 101), 0.001)
        assertEquals(500.0, PowerScaleConverter.calculateWattSeconds(500, 999), 0.001)
        assertEquals("1/1", PowerScaleConverter.formatPowerFraction(200))
        assertEquals("10.0", PowerScaleConverter.formatScale(150))
    }

    @Test
    fun `test 4 Fractional tenth-stop rounding and formatting boundaries`() {
        // Boundary >= 100 Ws rounds to integer
        assertEquals("100 Ws", PowerScaleConverter.formatWattSeconds(100, 100))

        // Boundary 10..99.9 Ws: strips .0 if whole number, else keeps 1 decimal
        assertEquals("50 Ws", PowerScaleConverter.formatWattSeconds(100, 90))
        assertEquals("62.5 Ws", PowerScaleConverter.formatWattSeconds(500, 70))
        assertEquals("31.3 Ws", PowerScaleConverter.formatWattSeconds(250, 70)) // 31.25 -> 31.3

        // Boundary < 10 Ws: 1 decimal place with US locale
        assertEquals("1.0 Ws", PowerScaleConverter.formatWattSeconds(500, 10)) // 0.9765625 -> 1.0
        assertEquals("0.2 Ws", PowerScaleConverter.formatWattSeconds(100, 10)) // 0.1953125 -> 0.2
    }

    @Test
    fun `test 5 Extreme fixture ratings and invalid parameter validation`() {
        // Pro-11 (2400 Ws)
        assertEquals("2400 Ws", PowerScaleConverter.formatWattSeconds(2400, 100))
        assertEquals("1200 Ws", PowerScaleConverter.formatWattSeconds(2400, 90))
        assertEquals("4.7 Ws", PowerScaleConverter.formatWattSeconds(2400, 10)) // 2400 / 512 = 4.6875 -> 4.7

        // A10 (76 Ws)
        assertEquals("76 Ws", PowerScaleConverter.formatWattSeconds(76, 100))
        assertEquals("9.5 Ws", PowerScaleConverter.formatWattSeconds(76, 70)) // 76 / 8 = 9.5

        // Invalid maxWs ratings must throw IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            PowerScaleConverter.calculateWattSeconds(0, 50)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PowerScaleConverter.calculateWattSeconds(-100, 50)
        }
    }

    @Test
    fun `test 6 Monotonicity guarantee across all 100 tenth-stop levels`() {
        for (k in 1..99) {
            val lower = PowerScaleConverter.calculateWattSeconds(500, k)
            val higher = PowerScaleConverter.calculateWattSeconds(500, k + 1)
            assertTrue("Power at $k ($lower) must be strictly less than power at ${k + 1} ($higher)", lower < higher)
        }
    }
}
