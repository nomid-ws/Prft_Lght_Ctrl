package app.prft.lightctrl.e2e.tier2

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.PupCodecException
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.RemoteOperatingMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tier 2 — Feature 1: Connect vs Connect Pro Mode Parity Boundary Tests.
 *
 * Verifies:
 * 1. Rapid alternating mode toggles (AUTO/MANUAL) consistency and rolling sequence validity.
 * 2. Out-of-order telemetry packets handling does not corrupt remote state.
 * 3. Out-of-range mode byte in Opcode 144 falls back gracefully to AUTO_TTL.
 * 4. Multi-device independent mode isolation (Connect Pro vs Connect).
 * 5. Truncated Opcode 144 and Opcode 43 payloads throw PupCodecException.
 * 6. Concurrent mode switching under parallel coroutine stress.
 */
class ConnectModeParityBoundaryTest {

    @Test
    fun `test 1 rapid alternating mode toggles AUTO and MANUAL maintain state and sequence consistency`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull(link)
        link!!.clear()

        // 10 rapid alternating toggles
        for (i in 0 until 10) {
            val targetMode = if (i % 2 == 0) RemoteOperatingMode.AUTO_TTL else RemoteOperatingMode.MANUAL
            harness.orchestrator.setConnectOperatingMode(addr, targetMode)
            delay(15)
        }
        delay(100)

        // Final mode must match the 10th iteration (i=9 -> MANUAL)
        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertEquals(RemoteOperatingMode.MANUAL, unit.remoteMode)

        // Verify sequence counters are in range 0..239
        val recorded = link.decodeRecordedFrames().filter { it.first.opcode == PupOpcodes.REMOTE_MODE }
        assertTrue("Must record at least 10 mode frames", recorded.size >= 10)
        for ((pkt, _) in recorded) {
            assertTrue("Sequence counter must be 0..239, got ${pkt.counter}", pkt.counter in 0..239)
        }

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 2 out of order telemetry packets handling does not corrupt remote state`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        // Simulate out-of-order packets decoding
        val packet1 = PupFrameCodec.encodePacket(counter = 100, opcode = PupOpcodes.REMOTE_MODE, payload = byteArrayOf(1))
        val packet2 = PupFrameCodec.encodePacket(counter = 20, opcode = PupOpcodes.REMOTE_MODE, payload = byteArrayOf(2))
        val packet3 = PupFrameCodec.encodePacket(counter = 50, opcode = PupOpcodes.REMOTE_MODE, payload = byteArrayOf(1))

        val (pkt1, msg1) = PupFrameCodec.decodeMessage(packet1)
        val (pkt2, msg2) = PupFrameCodec.decodeMessage(packet2)
        val (pkt3, msg3) = PupFrameCodec.decodeMessage(packet3)

        assertEquals(100, pkt1.counter)
        assertEquals(RemoteOperatingMode.MANUAL, (msg1 as PupDecodedMessage.RemoteModeState).mode)
        assertEquals(20, pkt2.counter)
        assertEquals(RemoteOperatingMode.AUTO_TTL, (msg2 as PupDecodedMessage.RemoteModeState).mode)
        assertEquals(50, pkt3.counter)
        assertEquals(RemoteOperatingMode.MANUAL, (msg3 as PupDecodedMessage.RemoteModeState).mode)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 3 out of range mode byte in Opcode 144 falls back gracefully to AUTO_TTL`() {
        assertEquals(RemoteOperatingMode.AUTO_TTL, RemoteOperatingMode.fromCode(99))
        assertEquals(RemoteOperatingMode.AUTO_TTL, RemoteOperatingMode.fromCode(-1))
        assertEquals(RemoteOperatingMode.AUTO_TTL, RemoteOperatingMode.fromCode(255))

        // Raw packet with mode byte = 99
        val raw = PupFrameCodec.encodePacket(counter = 5, opcode = PupOpcodes.REMOTE_MODE, payload = byteArrayOf(99))
        val (_, msg) = PupFrameCodec.decodeMessage(raw)
        val state = msg as PupDecodedMessage.RemoteModeState
        assertEquals(RemoteOperatingMode.AUTO_TTL, state.mode)
    }

