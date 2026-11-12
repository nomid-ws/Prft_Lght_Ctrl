package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.GroupPreset
import app.prft.lightctrl.domain.StudioScene
import app.prft.lightctrl.domain.StudioSceneJsonCodec
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — Feature 7: Studio Scenes / Lighting Presets Coverage Tests.
 *
 * Verifies:
 * 1. 1-tap save scene updates savedScenes state flow.
 * 2. Recall scene by ID with exact parameter restoration.
 * 3. Multi-line setup notes preservation without loss.
 * 4. Full-fidelity JSON serialization & deserialization roundtrip.
 * 5. Apply scene dispatches power, channel, modes, and role labels to live studio state.
 * 6. Delete and import scene operations lifecycle.
 */
class StudioScenesCoverageTest {

    @Test
    fun `test 1 save scene stores preset and emits in savedScenes flow`() = runBlocking {
        val harness = E2ETestHarness()
        val scene = StudioScene(
            id = "scene-commercial-1",
            name = "Commercial Key/Fill",
            setupNotes = "3-light portrait rig",
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 85, isEnabled = true, roleLabel = "Key"),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 60, isEnabled = true, roleLabel = "Fill"),
            ),
            remoteChannel = 12,
        )

        harness.orchestrator.saveScene(scene)
        delay(40)

        val saved = harness.orchestrator.savedScenes.value
        assertEquals(1, saved.size)
        assertEquals("scene-commercial-1", saved[0].id)
        assertEquals("Commercial Key/Fill", saved[0].name)
        assertEquals("3-light portrait rig", saved[0].setupNotes)
        assertEquals(12, saved[0].remoteChannel)
        assertEquals(2, saved[0].groupPresets.size)
    }

    @Test
    fun `test 2 recall scene retrieves full fidelity properties by ID`() = runBlocking {
        val harness = E2ETestHarness()
        val scene1 = StudioScene(id = "s-1", name = "Scene One", groupPresets = listOf(GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 70)))
        val scene2 = StudioScene(
            id = "s-2",
            name = "Scene Two Full",
            remoteChannel = 5,
            remoteOperatingMode = RemoteOperatingMode.MANUAL,
            syncTriggerMode = SyncTriggerMode.AUTO_FP,
            afAssistMode = AfAssistMode.AUTO,
            rfPowerLevel = RfPowerLevel.P3,
            readySignalMode = ReadySignalMode.SOUND_AND_DIM,
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 90, isEnabled = true, isModelingEnabled = true, roleLabel = "Beauty Key", ttlEvBiasTenthStops = 10),
                GroupPreset(group = AirGroup.GROUP_C, powerTenthStops = 55, isEnabled = false, isModelingEnabled = false, roleLabel = "Rim", ttlEvBiasTenthStops = -5),
            ),
        )

        harness.orchestrator.saveScene(scene1)
        harness.orchestrator.saveScene(scene2)
        delay(40)

        val retrieved = harness.orchestrator.savedScenes.value.firstOrNull { it.id == "s-2" }
        assertNotNull(retrieved)
        assertEquals("Scene Two Full", retrieved!!.name)
        assertEquals(5, retrieved.remoteChannel)
        assertEquals(RemoteOperatingMode.MANUAL, retrieved.remoteOperatingMode)
        assertEquals(SyncTriggerMode.AUTO_FP, retrieved.syncTriggerMode)
        assertEquals(AfAssistMode.AUTO, retrieved.afAssistMode)
        assertEquals(RfPowerLevel.P3, retrieved.rfPowerLevel)
        assertEquals(ReadySignalMode.SOUND_AND_DIM, retrieved.readySignalMode)

        val gpA = retrieved.presetFor(AirGroup.GROUP_A)
        assertNotNull(gpA)
        assertEquals(90, gpA!!.powerTenthStops)
        assertTrue(gpA.isEnabled)
        assertTrue(gpA.isModelingEnabled)
        assertEquals("Beauty Key", gpA.roleLabel)
        assertEquals(10, gpA.ttlEvBiasTenthStops)

        val gpC = retrieved.presetFor(AirGroup.GROUP_C)
        assertNotNull(gpC)
        assertEquals(55, gpC!!.powerTenthStops)
        assertFalse(gpC.isEnabled)
        assertEquals(-5, gpC.ttlEvBiasTenthStops)
    }

    @Test
    fun `test 3 setup notes preservation across save and json codec`() {
        val notes = """
            Studio Setup: Studio 4B
            Key: B10X Plus in 5ft Octa @ 45° camera left
            Fill: A2 bounced into 40" white umbrella
            Rim: A2 with 20° grid and 0.5 CTO gel
            Background: Even wash across seamless gray paper
        """.trimIndent()

        val scene = StudioScene(
            name = "Detailed Studio Notes",
            setupNotes = notes,
            groupPresets = listOf(GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 75)),
        )

        val json = StudioSceneJsonCodec.encodeSingleToJson(scene)
        val decoded = StudioSceneJsonCodec.decodeFromJson(json)

        assertEquals(1, decoded.size)
        assertEquals(notes, decoded[0].setupNotes)
        assertTrue(decoded[0].setupNotes.contains("5ft Octa @ 45°"))
    }

    @Test
    fun `test 4 JSON serialization and deserialization full fidelity roundtrip`() {
        val original = StudioScene(
            id = "preset-master-42",
            name = "Commercial Fashion Octa",
            createdAtUtc = 1731420000000L,
            setupNotes = "Fashion high key commercial setup",
            remoteChannel = 14,
            remoteOperatingMode = RemoteOperatingMode.AUTO_TTL,
            syncTriggerMode = SyncTriggerMode.FIRST,
            afAssistMode = AfAssistMode.ON,
            rfPowerLevel = RfPowerLevel.P4,
            readySignalMode = ReadySignalMode.SOUND,
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 85, isEnabled = true, isModelingEnabled = true, roleLabel = "Main Key", ttlEvBiasTenthStops = 15),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 65, isEnabled = true, isModelingEnabled = false, roleLabel = "Fill", ttlEvBiasTenthStops = -10),
                GroupPreset(group = AirGroup.GROUP_C, powerTenthStops = 70, isEnabled = false, isModelingEnabled = false, roleLabel = "Background", ttlEvBiasTenthStops = 0),
            ),
        )

        val json = StudioSceneJsonCodec.encodeToJson(listOf(original))
        val decodedList = StudioSceneJsonCodec.decodeFromJson(json)

        assertEquals(1, decodedList.size)
        val decoded = decodedList[0]
        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.createdAtUtc, decoded.createdAtUtc)
        assertEquals(original.setupNotes, decoded.setupNotes)
        assertEquals(original.remoteChannel, decoded.remoteChannel)
        assertEquals(original.remoteOperatingMode, decoded.remoteOperatingMode)
        assertEquals(original.syncTriggerMode, decoded.syncTriggerMode)
        assertEquals(original.afAssistMode, decoded.afAssistMode)
        assertEquals(original.rfPowerLevel, decoded.rfPowerLevel)
        assertEquals(original.readySignalMode, decoded.readySignalMode)
        assertEquals(3, decoded.groupPresets.size)

        for (grp in listOf(AirGroup.GROUP_A, AirGroup.GROUP_B, AirGroup.GROUP_C)) {
            val origGp = original.presetFor(grp)!!
            val decGp = decoded.presetFor(grp)!!
            assertEquals(origGp.powerTenthStops, decGp.powerTenthStops)
            assertEquals(origGp.isEnabled, decGp.isEnabled)
            assertEquals(origGp.isModelingEnabled, decGp.isModelingEnabled)
            assertEquals(origGp.roleLabel, decGp.roleLabel)
            assertEquals(origGp.ttlEvBiasTenthStops, decGp.ttlEvBiasTenthStops)
        }
    }

    @Test
    fun `test 5 apply scene dispatches parameters to live hardware and updates studio state`() = runBlocking {
        val harness = E2ETestHarness()
        val cpAddr = VirtualBenchFixtures.ADDR_CONNECT_PRO
        val b10xAddr = VirtualBenchFixtures.ADDR_B10X_PLUS

        harness.connectByAddress(cpAddr)
        harness.connectByAddress(b10xAddr)
        harness.awaitActiveReadyCount(2)

        val scene = StudioScene(
            name = "Rembrandt Portrait",
            remoteChannel = 7,
            remoteOperatingMode = RemoteOperatingMode.MANUAL,
            syncTriggerMode = SyncTriggerMode.FIRST,
            afAssistMode = AfAssistMode.AUTO,
            rfPowerLevel = RfPowerLevel.P4,
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 82, isEnabled = true, isModelingEnabled = true, roleLabel = "Rembrandt Key"),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 54, isEnabled = false, isModelingEnabled = false, roleLabel = "Shadow Fill"),
            ),
        )

        harness.orchestrator.applyScene(scene)
        delay(120)

        // Verify Connect Pro hardware state
        val cp = harness.orchestrator.dashboardState.value.connectedUnits.first { it.address == cpAddr }
        assertEquals(7, cp.airChannel)
        assertEquals(RemoteOperatingMode.MANUAL, cp.remoteMode)
        assertEquals(82, cp.groupPowersTenthStops[0])
        assertEquals(54, cp.groupPowersTenthStops[1])

        // Verify group roles updated
        val roles = harness.orchestrator.dashboardState.value.groupRoles
        assertEquals("Rembrandt Key", roles[AirGroup.GROUP_A])
        assertEquals("Shadow Fill", roles[AirGroup.GROUP_B])

        // Verify UI banner
        val banner = harness.orchestrator.dashboardState.value.statusBanner
        assertNotNull(banner)
        assertTrue(banner!!.contains("Applied preset: Rembrandt Portrait"))

        harness.disconnectDevice(cpAddr)
        harness.disconnectDevice(b10xAddr)
    }

    @Test
    fun `test 6 delete scene and import scenes lifecycle`() = runBlocking {
        val harness = E2ETestHarness()
        val s1 = StudioScene(id = "scene-1", name = "Preset 1")
        val s2 = StudioScene(id = "scene-2", name = "Preset 2")

        harness.orchestrator.saveScene(s1)
        harness.orchestrator.saveScene(s2)
        delay(40)
        assertEquals(2, harness.orchestrator.savedScenes.value.size)

        // Delete s1
        harness.orchestrator.deleteScene("scene-1")
        delay(40)
        assertEquals(1, harness.orchestrator.savedScenes.value.size)
        assertEquals("scene-2", harness.orchestrator.savedScenes.value[0].id)

        // Import s1 back along with a new scene s3
        val s3 = StudioScene(id = "scene-3", name = "Preset 3")
        harness.orchestrator.importScenes(listOf(s1, s3))
        delay(40)

        val finalScenes = harness.orchestrator.savedScenes.value
        assertEquals(3, finalScenes.size)
        assertTrue(finalScenes.any { it.id == "scene-1" })
        assertTrue(finalScenes.any { it.id == "scene-2" })
        assertTrue(finalScenes.any { it.id == "scene-3" })
    }
}
