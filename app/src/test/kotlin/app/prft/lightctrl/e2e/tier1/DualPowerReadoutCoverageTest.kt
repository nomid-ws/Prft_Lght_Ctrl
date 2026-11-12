package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.PowerScaleConverter
import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — Feature 9: Dual Power Readout Coverage Tests.
 *
 * Verifies:
 * 1. B10X Plus (500 Ws) power conversions across standard f-stop scales.
 * 2. A2 (100 Ws) power conversions across standard f-stop scales.
 * 3. Photographic power fractions formatting across full and fractional stops.
 * 4. StudioPreferencesStore dual power readout toggle enablement.
 * 5. Combined dual readout output string format (scale, Ws, fraction).
 * 6. Connected B10X Plus unit card dual readout integration.
 */
class DualPowerReadoutCoverageTest {

    @Test
    fun `test 1 B10X Plus 500Ws calculation at 10_0, 9_0, 8_0, 7_0, and 1_0`() {
        val maxWs = 500

        assertEquals(500.0, PowerScaleConverter.calculateWattSeconds(maxWs, 100), 0.001)
        assertEquals(250.0, PowerScaleConverter.calculateWattSeconds(maxWs, 90), 0.001)
        assertEquals(125.0, PowerScaleConverter.calculateWattSeconds(maxWs, 80), 0.001)
        assertEquals(62.5, PowerScaleConverter.calculateWattSeconds(maxWs, 70), 0.001)
        assertEquals(0.9765625, PowerScaleConverter.calculateWattSeconds(maxWs, 10), 0.0001)

        assertEquals("500 Ws", PowerScaleConverter.formatWattSeconds(maxWs, 100))
        assertEquals("250 Ws", PowerScaleConverter.formatWattSeconds(maxWs, 90))
        assertEquals("125 Ws", PowerScaleConverter.formatWattSeconds(maxWs, 80))
        assertEquals("62.5 Ws", PowerScaleConverter.formatWattSeconds(maxWs, 70))
        assertEquals("1.0 Ws", PowerScaleConverter.formatWattSeconds(maxWs, 10))
    }

    @Test
    fun `test 2 A2 100Ws calculation across power scale`() {
        val maxWs = 100

        assertEquals(100.0, PowerScaleConverter.calculateWattSeconds(maxWs, 100), 0.001)
        assertEquals(50.0, PowerScaleConverter.calculateWattSeconds(maxWs, 90), 0.001)
        assertEquals(12.5, PowerScaleConverter.calculateWattSeconds(maxWs, 70), 0.001)
        assertEquals(0.1953125, PowerScaleConverter.calculateWattSeconds(maxWs, 10), 0.0001)

        assertEquals("100 Ws", PowerScaleConverter.formatWattSeconds(maxWs, 100))
        assertEquals("50 Ws", PowerScaleConverter.formatWattSeconds(maxWs, 90))
        assertEquals("12.5 Ws", PowerScaleConverter.formatWattSeconds(maxWs, 70))
        assertEquals("0.2 Ws", PowerScaleConverter.formatWattSeconds(maxWs, 10))
    }

    @Test
    fun `test 3 Power fraction formatting across full stops and fractional tenths`() {
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

        // Fractional tenths
        assertEquals("1/4 + 0.3 EV", PowerScaleConverter.formatPowerFraction(83))
        assertEquals("1/8 + 0.5 EV", PowerScaleConverter.formatPowerFraction(75))
        assertEquals("1/2 + 0.7 EV", PowerScaleConverter.formatPowerFraction(97))
    }

    @Test
    fun `test 4 StudioPreferencesStore dual power readout toggle enablement`() = runBlocking {
        val store = StudioPreferencesStore(context = null)

        assertFalse(store.isDualPowerReadoutEnabled.value)

        store.setDualPowerReadoutEnabled(true)
        assertTrue(store.isDualPowerReadoutEnabled().first())

        store.setDualPowerReadoutEnabled(false)
        assertFalse(store.isDualPowerReadoutEnabled().first())
    }

    @Test
    fun `test 5 Dual string output format combining scale, Ws, and fractions`() {
        // With 500 Ws
        assertEquals("10.0 (500 Ws · 1/1)", PowerScaleConverter.formatDualPowerReadout(100, 500))
        assertEquals("9.0 (250 Ws · 1/2)", PowerScaleConverter.formatDualPowerReadout(90, 500))
        assertEquals("7.0 (62.5 Ws · 1/8)", PowerScaleConverter.formatDualPowerReadout(70, 500))
        assertEquals("8.3 (154 Ws · 1/4 + 0.3 EV)", PowerScaleConverter.formatDualPowerReadout(83, 500))
        assertEquals("1.0 (1.0 Ws · 1/512)", PowerScaleConverter.formatDualPowerReadout(10, 500))

        // With 100 Ws
        assertEquals("10.0 (100 Ws · 1/1)", PowerScaleConverter.formatDualPowerReadout(100, 100))
        assertEquals("7.0 (12.5 Ws · 1/8)", PowerScaleConverter.formatDualPowerReadout(70, 100))
        assertEquals("1.0 (0.2 Ws · 1/512)", PowerScaleConverter.formatDualPowerReadout(10, 100))
    }

    @Test
    fun `test 6 Connected B10X Plus unit card dual readout integration`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(500, unit.modelSpec.maxWattSeconds)

        harness.orchestrator.setDualPowerReadoutEnabled(true)
        delay(30)

        val formatted = PowerScaleConverter.formatDualPowerReadout(
            unit.flashPowerTenthStops,
            unit.modelSpec.maxWattSeconds,
        )
        // Unit default power is 80 (8.0 stops)
        assertEquals("8.0 (125 Ws · 1/4)", formatted)

        harness.disconnectDevice(addr)
    }
}
