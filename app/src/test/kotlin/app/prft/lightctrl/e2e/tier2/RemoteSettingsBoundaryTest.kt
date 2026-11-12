package app.prft.lightctrl.e2e.tier2

import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AllSettingsTlvParser
import app.prft.lightctrl.protocol.BatteryChemistry
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.PupCodecException
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tier 2 — Feature 4: Remote Settings Sheet Boundary Tests.
 *
 * Verifies:
 * 1. Unknown enum codes fallback gracefully via fromCode without throwing.
 * 2. RF power level out-of-range codes decode to P4 fallback.
 * 3. AllSettingsV2 TLV dump decodes all remote settings records simultaneously.
 * 4. Out-of-bounds channel and sleep timeout values throw PupCodecException.
 * 5. Out-of-bounds brightness values are clamped safely.
 * 6. Truncated remote settings payloads throw PupCodecException.
 */
class RemoteSettingsBoundaryTest {

    @Test
    fun `test 1 unknown enum codes fallback gracefully via fromCode`() {
        // SyncTriggerMode unknown codes
        assertEquals(SyncTriggerMode.FIRST, SyncTriggerMode.fromCode(-1))
        assertEquals(SyncTriggerMode.FIRST, SyncTriggerMode.fromCode(99))

        // AfAssistMode unknown codes
        assertEquals(AfAssistMode.OFF, AfAssistMode.fromCode(-1))
        assertEquals(AfAssistMode.OFF, AfAssistMode.fromCode(99))

        // RfPowerLevel unknown codes
        assertEquals(RfPowerLevel.P4, RfPowerLevel.fromCode(-1))
        assertEquals(RfPowerLevel.P4, RfPowerLevel.fromCode(0))
        assertEquals(RfPowerLevel.P4, RfPowerLevel.fromCode(5))
        assertEquals(RfPowerLevel.P4, RfPowerLevel.fromCode(99))

        // ReadySignalMode unknown codes
        assertEquals(ReadySignalMode.SOUND, ReadySignalMode.fromCode(-1))
        assertEquals(ReadySignalMode.SOUND, ReadySignalMode.fromCode(99))

        // BatteryChemistry unknown codes
        assertEquals(BatteryChemistry.AUTO_DETECT, BatteryChemistry.fromCode(-1))
        assertEquals(BatteryChemistry.AUTO_DETECT, BatteryChemistry.fromCode(99))

        // DisplayBrightnessLevel unknown codes
        assertEquals(DisplayBrightnessLevel.HIGH, DisplayBrightnessLevel.fromCode(-1))
        assertEquals(DisplayBrightnessLevel.HIGH, DisplayBrightnessLevel.fromCode(99))
    }

    @Test
    fun `test 2 RF power level out-of-range codes decode to P4 fallback`() {
        val outOfRange = listOf(0, 5, 10, 255)
        for (code in outOfRange) {
            val raw = PupFrameCodec.encodePacket(counter = 1, opcode = PupOpcodes.RMI_RF_POWER, payload = byteArrayOf(code.toByte()))
            val (_, msg) = PupFrameCodec.decodeMessage(raw)
            val rf = msg as PupDecodedMessage.RemoteRfPower
            assertEquals("Code $code must fallback to P4", RfPowerLevel.P4, rf.level)
        }
    }

    @Test
    fun `test 3 AllSettingsV2 TLV dump decodes all remote settings records simultaneously`() {
        val tlvPayload = AllSettingsTlvParser.serialize(
            records = listOf(
                PupOpcodes.SYNC_MODE to byteArrayOf(SyncTriggerMode.GLOBAL_SHUTTER.code.toByte()),
                PupOpcodes.AF_ASSIST_MODE to byteArrayOf(AfAssistMode.ON.code.toByte()),
                PupOpcodes.RMI_RF_POWER to byteArrayOf(RfPowerLevel.P1.code.toByte()),
                PupOpcodes.RADIO_MODE_RANGE to byteArrayOf(1, 4),
                PupOpcodes.AUTO_OFF_TIMEOUT to byteArrayOf(120.toByte()),
                PupOpcodes.AUTO_STANDBY_TIMEOUT to byteArrayOf(45.toByte()),
                PupOpcodes.DISPLAY_BRIGHTNESS to byteArrayOf(DisplayBrightnessLevel.LOW.code.toByte()),
                PupOpcodes.BATTERY_TYPE to byteArrayOf(BatteryChemistry.LITHIUM.code.toByte()),
                PupOpcodes.CONTROL_SOUNDS_ENABLE to byteArrayOf(1),
            ),
            terminate = true,
        )
        val pkt = PupFrameCodec.encodePacket(counter = 99, opcode = PupOpcodes.ALL_SETTINGS_V2, payload = tlvPayload)
        val (_, decoded) = PupFrameCodec.decodeMessage(pkt)
        val dump = (decoded as PupDecodedMessage.BulkSettingsDump).result

        val sync = dump.entries.first { it.opcode == PupOpcodes.SYNC_MODE }.decoded as PupDecodedMessage.RemoteSyncMode
        assertEquals(SyncTriggerMode.GLOBAL_SHUTTER, sync.mode)

        val af = dump.entries.first { it.opcode == PupOpcodes.AF_ASSIST_MODE }.decoded as PupDecodedMessage.RemoteAfAssist
        assertEquals(AfAssistMode.ON, af.mode)

        val rf = dump.entries.first { it.opcode == PupOpcodes.RMI_RF_POWER }.decoded as PupDecodedMessage.RemoteRfPower
        assertEquals(RfPowerLevel.P1, rf.level)

        val autoOff = dump.entries.first { it.opcode == PupOpcodes.AUTO_OFF_TIMEOUT }.decoded as PupDecodedMessage.AutoOffMinutes
        assertEquals(120, autoOff.minutes)

        val standby = dump.entries.first { it.opcode == PupOpcodes.AUTO_STANDBY_TIMEOUT }.decoded as PupDecodedMessage.AutoStandbyMinutes
        assertEquals(45, standby.minutes)

        val brightness = dump.entries.first { it.opcode == PupOpcodes.DISPLAY_BRIGHTNESS }.decoded as PupDecodedMessage.DisplayBrightness
        assertEquals(DisplayBrightnessLevel.LOW, brightness.level)

        val battery = dump.entries.first { it.opcode == PupOpcodes.BATTERY_TYPE }.decoded as PupDecodedMessage.BatteryTypeSetting
        assertEquals(BatteryChemistry.LITHIUM, battery.chemistry)

        val sounds = dump.entries.first { it.opcode == PupOpcodes.CONTROL_SOUNDS_ENABLE }.decoded as PupDecodedMessage.ControlSoundsState
        assertTrue(sounds.enabled)
    }

