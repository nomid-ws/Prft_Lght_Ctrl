package app.prft.lightctrl.domain

import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioSceneJsonCodecTest {

    @Test
    fun `roundTrip multiSceneContainer preserves full fidelity`() {
        val scene1 = StudioScene(
            id = "scene-portrait-key",
            name = "Dramatic Portrait Key",
            createdAtUtc = 1700000000000L,
            setupNotes = "5ft Octa on Key, Strip with grid on Rim",
            remoteChannel = 12,
            remoteOperatingMode = RemoteOperatingMode.MANUAL,
            syncTriggerMode = SyncTriggerMode.FIRST,
            afAssistMode = AfAssistMode.AUTO,
            rfPowerLevel = RfPowerLevel.P4,
            readySignalMode = ReadySignalMode.SOUND_AND_DIM,
            groupPresets = listOf(
                GroupPreset(
                    group = AirGroup.GROUP_A,
                    powerTenthStops = 85,
                    isEnabled = true,
                    isModelingEnabled = true,
                    roleLabel = "Main Key",
                    ttlEvBiasTenthStops = null,
                ),
                GroupPreset(
                    group = AirGroup.GROUP_B,
                    powerTenthStops = 65,
                    isEnabled = true,
                    isModelingEnabled = false,
                    roleLabel = "Fill",
                    ttlEvBiasTenthStops = -10,
                ),
            ),
        )

        val scene2 = StudioScene(
            id = "scene-commercial-ttl",
            name = "Commercial Fashion TTL",
            createdAtUtc = 1700000050000L,
            setupNotes = "Full TTL studio setup",
            remoteChannel = 7,
            remoteOperatingMode = RemoteOperatingMode.AUTO_TTL,
            syncTriggerMode = SyncTriggerMode.AUTO_FP,
            afAssistMode = AfAssistMode.ON,
            rfPowerLevel = RfPowerLevel.P3,
            readySignalMode = ReadySignalMode.SOUND,
            groupPresets = listOf(
                GroupPreset(
                    group = AirGroup.GROUP_A,
                    powerTenthStops = 70,
                    isEnabled = true,
                    isModelingEnabled = true,
                    roleLabel = "Beauty Dish",
                    ttlEvBiasTenthStops = 15,
                ),
                GroupPreset(
                    group = AirGroup.GROUP_C,
                    powerTenthStops = 50,
                    isEnabled = false,
                    isModelingEnabled = false,
                    roleLabel = "Background",
                    ttlEvBiasTenthStops = 0,
                ),
            ),
        )

        val json = StudioSceneJsonCodec.encodeToJson(listOf(scene1, scene2))
        val decoded = StudioSceneJsonCodec.decodeFromJson(json)

        assertEquals(2, decoded.size)

        val d1 = decoded[0]
        assertEquals("scene-portrait-key", d1.id)
        assertEquals("Dramatic Portrait Key", d1.name)
        assertEquals(1700000000000L, d1.createdAtUtc)
        assertEquals("5ft Octa on Key, Strip with grid on Rim", d1.setupNotes)
        assertEquals(12, d1.remoteChannel)
        assertEquals(RemoteOperatingMode.MANUAL, d1.remoteOperatingMode)
        assertEquals(SyncTriggerMode.FIRST, d1.syncTriggerMode)
        assertEquals(AfAssistMode.AUTO, d1.afAssistMode)
        assertEquals(RfPowerLevel.P4, d1.rfPowerLevel)
        assertEquals(ReadySignalMode.SOUND_AND_DIM, d1.readySignalMode)
        assertEquals(2, d1.groupPresets.size)

        val gp1A = d1.presetFor(AirGroup.GROUP_A)
        assertNotNull(gp1A)
        assertEquals(85, gp1A!!.powerTenthStops)
        assertTrue(gp1A.isEnabled)
        assertTrue(gp1A.isModelingEnabled)
        assertEquals("Main Key", gp1A.roleLabel)
        assertNull(gp1A.ttlEvBiasTenthStops)

        val gp1B = d1.presetFor(AirGroup.GROUP_B)
        assertNotNull(gp1B)
        assertEquals(65, gp1B!!.powerTenthStops)
        assertEquals(-10, gp1B.ttlEvBiasTenthStops)

        val d2 = decoded[1]
        assertEquals("scene-commercial-ttl", d2.id)
        assertEquals(RemoteOperatingMode.AUTO_TTL, d2.remoteOperatingMode)
        assertEquals(SyncTriggerMode.AUTO_FP, d2.syncTriggerMode)
        assertEquals(15, d2.presetFor(AirGroup.GROUP_A)?.ttlEvBiasTenthStops)
    }

    @Test
    fun `roundTrip singleSceneObject encoded and decoded`() {
        val scene = StudioScene(
            name = "Single Rim Accent",
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_D, powerTenthStops = 45, roleLabel = "Rim Accent"),
            ),
        )
        val json = StudioSceneJsonCodec.encodeSingleToJson(scene)
        val decoded = StudioSceneJsonCodec.decodeFromJson(json)

        assertEquals(1, decoded.size)
        assertEquals(scene.id, decoded[0].id)
        assertEquals("Single Rim Accent", decoded[0].name)
        assertEquals(45, decoded[0].presetFor(AirGroup.GROUP_D)?.powerTenthStops)
        assertEquals("Rim Accent", decoded[0].presetFor(AirGroup.GROUP_D)?.roleLabel)
    }

    @Test
    fun `decode bareArrayFormat`() {
        val json = """
            [
              {
                "id": "bare-1",
                "name": "Bare Array Preset",
                "groupPresets": [
                  { "group": "A", "powerTenthStops": 75, "isEnabled": true }
                ]
              }
            ]
        """.trimIndent()
        val decoded = StudioSceneJsonCodec.decodeFromJson(json)
        assertEquals(1, decoded.size)
        assertEquals("bare-1", decoded[0].id)
        assertEquals("Bare Array Preset", decoded[0].name)
        assertEquals(AirGroup.GROUP_A, decoded[0].groupPresets[0].group)
        assertEquals(75, decoded[0].groupPresets[0].powerTenthStops)
    }

    @Test
    fun `decode corrupted and empty json throws StudioSceneCodecException`() {
        assertThrows(StudioSceneCodecException::class.java) {
            StudioSceneJsonCodec.decodeFromJson("")
        }
        assertThrows(StudioSceneCodecException::class.java) {
            StudioSceneJsonCodec.decodeFromJson("   ")
        }
        assertThrows(StudioSceneCodecException::class.java) {
            StudioSceneJsonCodec.decodeFromJson("not a json string")
        }
        assertThrows(StudioSceneCodecException::class.java) {
            StudioSceneJsonCodec.decodeFromJson("{}")
        }
    }

    @Test
    fun `decode resilience missing fields unknown enums and out-of-bounds numbers`() {
        val json = """
            {
              "scenes": [
                {
                  "remoteChannel": 150,
                  "remoteOperatingMode": "UNKNOWN_FUTURE_MODE",
                  "syncTriggerMode": "INVALID_SYNC",
                  "unknownExtraField": "ignored_cleanly",
                  "groupPresets": [
                    {
                      "group": "X",
                      "powerTenthStops": 250,
                      "ttlEvBiasTenthStops": 99
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = StudioSceneJsonCodec.decodeFromJson(json)
        assertEquals(1, decoded.size)

        val s = decoded[0]
        assertNotNull(s.id)
        assertTrue(s.id.isNotBlank())
        assertEquals("Untitled Preset", s.name)
        assertTrue(s.createdAtUtc > 0L)
        assertNull(s.remoteChannel) // 150 is out of 1..100 bounds -> null
        assertNull(s.remoteOperatingMode) // Unknown enum -> null
        assertNull(s.syncTriggerMode) // Unknown enum -> null

        assertEquals(1, s.groupPresets.size)
        val gp = s.groupPresets[0]
        assertEquals(AirGroup.GROUP_A, gp.group) // Unknown group "X" falls back to A
        assertEquals(100, gp.powerTenthStops) // 250 coerced to 100
        assertEquals(20, gp.ttlEvBiasTenthStops) // 99 coerced to 20
        assertNull(gp.roleLabel)
    }

    @Test
    fun `decode parses airGroup enum names and labels correctly`() {
        val json = """
            {
              "scenes": [
                {
                  "name": "Enum & Label Test",
                  "groupPresets": [
                    { "group": "GROUP_A", "powerTenthStops": 70 },
                    { "group": "GROUP_B", "powerTenthStops": 71 },
                    { "group": "GROUP_C", "powerTenthStops": 72 },
                    { "group": "GROUP_D", "powerTenthStops": 73 },
                    { "group": "GROUP_E", "powerTenthStops": 74 },
                    { "group": "GROUP_F", "powerTenthStops": 75 },
                    { "group": "ALL", "powerTenthStops": 76 },
                    { "group": "A", "powerTenthStops": 80 },
                    { "group": "B", "powerTenthStops": 81 },
                    { "group": "c", "powerTenthStops": 82 },
                    { "group": "group_d", "powerTenthStops": 83 },
                    { "group": "unknown_group", "powerTenthStops": 84 }
                  ]
                }
              ]
            }
        """.trimIndent()

        val scenes = StudioSceneJsonCodec.decodeFromJson(json)
        assertEquals(1, scenes.size)
        val gps = scenes[0].groupPresets
        assertEquals(12, gps.size)

        assertEquals(AirGroup.GROUP_A, gps[0].group)
        assertEquals(AirGroup.GROUP_B, gps[1].group)
        assertEquals(AirGroup.GROUP_C, gps[2].group)
        assertEquals(AirGroup.GROUP_D, gps[3].group)
        assertEquals(AirGroup.GROUP_E, gps[4].group)
        assertEquals(AirGroup.GROUP_F, gps[5].group)
        assertEquals(AirGroup.ALL, gps[6].group)

        assertEquals(AirGroup.GROUP_A, gps[7].group)
        assertEquals(AirGroup.GROUP_B, gps[8].group)
        assertEquals(AirGroup.GROUP_C, gps[9].group)
        assertEquals(AirGroup.GROUP_D, gps[10].group)
        assertEquals(AirGroup.GROUP_A, gps[11].group) // Unknown fallback to GROUP_A
    }

    @Test
    fun `decode explicit null ttlEvBiasTenthStops preserves null`() {
        val json = """
            {
              "scenes": [
                {
                  "name": "TTL EV Bias Null Tests",
                  "remoteChannel": null,
                  "groupPresets": [
                    { "group": "A", "powerTenthStops": 70, "ttlEvBiasTenthStops": null },
                    { "group": "B", "powerTenthStops": 70, "ttlEvBiasTenthStops": 0 },
                    { "group": "C", "powerTenthStops": 70, "ttlEvBiasTenthStops": -12 },
                    { "group": "D", "powerTenthStops": 70 }
                  ]
                }
              ]
            }
        """.trimIndent()

        val scenes = StudioSceneJsonCodec.decodeFromJson(json)
        assertEquals(1, scenes.size)
        val s = scenes[0]
        assertNull(s.remoteChannel)

        val gps = s.groupPresets
        assertEquals(4, gps.size)

        // Explicit null in JSON must decode as null, NOT 0
        assertNull(gps[0].ttlEvBiasTenthStops)

        // Explicit 0 in JSON must decode as 0
        assertEquals(0, gps[1].ttlEvBiasTenthStops)

        // Explicit negative value preserved
        assertEquals(-12, gps[2].ttlEvBiasTenthStops)

        // Omitted property must decode as null
        assertNull(gps[3].ttlEvBiasTenthStops)
    }
}
