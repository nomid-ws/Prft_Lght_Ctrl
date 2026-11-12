package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.HardwareCatalog
import app.prft.lightctrl.protocol.MonolightZoomMode
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.ReadySignalMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tier 1 — Feature 5: Monolight Zoom & Ready Signal Mode Coverage Tests.
 *
 * Verifies:
 * 1. Monolight Zoom Mode op=161 (MANUAL, AUTO) encode and decode.
 * 2. Zoom value op=163 (0..100) encode and decode.
 * 3. Ready Signal Mode op=217 (OFF, SOUND, DIM, SOUND_AND_DIM) encode and decode.
 * 4. MockBleTransport receives and mutates zoom and ready signal states.
 * 5. HardwareModelSpec capability detection for monolight archetypes.
 * 6. Monolight Zoom Range op=162 decoding.
 */
class MonolightZoomReadySignalCoverageTest {

    @Test
    fun `test 1 Monolight Zoom Mode op=161 MANUAL and AUTO encode and decode`() {
        val modes = listOf(
            MonolightZoomMode.MANUAL,
            MonolightZoomMode.AUTO,
        )

        for ((idx, mode) in modes.withIndex()) {
            val raw = PupFrameCodec.encodeZoomMode(counter = idx, mode = mode)
            val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

            assertEquals(idx, pkt.counter)
            assertEquals(PupOpcodes.ZOOM_MODE, pkt.opcode)
            assertEquals(1, pkt.payload.size)
            assertEquals(mode.code.toByte(), pkt.payload[0])

            val decoded = msg as PupDecodedMessage.MonolightZoomState
            assertEquals(mode, decoded.mode)
        }
    }

    @Test
    fun `test 2 Zoom value op=163 encode and decode 0 to 100`() {
        val positions = listOf(0, 10, 25, 50, 75, 90, 100)

        for ((idx, pos) in positions.withIndex()) {
            val raw = PupFrameCodec.encodeZoomValue(counter = idx + 10, position = pos)
            val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

            assertEquals(idx + 10, pkt.counter)
            assertEquals(PupOpcodes.ZOOM_VALUE, pkt.opcode)
            assertEquals(2, pkt.payload.size)

            val decoded = msg as PupDecodedMessage.MonolightZoomState
            assertEquals(pos, decoded.value)
        }
    }

    @Test
    fun `test 3 Ready Signal Mode op=217 encode and decode all 4 modes`() {
        val modes = listOf(
            ReadySignalMode.OFF,
            ReadySignalMode.SOUND,
            ReadySignalMode.DIM,
            ReadySignalMode.SOUND_AND_DIM,
        )

        for ((idx, mode) in modes.withIndex()) {
            val raw = PupFrameCodec.encodeReadySignalMode(counter = idx + 20, mode = mode)
            val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

            assertEquals(idx + 20, pkt.counter)
            assertEquals(PupOpcodes.READY_SIGNAL_MODE, pkt.opcode)
            assertEquals(1, pkt.payload.size)
            assertEquals(mode.code.toByte(), pkt.payload[0])

            val decoded = msg as PupDecodedMessage.ReadySignalState
            assertEquals(mode, decoded.mode)
        }
    }

    @Test
    fun `test 4 MockBleTransport receives and mutates zoom and ready signal states`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull(link)
        link!!.clear()

        // Command: Zoom Mode -> AUTO (1)
        val zoomModeFrame = PupFrameCodec.encodeZoomMode(counter = 30, mode = MonolightZoomMode.AUTO)
        link.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            zoomModeFrame,
        )

        // Command: Zoom Value -> 80
        val zoomValFrame = PupFrameCodec.encodeZoomValue(counter = 31, position = 80)
        link.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            zoomValFrame,
        )

        // Command: Ready Signal -> SOUND_AND_DIM (3)
        val readyFrame = PupFrameCodec.encodeReadySignalMode(counter = 32, mode = ReadySignalMode.SOUND_AND_DIM)
        link.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            readyFrame,
        )
        delay(60)

        // Query settings to inspect updated state in TLV dump
        val queryPkt = PupFrameCodec.encodeQueryAllSettingsV2(counter = 33)
        link.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            queryPkt,
        )
        delay(80)

        val recorded = link.decodeRecordedNotifications()
        val allSettingsPkt = recorded.firstOrNull { it.first.opcode == PupOpcodes.ALL_SETTINGS_V2 }

        assertNotNull("AllSettingsV2 dump must be present", allSettingsPkt)
        val dump = (allSettingsPkt!!.second as PupDecodedMessage.BulkSettingsDump).result

        val zoomModeEntry = dump.entries.firstOrNull { it.opcode == PupOpcodes.ZOOM_MODE }
        assertNotNull(zoomModeEntry)
        assertEquals(MonolightZoomMode.AUTO, (zoomModeEntry!!.decoded as PupDecodedMessage.MonolightZoomState).mode)

        val zoomValEntry = dump.entries.firstOrNull { it.opcode == PupOpcodes.ZOOM_VALUE }
        assertNotNull(zoomValEntry)
        assertEquals(80, (zoomValEntry!!.decoded as PupDecodedMessage.MonolightZoomState).value)

        val readyEntry = dump.entries.firstOrNull { it.opcode == PupOpcodes.READY_SIGNAL_MODE }
        assertNotNull(readyEntry)
        assertEquals(ReadySignalMode.SOUND_AND_DIM, (readyEntry!!.decoded as PupDecodedMessage.ReadySignalState).mode)

        harness.disconnectDevice(addr)
    }

    @Test
    fun `test 5 HardwareModelSpec capability detection for monolight archetypes`() {
        val b10xPro = HardwareCatalog.resolve(143)
        assertEquals(DeviceArchetype.MONOLIGHT, b10xPro.archetype)
        assertEquals(500, b10xPro.maxWattSeconds)
        assertTrue(b10xPro.supportsBiColorCct)
        assertTrue(b10xPro.supportsVideoDimming)

        val b10x = HardwareCatalog.resolve(144)
        assertEquals(DeviceArchetype.MONOLIGHT, b10x.archetype)
        assertEquals(250, b10x.maxWattSeconds)
        assertTrue(b10x.supportsBiColorCct)

        val a2 = HardwareCatalog.resolve(36)
        assertEquals(DeviceArchetype.MONOLIGHT, a2.archetype)
        assertEquals(100, a2.maxWattSeconds)
        assertFalse(a2.supportsBiColorCct)

        val connectPro = HardwareCatalog.resolve(162)
        assertEquals(DeviceArchetype.CONNECT_PRO_REMOTE, connectPro.archetype)
        assertNull(connectPro.maxWattSeconds)

        val connect = HardwareCatalog.resolve(151)
        assertEquals(DeviceArchetype.CONNECT_REMOTE, connect.archetype)
        assertNull(connect.maxWattSeconds)
    }

    @Test
    fun `test 6 Monolight Zoom Range op=162 decoding`() {
        // Construct 4-byte LE payload with min=0, max=100
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0.toShort())
            .putShort(100.toShort())
            .array()
        val raw = PupFrameCodec.encodePacket(counter = 40, opcode = PupOpcodes.ZOOM_RANGE, payload = payload)
        val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

        assertEquals(PupOpcodes.ZOOM_RANGE, pkt.opcode)
        val decoded = msg as PupDecodedMessage.MonolightZoomState
        assertEquals(0..100, decoded.range)
    }
}
