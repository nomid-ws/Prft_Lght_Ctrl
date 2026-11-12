package app.prft.lightctrl.e2e.tier4

import app.prft.lightctrl.ble.BleConnectionStage
import app.prft.lightctrl.ble.ProfotoGattProfile
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.GroupPreset
import app.prft.lightctrl.domain.PowerScaleConverter
import app.prft.lightctrl.domain.StudioScene
import app.prft.lightctrl.domain.StudioSceneJsonCodec
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.e2e.harness.encodeFrame
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.MonolightZoomMode
import app.prft.lightctrl.protocol.PupConstants
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.PupSecurityQuarantineException
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Tier 4 — Real-World Studio Application Scenarios (Scenarios 1-8 per TEST_INFRA.md).
 *
 * Exercises multi-fixture end-to-end studio lighting workflows across:
 * :core:protocol, :core:ble, :core:domain, and :app.
 */
class RealWorldStudioScenariosTest {

    private fun findManifestFile(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
        )
        val file = candidates.firstOrNull { it.isFile && it.exists() }
        assertNotNull("AndroidManifest.xml must be found", file)
        return file!!
    }

    private suspend fun awaitCondition(
        timeoutMs: Long = 8000L,
        pollIntervalMs: Long = 10L,
        predicate: () -> Boolean,
    ) {
        withTimeout(timeoutMs) {
            while (!predicate()) {
                delay(pollIntervalMs)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 1: High-Key Commercial Studio Portrait Workflow
    // Features: F1, F2, F4, F7, F8, F9, F11
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 1 High-Key Commercial Studio Portrait Workflow`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addrA2 = VirtualBenchFixtures.ADDR_A2

        harness.connectByAddress(addrPro)
        harness.connectByAddress(addrB10)
        harness.connectByAddress(addrA2)
        harness.awaitDeviceReady(addrPro)
        harness.awaitDeviceReady(addrB10)
        harness.awaitDeviceReady(addrA2)
        harness.awaitActiveReadyCount(3)

        val linkPro = harness.getRecordingLink(addrPro)!!
        val linkB10 = harness.getRecordingLink(addrB10)!!
        val linkA2 = harness.getRecordingLink(addrA2)!!
        linkPro.clear()
        linkB10.clear()
        linkA2.clear()

        // 1. Configure Custom Group Roles (F8)
        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, "Key Octa")
        harness.orchestrator.setGroupRole(AirGroup.GROUP_B, "Fill Strip")
        harness.orchestrator.setGroupRole(AirGroup.GROUP_C, "Background Rim")

        // 2. Enable Dual Power Readout (F9) and Hardware Volume Key Flash (F11)
        harness.orchestrator.setDualPowerReadoutEnabled(true)
        harness.orchestrator.setVolumeKeyTestFlashEnabled(true)
        awaitCondition {
            val state = harness.orchestrator.dashboardState.value
            state.isDualPowerReadoutEnabled && state.isVolumeKeyTestFlashEnabled
        }

        // 3. Configure Remote Settings (F4)
        harness.orchestrator.setRemoteSyncMode(addrPro, SyncTriggerMode.AUTO_FP)
        harness.orchestrator.setRemoteAfAssist(addrPro, AfAssistMode.AUTO)
        harness.orchestrator.setRemoteRfPower(addrPro, RfPowerLevel.P4)
        harness.orchestrator.setAirChannel(addrPro, 1)
        awaitCondition {
            val unit = harness.orchestrator.dashboardState.value.connectedUnits.firstOrNull { it.address == addrPro }
            val frames = linkPro.decodeRecordedFrames()
            unit?.syncMode == SyncTriggerMode.AUTO_FP &&
                unit.afAssistMode == AfAssistMode.AUTO &&
                unit.rfPowerLevel == RfPowerLevel.P4 &&
                unit.airChannel == 1 &&
                frames.any { it.first.opcode == PupOpcodes.SYNC_MODE } &&
                frames.any { it.first.opcode == PupOpcodes.AF_ASSIST_MODE } &&
                frames.any { it.first.opcode == PupOpcodes.RMI_RF_POWER }
        }

        // 4. Remote Mode Parity (F1) & Per-Group TTL EV Bias (F2)
        harness.orchestrator.setConnectOperatingMode(addrPro, RemoteOperatingMode.AUTO_TTL)
        harness.orchestrator.setGroupTtlEvBias(addrPro, AirGroup.GROUP_A, 7)   // +0.7 EV
        harness.orchestrator.setGroupTtlEvBias(addrPro, AirGroup.GROUP_B, -10) // -1.0 EV
        awaitCondition {
            val state = harness.orchestrator.dashboardState.value
            val unit = state.connectedUnits.firstOrNull { it.address == addrPro }
            val frames = linkPro.decodeRecordedFrames()
            unit?.remoteMode == RemoteOperatingMode.AUTO_TTL &&
                state.groupChannels.getOrNull(0)?.ttlBiasTenthStops == 7 &&
                state.groupChannels.getOrNull(1)?.ttlBiasTenthStops == -10 &&
                frames.any { it.first.opcode == PupOpcodes.REMOTE_MODE } &&
                frames.any { it.first.opcode == PupOpcodes.AIR_PX_BIAS }
        }

        // 5. Save Studio Scene (F7)
        val highKeyScene = StudioScene(
            name = "Commercial High-Key Portrait",
            setupNotes = "White seamless background, 5ft Octa Key, 3ft Strip Fill",
            remoteChannel = 1,
            remoteOperatingMode = RemoteOperatingMode.AUTO_TTL,
            syncTriggerMode = SyncTriggerMode.AUTO_FP,
            afAssistMode = AfAssistMode.AUTO,
            rfPowerLevel = RfPowerLevel.P4,
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 80, roleLabel = "Key Octa", ttlEvBiasTenthStops = 7),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 65, roleLabel = "Fill Strip", ttlEvBiasTenthStops = -10),
            ),
        )
        harness.orchestrator.saveScene(highKeyScene)
        harness.orchestrator.applyScene(highKeyScene)
        awaitCondition {
            val state = harness.orchestrator.dashboardState.value
            state.groupRoles[AirGroup.GROUP_A] == "Key Octa" &&
                state.groupRoles[AirGroup.GROUP_B] == "Fill Strip" &&
                state.groupChannels.getOrNull(0)?.powerTenthStops == 80 &&
                state.groupChannels.getOrNull(1)?.powerTenthStops == 65
        }

        // 6. Trigger Test Flash via Hardware Key (F11)
        harness.orchestrator.triggerMasterTestFlash()
        awaitCondition {
            linkPro.decodeRecordedFrames().any { it.first.opcode == PupOpcodes.TEST_FLASH }
        }

        // 7. Wire Assertions on Connect Pro
        val proFrames = linkPro.decodeRecordedFrames()
        assertTrue("Connect Pro must receive op=140 SYNC_MODE", proFrames.any { it.first.opcode == PupOpcodes.SYNC_MODE })
        assertTrue("Connect Pro must receive op=160 AF_ASSIST_MODE", proFrames.any { it.first.opcode == PupOpcodes.AF_ASSIST_MODE })
        assertTrue("Connect Pro must receive op=147 RMI_RF_POWER", proFrames.any { it.first.opcode == PupOpcodes.RMI_RF_POWER })
        assertTrue("Connect Pro must receive op=144 REMOTE_MODE", proFrames.any { it.first.opcode == PupOpcodes.REMOTE_MODE })
        assertTrue("Connect Pro must receive op=131 AIR_PX_BIAS", proFrames.any { it.first.opcode == PupOpcodes.AIR_PX_BIAS })
        assertTrue("Connect Pro must receive op=45 TEST_FLASH", proFrames.any { it.first.opcode == PupOpcodes.TEST_FLASH })

        // 8. State Assertions
        val state = harness.orchestrator.dashboardState.value
        assertEquals("Key Octa", state.groupRoles[AirGroup.GROUP_A])
        assertEquals("Fill Strip", state.groupRoles[AirGroup.GROUP_B])
        assertTrue(state.isDualPowerReadoutEnabled)
        assertTrue(state.isVolumeKeyTestFlashEnabled)

        val channelA = state.groupChannels[0]
        assertEquals("Key Octa", channelA.roleLabel)
        assertEquals(7, channelA.ttlBiasTenthStops)
        val channelB = state.groupChannels[1]
        assertEquals("Fill Strip", channelB.roleLabel)
        assertEquals(-10, channelB.ttlBiasTenthStops)

        val unitPro = state.connectedUnits.first { it.address == addrPro }
        assertEquals(RemoteOperatingMode.AUTO_TTL, unitPro.remoteMode)
        assertTrue(unitPro.ttlAutoEnabled)
        assertEquals(SyncTriggerMode.AUTO_FP, unitPro.syncMode)
        assertEquals(AfAssistMode.AUTO, unitPro.afAssistMode)
        assertEquals(RfPowerLevel.P4, unitPro.rfPowerLevel)

        val unitB10 = state.connectedUnits.first { it.address == addrB10 }
        val unitA2 = state.connectedUnits.first { it.address == addrA2 }
        assertTrue(unitB10.isSharedWithActiveRemoteChannel)
        assertTrue(unitA2.isSharedWithActiveRemoteChannel)

        // Dual power readout checks
        assertEquals("8.0 (125 Ws · 1/4)", PowerScaleConverter.formatDualPowerReadout(channelA.powerTenthStops, 500))
        assertEquals("6.5 (8.8 Ws · 1/16 + 0.5 EV)", PowerScaleConverter.formatDualPowerReadout(channelB.powerTenthStops, 100))

        harness.disconnectDevice(addrPro)
        harness.disconnectDevice(addrB10)
        harness.disconnectDevice(addrA2)
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Low-Key Location Editorial Dual-Kit Shoot
    // Features: F1, F3, F7, F9, F10, F11
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 2 Low-Key Location Editorial Dual-Kit Shoot`() = runBlocking {
        val gateway = app.prft.lightctrl.e2e.harness.FakeBleGateway()
        val harness = E2ETestHarness(fakeGateway = gateway)
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val addrConnect = VirtualBenchFixtures.ADDR_CONNECT
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addrPro)
        harness.connectByAddress(addrConnect)
        harness.connectByAddress(addrB10)
        harness.awaitDeviceReady(addrPro)
        harness.awaitDeviceReady(addrConnect)
        harness.awaitDeviceReady(addrB10)
        harness.awaitActiveReadyCount(3)

        // 1. Auto-Reconnect Pinned Kit setup (F10)
        harness.orchestrator.setDevicePinned(addrPro, true)
        harness.orchestrator.setDevicePinned(addrConnect, true)
        harness.orchestrator.setDevicePinned(addrB10, true)
        harness.orchestrator.setAutoReconnectPinnedEnabled(true)
        harness.orchestrator.setDualPowerReadoutEnabled(true)

        val linkPro = harness.getRecordingLink(addrPro)!!
        val linkConnect = harness.getRecordingLink(addrConnect)!!
        linkPro.clear()
        linkConnect.clear()

        // 2. Mode Parity: Connect Pro MANUAL vs Connect Collar Switch (F1)
        harness.orchestrator.setConnectOperatingMode(addrPro, RemoteOperatingMode.MANUAL)
        harness.orchestrator.setConnectOperatingMode(addrConnect, RemoteOperatingMode.MANUAL)
        awaitCondition {
            val state = harness.orchestrator.dashboardState.value
            state.statusBanner == "Connect operating mode is physically controlled via collar switch" &&
                state.connectedUnits.firstOrNull { it.address == addrPro }?.remoteMode == RemoteOperatingMode.MANUAL
        }

        val stateAfterMode = harness.orchestrator.dashboardState.value
        assertEquals("Connect operating mode is physically controlled via collar switch", stateAfterMode.statusBanner)
        val unitConnect = stateAfterMode.connectedUnits.first { it.address == addrConnect }
        assertEquals(RemoteOperatingMode.AUTO_TTL, unitConnect.remoteMode) // Connect preserved in collar state

        // 3. Remote Power Floor 0.1 on Connect Pro (F3)
        harness.orchestrator.setConnectProGroupPower(addrPro, groupIndex = 0, tenthStops = 1) // 0.1 floor
        harness.orchestrator.setConnectProGroupPower(addrPro, groupIndex = 1, tenthStops = 5) // 0.5 floor
        awaitCondition {
            val frames = linkPro.decodeRecordedFrames()
            val frame = frames.lastOrNull { it.first.opcode == PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY }
            frame != null && frame.first.payload.size >= 2 && frame.first.payload[0] == 1.toByte() && frame.first.payload[1] == 5.toByte()
        }

        val proFrames = linkPro.decodeRecordedFrames()
        val floorFrame = proFrames.lastOrNull { it.first.opcode == PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY }
        assertNotNull("Connect Pro must receive op=177 AIR_ABSOLUTE_GROUP_ENERGY", floorFrame)
        assertEquals(1.toByte(), floorFrame!!.first.payload[0])
        assertEquals(5.toByte(), floorFrame.first.payload[1])

        // 4. Dual Power Readout at 0.1 Power Floor (F9)
        val readout0_1 = PowerScaleConverter.formatDualPowerReadout(1, 500)
        assertEquals("0.1 (0.5 Ws · 1/512 - 0.9 EV)", readout0_1)

        // 5. Studio Scene Save & Recall (F7)
        val lowKeyScene = StudioScene(
            name = "Moody Low-Key Editorial",
            remoteOperatingMode = RemoteOperatingMode.MANUAL,
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 1, roleLabel = "Key Low"),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 5, roleLabel = "Rim Accent"),
            ),
        )
        val json = StudioSceneJsonCodec.encodeToJson(listOf(lowKeyScene))
        val restoredScene = StudioSceneJsonCodec.decodeFromJson(json).first()
        harness.orchestrator.applyScene(restoredScene)
        awaitCondition {
            val state = harness.orchestrator.dashboardState.value
            state.groupRoles[AirGroup.GROUP_A] == "Key Low" &&
                state.groupRoles[AirGroup.GROUP_B] == "Rim Accent" &&
                state.groupChannels.getOrNull(0)?.powerTenthStops == 1 &&
                state.groupChannels.getOrNull(1)?.powerTenthStops == 5
        }

        // 6. Auto-Reconnect Recovery Test (F10)
        harness.orchestrator.disconnectDevice(addrPro)
        awaitCondition {
            harness.orchestrator.dashboardState.value.activeReadyCount == 2
        }
        assertEquals(2, harness.orchestrator.dashboardState.value.activeReadyCount)

        harness.orchestrator.startScan()
        val devPro = VirtualBenchFixtures.createKit().first { it.address == addrPro }.copy(isSimulated = false)
        gateway.updateScannedDevices(listOf(devPro))
        harness.awaitDeviceReady(addrPro, 3000L)

        // 7. Hardware Key Test Flash (F11)
        harness.orchestrator.setVolumeKeyTestFlashEnabled(true)
        awaitCondition {
            harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled
        }
        harness.orchestrator.triggerMasterTestFlash()

        val newProLink = harness.getRecordingLink(addrPro)!!
        awaitCondition {
            newProLink.decodeRecordedFrames().any { it.first.opcode == PupOpcodes.TEST_FLASH }
        }
        assertTrue("Connect Pro must receive op=45 TEST_FLASH", newProLink.decodeRecordedFrames().any { it.first.opcode == PupOpcodes.TEST_FLASH })

        harness.disconnectDevice(addrPro)
        harness.disconnectDevice(addrConnect)
        harness.disconnectDevice(addrB10)
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Fast TTL-to-Manual Handoff with Group Trims
    // Features: F1, F2, F3, F6, F9
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 3 Fast TTL-to-Manual Handoff with Group Trims`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addrPro)
        harness.awaitDeviceReady(addrPro)

        val link = harness.getRecordingLink(addrPro)!!

        // 1. Working Tree BLE Fixes (F6): Verify Auth handshake
        val authWrite = link.recordedWrites.firstOrNull { it.characteristicUuid == ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID }
        assertNotNull("Must authenticate via AUTH_CLIENT_ID_CHAR_UUID", authWrite)
        assertEquals(ProfotoGattProfile.AUTH_SERVICE_UUID, authWrite!!.serviceUuid)
        assertEquals("Profoto Client", authWrite.bytes.toString(Charsets.UTF_8))
        assertTrue(authWrite.withResponse)
        link.clear()

        // 2. Initial TTL metering phase (F1 & F2)
        harness.orchestrator.setConnectOperatingMode(addrPro, RemoteOperatingMode.AUTO_TTL)
        harness.orchestrator.setGroupTtlEvBias(addrPro, AirGroup.GROUP_A, 10)  // +1.0 EV
        harness.orchestrator.setGroupTtlEvBias(addrPro, AirGroup.GROUP_B, -15) // -1.5 EV
        awaitCondition {
            val frames = link.decodeRecordedFrames()
            val hasRemoteMode = frames.any { it.first.opcode == PupOpcodes.REMOTE_MODE }
            val biasCount = frames.count { it.first.opcode == PupOpcodes.AIR_PX_BIAS }
            val state = harness.orchestrator.dashboardState.value
            val unit = state.connectedUnits.firstOrNull { it.address == addrPro }
            hasRemoteMode && biasCount >= 2 && unit?.remoteMode == RemoteOperatingMode.AUTO_TTL
        }

        val ttlFrames = link.decodeRecordedFrames()
        assertTrue("Must send op=144 AUTO_TTL", ttlFrames.any { it.first.opcode == PupOpcodes.REMOTE_MODE })
        val biasFrames = ttlFrames.filter { it.first.opcode == PupOpcodes.AIR_PX_BIAS }
        assertTrue("Must send op=131 bias frames", biasFrames.size >= 2)

        // 3. Fast Handoff to MANUAL mode (F1)
        harness.orchestrator.setConnectOperatingMode(addrPro, RemoteOperatingMode.MANUAL)
        awaitCondition {
            val unit = harness.orchestrator.dashboardState.value.connectedUnits.firstOrNull { it.address == addrPro }
            unit?.remoteMode == RemoteOperatingMode.MANUAL && !unit.ttlAutoEnabled
        }

        val unitAfterManual = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrPro }
        assertEquals(RemoteOperatingMode.MANUAL, unitAfterManual.remoteMode)
        assertFalse("ttlAutoEnabled must be cleared upon manual handoff", unitAfterManual.ttlAutoEnabled)

        // 4. Remote Power Floor 0.1 and Group Trims in Manual (F3)
        harness.orchestrator.setConnectProGroupPower(addrPro, groupIndex = 2, tenthStops = 1) // Group C to 0.1 floor
        harness.orchestrator.stepRemoteAirGroupTrim(addrPro, AirGroup.GROUP_A, 3)              // Group A trim +0.3 EV
        awaitCondition {
            link.decodeRecordedFrames().any { it.first.opcode == PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY } &&
                harness.orchestrator.dashboardState.value.groupChannels.getOrNull(2)?.powerTenthStops == 1
        }

        val postManualFrames = link.decodeRecordedFrames()
        assertTrue("Must send op=177 for floor 0.1", postManualFrames.any { it.first.opcode == PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY })

        // 5. Dual Power Readout math verification (F9)
        val channelA = harness.orchestrator.dashboardState.value.groupChannels[0]
        val channelC = harness.orchestrator.dashboardState.value.groupChannels[2]
        assertEquals(1, channelC.powerTenthStops)
        assertEquals("0.1 (1/512 - 0.9 EV)", PowerScaleConverter.formatDualPowerReadout(channelC.powerTenthStops, maxWs = null))
        assertTrue(channelA.powerTenthStops > 0)

        harness.disconnectDevice(addrPro)
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Multi-Strobe Lighting Scene Migration via JSON
    // Features: F7, F8, F9, F10
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 4 Multi-Strobe Lighting Scene Migration via JSON`() = runBlocking {
        // Source harness builds and exports studio scene
        val sourceScene = StudioScene(
            name = "Commercial Multi-Strobe Migration Setup",
            setupNotes = "Key Beauty Dish camera right, Kicker camera left, Hair rim above",
            remoteChannel = 14,
            remoteOperatingMode = RemoteOperatingMode.MANUAL,
            syncTriggerMode = SyncTriggerMode.FIRST,
            afAssistMode = AfAssistMode.OFF,
            readySignalMode = ReadySignalMode.SOUND_AND_DIM,
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 85, roleLabel = "Key Beauty Dish", isEnabled = true, isModelingEnabled = true),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 60, roleLabel = "Kicker Softbox", isEnabled = true, isModelingEnabled = false),
                GroupPreset(group = AirGroup.GROUP_C, powerTenthStops = 40, roleLabel = "Hair Rim Light", isEnabled = true, isModelingEnabled = false),
                GroupPreset(group = AirGroup.GROUP_D, powerTenthStops = 10, roleLabel = "Background Grid", isEnabled = false, isModelingEnabled = false),
            ),
        )
        val json = StudioSceneJsonCodec.encodeToJson(listOf(sourceScene))
        assertTrue(json.contains("\"name\": \"Commercial Multi-Strobe Migration Setup\""))
        assertTrue(json.contains("\"roleLabel\": \"Key Beauty Dish\""))
        assertTrue(json.contains("\"remoteChannel\": 14"))

        // Target clean harness simulates secondary tablet/assistant device
        val targetHarness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addrA2 = VirtualBenchFixtures.ADDR_A2

        targetHarness.connectByAddress(addrPro)
        targetHarness.connectByAddress(addrB10)
        targetHarness.connectByAddress(addrA2)
        targetHarness.awaitDeviceReady(addrPro)
        targetHarness.awaitDeviceReady(addrB10)
        targetHarness.awaitDeviceReady(addrA2)
        targetHarness.awaitActiveReadyCount(3)

        targetHarness.orchestrator.setDualPowerReadoutEnabled(true)
        targetHarness.orchestrator.setDevicePinned(addrPro, true)
        targetHarness.orchestrator.setDevicePinned(addrB10, true)
        targetHarness.orchestrator.setDevicePinned(addrA2, true)

        val linkPro = targetHarness.getRecordingLink(addrPro)!!
        val linkB10 = targetHarness.getRecordingLink(addrB10)!!
        val linkA2 = targetHarness.getRecordingLink(addrA2)!!
        linkPro.clear()
        linkB10.clear()
        linkA2.clear()

        // Import and apply scene (F7 & F8)
        val imported = StudioSceneJsonCodec.decodeFromJson(json)
        targetHarness.orchestrator.importScenes(imported)
        targetHarness.orchestrator.applyScene(imported.first())
        try {
            awaitCondition(timeoutMs = 8000L) {
                linkPro.decodeRecordedFrames().any { it.first.opcode == PupOpcodes.AIR_CHANNEL } &&
                    linkB10.decodeRecordedFrames().any { it.first.opcode == PupOpcodes.AIR_CHANNEL } &&
                    linkA2.decodeRecordedFrames().any { it.first.opcode == PupOpcodes.AIR_CHANNEL }
            }
        } catch (e: Exception) {
            val proOps = linkPro.decodeRecordedFrames().map { it.first.opcode }
            val b10Ops = linkB10.decodeRecordedFrames().map { it.first.opcode }
            val a2Ops = linkA2.decodeRecordedFrames().map { it.first.opcode }
            val units = targetHarness.orchestrator.dashboardState.value.connectedUnits.map { "${it.address}: ready=${it.isReady}" }
            throw AssertionError(
                "DIAGNOSTIC FAILURE at line 456. proOps=$proOps, b10Ops=$b10Ops, a2Ops=$a2Ops, units=$units",
                e
            )
        }

        // Assert wire channel synchronization (AIR_CHANNEL = 11)
        val proChannelFrame = linkPro.decodeRecordedFrames().firstOrNull { it.first.opcode == PupOpcodes.AIR_CHANNEL }
        val b10ChannelFrame = linkB10.decodeRecordedFrames().firstOrNull { it.first.opcode == PupOpcodes.AIR_CHANNEL }
        val a2ChannelFrame = linkA2.decodeRecordedFrames().firstOrNull { it.first.opcode == PupOpcodes.AIR_CHANNEL }

        assertNotNull("Connect Pro receives channel frame", proChannelFrame)
        assertNotNull("B10X Plus receives channel frame", b10ChannelFrame)
        assertNotNull("A2 receives channel frame", a2ChannelFrame)
        assertEquals(14.toByte(), proChannelFrame!!.first.payload[0])
        assertEquals(14.toByte(), b10ChannelFrame!!.first.payload[0])
        assertEquals(14.toByte(), a2ChannelFrame!!.first.payload[0])

        // Assert state restoration
        awaitCondition {
            val state = targetHarness.orchestrator.dashboardState.value
            state.groupRoles[AirGroup.GROUP_A] == "Key Beauty Dish" &&
                state.groupRoles[AirGroup.GROUP_B] == "Kicker Softbox" &&
                state.groupRoles[AirGroup.GROUP_C] == "Hair Rim Light" &&
                state.groupRoles[AirGroup.GROUP_D] == "Background Grid" &&
                state.groupChannels.getOrNull(0)?.powerTenthStops == 85 &&
                state.groupChannels.getOrNull(1)?.powerTenthStops == 60 &&
                state.groupChannels.getOrNull(2)?.powerTenthStops == 40 &&
                state.groupChannels.getOrNull(3)?.powerTenthStops == 10
        }
        val state = targetHarness.orchestrator.dashboardState.value
        assertEquals("Key Beauty Dish", state.groupRoles[AirGroup.GROUP_A])
        assertEquals("Kicker Softbox", state.groupRoles[AirGroup.GROUP_B])
        assertEquals("Hair Rim Light", state.groupRoles[AirGroup.GROUP_C])
        assertEquals("Background Grid", state.groupRoles[AirGroup.GROUP_D])

        assertEquals(85, state.groupChannels[0].powerTenthStops)
        assertEquals(60, state.groupChannels[1].powerTenthStops)
        assertEquals(40, state.groupChannels[2].powerTenthStops)
        assertEquals(10, state.groupChannels[3].powerTenthStops)

        // Dual power readout math (F9)
        val readoutA = PowerScaleConverter.formatDualPowerReadout(state.groupChannels[0].powerTenthStops, 500)
        assertEquals("8.5 (177 Ws · 1/4 + 0.5 EV)", readoutA)
        val readoutB = PowerScaleConverter.formatDualPowerReadout(state.groupChannels[1].powerTenthStops, 100)
        assertEquals("6.0 (6.3 Ws · 1/16)", readoutB)

        targetHarness.disconnectDevice(addrPro)
        targetHarness.disconnectDevice(addrB10)
        targetHarness.disconnectDevice(addrA2)
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Monolight Zoom & Ready Signal Studio Tuning
    // Features: F4, F5, F8, F9
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 5 Monolight Zoom and Ready Signal Studio Tuning`() = runBlocking {
        val harness = E2ETestHarness()
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addrA2 = VirtualBenchFixtures.ADDR_A2
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addrB10)
        harness.connectByAddress(addrA2)
        harness.connectByAddress(addrPro)
        harness.awaitDeviceReady(addrB10)
        harness.awaitDeviceReady(addrA2)
        harness.awaitDeviceReady(addrPro)
        harness.awaitActiveReadyCount(3)

        val linkB10 = harness.getRecordingLink(addrB10)!!
        val linkA2 = harness.getRecordingLink(addrA2)!!
        val linkPro = harness.getRecordingLink(addrPro)!!
        linkB10.clear()
        linkA2.clear()
        linkPro.clear()

        // 1. Group Roles (F8) and Dual Power (F9)
        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, "Key Zoom Spot")
        harness.orchestrator.setGroupRole(AirGroup.GROUP_B, "Fill Wide")
        harness.orchestrator.setDualPowerReadoutEnabled(true)

        // 2. Monolight Zoom Tuning (F5): B10X Plus MANUAL pos 20, A2 AUTO
        harness.orchestrator.setMonolightZoom(addrB10, MonolightZoomMode.MANUAL, position = 20)
        harness.orchestrator.setMonolightZoom(addrA2, MonolightZoomMode.AUTO)
        awaitCondition {
            val b10Frames = linkB10.decodeRecordedFrames()
            val a2Frames = linkA2.decodeRecordedFrames()
            b10Frames.any { it.first.opcode == PupOpcodes.ZOOM_MODE } &&
                b10Frames.any { it.first.opcode == PupOpcodes.ZOOM_VALUE } &&
                a2Frames.any { it.first.opcode == PupOpcodes.ZOOM_MODE }
        }

        val b10Frames = linkB10.decodeRecordedFrames()
        val a2Frames = linkA2.decodeRecordedFrames()

        assertTrue("B10X Plus receives op=161 ZOOM_MODE", b10Frames.any { it.first.opcode == PupOpcodes.ZOOM_MODE })
        assertTrue("B10X Plus receives op=163 ZOOM_VALUE", b10Frames.any { it.first.opcode == PupOpcodes.ZOOM_VALUE })
        assertTrue("A2 receives op=161 ZOOM_MODE", a2Frames.any { it.first.opcode == PupOpcodes.ZOOM_MODE })

        // 3. Ready Signal Mode Studio Tuning (F5): B10X Plus SOUND_AND_DIM, A2 DIM
        harness.orchestrator.setReadySignalMode(addrB10, ReadySignalMode.SOUND_AND_DIM)
        harness.orchestrator.setReadySignalMode(addrA2, ReadySignalMode.DIM)
        awaitCondition {
            val b10FramesAfter = linkB10.decodeRecordedFrames()
            val a2FramesAfter = linkA2.decodeRecordedFrames()
            val state = harness.orchestrator.dashboardState.value
            val unitB10 = state.connectedUnits.firstOrNull { it.address == addrB10 }
            val unitA2 = state.connectedUnits.firstOrNull { it.address == addrA2 }
            b10FramesAfter.any { it.first.opcode == PupOpcodes.READY_SIGNAL_MODE } &&
                a2FramesAfter.any { it.first.opcode == PupOpcodes.READY_SIGNAL_MODE } &&
                unitB10?.readySignalMode == ReadySignalMode.SOUND_AND_DIM &&
                unitA2?.readySignalMode == ReadySignalMode.DIM
        }

        val b10FramesAfterReady = linkB10.decodeRecordedFrames()
        val a2FramesAfterReady = linkA2.decodeRecordedFrames()

        assertTrue("B10X Plus receives op=217 READY_SIGNAL_MODE", b10FramesAfterReady.any { it.first.opcode == PupOpcodes.READY_SIGNAL_MODE })
        assertTrue("A2 receives op=217 READY_SIGNAL_MODE", a2FramesAfterReady.any { it.first.opcode == PupOpcodes.READY_SIGNAL_MODE })

        val state = harness.orchestrator.dashboardState.value
        val unitB10 = state.connectedUnits.first { it.address == addrB10 }
        val unitA2 = state.connectedUnits.first { it.address == addrA2 }

        assertEquals(ReadySignalMode.SOUND_AND_DIM, unitB10.readySignalMode)
        assertTrue(unitB10.readySoundEnabled)
        assertTrue(unitB10.dimDuringRecharge)

        assertEquals(ReadySignalMode.DIM, unitA2.readySignalMode)
        assertFalse(unitA2.readySoundEnabled)
        assertTrue(unitA2.dimDuringRecharge)

        // 4. Remote Settings Console Tuning (F4) on Connect Pro
        harness.orchestrator.setRemoteSounds(addrPro, true)
        harness.orchestrator.setDisplayBrightness(addrPro, DisplayBrightnessLevel.HIGH)
        harness.orchestrator.setRemoteSleepTimeout(addrPro, 45)
        awaitCondition {
            val proFrames = linkPro.decodeRecordedFrames()
            proFrames.any { it.first.opcode == PupOpcodes.CONTROL_SOUNDS_ENABLE } &&
                proFrames.any { it.first.opcode == PupOpcodes.DISPLAY_BRIGHTNESS } &&
                proFrames.any { it.first.opcode == PupOpcodes.AUTO_STANDBY_TIMEOUT || it.first.opcode == PupOpcodes.AUTO_OFF_TIMEOUT }
        }

        val proFrames = linkPro.decodeRecordedFrames()
        assertTrue("Connect Pro receives op=38 CONTROL_SOUNDS_ENABLE", proFrames.any { it.first.opcode == PupOpcodes.CONTROL_SOUNDS_ENABLE })
        assertTrue("Connect Pro receives op=39 DISPLAY_BRIGHTNESS", proFrames.any { it.first.opcode == PupOpcodes.DISPLAY_BRIGHTNESS })
        assertTrue("Connect Pro receives op=40/41 sleep", proFrames.any { it.first.opcode == PupOpcodes.AUTO_STANDBY_TIMEOUT || it.first.opcode == PupOpcodes.AUTO_OFF_TIMEOUT })

        // Connect Pro must never receive monolight-only opcodes
        assertNull(proFrames.firstOrNull { it.first.opcode == PupOpcodes.READY_SIGNAL_MODE })
        assertNull(proFrames.firstOrNull { it.first.opcode == PupOpcodes.ZOOM_MODE })

        harness.disconnectDevice(addrB10)
        harness.disconnectDevice(addrA2)
        harness.disconnectDevice(addrPro)
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Remote Channel Coexistence & RF Power Tuning
    // Features: F4, F6, F10, F13
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 6 Remote Channel Coexistence and RF Power Tuning`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addrA2 = VirtualBenchFixtures.ADDR_A2

        harness.connectByAddress(addrPro)
        harness.connectByAddress(addrB10)
        harness.connectByAddress(addrA2)
        harness.awaitDeviceReady(addrPro)
        harness.awaitDeviceReady(addrB10)
        harness.awaitDeviceReady(addrA2)
        harness.awaitActiveReadyCount(3)

        // 1. Studio kit isolation via pinned MACs (F10)
        harness.orchestrator.setDevicePinned(addrPro, true)
        harness.orchestrator.setDevicePinned(addrB10, true)
        harness.orchestrator.setDevicePinned(addrA2, true)

        val linkPro = harness.getRecordingLink(addrPro)!!
        val linkB10 = harness.getRecordingLink(addrB10)!!
        val linkA2 = harness.getRecordingLink(addrA2)!!
        linkPro.clear()
        linkB10.clear()
        linkA2.clear()

        // 2. RF Power P2 tuning for rental bay coexistence (F4)
        harness.orchestrator.setRemoteRfPower(addrPro, RfPowerLevel.P2)
        awaitCondition {
            val proFrames = linkPro.decodeRecordedFrames()
            val unit = harness.orchestrator.dashboardState.value.connectedUnits.firstOrNull { it.address == addrPro }
            proFrames.any { it.first.opcode == PupOpcodes.RMI_RF_POWER } &&
                unit?.rfPowerLevel == RfPowerLevel.P2
        }

        val proFrames = linkPro.decodeRecordedFrames()
        val rfFrame = proFrames.firstOrNull { it.first.opcode == PupOpcodes.RMI_RF_POWER }
        assertNotNull("Connect Pro receives op=147 RMI_RF_POWER", rfFrame)
        assertEquals(RfPowerLevel.P2.code.toByte(), rfFrame!!.first.payload[0])
        assertEquals(RfPowerLevel.P2, harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrPro }.rfPowerLevel)

        // 3. Channel migration and coexistence verification
        // Connect Pro and B10X Plus set to channel 44, A2 left on mismatched channel 12
        harness.orchestrator.setAirChannel(addrPro, 44)
        harness.orchestrator.setAirChannel(addrB10, 44)
        harness.orchestrator.setAirChannel(addrA2, 12)
        awaitCondition {
            val state = harness.orchestrator.dashboardState.value
            val pro = state.connectedUnits.firstOrNull { it.address == addrPro }
            val b10 = state.connectedUnits.firstOrNull { it.address == addrB10 }
            val a2 = state.connectedUnits.firstOrNull { it.address == addrA2 }
            pro?.airChannel == 44 &&
                b10?.airChannel == 44 &&
                b10.isSharedWithActiveRemoteChannel &&
                a2?.airChannel == 12 &&
                !a2.isSharedWithActiveRemoteChannel
        }

        val stateMismatched = harness.orchestrator.dashboardState.value
        val b10Mismatched = stateMismatched.connectedUnits.first { it.address == addrB10 }
        val a2Mismatched = stateMismatched.connectedUnits.first { it.address == addrA2 }
        assertEquals(44, b10Mismatched.airChannel)
        assertTrue("B10X Plus is shared with active remote channel 44", b10Mismatched.isSharedWithActiveRemoteChannel)
        assertEquals(12, a2Mismatched.airChannel)
        assertFalse("A2 is on mismatched channel 12 and must NOT be marked shared", a2Mismatched.isSharedWithActiveRemoteChannel)

        // Now align A2 to channel 44
        harness.orchestrator.setAirChannel(addrA2, 44)
        awaitCondition {
            val state = harness.orchestrator.dashboardState.value
            val a2 = state.connectedUnits.firstOrNull { it.address == addrA2 }
            a2?.airChannel == 44 && a2.isSharedWithActiveRemoteChannel
        }

        val stateAligned = harness.orchestrator.dashboardState.value
        val a2Aligned = stateAligned.connectedUnits.first { it.address == addrA2 }
        assertEquals(44, a2Aligned.airChannel)
        assertTrue("A2 aligned to channel 44 is now shared with active remote", a2Aligned.isSharedWithActiveRemoteChannel)

        // 4. Working Tree BLE Fixes (F6) and Air-Gap Integrity (F13)
        // Verify Connect Pro link strictly operated on AUTH_SERVICE_UUID
        val proWrites = linkPro.recordedWrites
        assertTrue("All writes must target AUTH_SERVICE_UUID", proWrites.all { it.serviceUuid == ProfotoGattProfile.AUTH_SERVICE_UUID })

        harness.disconnectDevice(addrPro)
        harness.disconnectDevice(addrB10)
        harness.disconnectDevice(addrA2)
    }

    // -------------------------------------------------------------------------
    // Scenario 7: Eyes-Free Hardware Key Triggered Flash Test Rig
    // Features: F6, F11, F13
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 7 Eyes-Free Hardware Key Triggered Flash Test Rig`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addrA2 = VirtualBenchFixtures.ADDR_A2

        harness.connectByAddress(addrPro)
        harness.connectByAddress(addrB10)
        harness.connectByAddress(addrA2)
        harness.awaitDeviceReady(addrPro)
        harness.awaitDeviceReady(addrB10)
        harness.awaitDeviceReady(addrA2)
        harness.awaitActiveReadyCount(3)

        val linkPro = harness.getRecordingLink(addrPro)!!
        val linkB10 = harness.getRecordingLink(addrB10)!!
        val linkA2 = harness.getRecordingLink(addrA2)!!

        // Set remote and B10X Plus on channel 5, A2 standalone on channel 9
        harness.orchestrator.setAirChannel(addrPro, 5)
        harness.orchestrator.setAirChannel(addrB10, 5)
        harness.orchestrator.setAirChannel(addrA2, 9)
        awaitCondition {
            val state = harness.orchestrator.dashboardState.value
            val pro = state.connectedUnits.firstOrNull { it.address == addrPro }
            val b10 = state.connectedUnits.firstOrNull { it.address == addrB10 }
            val a2 = state.connectedUnits.firstOrNull { it.address == addrA2 }
            pro?.airChannel == 5 &&
                b10?.airChannel == 5 &&
                b10.isSharedWithActiveRemoteChannel &&
                a2?.airChannel == 9 &&
                !a2.isSharedWithActiveRemoteChannel
        }

        linkPro.clear()
        linkB10.clear()
        linkA2.clear()

        // 1. Initial State: Volume key flash disabled by default
        assertFalse(harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled)

        // 2. Enable Volume Key Test Flash (F11)
        harness.orchestrator.setVolumeKeyTestFlashEnabled(true)
        awaitCondition {
            harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled
        }
        assertTrue(harness.orchestrator.dashboardState.value.isVolumeKeyTestFlashEnabled)

        // 3. Trigger Master Test Flash (simulating KeyEvent volume press)
        harness.orchestrator.triggerMasterTestFlash()
        awaitCondition {
            linkPro.decodeRecordedFrames().any { it.first.opcode == PupOpcodes.TEST_FLASH } &&
                linkA2.decodeRecordedFrames().any { it.first.opcode == PupOpcodes.TEST_FLASH }
        }

        // 4. Verify Intelligent Routing:
        // Connect Pro covers channel 5: receives Opcode 45 TEST_FLASH
        val proFlash = linkPro.decodeRecordedFrames().firstOrNull { it.first.opcode == PupOpcodes.TEST_FLASH }
        assertNotNull("Connect Pro must receive op=45 TEST_FLASH to trigger channel 5", proFlash)

        // B10X Plus is on channel 5 (covered by remote): receives NO redundant direct BLE flash packet
        val b10Flash = linkB10.decodeRecordedFrames().firstOrNull { it.first.opcode == PupOpcodes.TEST_FLASH }
        assertNull("B10X Plus on channel 5 is covered by Connect Pro and must NOT receive direct BLE flash packet", b10Flash)

        // A2 is on channel 9 (not covered by remote): receives direct Opcode 45 TEST_FLASH
        val a2Flash = linkA2.decodeRecordedFrames().firstOrNull { it.first.opcode == PupOpcodes.TEST_FLASH }
        assertNotNull("A2 on channel 9 is standalone and must receive direct BLE op=45 TEST_FLASH", a2Flash)

        // 5. Disconnected Studio Robustness Check (F13)
        harness.disconnectDevice(addrPro)
        harness.disconnectDevice(addrB10)
        harness.disconnectDevice(addrA2)
        awaitCondition {
            harness.orchestrator.dashboardState.value.activeReadyCount == 0
        }

        assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)
        // Calling master test flash in empty studio must never crash or throw
        harness.orchestrator.triggerMasterTestFlash()
    }

    // -------------------------------------------------------------------------
    // Scenario 8: Air-Gap Security Audit & DFU Inviolability Rig
    // Features: F6, F13
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 8 Air-Gap Security Audit and DFU Inviolability Rig`() = runBlocking {
        // 1. AndroidManifest Air-Gap Inspection (F13)
        val manifest = findManifestFile().readText()
        assertFalse(
            "Air-gap violation: AndroidManifest must strictly exclude android.permission.INTERNET",
            manifest.contains("android.permission.INTERNET"),
        )
        assertFalse(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertFalse(manifest.contains("android.permission.CHANGE_NETWORK_STATE"))
        assertFalse(manifest.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(manifest.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(
            "BLUETOOTH_SCAN must include usesPermissionFlags=\"neverForLocation\"",
            manifest.contains("usesPermissionFlags=\"neverForLocation\"") ||
                manifest.contains("usesPermissionFlags=\n            \"neverForLocation\""),
        )

        // 2. Protocol Codec DFU Quarantine Inviolability (F13)
        for (dfuOpcode in PupConstants.QUARANTINED_OPCODES) {
            try {
                PupFrameCodec.encodeFrame(counter = 1, opcode = dfuOpcode)
                fail("Expected PupSecurityQuarantineException for quarantined opcode $dfuOpcode")
            } catch (e: PupSecurityQuarantineException) {
                assertTrue(e.message?.contains("QUARANTINE") == true)
            }

            try {
                PupFrameCodec.encodePacket(counter = 2, opcode = dfuOpcode)
                fail("Expected PupSecurityQuarantineException for encodePacket with opcode $dfuOpcode")
            } catch (e: PupSecurityQuarantineException) {
                assertTrue(e.message?.contains("QUARANTINE") == true)
            }
        }

        // 3. BLE GATT Profile DFU Quarantine Inviolability (F13)
        for (dfuUuid in ProfotoGattProfile.FORBIDDEN_DFU_CHAR_UUIDS) {
            try {
                ProfotoGattProfile.assertSafeCharacteristicUuid(dfuUuid)
                fail("Expected SecurityException for forbidden DFU characteristic UUID $dfuUuid")
            } catch (e: SecurityException) {
                assertTrue(e.message?.contains("CRITICAL DFU QUARANTINE VIOLATION") == true)
            }
        }

        // 4. Initial Studio State Starts DISCONNECTED (F13)
        val freshHarness = E2ETestHarness()
        assertEquals(0, freshHarness.orchestrator.dashboardState.value.connectedUnits.size)
        assertEquals(0, freshHarness.orchestrator.dashboardState.value.activeReadyCount)

        // 5. Connect Pro Working Tree BLE Fixes (F6) Service Resolution
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        freshHarness.connectByAddress(addrPro)
        freshHarness.awaitDeviceReady(addrPro)

        val linkPro = freshHarness.getRecordingLink(addrPro)!!
        val unit = freshHarness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrPro }
        assertTrue(unit.isReady)
        assertEquals(DeviceArchetype.CONNECT_PRO_REMOTE, unit.archetype)

        // Assert zero forbidden DFU service or characteristic interactions
        val writes = linkPro.recordedWrites
        assertTrue(writes.none { it.serviceUuid in ProfotoGattProfile.FORBIDDEN_DFU_SERVICE_UUIDS })
        assertTrue(writes.none { it.characteristicUuid in ProfotoGattProfile.FORBIDDEN_DFU_CHAR_UUIDS })

        freshHarness.disconnectDevice(addrPro)
    }
}
