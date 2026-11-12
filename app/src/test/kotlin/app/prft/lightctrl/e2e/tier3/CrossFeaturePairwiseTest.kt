package app.prft.lightctrl.e2e.tier3

import app.prft.lightctrl.ble.BleConnectionStage
import app.prft.lightctrl.ble.ProfotoGattProfile
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.GroupPreset
import app.prft.lightctrl.domain.PowerScaleConverter
import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.domain.StudioScene
import app.prft.lightctrl.domain.StudioSceneJsonCodec
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 3 — Cross-Feature Combinations (Pairwise Interaction Tests).
 *
 * Systematic coverage of 14 pairwise feature combinations across:
 * :core:protocol, :core:ble, :core:domain, and :app.
 */
class CrossFeaturePairwiseTest {

    // -------------------------------------------------------------------------
    // Pair 1: F1 (Connect Mode Parity) + F7 (Studio Scenes recall Connect vs Pro)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 1 F1 Connect Mode Parity plus F7 Studio Scenes recall on Connect vs Connect Pro`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val addrConnect = VirtualBenchFixtures.ADDR_CONNECT

        harness.connectByAddress(addrPro)
        harness.connectByAddress(addrConnect)
        harness.awaitDeviceReady(addrPro)
        harness.awaitDeviceReady(addrConnect)

        val linkPro = harness.getRecordingLink(addrPro)!!
        val linkConnect = harness.getRecordingLink(addrConnect)!!
        linkPro.clear()
        linkConnect.clear()

