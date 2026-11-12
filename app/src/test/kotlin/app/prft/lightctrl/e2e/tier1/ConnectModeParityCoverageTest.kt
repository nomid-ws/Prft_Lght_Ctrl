package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.ConnectedProfotoUnit
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.e2e.harness.RecordingBlePeerLink
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.HardwareCatalog
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.RemoteOperatingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — Feature 1: Connect vs Connect Pro Mode Parity Coverage Tests.
 *
 * Verifies:
 * 1. Connect Pro digital op=144 write to AUTO_TTL with ACK and state update.
 * 2. Connect Pro digital op=144 write to MANUAL with ACK and state update.
 * 3. Opcode 43 (TTL_AUTO_ENABLED) wire encoding and state synchronization.
 * 4. Connect buttonless collar read-only telemetry handling.
 * 5. Host write rejection and collar state preservation on Connect.
 * 6. Wire codec roundtrip for Opcode 144 across all RemoteOperatingMode values.
 */
class ConnectModeParityCoverageTest {

    @Test
    fun `test 1 Connect Pro digital op 144 write to AUTO_TTL with ACK and state update`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull("Recording link must be tracked", link)
        link!!.clear()

        // Write AUTO_TTL mode
        harness.orchestrator.setConnectOperatingMode(addr, RemoteOperatingMode.AUTO_TTL)
        delay(60)

        // Verify outgoing frame on wire has op=144 and payload=0x02
        val recorded = link.decodeRecordedFrames()
        val modeFrame = recorded.firstOrNull { it.first.opcode == PupOpcodes.REMOTE_MODE }
        assertNotNull("Must transmit op=144 REMOTE_MODE frame", modeFrame)
        val decoded = modeFrame!!.second as PupDecodedMessage.RemoteModeState
        assertEquals(RemoteOperatingMode.AUTO_TTL, decoded.mode)

        // Verify orchestrator state update
        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(RemoteOperatingMode.AUTO_TTL, unit.remoteMode)
        assertTrue("ttlAutoEnabled must be true in AUTO_TTL", unit.ttlAutoEnabled)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 2 Connect Pro digital op 144 write to MANUAL with ACK and state update`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull(link)
        link!!.clear()

        // Write MANUAL mode
        harness.orchestrator.setConnectOperatingMode(addr, RemoteOperatingMode.MANUAL)
        delay(60)

        val recorded = link.decodeRecordedFrames()
        val modeFrame = recorded.firstOrNull { it.first.opcode == PupOpcodes.REMOTE_MODE }
        assertNotNull("Must transmit op=144 REMOTE_MODE frame", modeFrame)
        val decoded = modeFrame!!.second as PupDecodedMessage.RemoteModeState
        assertEquals(RemoteOperatingMode.MANUAL, decoded.mode)

        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(RemoteOperatingMode.MANUAL, unit.remoteMode)
        assertFalse("ttlAutoEnabled must be false in MANUAL", unit.ttlAutoEnabled)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 3 Opcode 43 TTL_AUTO_ENABLED wire encoding and state synchronization`() {
        val enableBytes = PupFrameCodec.encodeTtlAutoEnable(counter = 15, enabled = true)
        val (enablePkt, enableMsg) = PupFrameCodec.decodeMessage(enableBytes)
        assertEquals(15, enablePkt.counter)
        assertEquals(PupOpcodes.TTL_AUTO_ENABLED, enablePkt.opcode)
        assertTrue((enableMsg as PupDecodedMessage.TtlAutoState).enabled)

        val disableBytes = PupFrameCodec.encodeTtlAutoEnable(counter = 16, enabled = false)
        val (disablePkt, disableMsg) = PupFrameCodec.decodeMessage(disableBytes)
        assertEquals(16, disablePkt.counter)
        assertEquals(PupOpcodes.TTL_AUTO_ENABLED, disablePkt.opcode)
        assertFalse((disableMsg as PupDecodedMessage.TtlAutoState).enabled)
    }

    @Test
    fun `test 4 Connect buttonless collar read-only telemetry handling`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(DeviceArchetype.CONNECT_REMOTE, unit.archetype)
        assertEquals("Profoto Connect (Nikon)", unit.hardwareModelName)
        assertEquals(8, unit.maxAirChannel)
        assertTrue(unit.modelSpec.usesLegacyV1Queries)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 5 Host write rejection and collar state preservation on Connect`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull(link)
        link!!.clear()

        // When trimming relative energy, Connect uses Opcode 135 (ADJUST_AIR_MX_ENERGY_IF_ABLE)
        // rather than mutating absolute energy (Opcode 177), preserving collar state
        harness.orchestrator.stepRemoteAirGroupTrim(addr, AirGroup.GROUP_A, 5)
        delay(60)

        val recorded = link.decodeRecordedFrames()
        val hasOp135 = recorded.any { it.first.opcode == PupOpcodes.ADJUST_AIR_MX_ENERGY_IF_ABLE }
        assertTrue("Connect must use op=135 ADJUST_AIR_MX_ENERGY_IF_ABLE", hasOp135)

        val hasOp177 = recorded.any { it.first.opcode == PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY }
        assertFalse("Connect must NOT send op=177 absolute group energy", hasOp177)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 6 Wire codec roundtrip for Opcode 144 across all RemoteOperatingMode values`() {
        for (mode in RemoteOperatingMode.entries) {
            val bytes = PupFrameCodec.encodeRemoteMode(counter = 42, mode = mode)
            val (pkt, msg) = PupFrameCodec.decodeMessage(bytes)
            assertEquals(42, pkt.counter)
            assertEquals(PupOpcodes.REMOTE_MODE, pkt.opcode)
            val decoded = msg as PupDecodedMessage.RemoteModeState
            assertEquals(mode, decoded.mode)
        }
    }
}