    @Test
    fun `test 4 out-of-bounds channel and sleep timeout values throw PupCodecException`() {
        // Channel boundaries (1..100)
        try {
            PupFrameCodec.encodeAirChannel(counter = 1, channel = 0)
            fail("Expected exception for channel 0")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("Air channel out of range"))
        }

        try {
            PupFrameCodec.encodeAirChannel(counter = 1, channel = 101)
            fail("Expected exception for channel 101")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("Air channel out of range"))
        }

        // Auto-off boundaries (0..255)
        try {
            PupFrameCodec.encodeAutoOffTimeout(counter = 1, minutes = -1)
            fail("Expected exception for auto-off -1")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("Auto-off timeout out of range"))
        }

        try {
            PupFrameCodec.encodeAutoOffTimeout(counter = 1, minutes = 256)
            fail("Expected exception for auto-off 256")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("Auto-off timeout out of range"))
        }

        // Auto-standby boundaries (0..255)
        try {
            PupFrameCodec.encodeAutoStandbyTimeout(counter = 1, minutes = -1)
            fail("Expected exception for auto-standby -1")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("Auto-standby timeout out of range"))
        }

        try {
            PupFrameCodec.encodeAutoStandbyTimeout(counter = 1, minutes = 256)
            fail("Expected exception for auto-standby 256")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("Auto-standby timeout out of range"))
        }
    }

    @Test
    fun `test 5 out-of-bounds brightness values are clamped safely`() {
        // -10 clamped to 0 (LOW)
        val rawLow = PupFrameCodec.encodeDisplayBrightness(counter = 1, brightness = -10)
        assertEquals(0.toByte(), rawLow[3])
        val (_, msgLow) = PupFrameCodec.decodeMessage(rawLow)
        assertEquals(DisplayBrightnessLevel.LOW, (msgLow as PupDecodedMessage.DisplayBrightness).level)

        // 10 clamped to 3 (AUTO)
        val rawHigh = PupFrameCodec.encodeDisplayBrightness(counter = 2, brightness = 10)
        assertEquals(3.toByte(), rawHigh[3])
        val (_, msgHigh) = PupFrameCodec.decodeMessage(rawHigh)
        assertEquals(DisplayBrightnessLevel.AUTO, (msgHigh as PupDecodedMessage.DisplayBrightness).level)
    }

    @Test
    fun `test 6 truncated remote settings payloads throw PupCodecException`() {
        val opcodesToTest = listOf(
            PupOpcodes.SYNC_MODE,
            PupOpcodes.AF_ASSIST_MODE,
            PupOpcodes.RMI_RF_POWER,
            PupOpcodes.AUTO_OFF_TIMEOUT,
            PupOpcodes.AUTO_STANDBY_TIMEOUT,
            PupOpcodes.DISPLAY_BRIGHTNESS,
            PupOpcodes.BATTERY_TYPE,
            PupOpcodes.CONTROL_SOUNDS_ENABLE,
        )

        for (op in opcodesToTest) {
            val truncated = PupFrameCodec.encodePacket(counter = 1, opcode = op, payload = ByteArray(0))
            try {
                PupFrameCodec.decodeMessage(truncated)
                fail("Expected PupCodecException for truncated opcode $op")
            } catch (e: PupCodecException) {
                assertTrue(e.message!!.contains("payload truncated"))
            }
        }

        // Radio mode range requires 2 bytes
        val truncatedRange = PupFrameCodec.encodePacket(counter = 1, opcode = PupOpcodes.RADIO_MODE_RANGE, payload = byteArrayOf(1))
        try {
            PupFrameCodec.decodeMessage(truncatedRange)
            fail("Expected PupCodecException for truncated radio mode range")
        } catch (e: PupCodecException) {
            assertTrue(e.message!!.contains("RadioModeRange payload truncated"))
        }
    }
}
