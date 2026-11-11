package app.prft.lightctrl.domain

import app.prft.lightctrl.ble.BleAdapterGateway
import app.prft.lightctrl.ble.BlePeerLink
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.DeviceArchetype
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioOrchestratorTest {

    private class FakeBleGateway : BleAdapterGateway {
        override val isBluetoothAvailable: Boolean = true
        override val isBluetoothEnabled: StateFlow<Boolean> = MutableStateFlow(true)
        override val scannedDevices: StateFlow<List<DiscoveredProfotoDevice>> =
            MutableStateFlow(VirtualBenchFixtures.createKit())
        override val isScanning: StateFlow<Boolean> = MutableStateFlow(false)

        override fun refreshAdapterState() = Unit
        override fun startScan() = Unit
        override fun stopScan() = Unit
        override fun openPeerLink(device: DiscoveredProfotoDevice): BlePeerLink =
            VirtualBenchFixtures.createPeerLink(device, fastTimings = true)
    }

    @Test
    fun `starts strictly disconnected and hydrates B10X Plus, A2, Connect Pro with channel coexistence`() = runBlocking {
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = FakeBleGateway(),
            groupPacingMs = 5L,
        )

        // Verify zero fake hardware state on startup
        assertTrue(orchestrator.dashboardState.value.connectedUnits.isEmpty())
        assertFalse(orchestrator.dashboardState.value.benchSimulatorEnabled)

        val kit = VirtualBenchFixtures.createKit()
        val b10xPlus = kit.first { it.address == VirtualBenchFixtures.ADDR_B10X_PLUS }
        val a2 = kit.first { it.address == VirtualBenchFixtures.ADDR_A2 }
        val connectPro = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        orchestrator.connectDevice(b10xPlus, fastSimTimings = true)
        orchestrator.connectDevice(a2, fastSimTimings = true)

        // Wait briefly for 10-stage FSM + AllSettingsV2 TLV hydration
        var attempts = 0
        while (
            (orchestrator.dashboardState.value.activeReadyCount < 2 ||
                orchestrator.dashboardState.value.connectedUnits.none { it.address == b10xPlus.address && it.chargerPluggedIn }) &&
            attempts < 60
        ) {
            delay(25)
            attempts++
        }
        assertEquals(2, orchestrator.dashboardState.value.activeReadyCount)

        val unitsBeforeRemote = orchestrator.dashboardState.value.connectedUnits
        val hydratedB10x = unitsBeforeRemote.first { it.address == b10xPlus.address }
        val hydratedA2 = unitsBeforeRemote.first { it.address == a2.address }
        assertEquals(143, hydratedB10x.productId)
        assertEquals(36, hydratedA2.productId)
        assertTrue(hydratedB10x.chargerPluggedIn)
        assertFalse(hydratedB10x.isSharedWithActiveRemoteChannel)

        // Paced standalone group trim (+1.0 stop = +10 tenths) clamps B10X (80->90) and A2 (65->75)
        orchestrator.trimStudioGroupFixtures(AirGroup.ALL, 10)
        delay(80)
        assertEquals(90, orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xPlus.address }.flashPowerTenthStops)
        assertEquals(75, orchestrator.dashboardState.value.connectedUnits.first { it.address == a2.address }.flashPowerTenthStops)

        // Connect Connect Pro on same channel 1 -> coexistence badge activates on B10X Plus & A2
        orchestrator.connectDevice(connectPro, fastSimTimings = true)
        attempts = 0
        while (orchestrator.dashboardState.value.activeReadyCount < 3 && attempts < 40) {
            delay(25)
            attempts++
        }
        val afterRemote = orchestrator.dashboardState.value.connectedUnits
        val cpUnit = afterRemote.first { it.address == connectPro.address }
        assertEquals(DeviceArchetype.CONNECT_PRO_REMOTE, cpUnit.archetype)
        assertEquals("Profoto Connect Pro (Nikon)", cpUnit.hardwareModelName)
        assertTrue(afterRemote.first { it.address == b10xPlus.address }.isSharedWithActiveRemoteChannel)
        assertTrue(afterRemote.first { it.address == a2.address }.isSharedWithActiveRemoteChannel)

        // Assign equipment nickname: primary label updates while Hardware Model stays permanently visible
        orchestrator.renameUnitAlias(connectPro.address, "Main Nikon Trigger")
        attempts = 0
        while (!orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }.hasNickname && attempts < 20) {
            delay(15)
            attempts++
        }
        val renamedCp = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }
        assertTrue(renamedCp.hasNickname)
        assertEquals("Main Nikon Trigger", renamedCp.displayTitle)
        assertEquals("Profoto Connect Pro (Nikon)", renamedCp.hardwareModelName)

        // Verify buttonless Profoto Connect (Nikon, 151) supports Group Modeling LED (Opcode 138) & Group Standby (Opcode 137)
        val connectTrigger = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT }
        orchestrator.disconnectDevice(connectPro.address)
        orchestrator.connectDevice(connectTrigger, fastSimTimings = true)
        attempts = 0
        while (
            orchestrator.dashboardState.value.connectedUnits.none { it.address == connectTrigger.address && it.isReady } &&
            attempts < 40
        ) {
            delay(25)
            attempts++
        }
        orchestrator.toggleConnectProGroupModeling(connectTrigger.address, 0) // Group A LED ON (bit 0)
        delay(30)
        val cnUnit = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectTrigger.address }
        assertEquals(1, cnUnit.airModelLightMask and 0x01)
        assertTrue(orchestrator.dashboardState.value.groupChannels[0].isModelingEnabled)
    }
}
