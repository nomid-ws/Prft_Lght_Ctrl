package app.prft.lightctrl.domain

import app.prft.lightctrl.ble.BleAdapterGateway
import app.prft.lightctrl.ble.BlePeerLink
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.BatteryChemistry
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.MonolightZoomMode
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun `connectPro Auto and Manual mode parity synchronizes tri-state`() = runBlocking {
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = FakeBleGateway(),
            groupPacingMs = 5L,
        )
        val kit = VirtualBenchFixtures.createKit()
        val connectPro = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        orchestrator.connectDevice(connectPro, fastSimTimings = true)
        var attempts = 0
        while (orchestrator.dashboardState.value.activeReadyCount < 1 && attempts < 40) {
            delay(25)
            attempts++
        }
        assertEquals(1, orchestrator.dashboardState.value.activeReadyCount)

        // Switch to AUTO_TTL
        orchestrator.setConnectOperatingMode(connectPro.address, RemoteOperatingMode.AUTO_TTL)
        delay(50)
        var cp = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }
        assertEquals(RemoteOperatingMode.AUTO_TTL, cp.remoteMode)
        assertTrue(cp.ttlAutoEnabled)
        assertEquals(FlashMode.TTL, cp.flashMode)

        // Switch to MANUAL
        orchestrator.setConnectOperatingMode(connectPro.address, RemoteOperatingMode.MANUAL)
        delay(50)
        cp = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }
        assertEquals(RemoteOperatingMode.MANUAL, cp.remoteMode)
        assertFalse(cp.ttlAutoEnabled)
        assertEquals(FlashMode.MANUAL, cp.flashMode)

        // Switch via setRemoteFlashMode to TTL
        orchestrator.setRemoteFlashMode(connectPro.address, FlashMode.TTL)
        delay(50)
        cp = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }
        assertEquals(RemoteOperatingMode.AUTO_TTL, cp.remoteMode)
        assertTrue(cp.ttlAutoEnabled)
        assertEquals(FlashMode.TTL, cp.flashMode)
    }

    @Test
    fun `connect collar mode guard blocks software mode write and updates banner`() = runBlocking {
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = FakeBleGateway(),
            groupPacingMs = 5L,
        )
        val kit = VirtualBenchFixtures.createKit()
        val connect = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT }

        orchestrator.connectDevice(connect, fastSimTimings = true)
        var attempts = 0
        while (orchestrator.dashboardState.value.activeReadyCount < 1 && attempts < 40) {
            delay(25)
            attempts++
        }

        val testModes = listOf(RemoteOperatingMode.OFF, RemoteOperatingMode.MANUAL, RemoteOperatingMode.AUTO_TTL)
        for (mode in testModes) {
            orchestrator.clearStatusBanner()
            orchestrator.setConnectOperatingMode(connect.address, mode)
            var bannerAttempts = 0
            while (orchestrator.dashboardState.value.statusBanner == null && bannerAttempts < 20) {
                delay(15)
                bannerAttempts++
            }
            val banner = orchestrator.dashboardState.value.statusBanner
            assertNotNull("Status banner must be set when rejecting mode $mode", banner)
            assertTrue("Status banner must indicate physical collar control", banner!!.contains("physically controlled via collar switch"))
            val unit = orchestrator.dashboardState.value.connectedUnits.first { it.address == connect.address }
            assertEquals("Unit mode must remain AUTO_TTL", RemoteOperatingMode.AUTO_TTL, unit.remoteMode)
        }
    }

    @Test
    fun `group ttl ev bias supports groups A B C and ALL, rejects D`() = runBlocking {
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = FakeBleGateway(),
            groupPacingMs = 5L,
        )
        val kit = VirtualBenchFixtures.createKit()
        val connectPro = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        orchestrator.connectDevice(connectPro, fastSimTimings = true)
        var attempts = 0
        while (orchestrator.dashboardState.value.activeReadyCount < 1 && attempts < 40) {
            delay(25)
            attempts++
        }

        // Group A: +1.5 EV (+15 tenths)
        orchestrator.setGroupTtlEvBias(connectPro.address, AirGroup.GROUP_A, 15)
        delay(50)
        var cp = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }
        assertEquals(15, cp.groupTtlBiasesTenthStops[AirGroup.GROUP_A])
        assertEquals(15, cp.ttlEvCompTenthStops)

        // Group B: -1.0 EV (-10 tenths)
        orchestrator.setGroupTtlEvBias(connectPro.address, AirGroup.GROUP_B, -10)
        delay(50)
        cp = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }
        assertEquals(-10, cp.groupTtlBiasesTenthStops[AirGroup.GROUP_B])
        assertEquals(15, cp.ttlEvCompTenthStops) // Master unchanged

        // Group D: invalid, should be rejected
        orchestrator.setGroupTtlEvBias(connectPro.address, AirGroup.GROUP_D, 5)
        var bannerAttempts = 0
        while (orchestrator.dashboardState.value.statusBanner == null && bannerAttempts < 20) {
            delay(15)
            bannerAttempts++
        }
        val banner = orchestrator.dashboardState.value.statusBanner
        assertNotNull(banner)
        assertTrue(banner!!.contains("TTL EV bias only supported on Groups A..C and ALL"))
    }

    @Test
    fun `remote power floor 0_1 accepts values 1 to 100 on Connect Pro and dashboard`() = runBlocking {
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = FakeBleGateway(),
            groupPacingMs = 5L,
        )
        val kit = VirtualBenchFixtures.createKit()
        val connectPro = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        orchestrator.connectDevice(connectPro, fastSimTimings = true)
        var attempts = 0
        while (orchestrator.dashboardState.value.activeReadyCount < 1 && attempts < 40) {
            delay(25)
            attempts++
        }

        // Set group A power to 1 (0.1 f-stop) via setConnectProGroupPower
        orchestrator.setConnectProGroupPower(connectPro.address, 0, 1)
        delay(50)
        var cp = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }
        assertEquals(1, cp.groupPowersTenthStops[0])

        // Set group A power to 1 (0.1 f-stop) via setStudioGroupPowerTenthStops
        orchestrator.setStudioGroupPowerTenthStops(AirGroup.GROUP_A, 1)
        delay(50)
        cp = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }
        assertEquals(1, cp.groupPowersTenthStops[0])
        assertEquals(1, orchestrator.dashboardState.value.groupChannels[0].powerTenthStops)
    }

    @Test
    fun `remote settings dispatch sync af rf sleep brightness battery sounds`() = runBlocking {
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = FakeBleGateway(),
            groupPacingMs = 5L,
        )
        val kit = VirtualBenchFixtures.createKit()
        val connectPro = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        orchestrator.connectDevice(connectPro, fastSimTimings = true)
        var attempts = 0
        while (orchestrator.dashboardState.value.activeReadyCount < 1 && attempts < 40) {
            delay(25)
            attempts++
        }

        orchestrator.setRemoteSyncMode(connectPro.address, SyncTriggerMode.SECOND)
        orchestrator.setRemoteAfAssist(connectPro.address, AfAssistMode.ON)
        orchestrator.setRemoteRfPower(connectPro.address, RfPowerLevel.P2)
        orchestrator.setRemoteSleepTimeout(connectPro.address, 45)
        orchestrator.setRemoteBrightness(connectPro.address, 1)
        orchestrator.setRemoteBatteryType(connectPro.address, 2) // Alkaline
        orchestrator.setRemoteSounds(connectPro.address, false)

        delay(80)
        val cp = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }
        assertEquals(SyncTriggerMode.SECOND, cp.syncMode)
        assertEquals(AfAssistMode.ON, cp.afAssistMode)
        assertEquals(RfPowerLevel.P2, cp.rfPowerLevel)
        assertEquals(45, cp.autoOffMinutes)
        assertEquals(DisplayBrightnessLevel.MEDIUM, cp.displayBrightness)
        assertEquals(BatteryChemistry.ALKALINE, cp.batteryChemistry)
        assertFalse(cp.controlSoundsEnabled)
    }

    @Test
    fun `monolight zoom and ready signal mode dispatch`() = runBlocking {
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = FakeBleGateway(),
            groupPacingMs = 5L,
        )
        val kit = VirtualBenchFixtures.createKit()
        val b10x = kit.first { it.address == VirtualBenchFixtures.ADDR_B10X_PLUS }

        orchestrator.connectDevice(b10x, fastSimTimings = true)
        var attempts = 0
        while (orchestrator.dashboardState.value.activeReadyCount < 1 && attempts < 40) {
            delay(25)
            attempts++
        }

        // Monolight zoom
        orchestrator.setMonolightZoom(b10x.address, MonolightZoomMode.MANUAL, 25)
        delay(50)
        var unit = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10x.address }
        assertEquals(MonolightZoomMode.MANUAL, unit.zoomMode)
        assertEquals(25, unit.zoomValue)

        // Ready signal mode DIM
        orchestrator.setReadySignalMode(b10x.address, ReadySignalMode.DIM)
        delay(50)
        unit = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10x.address }
        assertEquals(ReadySignalMode.DIM, unit.readySignalMode)
        assertFalse(unit.readySoundEnabled)
        assertTrue(unit.dimDuringRecharge)

        // Ready signal mode SOUND_AND_DIM
        orchestrator.setReadySignalMode(b10x.address, ReadySignalMode.SOUND_AND_DIM)
        delay(50)
        unit = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10x.address }
        assertEquals(ReadySignalMode.SOUND_AND_DIM, unit.readySignalMode)
        assertTrue(unit.readySoundEnabled)
        assertTrue(unit.dimDuringRecharge)
    }

    @Test
    fun `applyScene recalls preset lighting setup across kit`() = runBlocking {
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = FakeBleGateway(),
            groupPacingMs = 5L,
        )
        val kit = VirtualBenchFixtures.createKit()
        val connectPro = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        orchestrator.connectDevice(connectPro, fastSimTimings = true)
        var attempts = 0
        while (orchestrator.dashboardState.value.activeReadyCount < 1 && attempts < 40) {
            delay(25)
            attempts++
        }

        val scene = StudioScene(
            name = "Studio Rembrandt",
            remoteChannel = 5,
            remoteOperatingMode = RemoteOperatingMode.MANUAL,
            groupPresets = listOf(
                GroupPreset(
                    group = AirGroup.GROUP_A,
                    powerTenthStops = 82,
                    isEnabled = true,
                    isModelingEnabled = true,
                    roleLabel = "Rembrandt Key",
                ),
                GroupPreset(
                    group = AirGroup.GROUP_B,
                    powerTenthStops = 54,
                    isEnabled = false,
                    isModelingEnabled = false,
                    roleLabel = "Shadow Fill",
                ),
            ),
        )

        orchestrator.applyScene(scene)
        delay(120)

        val cp = orchestrator.dashboardState.value.connectedUnits.first { it.address == connectPro.address }
        assertEquals(5, cp.airChannel)
        assertEquals(RemoteOperatingMode.MANUAL, cp.remoteMode)
        assertEquals(82, cp.groupPowersTenthStops[0])
        assertEquals(54, cp.groupPowersTenthStops[1])

        val roles = orchestrator.dashboardState.value.groupRoles
        assertEquals("Rembrandt Key", roles[AirGroup.GROUP_A])
        assertEquals("Shadow Fill", roles[AirGroup.GROUP_B])

        val banner = orchestrator.dashboardState.value.statusBanner
        assertNotNull(banner)
        assertTrue(banner!!.contains("Applied preset: Studio Rembrandt"))
    }

    @Test
    fun `auto reconnect pinned kit connects matching scanned device`() = runBlocking {
        val kit = VirtualBenchFixtures.createKit()
        val b10x = kit.first { it.address == VirtualBenchFixtures.ADDR_B10X_PLUS }
        val gateway = FakeBleGateway()

        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = gateway,
            groupPacingMs = 5L,
        )

        // Pin the B10X Plus MAC and verify auto-connect triggers on discovery
        orchestrator.setDevicePinned(b10x.address, true)
        orchestrator.setAutoReconnectPinnedEnabled(true)

        var attempts = 0
        while (orchestrator.dashboardState.value.activeReadyCount < 1 && attempts < 60) {
            delay(25)
            attempts++
        }
        assertEquals(1, orchestrator.dashboardState.value.activeReadyCount)
        val connected = orchestrator.dashboardState.value.connectedUnits.firstOrNull { it.address == b10x.address }
        assertNotNull(connected)
    }

    @Test
    fun `bench simulator guard blocks simulated devices in release mode`() = runBlocking {
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = FakeBleGateway(),
            groupPacingMs = 5L,
            allowSimulator = false, // Simulate release build (BuildConfig.DEBUG == false)
        )

        orchestrator.setBenchSimulatorEnabled(true)
        assertFalse(orchestrator.dashboardState.value.benchSimulatorEnabled)
        var bannerAttempts = 0
        while (orchestrator.dashboardState.value.statusBanner == null && bannerAttempts < 20) {
            delay(15)
            bannerAttempts++
        }
        val banner = orchestrator.dashboardState.value.statusBanner
        assertNotNull(banner)
        assertTrue(banner!!.contains("Bench Simulator is disabled in release builds"))
    }
}
