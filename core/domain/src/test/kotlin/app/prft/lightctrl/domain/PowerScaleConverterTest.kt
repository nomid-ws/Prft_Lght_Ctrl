package app.prft.lightctrl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerScaleConverterTest {

    @Test
    fun `calculateWattSeconds full stops 500Ws fixture`() {
        assertEquals(500.0, PowerScaleConverter.calculateWattSeconds(500, 100), 0.001)
        assertEquals(250.0, PowerScaleConverter.calculateWattSeconds(500, 90), 0.001)
        assertEquals(125.0, PowerScaleConverter.calculateWattSeconds(500, 80), 0.001)
        assertEquals(62.5, PowerScaleConverter.calculateWattSeconds(500, 70), 0.001)
        assertEquals(31.25, PowerScaleConverter.calculateWattSeconds(500, 60), 0.001)
        assertEquals(0.9765625, PowerScaleConverter.calculateWattSeconds(500, 10), 0.001)
    }

    @Test
    fun `calculateWattSeconds other fixture ratings`() {
        // B10X (250 Ws)
        assertEquals(250.0, PowerScaleConverter.calculateWattSeconds(250, 100), 0.001)
        assertEquals(31.25, PowerScaleConverter.calculateWattSeconds(250, 70), 0.001)

        // A2 (100 Ws)
        assertEquals(100.0, PowerScaleConverter.calculateWattSeconds(100, 100), 0.001)
        assertEquals(12.5, PowerScaleConverter.calculateWattSeconds(100, 70), 0.001)
        assertEquals(0.1953125, PowerScaleConverter.calculateWattSeconds(100, 10), 0.001)

        // Pro-11 (2400 Ws)
        assertEquals(2400.0, PowerScaleConverter.calculateWattSeconds(2400, 100), 0.001)
        assertEquals(1200.0, PowerScaleConverter.calculateWattSeconds(2400, 90), 0.001)
    }

    @Test
    fun `calculateWattSeconds Connect Pro floor 0_1 and monotonicity`() {
        val ws0_1 = PowerScaleConverter.calculateWattSeconds(500, 1)
        assertEquals(0.523, ws0_1, 0.001)

        for (k in 1 until 100) {
            val lower = PowerScaleConverter.calculateWattSeconds(500, k)
            val higher = PowerScaleConverter.calculateWattSeconds(500, k + 1)
            assertTrue("Expected strictly increasing at k=$k: $lower < $higher", lower < higher)
        }
    }

    @Test
    fun `formatWattSeconds rounds accurately across power tiers`() {
        assertEquals("500 Ws", PowerScaleConverter.formatWattSeconds(500, 100))
        assertEquals("125 Ws", PowerScaleConverter.formatWattSeconds(500, 80))
        assertEquals("62.5 Ws", PowerScaleConverter.formatWattSeconds(500, 70))
        assertEquals("1.0 Ws", PowerScaleConverter.formatWattSeconds(500, 10))
        assertEquals("0.5 Ws", PowerScaleConverter.formatWattSeconds(500, 1))
    }

    @Test
    fun `formatPowerFraction formats full stops, intermediate tenths, and sub-1_0 fractions`() {
        // Full stops
        assertEquals("1/1", PowerScaleConverter.formatPowerFraction(100))
        assertEquals("1/2", PowerScaleConverter.formatPowerFraction(90))
        assertEquals("1/4", PowerScaleConverter.formatPowerFraction(80))
        assertEquals("1/8", PowerScaleConverter.formatPowerFraction(70))
        assertEquals("1/16", PowerScaleConverter.formatPowerFraction(60))
        assertEquals("1/32", PowerScaleConverter.formatPowerFraction(50))
        assertEquals("1/64", PowerScaleConverter.formatPowerFraction(40))
        assertEquals("1/128", PowerScaleConverter.formatPowerFraction(30))
        assertEquals("1/256", PowerScaleConverter.formatPowerFraction(20))
        assertEquals("1/512", PowerScaleConverter.formatPowerFraction(10))

        // Intermediate tenths
        assertEquals("1/4 + 0.3 EV", PowerScaleConverter.formatPowerFraction(83))
        assertEquals("1/8 + 0.5 EV", PowerScaleConverter.formatPowerFraction(75))
        assertEquals("1/2 + 0.7 EV", PowerScaleConverter.formatPowerFraction(97))

        // Sub 1.0 down to 0.1
        assertEquals("1/512 - 0.9 EV", PowerScaleConverter.formatPowerFraction(1))
        assertEquals("1/512 - 0.5 EV", PowerScaleConverter.formatPowerFraction(5))
    }

    @Test
    fun `formatDualPowerReadout handles maxWs present and null`() {
        assertEquals("7.0 (62.5 Ws · 1/8)", PowerScaleConverter.formatDualPowerReadout(70, 500))
        assertEquals("8.3 (154 Ws · 1/4 + 0.3 EV)", PowerScaleConverter.formatDualPowerReadout(83, 500))
        assertEquals("0.1 (0.5 Ws · 1/512 - 0.9 EV)", PowerScaleConverter.formatDualPowerReadout(1, 500))
        assertEquals("7.0 (1/8)", PowerScaleConverter.formatDualPowerReadout(70, null))
        assertEquals("8.3 (1/4 + 0.3 EV)", PowerScaleConverter.formatDualPowerReadout(83, null))
    }

    @Test
    fun `clamping and validation`() {
        assertEquals(0.523, PowerScaleConverter.calculateWattSeconds(500, -10), 0.001)
        assertEquals(500.0, PowerScaleConverter.calculateWattSeconds(500, 150), 0.001)
        assertThrows(IllegalArgumentException::class.java) {
            PowerScaleConverter.calculateWattSeconds(0, 50)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PowerScaleConverter.calculateWattSeconds(-500, 50)
        }
    }
}
