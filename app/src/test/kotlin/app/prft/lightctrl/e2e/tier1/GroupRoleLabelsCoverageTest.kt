package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.AirGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 — Feature 8: Custom Group Role Labels Coverage Tests.
 *
 * Verifies:
 * 1. Assigning roles for all groups A..F reflects in groupRoles StateFlow.
 * 2. Clean initial state has zero roles assigned.
 * 3. Clearing roles via null or empty string removes entries.
 * 4. Updating an existing group's role cleanly overwrites.
 * 5. Roles reflect in orchestrator dashboardState and groupChannels.
 * 6. StudioPreferencesStore in-memory isolation across operations.
 */
class GroupRoleLabelsCoverageTest {

    @Test
    fun `test 1 assign distinct roles across all groups A through F`() = runBlocking {
        val harness = E2ETestHarness()
        val expected = mapOf(
            AirGroup.GROUP_A to "Main Key",
            AirGroup.GROUP_B to "Softbox Fill",
            AirGroup.GROUP_C to "Rim Kicker",
            AirGroup.GROUP_D to "Hair Accent",
            AirGroup.GROUP_E to "Background Wash",
            AirGroup.GROUP_F to "Floor Sweeper",
        )

        expected.forEach { (grp, label) ->
            harness.orchestrator.setGroupRole(grp, label)
        }
        delay(60)

        val roles = harness.orchestrator.dashboardState.value.groupRoles
        assertEquals(6, roles.size)
        expected.forEach { (grp, label) ->
            assertEquals(label, roles[grp])
        }

        val channels = harness.orchestrator.dashboardState.value.groupChannels
        assertEquals(6, channels.size)
        expected.forEach { (grp, label) ->
            val ch = channels.first { it.group == grp }
            assertEquals(label, ch.roleLabel)
        }
    }

    @Test
    fun `test 2 clean default state has no assigned roles`() = runBlocking {
        val harness = E2ETestHarness()

        val roles = harness.orchestrator.dashboardState.value.groupRoles
        assertTrue("Initial group roles must be empty", roles.isEmpty())

        val channels = harness.orchestrator.dashboardState.value.groupChannels
        channels.forEach { ch ->
            assertNull("Group ${ch.group} roleLabel must be null by default", ch.roleLabel)
        }
    }

    @Test
    fun `test 3 clear role using null or empty string removes role entry`() = runBlocking {
        val harness = E2ETestHarness()

        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, "Key Light")
        harness.orchestrator.setGroupRole(AirGroup.GROUP_B, "Fill Light")
        delay(40)
        assertEquals(2, harness.orchestrator.dashboardState.value.groupRoles.size)

        // Clear Group A with null
        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, null)
        delay(40)
        val afterNull = harness.orchestrator.dashboardState.value.groupRoles
        assertEquals(1, afterNull.size)
        assertFalse(afterNull.containsKey(AirGroup.GROUP_A))
        assertEquals("Fill Light", afterNull[AirGroup.GROUP_B])

        // Clear Group B with empty string
        harness.orchestrator.setGroupRole(AirGroup.GROUP_B, "")
        delay(40)
        val afterEmpty = harness.orchestrator.dashboardState.value.groupRoles
        assertTrue(afterEmpty.isEmpty())
    }

    @Test
    fun `test 4 update role label overwrites existing value without side-effects`() = runBlocking {
        val harness = E2ETestHarness()

        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, "Umbrella Key")
        harness.orchestrator.setGroupRole(AirGroup.GROUP_B, "Fill")
        delay(40)

        assertEquals("Umbrella Key", harness.orchestrator.dashboardState.value.groupRoles[AirGroup.GROUP_A])

        // Overwrite Group A
        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, "Beauty Dish Key")
        delay(40)

        val roles = harness.orchestrator.dashboardState.value.groupRoles
        assertEquals(2, roles.size)
        assertEquals("Beauty Dish Key", roles[AirGroup.GROUP_A])
        assertEquals("Fill", roles[AirGroup.GROUP_B])
    }

    @Test
    fun `test 5 role labels propagate into dashboardState groupChannels`() = runBlocking {
        val harness = E2ETestHarness()

        harness.orchestrator.setGroupRole(AirGroup.GROUP_C, "Backlight")
        delay(40)

        val chC = harness.orchestrator.dashboardState.value.groupChannels.first { it.group == AirGroup.GROUP_C }
        assertEquals("Backlight", chC.roleLabel)

        val chD = harness.orchestrator.dashboardState.value.groupChannels.first { it.group == AirGroup.GROUP_D }
        assertNull(chD.roleLabel)
    }

    @Test
    fun `test 6 StudioPreferencesStore direct in-memory flow emissions`() = runBlocking {
        val store = StudioPreferencesStore(context = null)

        assertTrue(store.getGroupRoles().first().isEmpty())

        store.setGroupRole(AirGroup.GROUP_A, "Key")
        store.setGroupRole(AirGroup.GROUP_B, "Fill")

        val current = store.getGroupRoles().first()
        assertEquals(2, current.size)
        assertEquals("Key", current[AirGroup.GROUP_A])
        assertEquals("Fill", current[AirGroup.GROUP_B])
    }
}
