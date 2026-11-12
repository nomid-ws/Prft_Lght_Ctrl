package app.prft.lightctrl.e2e.tier2

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.e2e.harness.FakeBleGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 — Feature 10: Auto-Reconnect Pinned Kit Boundary Tests.
 *
 * Verifies:
 * 1. Unpinned device advertisement is completely ignored by auto-reconnect.
 * 2. Duplicate rapid scan advertisements do not trigger duplicate connection jobs.
 * 3. Disabling auto-reconnect toggle completely halts reconnect attempts.
 * 4. Explicit manual disconnect suppresses reconnect until rescan clears guard.
 * 5. Malformed, blank, and invalid MAC addresses handled gracefully.
 * 6. Rapid connect-disconnect cycles preserve orchestrator session integrity.
 */
class AutoReconnectPinnedKitBoundaryTest {

    @Test
    fun `test 1 Unpinned device advertisement ignored`() = runBlocking {
        val gateway = FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)
        val pinnedAddr = VirtualBenchFixtures.ADDR_B10X_PLUS
        val unpinnedAddr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.orchestrator.setDevicePinned(pinnedAddr, true)
        withTimeout(2000L) {
            while (!harness.orchestrator.dashboardState.value.pinnedKitMacs.contains(pinnedAddr)) {
                delay(20)
            }
        }

        // Emit advertisement for ONLY unpinned Connect Pro
        val unpinnedDev = VirtualBenchFixtures.createKit().first { it.address == unpinnedAddr }.copy(isSimulated = false)
        gateway.updateScannedDevices(listOf(unpinnedDev))
        delay(120)

        assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)
        assertTrue(harness.orchestrator.dashboardState.value.connectedUnits.isEmpty())
    }

    @Test
    fun `test 2 Duplicate scan advertisements do not trigger duplicate connection jobs`() = runBlocking {
        val gateway = FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS
        val dev = VirtualBenchFixtures.createKit().first { it.address == addr }.copy(isSimulated = false)

        harness.orchestrator.setDevicePinned(addr, true)
        withTimeout(2000L) {
            while (!harness.orchestrator.dashboardState.value.pinnedKitMacs.contains(addr)) {
                delay(20)
            }
        }

        // Emit identical advertisement 5 times in rapid succession
        repeat(5) {
            gateway.updateScannedDevices(listOf(dev))
            delay(10)
        }

        harness.awaitDeviceReady(addr, timeoutMs = 3000L)

        val units = harness.orchestrator.dashboardState.value.connectedUnits
        assertEquals(1, units.size)
        assertEquals(1, harness.orchestrator.dashboardState.value.activeReadyCount)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 3 Auto-reconnect disabled toggle completely ignores pinned device scan`() = runBlocking {
        val gateway = FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS
        val dev = VirtualBenchFixtures.createKit().first { it.address == addr }.copy(isSimulated = false)

        harness.orchestrator.setDevicePinned(addr, true)
        harness.orchestrator.setAutoReconnectPinnedEnabled(false)
        withTimeout(2000L) {
            while (harness.orchestrator.dashboardState.value.isAutoReconnectPinnedEnabled) {
                delay(20)
            }
        }

        gateway.updateScannedDevices(listOf(dev))
        delay(150)

        // Must not connect
        assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)
        assertTrue(harness.orchestrator.dashboardState.value.connectedUnits.isEmpty())

        // Re-enable toggle
        harness.orchestrator.setAutoReconnectPinnedEnabled(true)
        harness.awaitDeviceReady(addr, timeoutMs = 3000L)

        assertEquals(1, harness.orchestrator.dashboardState.value.activeReadyCount)
        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 4 Manual disconnect isolation prevents reconnect loop and clears on startScan`() = runBlocking {
        val gateway = FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS
        val dev = VirtualBenchFixtures.createKit().first { it.address == addr }.copy(isSimulated = false)

        harness.orchestrator.setDevicePinned(addr, true)
        gateway.updateScannedDevices(listOf(dev))
        harness.awaitDeviceReady(addr, timeoutMs = 3000L)
        assertEquals(1, harness.orchestrator.dashboardState.value.activeReadyCount)

        // Explicit manual disconnect
        harness.orchestrator.disconnectDevice(addr)
        delay(100)
        assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)

        // Device remains in scanned list, but manual disconnect prevents reconnect
        gateway.updateScannedDevices(listOf(dev))
        delay(150)
        assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)

        // Calling startScan clears manuallyDisconnectedMacs and allows reconnection upon scan advertisement
        harness.orchestrator.startScan()
        gateway.updateScannedDevices(emptyList())
        delay(20)
        gateway.updateScannedDevices(listOf(dev))
        harness.awaitDeviceReady(addr, timeoutMs = 3000L)

        assertEquals(1, harness.orchestrator.dashboardState.value.activeReadyCount)
        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 5 Malformed and blank MAC address handling`() = runBlocking {
        val store = StudioPreferencesStore(context = null)

        // Blank and whitespace-only addresses must be ignored
        store.setDevicePinned("", true)
        store.setDevicePinned("   \t  ", true)
        assertTrue("Blank addresses must not be pinned", store.pinnedKitMacs.value.isEmpty())

        // Non-standard address string normalized safely
        store.setDevicePinned("invalid:mac:addr", true)
        assertEquals(setOf("INVALID:MAC:ADDR"), store.pinnedKitMacs.value)

        // Clean unpin
        store.setDevicePinned("INVALID:MAC:ADDR", false)
        assertTrue(store.pinnedKitMacs.value.isEmpty())
    }

    @Test
    fun `test 6 Rapid connect-disconnect cycles preserve orchestrator session integrity`() = runBlocking {
        val gateway = FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS
        val dev = VirtualBenchFixtures.createKit().first { it.address == addr }.copy(isSimulated = false)

        harness.orchestrator.setDevicePinned(addr, true)

        for (cycle in 1..2) {
            harness.orchestrator.startScan()
            gateway.updateScannedDevices(emptyList())
            delay(10)
            gateway.updateScannedDevices(listOf(dev))
            harness.awaitDeviceReady(addr, timeoutMs = 3000L)
            assertEquals(1, harness.orchestrator.dashboardState.value.activeReadyCount)

            harness.orchestrator.disconnectDevice(addr)
            delay(60)
            assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)
        }

        // Final connection
        harness.orchestrator.startScan()
        gateway.updateScannedDevices(emptyList())
        delay(10)
        gateway.updateScannedDevices(listOf(dev))
        harness.awaitDeviceReady(addr, timeoutMs = 3000L)
        assertEquals(1, harness.orchestrator.dashboardState.value.activeReadyCount)

        harness.disconnectDevice(addr)
    }
}
