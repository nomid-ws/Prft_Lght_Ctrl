package app.prft.lightctrl.e2e.tier2

import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.protocol.AirGroup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 — Feature 8: Custom Group Role Labels Boundary Tests.
 *
 * Verifies:
 * 1. Empty and whitespace-only strings are trimmed and treated as deletion.
 * 2. Extremely long role labels (2,000+ chars) store and retrieve safely.
 * 3. AirGroup.ALL is strictly ignored and rejected from role assignments.
 * 4. Clearing unassigned role is a safe no-op.
 * 5. Leading and trailing whitespace is trimmed properly.
 * 6. Unicode, emojis, and punctuation are preserved verbatim.
 * 7. Rapid concurrent role updates maintain thread safety without exception.
 */
class GroupRoleLabelsBoundaryTest {

    @Test
    fun `test 1 empty and whitespace-only strings treat as role removal`() = runBlocking {
        val harness = E2ETestHarness()

        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, "Key Light")
        delay(40)
        assertEquals("Key Light", harness.orchestrator.dashboardState.value.groupRoles[AirGroup.GROUP_A])

        // Whitespace tabs and newlines
        harness.orchestrator.setGroupRole(AirGroup.GROUP_A, "   \t\n  ")
        delay(40)

        val roles = harness.orchestrator.dashboardState.value.groupRoles
        assertFalse("Group A role must be removed", roles.containsKey(AirGroup.GROUP_A))
        assertNull("Group A channel role must be null", harness.orchestrator.dashboardState.value.groupChannels.first { it.group == AirGroup.GROUP_A }.roleLabel)
    }

    @Test
    fun `test 2 extremely long role label 2000 characters stores safely`() = runBlocking {
        val harness = E2ETestHarness()
        val longLabel = "Role-".repeat(400) // 2000 chars

        harness.orchestrator.setGroupRole(AirGroup.GROUP_B, longLabel)
        delay(40)

        val stored = harness.orchestrator.dashboardState.value.groupRoles[AirGroup.GROUP_B]
        assertEquals(longLabel, stored)
        assertEquals(2000, stored?.length)
    }

    @Test
    fun `test 3 AirGroup ALL is strictly ignored and never added to roles map`() = runBlocking {
        val harness = E2ETestHarness()

        harness.orchestrator.setGroupRole(AirGroup.ALL, "Master Studio Key")
        delay(40)

        val roles = harness.orchestrator.dashboardState.value.groupRoles
        assertFalse("AirGroup.ALL must never be added to groupRoles", roles.containsKey(AirGroup.ALL))
        assertTrue("Roles map must remain empty", roles.isEmpty())
    }

    @Test
    fun `test 4 clearing unassigned role is a safe no-op`() = runBlocking {
        val harness = E2ETestHarness()

        // Call clear on unassigned groups
        harness.orchestrator.setGroupRole(AirGroup.GROUP_E, null)
        harness.orchestrator.setGroupRole(AirGroup.GROUP_F, "")
        delay(40)

        assertTrue(harness.orchestrator.dashboardState.value.groupRoles.isEmpty())
    }

    @Test
    fun `test 5 leading and trailing whitespace is trimmed properly`() = runBlocking {
        val harness = E2ETestHarness()

        harness.orchestrator.setGroupRole(AirGroup.GROUP_C, "  \t  Rim Light 45°  \n  ")
        delay(40)

        val role = harness.orchestrator.dashboardState.value.groupRoles[AirGroup.GROUP_C]
        assertEquals("Rim Light 45°", role)
    }

    @Test
    fun `test 6 unicode emojis and special punctuation preserved verbatim`() = runBlocking {
        val harness = E2ETestHarness()
        val specialRoles = mapOf(
            AirGroup.GROUP_A to "Main Key 💡",
            AirGroup.GROUP_B to "フィル (Fill) · 1/2 Stop",
            AirGroup.GROUP_C to "Hintergrund-Licht (Gelb)",
            AirGroup.GROUP_D to "Hair & Accent / <Top>",
        )

        specialRoles.forEach { (grp, label) ->
            harness.orchestrator.setGroupRole(grp, label)
        }
        delay(60)

        val roles = harness.orchestrator.dashboardState.value.groupRoles
        specialRoles.forEach { (grp, label) ->
            assertEquals(label, roles[grp])
        }
    }

    @Test
    fun `test 7 rapid concurrent asynchronous role updates maintain thread safety`() = runBlocking {
        val harness = E2ETestHarness()
        val groups = listOf(AirGroup.GROUP_A, AirGroup.GROUP_B, AirGroup.GROUP_C, AirGroup.GROUP_D, AirGroup.GROUP_E, AirGroup.GROUP_F)

        coroutineScope {
            val jobs = (1..50).map { iter ->
                async {
                    val grp = groups[iter % groups.size]
                    val label = "Role-$grp-$iter"
                    harness.orchestrator.setGroupRole(grp, label)
                }
            }
            jobs.awaitAll()
        }
        delay(100)

        val roles = harness.orchestrator.dashboardState.value.groupRoles
        assertTrue(roles.isNotEmpty())
        roles.forEach { (grp, label) ->
            assertTrue(label.startsWith("Role-$grp-"))
        }
    }
}
