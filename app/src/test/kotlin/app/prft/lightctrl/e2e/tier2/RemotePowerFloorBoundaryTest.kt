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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tier 2 — Feature 3: Remote Power Floor 0.1 (op=177) Boundary Tests.
 *
 * Verifies:
 * 1. Value 0 in group power is rejected by PupFrameCodec.
 * 2. Value > 100 in group power is rejected by PupFrameCodec.
 * 3. Fractional tenth-stop step down from 1.5 stops respects 0.1 floor.
 * 4. Partial group maps or missing group entries throw PupCodecException.
 * 5. Exact boundary values 1 (0.1 f-stops) and 100 (10.0 f-stops) roundtrip cleanly.
 * 6. Truncated op=177 payload throws PupCodecException.
 */
class RemotePowerFloorBoundaryTest {

    @Test
    fun `test 1 value 0 in group power is rejected by PupFrameCodec`() {
        val invalidList = listOf(0, 50, 50, 50, 50, 50)
        try {
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 1, groupTenthStops = invalidList)
            fail("Expected PupCodecException for power 0")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("power out of range (1..100): 0"))
        }

        val invalidMap = mapOf(
            AirGroup.GROUP_A to 0,
            AirGroup.GROUP_B to 50,
            AirGroup.GROUP_C to 50,
            AirGroup.GROUP_D to 50,
            AirGroup.GROUP_E to 50,
            AirGroup.GROUP_F to 50,
        )
        try {
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 2, groupEnergies = invalidMap)
            fail("Expected PupCodecException for map containing power 0")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("power out of range (1..100): 0"))
        }
    }

    @Test
    fun `test 2 value greater than 100 in group power is rejected by PupFrameCodec`() {
        val invalidList = listOf(101, 50, 50, 50, 50, 50)
        try {
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 3, groupTenthStops = invalidList)
            fail("Expected PupCodecException for power 101")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("power out of range (1..100): 101"))
        }

        val invalidMap = mapOf(
            AirGroup.GROUP_A to 150,
            AirGroup.GROUP_B to 50,
            AirGroup.GROUP_C to 50,
            AirGroup.GROUP_D to 50,
            AirGroup.GROUP_E to 50,
            AirGroup.GROUP_F to 50,
        )
        try {
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 4, groupEnergies = invalidMap)
            fail("Expected PupCodecException for map containing power 150")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("power out of range (1..100): 150"))
        }
    }

    @Test
    fun `test 3 fractional tenth-stop step down from 15 stops respects 0 1 floor`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull(link)

        // Set initial Group A power to 15 (1.5 stops)
        val initialPowers = listOf(15, 50, 50, 50, 50, 50)
        val setFrame = PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 10, groupTenthStops = initialPowers)
        link!!.delegate.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.AUTH_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            setFrame,
        )
        delay(60)

        // Send step down of -30 tenths (would be -15 if unclamped)
        val adjustFrame = PupFrameCodec.encodeAirAdjustEnergy(counter = 11, group = AirGroup.GROUP_A, deltaOrTargetTenthStops = -30)
        link.delegate.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.AUTH_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            adjustFrame,
        )
        delay(60)

        // Query settings to inspect clamped state
        val queryPkt = PupFrameCodec.encodeQueryAllSettingsV2(counter = 12)
        link.delegate.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.AUTH_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            queryPkt,
        )
        delay(80)

        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals("Group A power must be clamped to 1 (0.1 floor)", 1, unit.groupPowersTenthStops[0])

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 4 partial group maps or wrong list size throw PupCodecException`() {
        // Missing groups D, E, F
        val partialMap = mapOf(
            AirGroup.GROUP_A to 50,
            AirGroup.GROUP_B to 50,
            AirGroup.GROUP_C to 50,
        )
        try {
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 5, groupEnergies = partialMap)
            fail("Expected PupCodecException for partial map missing groups")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("Missing energy for group"))
        }

        // List with only 5 elements
        try {
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 6, groupTenthStops = listOf(10, 20, 30, 40, 50))
            fail("Expected PupCodecException for 5-element list")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("requires 6 group values (A..F), got 5"))
        }
    }

    @Test
    fun `test 5 exact boundary values 1 and 100 encode and roundtrip cleanly`() {
        // Lower boundary: all 1s
        val minList = listOf(1, 1, 1, 1, 1, 1)
        val rawMin = PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 7, groupTenthStops = minList)
        val (pktMin, msgMin) = PupFrameCodec.decodeMessage(rawMin)
        assertEquals(minList, (msgMin as PupDecodedMessage.AirAbsoluteGroupPower).groupTenthStops)
        for (i in 0 until 6) {
            assertEquals(0x01.toByte(), pktMin.payload[i])
        }

        // Upper boundary: all 100s
        val maxList = listOf(100, 100, 100, 100, 100, 100)
        val rawMax = PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 8, groupTenthStops = maxList)
        val (pktMax, msgMax) = PupFrameCodec.decodeMessage(rawMax)
        assertEquals(maxList, (msgMax as PupDecodedMessage.AirAbsoluteGroupPower).groupTenthStops)
        for (i in 0 until 6) {
            assertEquals(0x64.toByte(), pktMax.payload[i]) // 100 in hex = 0x64
        }
    }

    @Test
    fun `test 6 truncated op=177 payload throws PupCodecException`() {
        for (size in 0..5) {
            val truncated = PupFrameCodec.encodePacket(counter = 1, opcode = PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY, payload = ByteArray(size))
            try {
                PupFrameCodec.decodeMessage(truncated)
                fail("Expected PupCodecException for payload size $size")
            } catch (e: PupCodecException) {
                assertTrue(e.message!!.contains("AirAbsoluteGroupEnergy payload truncated"))
            }
        }
    }
}
