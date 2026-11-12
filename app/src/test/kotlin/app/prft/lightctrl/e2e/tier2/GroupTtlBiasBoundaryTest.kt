package app.prft.lightctrl.e2e.tier2

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.PupCodecException
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tier 2 — Feature 2: Per-Group TTL EV Bias (op=131) Boundary Tests.
 *
 * Verifies:
 * 1. Clamping/rejection of bias < -20 and > +20 in PupFrameCodec.
 * 2. Rejection of invalid groups (GROUP_D, GROUP_E, GROUP_F) for op=131.
 * 3. Exact boundary values: -20 (-2.0 EV), 0 (0.0 EV), +20 (+2.0 EV).
 * 4. Signed two's complement byte encoding assertions.
 * 5. StudioOrchestrator clamps out-of-range bias to -20 and +20.
 * 6. Truncated op=131 payload throws PupCodecException.
 */
class GroupTtlBiasBoundaryTest {

    @Test
    fun `test 1 clamping or rejection of bias values less than -20 and greater than +20`() {
        val invalidValues = listOf(-100, -50, -25, -21, 21, 25, 50, 100)
        for (v in invalidValues) {
            try {
                PupFrameCodec.encodeAirPxBias(counter = 1, tenthStops = v, group = AirGroup.GROUP_A)
                fail("Expected PupCodecException for tenthStops=$v")
            } catch (e: PupCodecException) {
                assertTrue("Error message must mention range: ${e.message}", e.message!!.contains("out of range (-20..20)"))
            }
        }
    }

    @Test
    fun `test 2 rejection of invalid groups GROUP_D GROUP_E GROUP_F for op=131`() {
        val invalidGroups = listOf(AirGroup.GROUP_D, AirGroup.GROUP_E, AirGroup.GROUP_F)
        for (grp in invalidGroups) {
            try {
                PupFrameCodec.encodeAirPxBias(counter = 2, tenthStops = 10, group = grp)
                fail("Expected PupCodecException for group $grp")
            } catch (e: PupCodecException) {
                assertTrue("Error message must indicate group restriction: ${e.message}", e.message!!.contains("only supports groups A..C"))
            }
        }
    }

    @Test
    fun `test 3 exact boundary values -20, 0, and +20`() {
        // -20 boundary (-2.0 EV)
        val rawNeg = PupFrameCodec.encodeAirPxBias(counter = 10, tenthStops = -20, group = AirGroup.GROUP_A)
        val (pktNeg, msgNeg) = PupFrameCodec.decodeMessage(rawNeg)
        assertEquals((-20).toByte(), pktNeg.payload[0])
        assertEquals(-20, (msgNeg as PupDecodedMessage.AirGroupTtlBias).tenthStops)
        assertEquals(-2.0f, msgNeg.ev, 0.001f)

        // 0 boundary (0.0 EV)
        val rawZero = PupFrameCodec.encodeAirPxBias(counter = 11, tenthStops = 0, group = AirGroup.GROUP_A)
        val (pktZero, msgZero) = PupFrameCodec.decodeMessage(rawZero)
        assertEquals(0.toByte(), pktZero.payload[0])
        assertEquals(0, (msgZero as PupDecodedMessage.AirGroupTtlBias).tenthStops)
        assertEquals(0.0f, msgZero.ev, 0.001f)

        // +20 boundary (+2.0 EV)
        val rawPos = PupFrameCodec.encodeAirPxBias(counter = 12, tenthStops = 20, group = AirGroup.GROUP_A)
        val (pktPos, msgPos) = PupFrameCodec.decodeMessage(rawPos)
        assertEquals(20.toByte(), pktPos.payload[0])
        assertEquals(20, (msgPos as PupDecodedMessage.AirGroupTtlBias).tenthStops)
        assertEquals(2.0f, msgPos.ev, 0.001f)
    }

    @Test
    fun `test 4 signed two's complement byte encoding assertions`() {
        val testPairs = listOf(
            -20 to 0xEC.toByte(),
            -15 to 0xF1.toByte(),
            -10 to 0xF6.toByte(),
            -1 to 0xFF.toByte(),
            0 to 0x00.toByte(),
            1 to 0x01.toByte(),
            10 to 0x0A.toByte(),
            20 to 0x14.toByte(),
        )

        for ((tenths, expectedByte) in testPairs) {
            val bytes = PupFrameCodec.encodeAirPxBias(counter = 0, tenthStops = tenths, group = AirGroup.GROUP_A)
            assertEquals("Byte at index 3 must match signed representation", expectedByte, bytes[3])

            val (_, msg) = PupFrameCodec.decodeMessage(bytes)
            val bias = msg as PupDecodedMessage.AirGroupTtlBias
            assertEquals(tenths, bias.tenthStops)
        }
    }

    @Test
    fun `test 5 StudioOrchestrator clamps out-of-range bias to -20 and +20`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        // Test clamping of extreme negative bias
        harness.orchestrator.setTtlEvCompensation(addr, -45)
        delay(60)
        var unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(-20, unit.ttlEvCompTenthStops)

        // Test clamping of extreme positive bias
        harness.orchestrator.setTtlEvCompensation(addr, 55)
        delay(60)
        unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(20, unit.ttlEvCompTenthStops)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 6 truncated op=131 payload throws PupCodecException`() {
        val truncated0 = PupFrameCodec.encodePacket(counter = 1, opcode = PupOpcodes.AIR_PX_BIAS, payload = ByteArray(0))
        try {
            PupFrameCodec.decodeMessage(truncated0)
            fail("Expected PupCodecException for empty payload")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("AirPxBias payload truncated"))
        }

        val truncated1 = PupFrameCodec.encodePacket(counter = 2, opcode = PupOpcodes.AIR_PX_BIAS, payload = byteArrayOf(10))
        try {
            PupFrameCodec.decodeMessage(truncated1)
            fail("Expected PupCodecException for 1-byte payload")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("AirPxBias payload truncated"))
        }
    }
}