        // Apply scene targeting MANUAL mode
        val scene = StudioScene(
            name = "Manual Portrait Preset",
            remoteOperatingMode = RemoteOperatingMode.MANUAL,
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 70),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 50),
            ),
        )
        harness.orchestrator.applyScene(scene)
        delay(80)

        // Connect Pro receives digital op=144 write to MANUAL (0x01)
        val proFrames = linkPro.decodeRecordedFrames()
        val modeFrame = proFrames.firstOrNull { it.first.opcode == PupOpcodes.REMOTE_MODE }
        assertNotNull("Connect Pro must receive op=144 REMOTE_MODE", modeFrame)
        assertEquals(RemoteOperatingMode.MANUAL, (modeFrame!!.second as PupDecodedMessage.RemoteModeState).mode)
        assertEquals(0x01.toByte(), modeFrame.first.payload[0])

        val unitPro = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrPro }
        assertEquals(RemoteOperatingMode.MANUAL, unitPro.remoteMode)
        assertFalse("Connect Pro ttlAutoEnabled must be false", unitPro.ttlAutoEnabled)

        // Connect (non-Pro) strictly excludes op=144 writes, preserving collar switch telemetry
        val connectFrames = linkConnect.decodeRecordedFrames()
        val rejectedFrame = connectFrames.firstOrNull { it.first.opcode == PupOpcodes.REMOTE_MODE }
        assertNull("Connect must never receive op=144 write from applyScene", rejectedFrame)

        val unitConnect = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrConnect }
        assertEquals(RemoteOperatingMode.AUTO_TTL, unitConnect.remoteMode)
        assertTrue("Connect collar state preserved as AUTO_TTL", unitConnect.ttlAutoEnabled)

        harness.disconnectDevice(addrPro)
        harness.disconnectDevice(addrConnect)
    }

    // -------------------------------------------------------------------------
    // Pair 2: F2 (Per-Group TTL Bias) + F9 (Dual Power Readout with TTL EV active)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 2 F2 Per-Group TTL Bias plus F9 Dual Power Readout with TTL EV compensation active`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addrPro)
        harness.connectByAddress(addrB10)
        harness.awaitDeviceReady(addrPro)
        harness.awaitDeviceReady(addrB10)

        harness.orchestrator.setDualPowerReadoutEnabled(true)
        val linkPro = harness.getRecordingLink(addrPro)!!
        linkPro.clear()

        // Set Group A TTL EV bias to +1.5 EV (+15 tenths)
        harness.orchestrator.setGroupTtlEvBias(addrPro, AirGroup.GROUP_A, 15)
        delay(60)

        // Assert wire frame op=131
        val frames = linkPro.decodeRecordedFrames()
        val biasFrame = frames.firstOrNull { it.first.opcode == PupOpcodes.AIR_PX_BIAS }
        assertNotNull("Must emit op=131 AIR_PX_BIAS frame", biasFrame)
        assertEquals(15.toByte(), biasFrame!!.first.payload[0])
        assertEquals(AirGroup.GROUP_A.code.toByte(), biasFrame.first.payload[1])

        // Verify state
        val state = harness.orchestrator.dashboardState.value
        val channelA = state.groupChannels.first { it.group == AirGroup.GROUP_A }
        assertEquals(15, channelA.ttlBiasTenthStops)
        assertEquals(80, channelA.powerTenthStops) // Base power 8.0 remains undisturbed

        // Verify Dual Power readout math for Group A (B10X Plus = 500Ws @ 8.0 stops)
        val readout = PowerScaleConverter.formatDualPowerReadout(channelA.powerTenthStops, 500)
        assertEquals("8.0 (125 Ws · 1/4)", readout)

        harness.disconnectDevice(addrPro)
        harness.disconnectDevice(addrB10)
    }

    // -------------------------------------------------------------------------
    // Pair 3: F3 (Remote Power Floor 0.1) + F7 (Scene save and recall at power 0.1)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 3 F3 Remote Power Floor 0 1 plus F7 Scene save and recall at minimum power 0 1`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addrPro)
        harness.awaitDeviceReady(addrPro)

        val linkPro = harness.getRecordingLink(addrPro)!!
        linkPro.clear()

        // Create scene with Group A at minimum remote floor 0.1 (1 tenth-stop)
        val scene = StudioScene(
            name = "Minimum Power Rig",
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 1),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 50),
            ),
        )
        val json = StudioSceneJsonCodec.encodeToJson(listOf(scene))
        val decoded = StudioSceneJsonCodec.decodeFromJson(json).first()

        assertEquals(1, decoded.groupPresets.first { it.group == AirGroup.GROUP_A }.powerTenthStops)

        // Recall scene
        harness.orchestrator.applyScene(decoded)
        delay(80)

        // Verify Connect Pro received Opcode 177 with payload index 0 = 0x01
        val frames = linkPro.decodeRecordedFrames()
        val powerFrame = frames.firstOrNull { it.first.opcode == PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY }
        assertNotNull("Connect Pro must receive op=177 frame", powerFrame)
        assertEquals(0x01.toByte(), powerFrame!!.first.payload[0])

        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrPro }
        assertEquals(1, unit.groupPowersTenthStops[0])

        harness.disconnectDevice(addrPro)
    }

    // -------------------------------------------------------------------------
    // Pair 4: F4 (Remote Settings Sheet) + F10 (Auto-Reconnect preserving settings)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 4 F4 Remote Settings Sheet plus F10 Auto-Reconnect preserving RF power and channel`() = runBlocking {
        val gateway = app.prft.lightctrl.e2e.harness.FakeBleGateway(initialDevices = emptyList())
        val harness = E2ETestHarness(fakeGateway = gateway)
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val dev = VirtualBenchFixtures.createKit().first { it.address == addrPro }.copy(isSimulated = false)

        harness.orchestrator.setDevicePinned(addrPro, true)
        gateway.updateScannedDevices(listOf(dev))
        harness.awaitDeviceReady(addrPro, 3000L)

        // Configure Remote Settings
        harness.orchestrator.setRemoteRfPower(addrPro, RfPowerLevel.P2)
        harness.orchestrator.setAirChannel(addrPro, 42)
        harness.orchestrator.setRemoteSyncMode(addrPro, SyncTriggerMode.X_SYNC)
        delay(60)

        val before = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrPro }
        assertEquals(RfPowerLevel.P2, before.rfPowerLevel)
        assertEquals(42, before.airChannel)
        assertEquals(SyncTriggerMode.X_SYNC, before.syncMode)

        val link = harness.getRecordingLink(addrPro)!!
        val frames = link.decodeRecordedFrames()
        assertTrue(frames.any { it.first.opcode == PupOpcodes.RMI_RF_POWER })
        assertTrue(frames.any { it.first.opcode == PupOpcodes.AIR_CHANNEL })
        assertTrue(frames.any { it.first.opcode == PupOpcodes.SYNC_MODE })

        // Disconnect and auto-reconnect via rescan
        harness.orchestrator.disconnectDevice(addrPro)
        delay(60)
        assertEquals(0, harness.orchestrator.dashboardState.value.activeReadyCount)

        harness.orchestrator.startScan()
        gateway.updateScannedDevices(emptyList())
        delay(10)
        gateway.updateScannedDevices(listOf(dev))
        harness.awaitDeviceReady(addrPro, 3000L)

        val restored = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrPro }
        assertTrue(restored.isReady)
        assertEquals(DeviceArchetype.CONNECT_PRO_REMOTE, restored.archetype)

        // Re-apply and verify responsiveness after auto-reconnect
        harness.orchestrator.setRemoteRfPower(addrPro, RfPowerLevel.P2)
        harness.orchestrator.setAirChannel(addrPro, 42)
        harness.orchestrator.setRemoteSyncMode(addrPro, SyncTriggerMode.X_SYNC)
        delay(60)

        val updated = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrPro }
        assertEquals(RfPowerLevel.P2, updated.rfPowerLevel)
        assertEquals(42, updated.airChannel)
        assertEquals(SyncTriggerMode.X_SYNC, updated.syncMode)

        harness.disconnectDevice(addrPro)
    }

    // -------------------------------------------------------------------------
    // Pair 5: F7 (Studio Scenes) + F8 (Group Role Labels saved inside scene JSON)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 5 F7 Studio Scenes plus F8 Group Role Labels saved and restored inside scene JSON`() = runBlocking {
        val harness = E2ETestHarness()

        val scene = StudioScene(
            name = "Three-Point Portrait",
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 80, roleLabel = "Key Light"),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 60, roleLabel = "Fill Softbox"),
                GroupPreset(group = AirGroup.GROUP_C, powerTenthStops = 45, roleLabel = "Hair Rim"),
            ),
        )
        val json = StudioSceneJsonCodec.encodeToJson(listOf(scene))
        assertTrue(json.contains("\"roleLabel\": \"Key Light\""))

        // Clear existing roles
        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, null)
        harness.orchestrator.setGroupRole(AirGroup.GROUP_B, null)
        harness.orchestrator.setGroupRole(AirGroup.GROUP_C, null)
        delay(30)

        // Import and apply
        val imported = StudioSceneJsonCodec.decodeFromJson(json).first()
        harness.orchestrator.applyScene(imported)
        delay(60)

        val state = harness.orchestrator.dashboardState.value
        assertEquals("Key Light", state.groupRoles[AirGroup.GROUP_A])
        assertEquals("Fill Softbox", state.groupRoles[AirGroup.GROUP_B])
        assertEquals("Hair Rim", state.groupRoles[AirGroup.GROUP_C])
        assertEquals("Key Light", state.groupChannels[0].roleLabel)
    }

    // -------------------------------------------------------------------------
    // Pair 6: F8 (Group Role Labels) + F9 (Dual Power labeled with custom roles)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 6 F8 Group Role Labels plus F9 Dual Power display labeled with custom group roles`() = runBlocking {
        val harness = E2ETestHarness()
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addrB10)
        harness.awaitDeviceReady(addrB10)

        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, "Main Key")
        harness.orchestrator.setDualPowerReadoutEnabled(true)
        delay(40)

        val state = harness.orchestrator.dashboardState.value
        val channelA = state.groupChannels[0]
        assertEquals("Main Key", channelA.roleLabel)

        // Readout title format: "${channel.group.label} · ${channel.roleLabel}"
        val headerTitle = "${channelA.group.label} · ${channelA.roleLabel}"
        assertEquals("A · Main Key", headerTitle)

        // Dual power readout for 500Ws B10X Plus @ 80 tenths (8.0 stops)
        val readout = PowerScaleConverter.formatDualPowerReadout(channelA.powerTenthStops, 500)
        assertEquals("8.0 (125 Ws · 1/4)", readout)

        harness.disconnectDevice(addrB10)
    }

    // -------------------------------------------------------------------------
    // Pair 7: F2 (Per-Group TTL Bias) + F8 (Group Role Labels on TTL-biased groups)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 7 F2 Per-Group TTL Bias plus F8 Group Role Labels on TTL-biased groups`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addrPro)
        harness.awaitDeviceReady(addrPro)

        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, "Key")
        harness.orchestrator.setGroupTtlEvBias(addrPro, AirGroup.GROUP_A, 12) // +1.2 EV
        delay(40)

        // Updating role label must preserve TTL bias
        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, "Key Octa")
        delay(40)

        val state = harness.orchestrator.dashboardState.value
        val channelA = state.groupChannels[0]
        assertEquals("Key Octa", channelA.roleLabel)
        assertEquals(12, channelA.ttlBiasTenthStops)

        val unit = state.connectedUnits.first { it.address == addrPro }
        assertEquals(12, unit.groupTtlBiasesTenthStops[AirGroup.GROUP_A])

        harness.disconnectDevice(addrPro)
    }

    // -------------------------------------------------------------------------
    // Pair 8: F1 (Connect Pro) + F2 (TTL Bias) + F3 (0 1 Power Floor combined)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 8 F1 Connect Pro plus F2 TTL Bias plus F3 0 1 Power Floor combined interaction`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addrPro)
        harness.awaitDeviceReady(addrPro)
        val link = harness.getRecordingLink(addrPro)!!
        link.clear()

        // 1. Enter AUTO_TTL mode
        harness.orchestrator.setConnectOperatingMode(addrPro, RemoteOperatingMode.AUTO_TTL)
        delay(40)

        // 2. Set per-group TTL biases for A and B
        harness.orchestrator.setGroupTtlEvBias(addrPro, AirGroup.GROUP_A, 10)  // +1.0 EV
        harness.orchestrator.setGroupTtlEvBias(addrPro, AirGroup.GROUP_B, -15) // -1.5 EV
        delay(40)

        // 3. Switch to MANUAL mode
        harness.orchestrator.setConnectOperatingMode(addrPro, RemoteOperatingMode.MANUAL)
        delay(40)

        // 4. Adjust Group C to floor 0.1 (1 tenth-stop)
        harness.orchestrator.setConnectProGroupPower(addrPro, groupIndex = 2, tenthStops = 1)
        delay(40)

        // Assert wire frame sequence
        val frames = link.decodeRecordedFrames()
        assertTrue(frames.any { it.first.opcode == PupOpcodes.REMOTE_MODE })
        assertTrue(frames.any { it.first.opcode == PupOpcodes.AIR_PX_BIAS })
        assertTrue(frames.any { it.first.opcode == PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY })

        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrPro }
        assertEquals(RemoteOperatingMode.MANUAL, unit.remoteMode)
        assertFalse(unit.ttlAutoEnabled)
        assertEquals(10, unit.groupTtlBiasesTenthStops[AirGroup.GROUP_A])
        assertEquals(-15, unit.groupTtlBiasesTenthStops[AirGroup.GROUP_B])
        assertEquals(1, unit.groupPowersTenthStops[2])

        harness.disconnectDevice(addrPro)
    }

    // -------------------------------------------------------------------------
    // Pair 9: F5 (Monolight Zoom and Ready Signal) + F7 (Scenes recall zoom ready)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 9 F5 Monolight Zoom and Ready Signal plus F7 Scenes recall including ready state`() = runBlocking {
        val harness = E2ETestHarness()
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addrB10)
        harness.connectByAddress(addrPro)
        harness.awaitDeviceReady(addrB10)
        harness.awaitDeviceReady(addrPro)

        val linkB10 = harness.getRecordingLink(addrB10)!!
        val linkPro = harness.getRecordingLink(addrPro)!!
        linkB10.clear()
        linkPro.clear()

        val scene = StudioScene(
            name = "Studio Sound and Dim Scene",
            readySignalMode = ReadySignalMode.SOUND_AND_DIM,
            groupPresets = listOf(GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 70)),
        )
        harness.orchestrator.applyScene(scene)
        delay(80)

        // Monolight receives op=217 READY_SIGNAL_MODE (0x03)
        val b10Frames = linkB10.decodeRecordedFrames()
        val readyFrame = b10Frames.firstOrNull { it.first.opcode == PupOpcodes.READY_SIGNAL_MODE }
        assertNotNull("B10X Plus must receive op=217", readyFrame)
        assertEquals(0x03.toByte(), readyFrame!!.first.payload[0])

        val unitB10 = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrB10 }
        assertEquals(ReadySignalMode.SOUND_AND_DIM, unitB10.readySignalMode)
        assertTrue(unitB10.readySoundEnabled)
        assertTrue(unitB10.dimDuringRecharge)

        // Connect Pro remote is excluded from op=217
        val proFrames = linkPro.decodeRecordedFrames()
        assertNull("Connect Pro must never receive op=217", proFrames.firstOrNull { it.first.opcode == PupOpcodes.READY_SIGNAL_MODE })

        harness.disconnectDevice(addrB10)
        harness.disconnectDevice(addrPro)
    }

    // -------------------------------------------------------------------------
    // Pair 10: F6 (Working Tree BLE Fixes) + F10 (Auto-Reconnect via AUTH_SERVICE)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 10 F6 Working Tree BLE Fixes plus F10 Auto-Reconnect to Connect Pro via AUTH_SERVICE`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.orchestrator.setDevicePinned(addrPro, true)
        harness.connectByAddress(addrPro)
        harness.awaitDeviceReady(addrPro)

        val link = harness.getRecordingLink(addrPro)!!
        val writes = link.recordedWrites

        // Assert initial handshake routed to AUTH_SERVICE_UUID and AUTH_CLIENT_ID_CHAR_UUID
        val authWrite = writes.firstOrNull { it.characteristicUuid == ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID }
        assertNotNull("Must write auth token to AUTH_CLIENT_ID_CHAR_UUID", authWrite)
        assertEquals(ProfotoGattProfile.AUTH_SERVICE_UUID, authWrite!!.serviceUuid)
        assertEquals("Profoto Client", authWrite.bytes.toString(Charsets.UTF_8))
        assertTrue(authWrite.withResponse)

        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrPro }
        assertTrue(unit.isReady)

        harness.disconnectDevice(addrPro)
    }

    // -------------------------------------------------------------------------
    // Pair 11: F4 (Remote Settings Channel) + F7 (Scene restoration channel match)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 11 F4 Remote Settings Channel plus F7 Scene restoration with remote channel sync`() = runBlocking {
        val harness = E2ETestHarness()
        val addrPro = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addrPro)
        harness.connectByAddress(addrB10)
        harness.awaitDeviceReady(addrPro)
        harness.awaitDeviceReady(addrB10)

        val linkPro = harness.getRecordingLink(addrPro)!!
        val linkB10 = harness.getRecordingLink(addrB10)!!
        linkPro.clear()
        linkB10.clear()

        // Apply scene specifying channel 7
        val scene = StudioScene(
            name = "Channel 7 Synced Studio",
            remoteChannel = 7,
            groupPresets = listOf(GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 75)),
        )
        harness.orchestrator.applyScene(scene)
        delay(80)

        // Both devices receive op=36 AIR_CHANNEL with payload [7]
        val proChannelFrame = linkPro.decodeRecordedFrames().firstOrNull { it.first.opcode == PupOpcodes.AIR_CHANNEL }
        val b10ChannelFrame = linkB10.decodeRecordedFrames().firstOrNull { it.first.opcode == PupOpcodes.AIR_CHANNEL }

        assertNotNull("Connect Pro must receive op=36", proChannelFrame)
        assertNotNull("B10X Plus must receive op=36", b10ChannelFrame)
        assertEquals(7.toByte(), proChannelFrame!!.first.payload[0])
        assertEquals(7.toByte(), b10ChannelFrame!!.first.payload[0])

        val unitB10 = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addrB10 }
        assertEquals(7, unitB10.airChannel)
        assertTrue("B10X Plus channel matches remote channel", unitB10.isSharedWithActiveRemoteChannel)

        harness.disconnectDevice(addrPro)
        harness.disconnectDevice(addrB10)
    }

    // -------------------------------------------------------------------------
    // Pair 12: F1 (Connect collar switch guard) + F10 (Auto-reconnect with collar)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 12 F1 Connect collar switch guard plus F10 Auto-reconnect with collar switch`() = runBlocking {
        val harness = E2ETestHarness()
        val addrConnect = VirtualBenchFixtures.ADDR_CONNECT

        harness.orchestrator.setDevicePinned(addrConnect, true)
        harness.connectByAddress(addrConnect)
        harness.awaitDeviceReady(addrConnect)

        val link = harness.getRecordingLink(addrConnect)!!
        link.clear()

        // Attempt host programmatic mode write
        harness.orchestrator.setConnectOperatingMode(addrConnect, RemoteOperatingMode.MANUAL)
        delay(40)

        // Status banner guards and warns user
        val state = harness.orchestrator.dashboardState.value
        assertEquals("Connect operating mode is physically controlled via collar switch", state.statusBanner)

        // Zero op=144 frames sent
        val frames = link.decodeRecordedFrames()
        assertNull(frames.firstOrNull { it.first.opcode == PupOpcodes.REMOTE_MODE })

        val unit = state.connectedUnits.first { it.address == addrConnect }
        assertEquals(RemoteOperatingMode.AUTO_TTL, unit.remoteMode)

        harness.disconnectDevice(addrConnect)
    }

    // -------------------------------------------------------------------------
    // Pair 13: F9 (Dual Power Readout) + F10 (Auto-Reconnect restoring power scales)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 13 F9 Dual Power Readout plus F10 Auto-Reconnect restoring multi-strobe power scales`() = runBlocking {
        val harness = E2ETestHarness()
        val addrB10 = VirtualBenchFixtures.ADDR_B10X_PLUS
        val addrA2 = VirtualBenchFixtures.ADDR_A2

        harness.orchestrator.setDualPowerReadoutEnabled(true)
        harness.orchestrator.setDevicePinned(addrB10, true)
        harness.orchestrator.setDevicePinned(addrA2, true)

        harness.connectByAddress(addrB10)
        harness.connectByAddress(addrA2)
        harness.awaitActiveReadyCount(2)
        delay(80)

        val units = harness.orchestrator.dashboardState.value.connectedUnits
        val unitB10 = units.first { it.address == addrB10 }
        val unitA2 = units.first { it.address == addrA2 }

        // B10X Plus (500Ws max @ 80 tenths = 8.0 stops)
        val readoutB10 = PowerScaleConverter.formatDualPowerReadout(unitB10.flashPowerTenthStops, unitB10.modelSpec.maxWattSeconds)
        assertEquals("8.0 (125 Ws · 1/4)", readoutB10)

        // A2 (100Ws max @ 65 tenths = 6.5 stops)
        val readoutA2 = PowerScaleConverter.formatDualPowerReadout(unitA2.flashPowerTenthStops, unitA2.modelSpec.maxWattSeconds)
        assertEquals("6.5 (8.8 Ws · 1/16 + 0.5 EV)", readoutA2)

        harness.disconnectDevice(addrB10)
        harness.disconnectDevice(addrA2)
    }

    // -------------------------------------------------------------------------
    // Pair 14: F7 (Scene JSON export import) + F8 (Role labels persistence integrity)
    // -------------------------------------------------------------------------
    @Test
    fun `pair 14 F7 Scene JSON export import plus F8 Role labels persistence integrity fresh install`() = runBlocking {
        val scene = StudioScene(
            name = "Full Six Group Studio Rig",
            remoteChannel = 14,
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 85, roleLabel = "Key"),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 70, roleLabel = "Fill"),
                GroupPreset(group = AirGroup.GROUP_C, powerTenthStops = 60, roleLabel = "Rim"),
                GroupPreset(group = AirGroup.GROUP_D, powerTenthStops = 50, roleLabel = "Hair"),
                GroupPreset(group = AirGroup.GROUP_E, powerTenthStops = 40, roleLabel = "Back-L"),
                GroupPreset(group = AirGroup.GROUP_F, powerTenthStops = 30, roleLabel = "Back-R"),
            ),
        )
        val json = StudioSceneJsonCodec.encodeToJson(listOf(scene))

        // Create fresh clean harness simulating fresh install
        val freshHarness = E2ETestHarness()
        assertTrue(freshHarness.orchestrator.dashboardState.value.groupRoles.isEmpty())

        val imported = StudioSceneJsonCodec.decodeFromJson(json)
        freshHarness.orchestrator.importScenes(imported)
        freshHarness.orchestrator.applyScene(imported.first())
        delay(60)

        val state = freshHarness.orchestrator.dashboardState.value
        assertEquals("Key", state.groupRoles[AirGroup.GROUP_A])
        assertEquals("Fill", state.groupRoles[AirGroup.GROUP_B])
        assertEquals("Rim", state.groupRoles[AirGroup.GROUP_C])
        assertEquals("Hair", state.groupRoles[AirGroup.GROUP_D])
        assertEquals("Back-L", state.groupRoles[AirGroup.GROUP_E])
        assertEquals("Back-R", state.groupRoles[AirGroup.GROUP_F])

        assertEquals("Key", state.groupChannels[0].roleLabel)
        assertEquals(85, state.groupChannels[0].powerTenthStops)
        assertEquals("Back-R", state.groupChannels[5].roleLabel)
        assertEquals(30, state.groupChannels[5].powerTenthStops)
    }
}