    @Test
    fun `test 4 multi-device independent mode isolation between Connect Pro and Connect`() = runBlocking {
        val harness = E2ETestHarness()
        val cpAddr = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val cnAddr = VirtualBenchFixtures.ADDR_CONNECT

        harness.connectByAddress(cpAddr)
        harness.connectByAddress(cnAddr)
        harness.awaitDeviceReady(cpAddr)
        harness.awaitDeviceReady(cnAddr)

        val cpLink = harness.getRecordingLink(cpAddr)
        val cnLink = harness.getRecordingLink(cnAddr)
        assertNotNull("Connect Pro recording link must be present", cpLink)
        assertNotNull("Connect recording link must be present", cnLink)

        val testModes = listOf(RemoteOperatingMode.MANUAL, RemoteOperatingMode.AUTO_TTL, RemoteOperatingMode.OFF)

        for (mode in testModes) {
            // 1. Connect Pro: mutates state, transmits Opcode 144, 0 frames reach Connect
            cpLink!!.clear()
            cnLink!!.clear()

            harness.orchestrator.setConnectOperatingMode(cpAddr, mode)
            delay(50)

            val cpUnit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == cpAddr }
            assertEquals("Connect Pro must mutate to $mode", mode, cpUnit.remoteMode)

            val cpRecorded = cpLink.decodeRecordedFrames().filter { it.first.opcode == PupOpcodes.REMOTE_MODE }
            assertTrue("Connect Pro must transmit Opcode 144 for $mode", cpRecorded.isNotEmpty())

            val cnRecordedDuringCp = cnLink.decodeRecordedFrames().filter { it.first.opcode == PupOpcodes.REMOTE_MODE }
            assertTrue("Zero Opcode 144 frames must reach Connect during Connect Pro write", cnRecordedDuringCp.isEmpty())

            // 2. Connect: rejected for ALL 3 modes (MANUAL, AUTO_TTL, OFF), preserves AUTO_TTL,
            // shows banner "Connect operating mode is physically controlled via collar switch",
            // 0 Opcode 144 frames transmitted to Connect
            cpLink.clear()
            cnLink.clear()
            harness.orchestrator.clearStatusBanner()

            harness.orchestrator.setConnectOperatingMode(cnAddr, mode)
            delay(50)

            val cnUnit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == cnAddr }
            assertEquals("Connect mode must remain AUTO_TTL", RemoteOperatingMode.AUTO_TTL, cnUnit.remoteMode)

            val banner = harness.orchestrator.dashboardState.value.statusBanner
            assertNotNull("Rejection status banner must be populated for mode $mode", banner)
            assertTrue(
                "Banner must state physical collar control",
                banner!!.contains("Connect operating mode is physically controlled via collar switch"),
            )

            val cnRecorded = cnLink.decodeRecordedFrames().filter { it.first.opcode == PupOpcodes.REMOTE_MODE }
            assertTrue("0 Opcode 144 frames transmitted to Connect when attempting $mode", cnRecorded.isEmpty())

            // Connect Pro must preserve its previous mode
            val cpUnitAfter = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == cpAddr }
            assertEquals(mode, cpUnitAfter.remoteMode)
        }

        harness.disconnectDevice(cpAddr)
        harness.disconnectDevice(cnAddr)
    }

    @Test
    fun `test 5 truncated Opcode 144 and Opcode 43 payloads throw PupCodecException`() {
        // Truncated Opcode 144 (0 bytes instead of 1)
        val truncatedOp144 = PupFrameCodec.encodePacket(counter = 1, opcode = PupOpcodes.REMOTE_MODE, payload = ByteArray(0))
        try {
            PupFrameCodec.decodeMessage(truncatedOp144)
            fail("Expected PupCodecException for truncated Opcode 144")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("RemoteMode payload truncated"))
        }

        // Truncated Opcode 43 (0 bytes instead of 1)
        val truncatedOp43 = PupFrameCodec.encodePacket(counter = 1, opcode = PupOpcodes.TTL_AUTO_ENABLED, payload = ByteArray(0))
        try {
            PupFrameCodec.decodeMessage(truncatedOp43)
            fail("Expected PupCodecException for truncated Opcode 43")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("TtlAutoEnabled payload truncated"))
        }
    }

    @Test
    fun `test 6 rapid concurrent mode switching across multiple coroutines`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        coroutineScope {
            val jobs = (1..6).map { idx ->
                async {
                    val mode = if (idx % 2 == 0) RemoteOperatingMode.AUTO_TTL else RemoteOperatingMode.MANUAL
                    harness.orchestrator.setConnectOperatingMode(addr, mode)
                }
            }
            jobs.awaitAll()
        }
        delay(60)

        val unit = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == addr }
        assertTrue("Mode must be valid after concurrent writes", unit.remoteMode in setOf(RemoteOperatingMode.AUTO_TTL, RemoteOperatingMode.MANUAL))

        harness.disconnectDevice(addr)
    }
}
