package app.prft.lightctrl.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PupFrameCodecTest {

    @Test
    fun `sequence counter wraps strictly modulo 240`() {
        val counter = SequenceCounter(238)
        assertEquals(238, counter.next())
        assertEquals(239, counter.next())
        assertEquals(0, counter.next())
        assertEquals(1, counter.next())
    }

    @Test
    fun `quarantined firmware opcodes throw PupSecurityQuarantineException`() {
        for (forbidden in listOf(46, 142, 154)) {
            var blocked = false
            try {
                PupFrameCodec.encodePacket(counter = 1, opcode = forbidden)
            } catch (_: PupSecurityQuarantineException) {
                blocked = true
            }
            assertTrue("Expected opcode $forbidden to be quarantined", blocked)
        }
    }

    @Test
    fun `B10X Plus and A2 flash power tenth-stop wire layout matches spec`() {
        // 7.4 stops -> 74 (0x4A), counter = 5 -> [0x05, 0x12, 0x00, 0x4A]
        val raw = PupFrameCodec.encodeFlashPowerFStops(counter = 5, fStops = 7.4f)
        assertArrayEquals(byteArrayOf(0x05, 0x12, 0x00, 0x4A), raw)

        val (pkt, msg) = PupFrameCodec.decodeMessage(raw)
        assertEquals(5, pkt.counter)
        assertEquals(PupOpcodes.MX_ENERGY, pkt.opcode)
        assertFalse(pkt.isQuery)
        val power = msg as PupDecodedMessage.FlashPower
        assertEquals(74, power.tenthStops)
        assertEquals(7.4f, power.fStops, 0.01f)
    }

    @Test
    fun `B10X Plus 3250lm continuous LED Kelvin CCT and FlickerFree mode roundtrip`() {
        // 5600K = 0x15E0 little-endian -> E0 15, opcode 36 (0x0024)
        val rawCct = PupFrameCodec.encodeVideoLightCct(counter = 3, kelvin = 5600)
        assertArrayEquals(byteArrayOf(0x03, 0x24, 0x00, 0xE0.toByte(), 0x15), rawCct)
        val (_, cctMsg) = PupFrameCodec.decodeMessage(rawCct)
        assertEquals(5600, (cctMsg as PupDecodedMessage.VideoLightCct).kelvin)

        val rawMode = PupFrameCodec.encodeVideoLightPowerMode(counter = 4, mode = VideoLightPowerMode.FLICKER_FREE)
        val (_, modeMsg) = PupFrameCodec.decodeMessage(rawMode)
        assertEquals(VideoLightPowerMode.FLICKER_FREE, (modeMsg as PupDecodedMessage.VideoLightMode).mode)

        val rawOrient = PupFrameCodec.encodeDisplayOrientation(counter = 5, orientation = DisplayOrientationMode.INVERTED)
        val (_, orientMsg) = PupFrameCodec.decodeMessage(rawOrient)
        assertEquals(DisplayOrientationMode.INVERTED, (orientMsg as PupDecodedMessage.DisplayOrientation).orientation)
    }

    @Test
    fun `Connect Pro 6-group absolute energy and 100-channel AirX roundtrip`() {
        val groups = listOf(100, 85, 70, 55, 40, 25)
        val raw = PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 12, groupTenthStops = groups)
        val (_, msg) = PupFrameCodec.decodeMessage(raw)
        assertEquals(groups, (msg as PupDecodedMessage.AirAbsoluteGroupPower).groupTenthStops)

        val rawCh = PupFrameCodec.encodeAirChannel(counter = 13, channel = 88)
        val (_, chMsg) = PupFrameCodec.decodeMessage(rawCh)
        assertEquals(88, (chMsg as PupDecodedMessage.AirChannelAssignment).channel)
    }

    @Test
    fun `Connect AirTTL negative EV compensation signed int8 roundtrip`() {
        val raw = PupFrameCodec.encodeAirTtlEvComp(counter = 9, tenthStops = -13) // -1.3 EV
        val (_, msg) = PupFrameCodec.decodeMessage(raw)
        val ev = msg as PupDecodedMessage.AirTtlEvCompensation
        assertEquals(-13, ev.tenthStops)
        assertEquals(-1.3f, ev.ev, 0.01f)
    }

    @Test
    fun `AllSettingsV2 TLV stream hydrates B10X Plus profile and terminates on zero`() {
        val tlvPayload = AllSettingsTlvParser.serialize(
            records = listOf(
                PupOpcodes.HARDWARE_ID to byteArrayOf(143.toByte()), // B10X Plus 500Ws
                PupOpcodes.MX_ENERGY to byteArrayOf(80),             // 8.0 stops
                PupOpcodes.VIDEO_LIGHT_COLOR_TEMPERATURE to byteArrayOf(0xB8.toByte(), 0x0B), // 3000K
                PupOpcodes.BATTERY_CHARGER_CONNECTED to byteArrayOf(1),
                PupOpcodes.BATTERY_INDICATOR to byteArrayOf(4),
            ),
            terminate = true,
        )
        val packetBytes = PupFrameCodec.encodePacket(counter = 21, opcode = PupOpcodes.ALL_SETTINGS_V2, payload = tlvPayload)
        val (_, decoded) = PupFrameCodec.decodeMessage(packetBytes)
        val dump = (decoded as PupDecodedMessage.BulkSettingsDump).result
        assertTrue(dump.finished)
        assertEquals(5, dump.entries.size)
        assertEquals(143, (dump.entries[0].decoded as PupDecodedMessage.HardwareProductId).productId)
        assertEquals("Profoto B10X Pro / Plus", HardwareCatalog.resolve(143).marketingName)
        assertEquals(80, (dump.entries[1].decoded as PupDecodedMessage.FlashPower).tenthStops)
        assertEquals(3000, (dump.entries[2].decoded as PupDecodedMessage.VideoLightCct).kelvin)
        assertTrue((dump.entries[3].decoded as PupDecodedMessage.ChargerStatus).pluggedIn)
    }

    @Test
    fun `FlashSchedulingReceivedEvt and FlashFiredEvt decode recycle and misfire telemetry`() {
        val recyclePayload = byteArrayOf(0x00, 0xDC.toByte(), 0x05, 0x00, 0x00) // 1500 ms
        val recyclePkt = PupFrameCodec.encodePacket(7, PupOpcodes.FLASH_SCHEDULING_RECEIVED_EVT, recyclePayload)
        val (_, recycleMsg) = PupFrameCodec.decodeMessage(recyclePkt)
        val evt = recycleMsg as PupDecodedMessage.FlashRecycleEvent
        assertEquals(FlashSchedulingState.SCHEDULED_OK, evt.state)
        assertEquals(1500L, evt.rechargeTimeMs)

        // Normal discharge (rawState = 99 -> unmapped -> null error -> isOk = true)
        val okPayload = byteArrayOf(0x63, 0x00)
        val (_, okFired) = PupFrameCodec.decodeMessage(
            PupFrameCodec.encodePacket(8, PupOpcodes.FLASH_FIRED_EVT, okPayload)
        )
        assertNull((okFired as PupDecodedMessage.FlashFiredResult).error)
        assertTrue(okFired.isOk)
    }

    @Test
    fun `HardwareCatalog resolves B10X Plus, B10X, A2, Connect Pro Nikon, and Connect Nikon accurately`() {
        assertEquals(DeviceArchetype.MONOLIGHT, HardwareCatalog.resolve(143).archetype)
        assertEquals(DeviceArchetype.MONOLIGHT, HardwareCatalog.resolve(144).archetype)
        assertEquals(DeviceArchetype.MONOLIGHT, HardwareCatalog.resolve(36).archetype)
        assertFalse(HardwareCatalog.resolve(36).supportsBiColorCct)
        assertEquals("Profoto Connect Pro (Nikon)", HardwareCatalog.resolve(162, "Connect-9999").marketingName)
        assertEquals("Profoto Connect (Nikon)", HardwareCatalog.resolve(151, "Connect-7076").marketingName)
        assertEquals(DeviceArchetype.CONNECT_PRO_REMOTE, HardwareCatalog.resolve(162).archetype)
        assertEquals(DeviceArchetype.CONNECT_REMOTE, HardwareCatalog.resolve(151).archetype)
        assertTrue(HardwareCatalog.resolve(151).usesLegacyV1Queries)
    }

    @Test
    fun `Profoto A2 V2 head opcodes, PxEnergy separation, RemoteMode enum, and Opcode 135 byte order match hardware`() {
        // Live Profoto A2 AllSettingsV2 sequence: op=803(head=0, 71 -> 7.1 stops) followed by op=20(PxEnergy=0)
        val tlv = AllSettingsTlvParser.serialize(
            records = listOf(
                PupOpcodes.HARDWARE_ID to byteArrayOf(36),
                PupOpcodes.AIR_GROUP_V2 to byteArrayOf(0, AirGroup.GROUP_B.code.toByte()),
                PupOpcodes.MX_ENERGY_V2 to byteArrayOf(0, 71),
                PupOpcodes.PX_ENERGY to byteArrayOf(0),
                PupOpcodes.PX_ENERGY_RANGE to byteArrayOf((-20).toByte(), 20),
                PupOpcodes.HEAD_ON_V2 to byteArrayOf(0, 1),
                PupOpcodes.VIDEO_LIGHT_ENABLE_V2 to byteArrayOf(0, 0),
                PupOpcodes.REMOTE_MODE to byteArrayOf(2), // 2 = Auto (TTL) per L7/d5.java
            ),
            terminate = true,
        )
        val dump = (PupFrameCodec.decodePayload(PupOpcodes.ALL_SETTINGS_V2, tlv) as PupDecodedMessage.BulkSettingsDump).result
        val flashV2 = dump.entries[2].decoded as PupDecodedMessage.FlashPowerV2
        assertEquals(0, flashV2.headId)
        assertEquals(71, flashV2.tenthStops)
        assertTrue(dump.entries[3].decoded is PupDecodedMessage.PxTtlBias)
        assertTrue(dump.entries[4].decoded is PupDecodedMessage.PxTtlBiasRange)
        val remoteMode = dump.entries[7].decoded as PupDecodedMessage.RemoteModeState
        assertEquals(RemoteOperatingMode.AUTO_TTL, remoteMode.mode)

        // Opcode 135: Byte 0 = amount (-5), Byte 1 = group (GROUP_B = 2, ALL = 7 per L7/c bytecode)
        val trimBytes = PupFrameCodec.encodeAdjustAirMxEnergyIfAble(counter = 4, group = AirGroup.GROUP_B, stepTenthStops = -5)
        assertArrayEquals(byteArrayOf(0x04, 0x87.toByte(), 0x00, (-5).toByte(), 0x02), trimBytes)
        val trimAllBytes = PupFrameCodec.encodeAdjustAirMxEnergyIfAble(counter = 5, group = AirGroup.ALL, stepTenthStops = 5)
        assertArrayEquals(byteArrayOf(0x05, 0x87.toByte(), 0x00, 0x05, 0x07), trimAllBytes)

        // Opcode 131 (AirPxBias): Byte 0 = signed int8 bias (-7 -> -0.7 EV), Byte 1 = group (ALL = 7)
        val biasBytes = PupFrameCodec.encodeAirPxBias(counter = 6, tenthStops = -7, group = AirGroup.ALL)
        assertArrayEquals(byteArrayOf(0x06, 0x83.toByte(), 0x00, (-7).toByte(), 0x07), biasBytes)
        val (_, biasMsg) = PupFrameCodec.decodeMessage(biasBytes)
        val bias = biasMsg as PupDecodedMessage.AirGroupTtlBias
        assertEquals(-7, bias.tenthStops)
        assertEquals(AirGroup.ALL, bias.group)
    }
}
