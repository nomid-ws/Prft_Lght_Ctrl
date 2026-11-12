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

    @Test
    fun `AirPxBias opcode 131 boundary check and group restrictions`() {
        // Valid boundaries -20..20 and groups A..C + ALL
        val validA = PupFrameCodec.encodeAirPxBias(counter = 1, tenthStops = -20, group = AirGroup.GROUP_A)
        assertEquals(-20, (PupFrameCodec.decodeMessage(validA).second as PupDecodedMessage.AirGroupTtlBias).tenthStops)
        val validC = PupFrameCodec.encodeAirPxBias(counter = 2, tenthStops = 20, group = AirGroup.GROUP_C)
        assertEquals(20, (PupFrameCodec.decodeMessage(validC).second as PupDecodedMessage.AirGroupTtlBias).tenthStops)
        val validB = PupFrameCodec.encodeAirPxBias(counter = 3, tenthStops = 0, group = AirGroup.GROUP_B)
        assertEquals(0, (PupFrameCodec.decodeMessage(validB).second as PupDecodedMessage.AirGroupTtlBias).tenthStops)

        // tenthStops out of bounds (-21 and 21)
        var errThrown = false
        try {
            PupFrameCodec.encodeAirPxBias(counter = 4, tenthStops = -21, group = AirGroup.GROUP_A)
        } catch (_: PupCodecException) {
            errThrown = true
        }
        assertTrue("Expected exception for tenthStops = -21", errThrown)

        errThrown = false
        try {
            PupFrameCodec.encodeAirPxBias(counter = 5, tenthStops = 21, group = AirGroup.GROUP_A)
        } catch (_: PupCodecException) {
            errThrown = true
        }
        assertTrue("Expected exception for tenthStops = 21", errThrown)

        // Non-TTL groups D, E, F rejected
        for (disallowed in listOf(AirGroup.GROUP_D, AirGroup.GROUP_E, AirGroup.GROUP_F)) {
            var groupBlocked = false
            try {
                PupFrameCodec.encodeAirPxBias(counter = 6, tenthStops = 0, group = disallowed)
            } catch (_: PupCodecException) {
                groupBlocked = true
            }
            assertTrue("Expected group $disallowed to be rejected", groupBlocked)
        }
    }

    @Test
    fun `AirAbsoluteGroupEnergy opcode 177 Map overload and 1 to 100 f-stop bounds`() {
        val validMap = mapOf(
            AirGroup.GROUP_A to 1,
            AirGroup.GROUP_B to 25,
            AirGroup.GROUP_C to 50,
            AirGroup.GROUP_D to 75,
            AirGroup.GROUP_E to 90,
            AirGroup.GROUP_F to 100,
        )
        val raw = PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 10, groupEnergies = validMap)
        val (_, msg) = PupFrameCodec.decodeMessage(raw)
        val powerMsg = msg as PupDecodedMessage.AirAbsoluteGroupPower
        assertEquals(listOf(1, 25, 50, 75, 90, 100), powerMsg.groupTenthStops)

        // Power out of bounds: 0 (below floor) and 101 (above max)
        var blocked = false
        try {
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 11, groupEnergies = validMap + (AirGroup.GROUP_A to 0))
        } catch (_: PupCodecException) {
            blocked = true
        }
        assertTrue("Expected 0 power to be rejected", blocked)

        blocked = false
        try {
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 12, groupEnergies = validMap + (AirGroup.GROUP_F to 101))
        } catch (_: PupCodecException) {
            blocked = true
        }
        assertTrue("Expected 101 power to be rejected", blocked)

        // Incomplete map missing a group
        blocked = false
        try {
            val incompleteMap = mapOf(
                AirGroup.GROUP_A to 10,
                AirGroup.GROUP_B to 20,
            )
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 13, groupEnergies = incompleteMap)
        } catch (_: PupCodecException) {
            blocked = true
        }
        assertTrue("Expected incomplete map to be rejected", blocked)
    }

    @Test
    fun `Remote settings and triggers opcodes encode and decode accurately`() {
        // Opcode 140: SyncMode
        val rawSync = PupFrameCodec.encodeSyncMode(counter = 1, mode = SyncTriggerMode.GLOBAL_SHUTTER)
        val (_, syncMsg) = PupFrameCodec.decodeMessage(rawSync)
        assertEquals(SyncTriggerMode.GLOBAL_SHUTTER, (syncMsg as PupDecodedMessage.RemoteSyncMode).mode)

        // Opcode 160: AfAssistMode
        val rawAf = PupFrameCodec.encodeAfAssistMode(counter = 2, mode = AfAssistMode.AUTO)
        val (_, afMsg) = PupFrameCodec.decodeMessage(rawAf)
        assertEquals(AfAssistMode.AUTO, (afMsg as PupDecodedMessage.RemoteAfAssist).mode)

        // Opcode 147: RmiRfPower
        val rawRf = PupFrameCodec.encodeRfPower(counter = 3, level = RfPowerLevel.P2)
        val (_, rfMsg) = PupFrameCodec.decodeMessage(rawRf)
        assertEquals(RfPowerLevel.P2, (rfMsg as PupDecodedMessage.RemoteRfPower).level)

        // Opcode 148: RadioModeRange (decoder)
        val rawRfRange = PupFrameCodec.encodePacket(counter = 4, opcode = PupOpcodes.RADIO_MODE_RANGE, payload = byteArrayOf(1, 4))
        val (_, rfRangeMsg) = PupFrameCodec.decodeMessage(rawRfRange)
        val rfRange = rfRangeMsg as PupDecodedMessage.RfPowerRange
        assertEquals(1, rfRange.minDbm)
        assertEquals(4, rfRange.maxDbm)

        // Opcode 161: ZoomMode
        val rawZoomMode = PupFrameCodec.encodeZoomMode(counter = 5, mode = MonolightZoomMode.AUTO)
        val (_, zoomModeMsg) = PupFrameCodec.decodeMessage(rawZoomMode)
        assertEquals(MonolightZoomMode.AUTO, (zoomModeMsg as PupDecodedMessage.MonolightZoomState).mode)

        // Opcode 162: ZoomRange (decoder: 24..105 mm)
        val zoomRangePayload = byteArrayOf(24, 0, 105, 0)
        val rawZoomRange = PupFrameCodec.encodePacket(counter = 6, opcode = PupOpcodes.ZOOM_RANGE, payload = zoomRangePayload)
        val (_, zoomRangeMsg) = PupFrameCodec.decodeMessage(rawZoomRange)
        assertEquals(24..105, (zoomRangeMsg as PupDecodedMessage.MonolightZoomState).range)

        // Opcode 163: ZoomValue
        val rawZoomVal = PupFrameCodec.encodeZoomValue(counter = 7, position = 70)
        val (_, zoomValMsg) = PupFrameCodec.decodeMessage(rawZoomVal)
        assertEquals(70, (zoomValMsg as PupDecodedMessage.MonolightZoomState).value)

        // Opcode 217: ReadySignalMode
        val rawReady = PupFrameCodec.encodeReadySignalMode(counter = 8, mode = ReadySignalMode.SOUND_AND_DIM)
        val (_, readyMsg) = PupFrameCodec.decodeMessage(rawReady)
        assertEquals(ReadySignalMode.SOUND_AND_DIM, (readyMsg as PupDecodedMessage.ReadySignalState).mode)

        // Opcode 41: AutoOffTimeout
        val rawAutoOff = PupFrameCodec.encodeAutoOffTimeout(counter = 9, minutes = 60)
        val (_, autoOffMsg) = PupFrameCodec.decodeMessage(rawAutoOff)
        assertEquals(60, (autoOffMsg as PupDecodedMessage.AutoOffMinutes).minutes)

        // Opcode 40: AutoStandbyTimeout
        val rawStandby = PupFrameCodec.encodeAutoStandbyTimeout(counter = 10, minutes = 30)
        val (_, standbyMsg) = PupFrameCodec.decodeMessage(rawStandby)
        assertEquals(30, (standbyMsg as PupDecodedMessage.AutoStandbyMinutes).minutes)

        // Opcode 39: DisplayBrightness (Int overload)
        val rawBright = PupFrameCodec.encodeDisplayBrightness(counter = 11, brightness = 1)
        val (_, brightMsg) = PupFrameCodec.decodeMessage(rawBright)
        assertEquals(DisplayBrightnessLevel.MEDIUM, (brightMsg as PupDecodedMessage.DisplayBrightness).level)

        // Opcode 180: BatteryType (Int overload)
        val rawBat = PupFrameCodec.encodeBatteryType(counter = 12, batteryType = 1)
        val (_, batMsg) = PupFrameCodec.decodeMessage(rawBat)
        assertEquals(BatteryChemistry.NIMH, (batMsg as PupDecodedMessage.BatteryTypeSetting).chemistry)

        // Opcode 38: ControlSounds
        val rawSounds = PupFrameCodec.encodeControlSounds(counter = 13, enabled = false)
        val (_, soundsMsg) = PupFrameCodec.decodeMessage(rawSounds)
        assertFalse((soundsMsg as PupDecodedMessage.ControlSoundsState).enabled)
    }

    @Test
    fun `Quarantined firmware opcodes blocked across query and direct formats`() {
        for (opcode in listOf(PupOpcodes.FIRMWARE_ARM_QUARANTINED, PupOpcodes.BLOB_DATA_QUARANTINED, PupOpcodes.ARM_FW_UPDATE_QUARANTINED)) {
            // Direct command
            var blockedDirect = false
            try {
                PupFrameCodec.encodePacket(counter = 1, opcode = opcode)
            } catch (_: PupSecurityQuarantineException) {
                blockedDirect = true
            }
            assertTrue("Expected direct opcode $opcode to be quarantined", blockedDirect)

            // Query variant (with query bit 0x8000)
            var blockedQuery = false
            try {
                PupFrameCodec.encodeQuery(counter = 2, opcode = opcode)
            } catch (_: PupSecurityQuarantineException) {
                blockedQuery = true
            }
            assertTrue("Expected query variant of opcode $opcode to be quarantined", blockedQuery)
        }
    }

    @Test
    fun `HardwareCatalog maxWattSeconds populated accurately for all known flash heads and fallbacks`() {
        // Known flash models
        assertEquals(76, HardwareCatalog.resolve(30).maxWattSeconds)  // A10
        assertEquals(100, HardwareCatalog.resolve(36).maxWattSeconds) // A2
        assertEquals(250, HardwareCatalog.resolve(140).maxWattSeconds) // B10
        assertEquals(250, HardwareCatalog.resolve(144).maxWattSeconds) // B10X
        assertEquals(250, HardwareCatalog.resolve(53).maxWattSeconds)  // B20
        assertEquals(500, HardwareCatalog.resolve(141).maxWattSeconds) // B10 Plus
        assertEquals(500, HardwareCatalog.resolve(143).maxWattSeconds) // B10X Pro / Plus
        assertEquals(500, HardwareCatalog.resolve(52).maxWattSeconds)  // D30
        assertEquals(500, HardwareCatalog.resolve(54).maxWattSeconds)  // B30
        assertEquals(750, HardwareCatalog.resolve(48).maxWattSeconds)  // Pro-D3
        assertEquals(750, HardwareCatalog.resolve(49).maxWattSeconds)  // Pro-D3 750
        assertEquals(1250, HardwareCatalog.resolve(50).maxWattSeconds) // Pro-D3 1250
        assertEquals(750, HardwareCatalog.resolve(51).maxWattSeconds)  // Pro-B3
        assertEquals(2400, HardwareCatalog.resolve(4).maxWattSeconds)  // Pro-11

        // Remotes and Continuous Cinema LEDs (null maxWattSeconds)
        assertNull(HardwareCatalog.resolve(161).maxWattSeconds) // Connect Pro Canon
        assertNull(HardwareCatalog.resolve(166).maxWattSeconds) // Connect Pro ISO
        assertNull(HardwareCatalog.resolve(150).maxWattSeconds) // Connect Canon
        assertNull(HardwareCatalog.resolve(176).maxWattSeconds) // L1600D
        assertNull(HardwareCatalog.resolve(180).maxWattSeconds) // L600D
        assertNull(HardwareCatalog.resolve(174).maxWattSeconds) // ProPanel

        // Fallback name-based heuristics
        assertEquals(500, HardwareCatalog.resolve(0, "Profoto B1X").maxWattSeconds)
        assertEquals(250, HardwareCatalog.resolve(0, "Profoto B2").maxWattSeconds)
        assertEquals(1000, HardwareCatalog.resolve(0, "Profoto D2 1000").maxWattSeconds)
        assertEquals(500, HardwareCatalog.resolve(0, "Profoto D2 500").maxWattSeconds)
        assertEquals(2400, HardwareCatalog.resolve(0, "Profoto Pro-10").maxWattSeconds)
        assertEquals(76, HardwareCatalog.resolve(0, "Profoto A1X").maxWattSeconds)
    }
}
