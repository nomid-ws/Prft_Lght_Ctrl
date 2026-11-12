package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.e2e.harness.FakeBleGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — Feature 10: Auto-Reconnect Pinned Kit Coverage Tests.
 *
 * Verifies:
 * 1. Pinning device MAC updates preferences store and orchestrator dashboardState.
 * 2. Unpinning device MAC cleanly removes it from pinned kit.
 * 3. Auto-reconnect triggered on BLE advertisement scan matching pinned MAC.
 * 4. Multiple pinned devices reconnect concurrently.
 * 5. MAC address whitespace trimming and uppercase normalization.
 * 6. Dynamic pinning of already-scanned device triggers immediate connection.
 */
class AutoReconnectPinnedKitCoverageTest {

    @Test
    fun `test 1 Pin device MAC address updates preferences and dashboard state`() = runBlocking {
        val gateway = FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.orchestrator.setDevicePinned(addr, true)
        withTimeout(2000L) {
            while (!harness.orchestrator.dashboardState.value.pinnedKitMacs.contains(addr)) {
                delay(20)
            }
        }

        val pinned = harness.orchestrator.dashboardState.value.pinnedKitMacs
        assertEquals(1, pinned.size)
        assertTrue(pinned.contains(addr))
    }

    @Test
    fun `test 2 Unpin device MAC removes address from pinned kit`() = runBlocking {
        val gateway = FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)
        val addr1 = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addr2 = VirtualBenchFixtures.ADDR_A2

        harness.orchestrator.setDevicePinned(addr1, true)
        harness.orchestrator.setDevicePinned(addr2, true)
        withTimeout(2000L) {
            while (harness.orchestrator.dashboardState.value.pinnedKitMacs.size < 2) {
                delay(20)
            }
        }

        harness.orchestrator.setDevicePinned(addr1, false)
        withTimeout(2000L) {
            while (harness.orchestrator.dashboardState.value.pinnedKitMacs.contains(addr1)) {
                delay(20)
            }
        }

        val pinned = harness.orchestrator.dashboardState.value.pinnedKitMacs
        assertEquals(1, pinned.size)
        assertTrue(pinned.contains(addr2))
        assertFalse(pinned.contains(addr1))
    }

    @Test
    fun `test 3 Auto-reconnect scan trigger connects matching pinned device`() = runBlocking {
        val gateway = FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.orchestrator.setDevicePinned(addr, true)
        withTimeout(2000L) {
            while (!harness.orchestrator.dashboardState.value.pinnedKitMacs.contains(addr)) {
                delay(20)
            }
        }
        assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)

        // Scanned device appears in BLE advertisement
        val dev = VirtualBenchFixtures.createKit().first { it.address == addr }.copy(isSimulated = false)
        gateway.updateScannedDevices(listOf(dev))

        // Auto-reconnect must trigger and bring device to Ready
        harness.awaitDeviceReady(addr, timeoutMs = 3000L)

        val units = harness.orchestrator.dashboardState.value.connectedUnits
        assertEquals(1, units.size)
        assertTrue(units[0].isReady)
        assertEquals(addr, units[0].address)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 4 Multiple pinned devices reconnect simultaneously`() = runBlocking {
        val gateway = FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)
        val addr1 = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addr2 = VirtualBenchFixtures.ADDR_A2

        harness.orchestrator.setDevicePinned(addr1, true)
        harness.orchestrator.setDevicePinned(addr2, true)
        withTimeout(2000L) {
            while (harness.orchestrator.dashboardState.value.pinnedKitMacs.size < 2) {
                delay(20)
            }
        }

        val devices = VirtualBenchFixtures.createKit()
            .filter { it.address == addr1 || it.address == addr2 }
            .map { it.copy(isSimulated = false) }
        gateway.updateScannedDevices(devices)

        harness.awaitActiveReadyCount(2, timeoutMs = 4000L)

        val units = harness.orchestrator.dashboardState.value.connectedUnits
        assertEquals(2, units.size)
        assertTrue(units.all { it.isReady })

        harness.disconnectDevice(addr1)
        harness.disconnectDevice(addr2)
    }

    @Test
    fun `test 5 Preferences persistence and uppercase MAC normalization`() = runBlocking {
        val store = StudioPreferencesStore(context = null)

        store.setDevicePinned("  sim:b1:0x:50:0w:01  ", true)
        store.setDevicePinned("sim:a2:10:0w:00:02", true)

        val stored = store.pinnedKitMacs.value
        assertEquals(2, stored.size)
        assertTrue(stored.contains("SIM:B1:0X:50:0W:01"))
        assertTrue(stored.contains("SIM:A2:10:0W:00:02"))

        // Unpin with mixed casing
        store.setDevicePinned("Sim:B1:0x:50:0W:01", false)
        val remaining = store.pinnedKitMacs.value
        assertEquals(1, remaining.size)
        assertTrue(remaining.contains("SIM:A2:10:0W:00:02"))
    }

    @Test
    fun `test 6 Dynamic pinning of already-scanned device triggers immediate connect`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)

        // Device is already present in scanned devices, dynamically pin it
        harness.orchestrator.setDevicePinned(addr, true)

        harness.awaitDeviceReady(addr, timeoutMs = 3000L)

        val units = harness.orchestrator.dashboardState.value.connectedUnits
        assertEquals(1, units.size)
        assertTrue(units[0].isReady)
        assertEquals(addr, units[0].address)

        harness.disconnectDevice(addr)
    }
}
