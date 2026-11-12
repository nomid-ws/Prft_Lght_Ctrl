package app.prft.lightctrl.domain

import app.prft.lightctrl.protocol.AirGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioPreferencesStoreTest {

    @Test
    fun `default values with in-memory fallback on null context`() = runBlocking {
        val store = StudioPreferencesStore(context = null)

        assertTrue(store.groupRoles.value.isEmpty())
        assertTrue(store.pinnedKitMacs.value.isEmpty())
        assertFalse(store.isVolumeKeyTestFlashEnabled.value)
        assertTrue(store.isAutoReconnectPinnedEnabled.value)
        assertFalse(store.isDualPowerReadoutEnabled.value)

        assertTrue(store.getGroupRoles().first().isEmpty())
        assertTrue(store.getPinnedKitMacs().first().isEmpty())
        assertFalse(store.isVolumeKeyTestFlashEnabled().first())
        assertTrue(store.isAutoReconnectPinnedEnabled().first())
        assertFalse(store.isDualPowerReadoutEnabled().first())
    }

    @Test
    fun `group role assignment trim and removal`() = runBlocking {
        val store = StudioPreferencesStore(context = null)

        store.setGroupRole(AirGroup.GROUP_A, "  Main Key  ")
        store.setGroupRole(AirGroup.GROUP_B, "Fill Softbox")
        store.setGroupRole(AirGroup.ALL, "Master") // Must be ignored

        val roles = store.getGroupRoles().first()
        assertEquals(2, roles.size)
        assertEquals("Main Key", roles[AirGroup.GROUP_A])
        assertEquals("Fill Softbox", roles[AirGroup.GROUP_B])
        assertFalse(roles.containsKey(AirGroup.ALL))

        // Blank or null removes role
        store.setGroupRole(AirGroup.GROUP_A, "   ")
        val updatedRoles = store.getGroupRoles().first()
        assertEquals(1, updatedRoles.size)
        assertFalse(updatedRoles.containsKey(AirGroup.GROUP_A))
        assertEquals("Fill Softbox", updatedRoles[AirGroup.GROUP_B])

        store.setGroupRole(AirGroup.GROUP_B, null)
        assertTrue(store.getGroupRoles().first().isEmpty())
    }

    @Test
    fun `pinned kit mac addresses uppercase normalization and toggle`() = runBlocking {
        val store = StudioPreferencesStore(context = null)

        store.setDevicePinned("  4c:5b:18:22:33:aa  ", true)
        store.setDevicePinned("4C:5B:18:22:33:BB", true)

        val pinned = store.getPinnedKitMacs().first()
        assertEquals(2, pinned.size)
        assertTrue(pinned.contains("4C:5B:18:22:33:AA"))
        assertTrue(pinned.contains("4C:5B:18:22:33:BB"))

        // Unpin
        store.setDevicePinned("4c:5b:18:22:33:aa", false)
        val updated = store.getPinnedKitMacs().first()
        assertEquals(1, updated.size)
        assertFalse(updated.contains("4C:5B:18:22:33:AA"))
        assertTrue(updated.contains("4C:5B:18:22:33:BB"))
    }

    @Test
    fun `preferences toggles persistence and updates`() = runBlocking {
        val store = StudioPreferencesStore(context = null)

        store.setVolumeKeyTestFlashEnabled(true)
        assertTrue(store.isVolumeKeyTestFlashEnabled().first())

        store.setAutoReconnectPinnedEnabled(false)
        assertFalse(store.isAutoReconnectPinnedEnabled().first())

        store.setDualPowerReadoutEnabled(true)
        assertTrue(store.isDualPowerReadoutEnabled().first())
    }

    @Test
    fun `saved scenes save delete and import in memory fallback`() = runBlocking {
        val store = StudioPreferencesStore(context = null)
        assertTrue(store.savedScenes.value.isEmpty())

        val scene1 = StudioScene(
            id = "test-scene-1",
            name = "Commercial Key/Fill",
            setupNotes = "3-light portrait rig",
            groupPresets = listOf(
                GroupPreset(group = AirGroup.GROUP_A, powerTenthStops = 85, roleLabel = "Key"),
                GroupPreset(group = AirGroup.GROUP_B, powerTenthStops = 60, roleLabel = "Fill"),
            ),
            remoteChannel = 12,
        )

        store.saveScene(scene1)
        assertEquals(1, store.savedScenes.value.size)
        assertEquals("Commercial Key/Fill", store.savedScenes.value.first().name)

        // Update scene1 with new name
        val scene1Updated = scene1.copy(name = "Commercial Key/Fill v2")
        store.saveScene(scene1Updated)
        assertEquals(1, store.savedScenes.value.size)
        assertEquals("Commercial Key/Fill v2", store.savedScenes.value.first().name)

        val scene2 = StudioScene(
            id = "test-scene-2",
            name = "High Key Studio",
        )
        store.saveScene(scene2)
        assertEquals(2, store.savedScenes.value.size)

        // Delete scene1
        store.deleteScene("test-scene-1")
        assertEquals(1, store.savedScenes.value.size)
        assertEquals("test-scene-2", store.savedScenes.value.first().id)

        // Import scenes replaces
        store.importScenes(listOf(scene1, scene2))
        assertEquals(2, store.savedScenes.value.size)
    }
}
