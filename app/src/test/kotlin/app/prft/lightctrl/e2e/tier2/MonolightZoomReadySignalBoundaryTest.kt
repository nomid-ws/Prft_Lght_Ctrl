package app.prft.lightctrl.e2e.tier2

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.MonolightZoomMode
import app.prft.lightctrl.protocol.PupCodecException
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.ReadySignalMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tier 2 — Feature 5: Monolight Zoom & Ready Signal Mode Boundary Tests.
 *
 * Verifies:
 * 1. Zoom position negative or exceeding uint16 throws PupCodecException.
 * 2. Zoom range query op=162 decodes min and max correctly across custom bounds.
 * 3. Ready signal mode fallback for unknown codes defaults to SOUND.
 * 4. Non-zoom remote fixtures omit zoom records from AllSettingsV2 dump.
 * 5. Truncated zoom and ready signal payloads throw PupCodecException.
 * 6. MonolightZoomMode fallback for unknown codes defaults to AUTO.
 */
class MonolightZoomReadySignalBoundaryTest {

    @Test
    fun `test 1 zoom position negative or exceeding uint16 throws PupCodecException`() {
        try {
            PupFrameCodec.encodeZoomValue(counter = 1, position = -1)
            fail("Expected PupCodecException for position -1")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("Zoom position out of range"))
        }

