package app.prft.lightctrl.ui

import android.view.KeyEvent
import app.prft.lightctrl.domain.PowerScaleConverter
import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.domain.StudioScene
import app.prft.lightctrl.domain.GroupPreset
import app.prft.lightctrl.domain.StudioSceneJsonCodec
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.BatteryChemistry
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.MonolightZoomMode
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import app.prft.lightctrl.ui.components.STUDIO_GROUP_ROLE_SUGGESTIONS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 Test Suite: Adaptive UI, Studio Workflow, Presets, Settings & Hardware Controls.
 */
class Milestone3AdaptiveUiWorkflowTest {

    @Test
    fun `test 1 Remote Power Floor allows 0 point 1 stop on Connect Pro`() {
        // Enforces MIN_TENTH_STOPS = 1 (allowing 1..100)
        val powers = listOf(1, 10, 50, 80, 95, 100)
        val raw = PupFrameCodec.encodeAirAbsoluteGroupEnergy(counter = 1, groupTenthStops = powers)
        val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

        assertEquals(PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY, pkt.opcode)
        val decoded = msg as PupDecodedMessage.AirAbsoluteGroupPower
        assertEquals(1, decoded.groupTenthStops[0])
        assertEquals(10, decoded.groupTenthStops[1])
        assertEquals(100, decoded.groupTenthStops[5])
    }

    @Test
    fun `test 2 Dual Power Readout calculations and formatting`() {
        // 500 Ws strobe (B10X Pro)
        val fullPower500 = PowerScaleConverter.formatDualPowerReadout(100, 500)
        assertEquals("10.0 (500 Ws · 1/1)", fullPower500)

        // 1 stop down = 90 tenths = 250 Ws · 1/2
        val halfPower500 = PowerScaleConverter.formatDualPowerReadout(90, 500)
        assertEquals("9.0 (250 Ws · 1/2)", halfPower500)

        // 2 stops down = 80 tenths = 125 Ws · 1/4
        val quarterPower500 = PowerScaleConverter.formatDualPowerReadout(80, 500)
        assertEquals("8.0 (125 Ws · 1/4)", quarterPower500)

        // Minimum 1.0 (10 tenths, 9 stops down) = ~1.0 Ws
        val minPower500 = PowerScaleConverter.formatDualPowerReadout(10, 500)
        assertEquals("1.0 (1.0 Ws · 1/512)", minPower500)

        // 100 Ws strobe (A2)
        val fullPower100 = PowerScaleConverter.formatDualPowerReadout(100, 100)
        assertEquals("10.0 (100 Ws · 1/1)", fullPower100)

        val halfPower100 = PowerScaleConverter.formatDualPowerReadout(90, 100)
        assertEquals("9.0 (50 Ws · 1/2)", halfPower100)
    }

    @Test
    fun `test 3 Studio Scene Json Codec pure org json export and import`() {
        val originalScene = StudioScene(
            id = "scene-test-uuid-42",
            name = "Commercial Rembrandt Portrait",
            setupNotes = "B10X Pro in 3ft Octa key, A2 grid rim, A2 snoot hair",
            createdAtUtc = 1731420000000L,
            groupPresets = listOf(
                GroupPreset(
                    group = AirGroup.GROUP_A,
                    powerTenthStops = 85,
                    isEnabled = true,
                    isModelingEnabled = true,
                    roleLabel = "Key Light",
                ),
                GroupPreset(
                    group = AirGroup.GROUP_B,
                    powerTenthStops = 65,
                    isEnabled = true,
                    isModelingEnabled = false,
                    roleLabel = "Fill Shadow",
                ),
                GroupPreset(
                    group = AirGroup.GROUP_C,
                    powerTenthStops = 72,
                    isEnabled = true,
                    isModelingEnabled = false,
                    roleLabel = "Rim / Kicker",
                ),
            ),
            remoteChannel = 7,
            remoteOperatingMode = RemoteOperatingMode.MANUAL,
            syncTriggerMode = SyncTriggerMode.FIRST,
            afAssistMode = AfAssistMode.AUTO,
            rfPowerLevel = RfPowerLevel.P4,
            readySignalMode = ReadySignalMode.SOUND_AND_DIM,
        )

        // Encode single scene
        val json = StudioSceneJsonCodec.encodeSingleToJson(originalScene)
        assertFalse(json.isNullOrBlank())
        assertTrue(json.contains("Commercial Rembrandt Portrait"))
        assertTrue(json.contains("scene-test-uuid-42"))
        assertTrue(json.contains("Key Light"))

        // Decode single scene
        val decoded = StudioSceneJsonCodec.decodeScene(org.json.JSONObject(json))
        assertEquals(originalScene.id, decoded.id)
        assertEquals(originalScene.name, decoded.name)
        assertEquals(originalScene.setupNotes, decoded.setupNotes)
        assertEquals(originalScene.remoteChannel, decoded.remoteChannel)
        assertEquals(originalScene.remoteOperatingMode, decoded.remoteOperatingMode)
        assertEquals(originalScene.syncTriggerMode, decoded.syncTriggerMode)
        assertEquals(originalScene.afAssistMode, decoded.afAssistMode)
        assertEquals(originalScene.rfPowerLevel, decoded.rfPowerLevel)
        assertEquals(originalScene.readySignalMode, decoded.readySignalMode)
        assertEquals(3, decoded.groupPresets.size)
        assertEquals(85, decoded.presetFor(AirGroup.GROUP_A)?.powerTenthStops)
        assertEquals("Key Light", decoded.presetFor(AirGroup.GROUP_A)?.roleLabel)

        // List encode and decode
        val listJson = StudioSceneJsonCodec.encodeToJson(listOf(originalScene))
        val decodedList = StudioSceneJsonCodec.decodeFromJson(listJson)
        assertEquals(1, decodedList.size)
        assertEquals("Commercial Rembrandt Portrait", decodedList[0].name)
    }

