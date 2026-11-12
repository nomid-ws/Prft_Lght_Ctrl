package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.ble.ProfotoGattProfile
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.AllSettingsTlvParser
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — Feature 2: Per-Group TTL EV Bias (op=131) Coverage Tests.
 *
 * Verifies:
 * 1. Opcode 131 encoding for GROUP_A with signed tenth-stops (-20..+20).
 * 2. Opcode 131 encoding for GROUP_B and GROUP_C.
 * 3. Opcode 131 encoding for AirGroup.ALL.
 * 4. MockBleTransport state mutation on op=131 receipt.
 * 5. Decoding incoming PupDecodedMessage.AirGroupTtlBias telemetry.
 * 6. AirGroupTtlBias inside AllSettingsV2 TLV stream decodes per-group biases.
 */
class GroupTtlBiasCoverageTest {

    @Test
    fun `test 1 Opcode 131 encoding for GROUP_A with signed tenth-stops -20 to +20`() {
        val testValues = listOf(-20, -15, -1, 0, 1, 10, 20)
        for ((idx, tenths) in testValues.withIndex()) {
            val bytes = PupFrameCodec.encodeAirPxBias(counter = idx, tenthStops = tenths, group = AirGroup.GROUP_A)
            val (pkt, msg) = PupFrameCodec.decodeMessage(bytes)

            assertEquals(idx, pkt.counter)
            assertEquals(PupOpcodes.AIR_PX_BIAS, pkt.opcode)
            assertEquals(2, pkt.payload.size)
            assertEquals(tenths.toByte(), pkt.payload[0])
            assertEquals(AirGroup.GROUP_A.code.toByte(), pkt.payload[1])

            val bias = msg as PupDecodedMessage.AirGroupTtlBias
            assertEquals(tenths, bias.tenthStops)
            assertEquals(AirGroup.GROUP_A, bias.group)
            assertEquals(tenths / 10f, bias.ev, 0.001f)
        }
    }

    @Test
    fun `test 2 Opcode 131 encoding for GROUP_B and GROUP_C`() {
        // Group B (+1.5 EV = +15 tenths)
        val rawB = PupFrameCodec.encodeAirPxBias(counter = 5, tenthStops = 15, group = AirGroup.GROUP_B)
        val (pktB, msgB) = PupFrameCodec.decodeMessage(rawB)
        assertEquals(PupOpcodes.AIR_PX_BIAS, pktB.opcode)
        assertEquals(AirGroup.GROUP_B.code.toByte(), pktB.payload[1])
        val biasB = msgB as PupDecodedMessage.AirGroupTtlBias
        assertEquals(15, biasB.tenthStops)
        assertEquals(AirGroup.GROUP_B, biasB.group)
        assertEquals(1.5f, biasB.ev, 0.001f)

        // Group C (-1.8 EV = -18 tenths)
        val rawC = PupFrameCodec.encodeAirPxBias(counter = 6, tenthStops = -18, group = AirGroup.GROUP_C)
        val (pktC, msgC) = PupFrameCodec.decodeMessage(rawC)
        assertEquals(PupOpcodes.AIR_PX_BIAS, pktC.opcode)
        assertEquals(AirGroup.GROUP_C.code.toByte(), pktC.payload[1])
        val biasC = msgC as PupDecodedMessage.AirGroupTtlBias
        assertEquals(-18, biasC.tenthStops)
        assertEquals(AirGroup.GROUP_C, biasC.group)
        assertEquals(-1.8f, biasC.ev, 0.001f)
    }

    @Test
    fun `test 3 Opcode 131 encoding for AirGroup ALL`() {
        val rawAll = PupFrameCodec.encodeAirPxBias(counter = 11, tenthStops = -5, group = AirGroup.ALL)
        val (pktAll, msgAll) = PupFrameCodec.decodeMessage(rawAll)

        assertEquals(11, pktAll.counter)
        assertEquals(PupOpcodes.AIR_PX_BIAS, pktAll.opcode)
        assertEquals(AirGroup.ALL.code.toByte(), pktAll.payload[1])

        val biasAll = msgAll as PupDecodedMessage.AirGroupTtlBias
        assertEquals(-5, biasAll.tenthStops)
        assertEquals(AirGroup.ALL, biasAll.group)
        assertEquals(-0.5f, biasAll.ev, 0.001f)
    }

