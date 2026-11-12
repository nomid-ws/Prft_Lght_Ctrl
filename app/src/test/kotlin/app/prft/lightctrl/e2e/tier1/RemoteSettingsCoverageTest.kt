package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.BatteryChemistry
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — Feature 4: Remote Settings Sheet Coverage Tests.
 *
 * Verifies:
 * 1. Sync Mode (op=140) encoders & decoders (FIRST, SECOND, X_SYNC, AUTO_FP, GLOBAL_SHUTTER).
 * 2. AF-Assist Mode (op=160) encoders & decoders (OFF, AUTO, ON).
 * 3. RF Power Level (op=147) encoders & decoders (P1..P4) and RADIO_MODE_RANGE (op=148).
 * 4. Sleep / Auto-Off (op=40/41), Brightness (op=39), Battery Type (op=180), Sounds (op=38).
 * 5. Fast channel pad (1..100) op=11.
 * 6. MockBleTransport mutates remote settings state on receiving commands.
 */
class RemoteSettingsCoverageTest {

    @Test
    fun `test 1 Sync Mode op=140 encoders and decoders for all 5 modes`() {
        val modes = listOf(
            SyncTriggerMode.FIRST,
            SyncTriggerMode.SECOND,
            SyncTriggerMode.X_SYNC,
            SyncTriggerMode.AUTO_FP,
            SyncTriggerMode.GLOBAL_SHUTTER,
        )

        for ((idx, mode) in modes.withIndex()) {
            val raw = PupFrameCodec.encodeSyncMode(counter = idx, mode = mode)
            val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

            assertEquals(idx, pkt.counter)
            assertEquals(PupOpcodes.SYNC_MODE, pkt.opcode)
            assertEquals(1, pkt.payload.size)
            assertEquals(mode.code.toByte(), pkt.payload[0])

            val decoded = msg as PupDecodedMessage.RemoteSyncMode
            assertEquals(mode, decoded.mode)
        }
    }

    @Test
    fun `test 2 AF-Assist Mode op=160 encoders and decoders for all 3 modes`() {
        val modes = listOf(
            AfAssistMode.OFF,
            AfAssistMode.AUTO,
            AfAssistMode.ON,
        )

        for ((idx, mode) in modes.withIndex()) {
            val raw = PupFrameCodec.encodeAfAssistMode(counter = idx + 10, mode = mode)
            val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

            assertEquals(idx + 10, pkt.counter)
            assertEquals(PupOpcodes.AF_ASSIST_MODE, pkt.opcode)
            assertEquals(1, pkt.payload.size)
            assertEquals(mode.code.toByte(), pkt.payload[0])

            val decoded = msg as PupDecodedMessage.RemoteAfAssist
            assertEquals(mode, decoded.mode)
        }
    }

    @Test
    fun `test 3 RF Power Level op=147 encoders and decoders for P1 through P4`() {
        val levels = listOf(
            RfPowerLevel.P1,
            RfPowerLevel.P2,
            RfPowerLevel.P3,
            RfPowerLevel.P4,
        )

        for ((idx, level) in levels.withIndex()) {
            val raw = PupFrameCodec.encodeRfPower(counter = idx + 20, level = level)
            val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

            assertEquals(idx + 20, pkt.counter)
            assertEquals(PupOpcodes.RMI_RF_POWER, pkt.opcode)
            assertEquals(level.code.toByte(), pkt.payload[0])

            val decoded = msg as PupDecodedMessage.RemoteRfPower
            assertEquals(level, decoded.level)
        }

        // Also test radio mode range op=148
        val rangeRaw = PupFrameCodec.encodePacket(counter = 25, opcode = PupOpcodes.RADIO_MODE_RANGE, payload = byteArrayOf(1, 4))
        val (_, rangeMsg) = PupFrameCodec.decodeMessage(rangeRaw)
        val range = rangeMsg as PupDecodedMessage.RfPowerRange
        assertEquals(1, range.minDbm)
        assertEquals(4, range.maxDbm)
    }