    @Test
    fun `test 4 Group Role suggestions contains studio standards`() {
        assertTrue(STUDIO_GROUP_ROLE_SUGGESTIONS.contains("Key"))
        assertTrue(STUDIO_GROUP_ROLE_SUGGESTIONS.contains("Fill"))
        assertTrue(STUDIO_GROUP_ROLE_SUGGESTIONS.contains("Rim"))
        assertTrue(STUDIO_GROUP_ROLE_SUGGESTIONS.contains("Hair"))
        assertTrue(STUDIO_GROUP_ROLE_SUGGESTIONS.contains("Background"))
        assertTrue(STUDIO_GROUP_ROLE_SUGGESTIONS.contains("Kicker"))
    }

    @Test
    fun `test 5 Pinned kit MAC normalization and persistence`() = runBlocking {
        val store = StudioPreferencesStore(context = null)

        store.setDevicePinned("aa:bb:cc:dd:ee:ff", true)
        val pinned = store.pinnedKitMacs.value
        assertEquals(1, pinned.size)
        assertTrue(pinned.contains("AA:BB:CC:DD:EE:FF"))

        store.setDevicePinned("AA:BB:CC:DD:EE:FF", false)
        assertTrue(store.pinnedKitMacs.value.isEmpty())
    }

    @Test
    fun `test 6 Volume Key Flash Preference gating`() = runBlocking {
        val store = StudioPreferencesStore(context = null)
        assertFalse(store.isVolumeKeyTestFlashEnabled.value)

        store.setVolumeKeyTestFlashEnabled(true)
        assertTrue(store.isVolumeKeyTestFlashEnabled.value)

        store.setVolumeKeyTestFlashEnabled(false)
        assertFalse(store.isVolumeKeyTestFlashEnabled.value)
    }

    @Test
    fun `test 7 Connect Pro Remote Settings protocol opcodes and encoders`() {
        // Sync Trigger Mode (op 140)
        val rawSync = PupFrameCodec.encodeSyncMode(counter = 10, mode = SyncTriggerMode.AUTO_FP)
        val (syncPkt, _) = PupFrameCodec.decodeMessage(rawSync)
        assertEquals(PupOpcodes.SYNC_MODE, syncPkt.opcode)
        assertEquals(3, syncPkt.payload[0].toInt())

        // AF Assist Mode (op 160)
        val rawAf = PupFrameCodec.encodeAfAssistMode(counter = 11, mode = AfAssistMode.AUTO)
        val (afPkt, _) = PupFrameCodec.decodeMessage(rawAf)
        assertEquals(PupOpcodes.AF_ASSIST_MODE, afPkt.opcode)
        assertEquals(1, afPkt.payload[0].toInt())

        // RF Power Level (op 147)
        val rawRf = PupFrameCodec.encodeRfPower(counter = 12, level = RfPowerLevel.P3)
        val (rfPkt, _) = PupFrameCodec.decodeMessage(rawRf)
        assertEquals(PupOpcodes.RMI_RF_POWER, rfPkt.opcode)
        assertEquals(3, rfPkt.payload[0].toInt())

        // Control Sounds (op 38)
        val rawSound = PupFrameCodec.encodeControlSounds(counter = 13, enabled = true)
        val (soundPkt, _) = PupFrameCodec.decodeMessage(rawSound)
        assertEquals(PupOpcodes.CONTROL_SOUNDS_ENABLE, soundPkt.opcode)
        assertEquals(1, soundPkt.payload[0].toInt())
    }

    @Test
    fun `test 8 Monolight Zoom and Ready Signal Mode opcodes`() {
        // Zoom Auto mode (op 161)
        val rawZoomAuto = PupFrameCodec.encodeZoomMode(counter = 20, mode = MonolightZoomMode.AUTO)
        val (zoomAutoPkt, _) = PupFrameCodec.decodeMessage(rawZoomAuto)
        assertEquals(PupOpcodes.ZOOM_MODE, zoomAutoPkt.opcode)
        assertEquals(1, zoomAutoPkt.payload[0].toInt())

        // Zoom Manual mode + position (op 163)
        val rawZoomPos = PupFrameCodec.encodeZoomValue(counter = 21, position = 75)
        val (zoomPosPkt, _) = PupFrameCodec.decodeMessage(rawZoomPos)
        assertEquals(PupOpcodes.ZOOM_VALUE, zoomPosPkt.opcode)
        assertEquals(75, zoomPosPkt.payload[0].toInt())

        // Ready Signal Mode (op 217)
        val rawReady = PupFrameCodec.encodeReadySignalMode(counter = 22, mode = ReadySignalMode.SOUND_AND_DIM)
        val (readyPkt, _) = PupFrameCodec.decodeMessage(rawReady)
        assertEquals(PupOpcodes.READY_SIGNAL_MODE, readyPkt.opcode)
        assertEquals(3, readyPkt.payload[0].toInt())
    }
}