        try {
            PupFrameCodec.encodeZoomValue(counter = 2, position = 65536)
            fail("Expected PupCodecException for position 65536")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("Zoom position out of range"))
        }
    }

    @Test
    fun `test 2 Zoom range query op=162 decodes min and max correctly across custom bounds`() {
        // Range 24..105 (standard zoom focal mm equivalent)
        val payload1 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(24.toShort())
            .putShort(105.toShort())
            .array()
        val raw1 = PupFrameCodec.encodePacket(counter = 10, opcode = PupOpcodes.ZOOM_RANGE, payload = payload1)
        val (_, msg1) = PupFrameCodec.decodeMessage(raw1)
        val zoom1 = msg1 as PupDecodedMessage.MonolightZoomState
        assertEquals(24..105, zoom1.range)

        // Range 0..100 (percentage scale)
        val payload2 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0.toShort())
            .putShort(100.toShort())
            .array()
        val raw2 = PupFrameCodec.encodePacket(counter = 11, opcode = PupOpcodes.ZOOM_RANGE, payload = payload2)
        val (_, msg2) = PupFrameCodec.decodeMessage(raw2)
        val zoom2 = msg2 as PupDecodedMessage.MonolightZoomState
        assertEquals(0..100, zoom2.range)
    }

    @Test
    fun `test 3 Ready signal mode fallback for unknown codes defaults to SOUND`() {
        val unknownCodes = listOf(-1, 4, 10, 99)
        for (code in unknownCodes) {
            assertEquals("Code $code must fallback to SOUND", ReadySignalMode.SOUND, ReadySignalMode.fromCode(code))

            val raw = PupFrameCodec.encodePacket(counter = 1, opcode = PupOpcodes.READY_SIGNAL_MODE, payload = byteArrayOf(code.toByte()))
            val (_, msg) = PupFrameCodec.decodeMessage(raw)
            val ready = msg as PupDecodedMessage.ReadySignalState
            assertEquals(ReadySignalMode.SOUND, ready.mode)
        }
    }

    @Test
    fun `test 4 non-zoom remote fixtures omit zoom records from AllSettingsV2 dump`() = runBlocking {
        val harness = E2ETestHarness()
        val cpAddr = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val cnAddr = VirtualBenchFixtures.ADDR_CONNECT

        harness.connectByAddress(cpAddr)
        harness.connectByAddress(cnAddr)
        harness.awaitDeviceReady(cpAddr)
        harness.awaitDeviceReady(cnAddr)

        // Query Connect Pro
        val cpLink = harness.getRecordingLink(cpAddr)!!
        cpLink.clear()
        cpLink.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.AUTH_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            PupFrameCodec.encodeQueryAllSettingsV2(counter = 1),
        )
        delay(80)

        val cpDumpPkt = cpLink.decodeRecordedNotifications().firstOrNull { it.first.opcode == PupOpcodes.ALL_SETTINGS_V2 }
        assertNotNull("Connect Pro must emit AllSettingsV2", cpDumpPkt)
        val cpDump = (cpDumpPkt!!.second as PupDecodedMessage.BulkSettingsDump).result
        val cpZoomEntries = cpDump.entries.filter { it.opcode in setOf(PupOpcodes.ZOOM_MODE, PupOpcodes.ZOOM_RANGE, PupOpcodes.ZOOM_VALUE) }
        assertTrue("Connect Pro must NOT report zoom opcodes", cpZoomEntries.isEmpty())

        // Query Connect
        val cnLink = harness.getRecordingLink(cnAddr)!!
        cnLink.clear()
        cnLink.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.LEGACY_BASE_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            PupFrameCodec.encodeQueryAllSettingsV2(counter = 2),
        )
        delay(80)

        val cnDumpPkt = cnLink.decodeRecordedNotifications().firstOrNull { it.first.opcode == PupOpcodes.ALL_SETTINGS_V2 }
        assertNotNull("Connect must emit AllSettingsV2", cnDumpPkt)
        val cnDump = (cnDumpPkt!!.second as PupDecodedMessage.BulkSettingsDump).result
        val cnZoomEntries = cnDump.entries.filter { it.opcode in setOf(PupOpcodes.ZOOM_MODE, PupOpcodes.ZOOM_RANGE, PupOpcodes.ZOOM_VALUE) }
        assertTrue("Connect must NOT report zoom opcodes", cnZoomEntries.isEmpty())

        harness.disconnectDevice(cpAddr)
        harness.disconnectDevice(cnAddr)
    }

    @Test
    fun `test 5 truncated zoom and ready signal payloads throw PupCodecException`() {
        // Opcode 161 (ZOOM_MODE): requires 1 byte
        try {
            PupFrameCodec.decodeMessage(PupFrameCodec.encodePacket(counter = 1, opcode = PupOpcodes.ZOOM_MODE, payload = ByteArray(0)))
            fail("Expected exception for truncated ZOOM_MODE")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("ZoomMode payload truncated"))
        }

        // Opcode 162 (ZOOM_RANGE): requires 4 bytes
        for (size in 0..3) {
            try {
                PupFrameCodec.decodeMessage(PupFrameCodec.encodePacket(counter = 2, opcode = PupOpcodes.ZOOM_RANGE, payload = ByteArray(size)))
                fail("Expected exception for truncated ZOOM_RANGE size $size")
            } catch (e: PupCodecException) {
                assertTrue(e.message!!.contains("ZoomRange payload truncated"))
            }
        }

        // Opcode 163 (ZOOM_VALUE): requires 2 bytes
        for (size in 0..1) {
            try {
                PupFrameCodec.decodeMessage(PupFrameCodec.encodePacket(counter = 3, opcode = PupOpcodes.ZOOM_VALUE, payload = ByteArray(size)))
                fail("Expected exception for truncated ZOOM_VALUE size $size")
            } catch (e: PupCodecException) {
                assertTrue(e.message!!.contains("ZoomValue payload truncated"))
            }
        }

        // Opcode 217 (READY_SIGNAL_MODE): requires 1 byte
        try {
            PupFrameCodec.decodeMessage(PupFrameCodec.encodePacket(counter = 4, opcode = PupOpcodes.READY_SIGNAL_MODE, payload = ByteArray(0)))
            fail("Expected exception for truncated READY_SIGNAL_MODE")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("ReadySignalMode payload truncated"))
        }
    }

    @Test
    fun `test 6 MonolightZoomMode fallback for unknown codes defaults to AUTO`() {
        assertEquals(MonolightZoomMode.AUTO, MonolightZoomMode.fromCode(-1))
        assertEquals(MonolightZoomMode.AUTO, MonolightZoomMode.fromCode(99))

        val raw = PupFrameCodec.encodePacket(counter = 1, opcode = PupOpcodes.ZOOM_MODE, payload = byteArrayOf(99))
        val (_, msg) = PupFrameCodec.decodeMessage(raw)
        val zoom = msg as PupDecodedMessage.MonolightZoomState
        assertEquals(MonolightZoomMode.AUTO, zoom.mode)
    }
}