    @Test
    fun `test 4 MockBleTransport state mutation on op 131 receipt`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        // Wait until initial AllSettingsV2 hydration settles
        withTimeout(2000L) {
            while (true) {
                val u = harness.orchestrator.dashboardState.value.connectedUnits.firstOrNull { it.address == addr }
                if (u != null && u.isReady) break
                delay(10)
            }
        }

        val link = harness.getRecordingLink(addr)
        assertNotNull(link)
        link!!.clear()

        // Send -1.2 EV bias (-12 tenths)
        harness.orchestrator.setTtlEvCompensation(addr, -12)

        // Deterministically await recorded outgoing AIR_PX_BIAS frame
        withTimeout(2000L) {
            while (link.decodeRecordedFrames().none { it.first.opcode == PupOpcodes.AIR_PX_BIAS }) {
                delay(10)
            }
        }

        // Verify outgoing frame contains op=131 with -12 tenths
        val recorded = link.decodeRecordedFrames()
        val biasFrame = recorded.firstOrNull { it.first.opcode == PupOpcodes.AIR_PX_BIAS }
        assertNotNull("Must record AIR_PX_BIAS frame", biasFrame)
        val biasMsg = biasFrame!!.second as PupDecodedMessage.AirGroupTtlBias
        assertEquals(-12, biasMsg.tenthStops)

        // Verify orchestrator state update
        withTimeout(2000L) {
            while (true) {
                val u = harness.orchestrator.dashboardState.value.connectedUnits.firstOrNull { it.address == addr }
                if (u != null && u.ttlEvCompTenthStops == -12) break
                delay(10)
            }
        }
        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(-12, unit.ttlEvCompTenthStops)
        assertEquals(-1.2f, unit.ttlEvComp, 0.001f)

        // Await ACK from MockBleTransport confirming AIR_PX_BIAS was processed
        withTimeout(2000L) {
            while (link.decodeRecordedNotifications().none { it.first.opcode == PupOpcodes.ACK }) {
                delay(10)
            }
        }

        // Query MockBleTransport via encodeQueryAllSettingsV2 to verify peripheral state mutation
        val notifCountBeforeQuery = link.decodeRecordedNotifications().size
        val queryPkt = PupFrameCodec.encodeQueryAllSettingsV2(counter = 99)
        link.writeCharacteristicNoResponse(
            ProfotoGattProfile.AUTH_SERVICE_UUID,
            ProfotoGattProfile.PUP_TX_CHAR_UUID,
            queryPkt,
        )

