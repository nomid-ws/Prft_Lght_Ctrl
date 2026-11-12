package app.prft.lightctrl.e2e.tier2

import app.prft.lightctrl.domain.GroupPreset
import app.prft.lightctrl.domain.StudioScene
import app.prft.lightctrl.domain.StudioSceneCodecException
import app.prft.lightctrl.domain.StudioSceneJsonCodec
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.AirGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 — Feature 7: Studio Scenes / Lighting Presets Boundary Tests.
 *
 * Verifies:
 * 1. Empty or whitespace scene name defaults cleanly to "Untitled Preset".
 * 2. Maximum length setup notes (10,000+ chars) are preserved intact.
 * 3. Malformed JSON strings throw StudioSceneCodecException.
 * 4. Corrupted JSON fields are safely coerced (power in 1..100, TTL in -20..20, channel in 1..100).
 * 5. Empty groups list encodes, decodes, and applies without exception.
 * 6. Duplicate scene IDs handling in saveScene (updates) and importScenes (deduplicates).
 * 7. Unicode, multi-byte characters, and emojis in names, notes, and role labels.
 */
class StudioScenesBoundaryTest {

    @Test
    fun `test 1 empty or whitespace scene name defaults to Untitled Preset`() {
        val jsonNoName = """{"groupPresets": [{"group": "A", "powerTenthStops": 70}]}"""
        val decoded1 = StudioSceneJsonCodec.decodeFromJson(jsonNoName)
        assertEquals("Untitled Preset", decoded1[0].name)

        val jsonEmptyName = """{"name": "", "groupPresets": []}"""
        val decoded2 = StudioSceneJsonCodec.decodeFromJson(jsonEmptyName)
        assertEquals("Untitled Preset", decoded2[0].name)

        val jsonWhitespaceName = """{"name": "   \t  ", "groupPresets": []}"""
        val decoded3 = StudioSceneJsonCodec.decodeFromJson(jsonWhitespaceName)
        assertEquals("Untitled Preset", decoded3[0].name)
    }

    @Test
    fun `test 2 maximum length setup notes 10000 characters preserved without truncation`() {
        val longNotes = "Studio Note ".repeat(1000) // 12,000 chars
        val scene = StudioScene(
            name = "Extreme Notes Preset",
            setupNotes = longNotes,
            groupPresets = listOf(GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 80)),
        )

        val json = StudioSceneJsonCodec.encodeSingleToJson(scene)
        val decoded = StudioSceneJsonCodec.decodeFromJson(json)