    @Test
    fun `test 4 Sleep op=40, Auto-Off op=41, Brightness op=39, Battery Type op=180, Sounds op=38`() {
        // Opcode 40: Auto-Standby Timeout (30 min)
        val rawStandby = PupFrameCodec.encodeAutoStandbyTimeout(counter = 30, minutes = 30)
        val (pktStandby, msgStandby) = PupFrameCodec.decodeMessage(rawStandby)
        assertEquals(PupOpcodes.AUTO_STANDBY_TIMEOUT, pktStandby.opcode)
        assertEquals(30, (msgStandby as PupDecodedMessage.AutoStandbyMinutes).minutes)

        // Opcode 41: Auto-Off Timeout (90 min)
        val rawOff = PupFrameCodec.encodeAutoOffTimeout(counter = 31, minutes = 90)
        val (pktOff, msgOff) = PupFrameCodec.decodeMessage(rawOff)
        assertEquals(PupOpcodes.AUTO_OFF_TIMEOUT, pktOff.opcode)
        assertEquals(90, (msgOff as PupDecodedMessage.AutoOffMinutes).minutes)

        // Opcode 39: Display Brightness (HIGH = 2)
        val rawBright = PupFrameCodec.encodeDisplayBrightness(counter = 32, level = DisplayBrightnessLevel.HIGH)
        val (pktBright, msgBright) = PupFrameCodec.decodeMessage(rawBright)
        assertEquals(PupOpcodes.DISPLAY_BRIGHTNESS, pktBright.opcode)
        assertEquals(DisplayBrightnessLevel.HIGH, (msgBright as PupDecodedMessage.DisplayBrightness).level)

        // Opcode 180: Battery Chemistry (NiMH = 1)
        val rawBat = PupFrameCodec.encodeBatteryChemistry(counter = 33, chemistry = BatteryChemistry.NIMH)
        val (pktBat, msgBat) = PupFrameCodec.decodeMessage(rawBat)
        assertEquals(PupOpcodes.BATTERY_TYPE, pktBat.opcode)
        assertEquals(BatteryChemistry.NIMH, (msgBat as PupDecodedMessage.BatteryTypeSetting).chemistry)

        // Opcode 38: Sounds (true & false)
        val rawSoundsOn = PupFrameCodec.encodeControlSounds(counter = 34, enabled = true)
        val (_, msgSoundsOn) = PupFrameCodec.decodeMessage(rawSoundsOn)
        assertTrue((msgSoundsOn as PupDecodedMessage.ControlSoundsState).enabled)

        val rawSoundsOff = PupFrameCodec.encodeControlSounds(counter = 35, enabled = false)
        val (_, msgSoundsOff) = PupFrameCodec.decodeMessage(rawSoundsOff)
        assertFalse((msgSoundsOff as PupDecodedMessage.ControlSoundsState).enabled)
    }

    @Test
    fun `test 5 fast channel pad op=11 across full range 1 to 100`() {
        val testChannels = listOf(1, 10, 20, 50, 75, 99, 100)
        for ((idx, ch) in testChannels.withIndex()) {
            val raw = PupFrameCodec.encodeAirChannel(counter = idx, channel = ch)
            val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

            assertEquals(PupOpcodes.AIR_CHANNEL, pkt.opcode)
            assertEquals(ch.toByte(), pkt.payload[0])
            assertEquals(ch, (msg as PupDecodedMessage.AirChannelAssignment).channel)
        }
    }

    @Test
    fun `test 6 MockBleTransport mutates remote settings state on receiving commands`() = runBlocking {
        val harness = E2ETestHarness()
        val addr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(addr)
        harness.awaitDeviceReady(addr)

        val link = harness.getRecordingLink(addr)
        assertNotNull(link)
        link!!.clear()

        // Command: Sync Mode -> AUTO_FP (3)
        val syncFrame = PupFrameCodec.encodeSyncMode(counter = 50, mode = SyncTriggerMode.AUTO_FP)
        link.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.AUTH_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            syncFrame,
        )
        // Command: RF Power -> P2 (2)
        val rfFrame = PupFrameCodec.encodeRfPower(counter = 51, level = RfPowerLevel.P2)
        link.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.AUTH_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            rfFrame,
        )
        delay(60)

        // Query settings to verify MockBleTransport recorded state
        val queryPkt = PupFrameCodec.encodeQueryAllSettingsV2(counter = 52)
        link.writeCharacteristicNoResponse(
            app.prft.lightctrl.ble.ProfotoGattProfile.AUTH_SERVICE_UUID,
            app.prft.lightctrl.ble.ProfotoGattProfile.PUP_TX_CHAR_UUID,
            queryPkt,
        )
        delay(80)

        val recorded = link.decodeRecordedNotifications()
        val allSettingsPkt = recorded.firstOrNull { it.first.opcode == PupOpcodes.ALL_SETTINGS_V2 }

        assertNotNull("AllSettingsV2 dump must be emitted by MockBleTransport", allSettingsPkt)
        val dump = (allSettingsPkt!!.second as PupDecodedMessage.BulkSettingsDump).result

        val syncEntry = dump.entries.firstOrNull { it.opcode == PupOpcodes.SYNC_MODE }
        assertNotNull(syncEntry)
        assertEquals(SyncTriggerMode.AUTO_FP, (syncEntry!!.decoded as PupDecodedMessage.RemoteSyncMode).mode)

        val rfEntry = dump.entries.firstOrNull { it.opcode == PupOpcodes.RMI_RF_POWER }
        assertNotNull(rfEntry)
        assertEquals(RfPowerLevel.P2, (rfEntry!!.decoded as PupDecodedMessage.RemoteRfPower).level)

        harness.disconnectDevice(addr)
    }
}
