package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — Feature 3: Remote Power Floor 0.1 (op=177) Coverage Tests.
 *
 * Verifies:
 * 1. Connect Pro allows absolute group power down to 1 (0.1 f-stops) on wire.
 * 2. encodeAirAbsoluteGroupEnergy(Map<AirGroup, Int>) encodes 1..100 across A..F.
 * 3. MockBleTransport receives op=177 down to 1 on Connect Pro and updates state.
 * 4. Monolight standalone power enforces legacy floor 10 (1.0 f-stops).
 * 5. Multi-group power map encoding fidelity across all 6 groups A through F.
 * 6. Connect Pro group powers update via StudioOrchestrator.setConnectProGroupPower.
 */
class RemotePowerFloorCoverageTest {

    @Test
    fun `test 1 Connect Pro allows absolute group power down to 1 on wire`() {
        val powers = listOf(1, 1, 1, 1, 1, 1)
        val raw = PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 7, groupTenthStops = powers)
        val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

        assertEquals(7, pkt.counter)
        assertEquals(PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY, pkt.opcode)
        assertEquals(6, pkt.payload.size)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1, 1, 1), pkt.payload)

        val decoded = msg as PupDecodedMessage.AirAbsoluteGroupPower
        assertEquals(powers, decoded.groupTenthStops)
    }

    @Test
    fun `test 2 encodeAirAbsoluteGroupEnergy with Map of AirGroup encodes 1 to 100`() {
        val powerMap = mapOf(
            AirGroup.GROUP_A to 1,
            AirGroup.GROUP_B to 15,
            AirGroup.GROUP_C to 50,
            AirGroup.GROUP_D to 75,
            AirGroup.GROUP_E to 90,
            AirGroup.GROUP_F to 100,
        )
        val raw = PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 8, groupEnergies = powerMap)
        val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

        assertEquals(PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY, pkt.opcode)
        val decoded = msg as PupDecodedMessage.AirAbsoluteGroupPower
        assertEquals(listOf(1, 15, 50, 75, 90, 100), decoded.groupTenthStops)
    }

    @Test
    fun `test 3 MockBleTransport receives op=177 down to 1 on Connect Pro and updates state`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull(link)
        link!!.clear()

        // Write absolute group powers down to floor 1 (0.1 stops)
        val targetPowers = listOf(1, 20, 35, 50, 65, 80)
        val writeFrame = PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 19, groupTenthStops = targetPowers)
        link.delegate.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.AUTH_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            writeFrame,
        )
        delay(60)

        // Query settings to verify MockBleTransport state mutation
        val queryPkt = PupFrameCodec.encodeQueryAllSettingsV2(counter = 20)
        link.delegate.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.AUTH_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            queryPkt,
        )
        delay(80)

        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(1, unit.groupPowersTenthStops[0])
        assertEquals(targetPowers, unit.groupPowersTenthStops)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 4 Monolight standalone power enforces legacy floor 10`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val unitInitial = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(10, unitInitial.minFlashTenthStops)

        val link = harness.getRecordingLink(addr)
        assertNotNull(link)
        link!!.clear()

        // Attempt to set power below 10 (e.g. 5 = 0.5 stops)
        harness.orchestrator.setFlashPowerTenthStops(addr, 5)
        delay(60)

        // Must clamp to 10
        val unitAfter = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(10, unitAfter.flashPowerTenthStops)

        val recorded = link.decodeRecordedFrames()
        val powerFrame = recorded.firstOrNull { it.first.opcode == PupOpcodes.MX_ENERGY }
        assertNotNull("Must record MX_ENERGY frame", powerFrame)
        val powerMsg = powerFrame!!.second as PupDecodedMessage.FlashPower
        assertEquals(10, powerMsg.tenthStops)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 5 multi-group power map encoding fidelity across all 6 groups A through F`() {
        val permutations = listOf(
            listOf(1, 10, 20, 30, 40, 50),
            listOf(50, 40, 30, 20, 10, 1),
            listOf(100, 100, 100, 100, 100, 100),
            listOf(1, 100, 1, 100, 1, 100),
        )

        for ((seq, powers) in permutations.withIndex()) {
            val bytes = PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = seq, groupTenthStops = powers)
            val (pkt, msg) = PupFrameCodec.decodeMessage(bytes)
            assertEquals(PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY, pkt.opcode)
            assertEquals(powers, (msg as PupDecodedMessage.AirAbsoluteGroupPower).groupTenthStops)
        }
    }

    @Test
    fun `test 6 Connect Pro group powers update via StudioOrchestrator setConnectProGroupPower`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull(link)
        link!!.clear()

        // Set group index 0 (Group A) to 25 and group index 5 (Group F) to 85
        harness.orchestrator.setConnectProGroupPower(addr, 0, 25)
        harness.orchestrator.setConnectProGroupPower(addr, 5, 85)
        delay(80)

        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(25, unit.groupPowersTenthStops[0])
        assertEquals(85, unit.groupPowersTenthStops[5])

        val recorded = link.decodeRecordedFrames().filter { it.first.opcode == PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY }
        assertTrue("Must transmit op=177 AIR_ABSOLUTE_GROUP_ENERGY", recorded.isNotEmpty())

        harness.disconnectDevice(addr)
    }
}