        assertEquals(1, decoded.size)
        assertEquals(longNotes.length, decoded[0].setupNotes.length)
        assertEquals(longNotes, decoded[0].setupNotes)
    }

    @Test
    fun `test 3 malformed JSON strings throw StudioSceneCodecException`() {
        val invalidInputs = listOf(
            "",
            "   ",
            "not json at all",
            "{unclosed json",
            "[unclosed array",
            "12345",
            "true",
            "null",
            "{}", // Missing "scenes" or scene properties
        )

        for (input in invalidInputs) {
            assertThrows(StudioSceneCodecException::class.java) {
                StudioSceneJsonCodec.decodeFromJson(input)
            }
        }
    }

    @Test
    fun `test 4 corrupted JSON fields are safely coerced to valid ranges`() {
        val json = """
            {
              "scenes": [
                {
                  "remoteChannel": 199,
                  "remoteOperatingMode": "NON_EXISTENT_MODE",
                  "syncTriggerMode": "UNKNOWN_SYNC",
                  "groupPresets": [
                    {
                      "group": "GROUP_UNKNOWN_XYZ",
                      "powerTenthStops": 999,
                      "ttlEvBiasTenthStops": 150
                    },
                    {
                      "group": "B",
                      "powerTenthStops": -50,
                      "ttlEvBiasTenthStops": -80
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = StudioSceneJsonCodec.decodeFromJson(json)
        assertEquals(1, decoded.size)
        val scene = decoded[0]

        // Invalid remote channel (>100) becomes null
        assertNull(scene.remoteChannel)
        // Unknown enums become null
        assertNull(scene.remoteOperatingMode)
        assertNull(scene.syncTriggerMode)

        val gps = scene.groupPresets
        assertEquals(2, gps.size)

        // Out-of-bounds power (999) clamped to 100
        assertEquals(100, gps[0].powerTenthStops)
        // Out-of-bounds TTL bias (150) clamped to 20
        assertEquals(20, gps[0].ttlEvBiasTenthStops)
        // Unknown group fallback to GROUP_A
        assertEquals(AirGroup.GROUP_A, gps[0].group)

        // Negative power (-50) clamped to 1
        assertEquals(1, gps[1].powerTenthStops)
        // Negative TTL bias (-80) clamped to -20
        assertEquals(-20, gps[1].ttlEvBiasTenthStops)
    }

    @Test
    fun `test 5 empty group presets list applies without crashing orchestrator`() = runBlocking {
        val harness = E2ETestHarness()
        val emptyScene = StudioScene(
            name = "Empty Scene",
            setupNotes = "Zero groups configured",
            groupPresets = emptyList(),
        )

        val json = StudioSceneJsonCodec.encodeSingleToJson(emptyScene)
        val decoded = StudioSceneJsonCodec.decodeFromJson(json)
        assertEquals(0, decoded[0].groupPresets.size)

        // Apply to orchestrator without any exceptions
        harness.orchestrator.applyScene(decoded[0])
        delay(40)

        val banner = harness.orchestrator.dashboardState.value.statusBanner
        assertNotNull(banner)
        assertTrue(banner!!.contains("Applied preset: Empty Scene"))
    }

    @Test
    fun `test 6 duplicate scene IDs handled by updating in saveScene and deduplicating in importScenes`() = runBlocking {
        val harness = E2ETestHarness()
        val s1 = StudioScene(id = "fixed-uuid-1", name = "Original Version", setupNotes = "v1")
        val s1Updated = StudioScene(id = "fixed-uuid-1", name = "Updated Version", setupNotes = "v2")

        harness.orchestrator.saveScene(s1)
        delay(40)
        harness.orchestrator.saveScene(s1Updated)
        delay(40)

        // Must update existing, NOT create duplicate entry
        val saved = harness.orchestrator.savedScenes.value
        assertEquals(1, saved.size)
        assertEquals("Updated Version", saved[0].name)
        assertEquals("v2", saved[0].setupNotes)

        // Import duplicate scene ID must be ignored (skips already existing IDs)
        val s1OldImport = StudioScene(id = "fixed-uuid-1", name = "Old Version", setupNotes = "v0")
        harness.orchestrator.importScenes(listOf(s1OldImport))
        delay(40)

        val afterImport = harness.orchestrator.savedScenes.value
        assertEquals(1, afterImport.size)
        assertEquals("Updated Version", afterImport[0].name)
    }

    @Test
    fun `test 7 unicode multi-byte characters and emojis in names notes and roles`() {
        val scene = StudioScene(
            name = "ポートレート 💡 (Key & Fill) 📸",
            setupNotes = "Lighting setup: 5ft Octa (50% 格子) & 反射板 45°",
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 85, roleLabel = "キーライト 💡"),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 60, roleLabel = "Éclairage d'accentuation"),
            ),
        )

        val json = StudioSceneJsonCodec.encodeSingleToJson(scene)
        val decoded = StudioSceneJsonCodec.decodeFromJson(json)

        assertEquals(1, decoded.size)
        assertEquals("ポートレート 💡 (Key & Fill) 📸", decoded[0].name)
        assertEquals("Lighting setup: 5ft Octa (50% 格子) & 反射板 45°", decoded[0].setupNotes)
        assertEquals("キーライト 💡", decoded[0].presetFor(AirGroup.GROUP_A)?.roleLabel)
        assertEquals("Éclairage d'accentuation", decoded[0].presetFor(AirGroup.GROUP_B)?.roleLabel)
    }
}