        // Deterministically await AllSettingsV2 notification dump
        val dump = withTimeout(2000L) {
            while (true) {
                val notifications = link.decodeRecordedNotifications().drop(notifCountBeforeQuery)
                val allSettingsPkt = notifications.firstOrNull { it.first.opcode == PupOpcodes.ALL_SETTINGS_V2 }
                if (allSettingsPkt != null) {
                    val decoded = allSettingsPkt.second as? PupDecodedMessage.BulkSettingsDump
                    if (decoded != null && decoded.result.finished) {
                        return@withTimeout decoded.result
                    }
                }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("Timeout awaiting AllSettingsV2 dump")
        }

        val ttlCompEntry = dump.entries.firstOrNull { it.opcode == PupOpcodes.AIR_TTL_EV_COMP }
        assertNotNull("AllSettingsV2 dump must contain AIR_TTL_EV_COMP", ttlCompEntry)
        assertEquals(-12, (ttlCompEntry!!.decoded as PupDecodedMessage.AirTtlEvCompensation).tenthStops)

        val groupABiasEntry = dump.entries.firstOrNull {
            it.opcode == PupOpcodes.AIR_PX_BIAS &&
                (it.decoded as? PupDecodedMessage.AirGroupTtlBias)?.group == AirGroup.GROUP_A
        }
        assertNotNull("AllSettingsV2 dump must contain Group A bias entry", groupABiasEntry)
        assertEquals(-12, (groupABiasEntry!!.decoded as PupDecodedMessage.AirGroupTtlBias).tenthStops)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 5 Decoding incoming PupDecodedMessage AirGroupTtlBias telemetry`() {
        // Construct raw frame: counter=7, opcode=131 (0x0083), payload=[-14, 2] -> Group B, -1.4 EV
        val raw = byteArrayOf(0x07, 0x83.toByte(), 0x00, (-14).toByte(), 0x02)
        val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

        assertEquals(7, pkt.counter)
        assertEquals(PupOpcodes.AIR_PX_BIAS, pkt.opcode)
        val bias = msg as PupDecodedMessage.AirGroupTtlBias
        assertEquals(-14, bias.tenthStops)
        assertEquals(AirGroup.GROUP_B, bias.group)
        assertEquals(-1.4f, bias.ev, 0.001f)
    }

    @Test
    fun `test 6 AirGroupTtlBias inside AllSettingsV2 TLV stream decodes per-group biases`() {
        val tlvPayload = AllSettingsTlvParser.serialize(
            records = listOf(
                PupOpcodes.AIR_PX_BIAS to byteArrayOf(10, AirGroup.GROUP_A.code.toByte()),
                PupOpcodes.AIR_PX_BIAS to byteArrayOf((-15).toByte(), AirGroup.GROUP_B.code.toByte()),
                PupOpcodes.AIR_PX_BIAS to byteArrayOf(5, AirGroup.GROUP_C.code.toByte()),
            ),
            terminate = true,
        )
        val pkt = PupFrameCodec.encodePacket(counter = 20, opcode = PupOpcodes.ALL_SETTINGS_V2, payload = tlvPayload)
        val (_, decoded) = PupFrameCodec.decodeMessage(pkt)
        val dump = (decoded as PupDecodedMessage.BulkSettingsDump).result

        val biases = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.AirGroupTtlBias>()
        assertEquals(3, biases.size)
        assertEquals(AirGroup.GROUP_A, biases[0].group)
        assertEquals(10, biases[0].tenthStops)
        assertEquals(AirGroup.GROUP_B, biases[1].group)
        assertEquals(-15, biases[1].tenthStops)
        assertEquals(AirGroup.GROUP_C, biases[2].group)
        assertEquals(5, biases[2].tenthStops)
    }

    @Test
    fun `test 7 concurrent telemetry hydration and user edits remain consistent`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)!!
        link.clear()

        // Concurrently execute multiple group bias edits and an AllSettingsV2 dump query
        val j1 = launch { harness.orchestrator.setGroupTtlEvBias(addr, AirGroup.GROUP_A, 15) }
        val j2 = launch { harness.orchestrator.setGroupTtlEvBias(addr, AirGroup.GROUP_B, -10) }
        val j3 = launch { harness.orchestrator.setGroupTtlEvBias(addr, AirGroup.GROUP_C, 5) }
        val j4 = launch {
            val queryPkt = PupFrameCodec.encodeQueryAllSettingsV2(counter = 42)
            link.writeCharacteristicNoResponse(
                ProfotoGattProfile.AUTH_SERVICE_UUID,
                ProfotoGattProfile.PUP_TX_CHAR_UUID,
                queryPkt,
            )
        }

        joinAll(j1, j2, j3, j4)

        // Deterministically await until state convergence
        withTimeout(2000L) {
            while (true) {
                val unit = harness.orchestrator.dashboardState.value.connectedUnits.firstOrNull { it.address == addr }
                if (unit != null &&
                    unit.groupTtlBiasesTenthStops[AirGroup.GROUP_A] == 15 &&
                    unit.groupTtlBiasesTenthStops[AirGroup.GROUP_B] == -10 &&
                    unit.groupTtlBiasesTenthStops[AirGroup.GROUP_C] == 5 &&
                    unit.ttlEvCompTenthStops == 15
                ) {
                    break
                }
                delay(10)
            }
        }

        val finalUnit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals("Master ttlEvCompTenthStops must equal Group A bias", 15, finalUnit.ttlEvCompTenthStops)
        assertEquals(15, finalUnit.groupTtlBiasesTenthStops[AirGroup.GROUP_A])
        assertEquals(-10, finalUnit.groupTtlBiasesTenthStops[AirGroup.GROUP_B])
        assertEquals(5, finalUnit.groupTtlBiasesTenthStops[AirGroup.GROUP_C])

        harness.disconnectDevice(addr)
    }
}
