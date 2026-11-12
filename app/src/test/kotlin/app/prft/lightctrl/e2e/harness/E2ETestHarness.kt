package app.prft.lightctrl.e2e.harness

import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.StudioOrchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * End-to-end test harness orchestrating StudioOrchestrator with instant virtual timings
 * and fake BLE gateway.
 */
class E2ETestHarness(
    val fakeGateway: FakeBleGateway = FakeBleGateway(),
    val groupPacingMs: Long = 0L,
) {
    val orchestrator = StudioOrchestrator(
        appContext = null,
        bleGateway = fakeGateway,
        groupPacingMs = groupPacingMs,
    )

    val kit: List<DiscoveredProfotoDevice>
        get() = fakeGateway.scannedDevices.value

    fun getRecordingLink(address: String): RecordingBlePeerLink? =
        fakeGateway.getRecordingLink(address)

    suspend fun connectDevice(device: DiscoveredProfotoDevice, fastSimTimings: Boolean = true) {
        orchestrator.connectDevice(device.copy(isSimulated = false), fastSimTimings = fastSimTimings)
    }

    suspend fun connectByAddress(address: String) {
        val dev = kit.firstOrNull { it.address == address }
            ?: throw IllegalArgumentException("Device $address not found in kit")
        connectDevice(dev)
    }

    suspend fun awaitDeviceReady(address: String, timeoutMs: Long = 2000L) {
        withTimeout(timeoutMs) {
            while (orchestrator.dashboardState.value.connectedUnits.none { it.address == address && it.isReady }) {
                delay(15)
            }
            delay(40)
        }
    }

    suspend fun awaitActiveReadyCount(count: Int, timeoutMs: Long = 3000L) {
        withTimeout(timeoutMs) {
            while (orchestrator.dashboardState.value.activeReadyCount < count) {
                delay(20)
            }
        }
    }

    suspend fun disconnectDevice(address: String) {
        orchestrator.disconnectDevice(address)
    }
}
