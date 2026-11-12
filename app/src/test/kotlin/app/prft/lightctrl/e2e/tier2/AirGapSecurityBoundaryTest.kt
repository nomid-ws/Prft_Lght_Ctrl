package app.prft.lightctrl.e2e.tier2

import app.prft.lightctrl.ble.ProfotoGattProfile
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.e2e.harness.encodeFrame
import app.prft.lightctrl.protocol.PupConstants
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.PupSecurityQuarantineException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Tier 2 — Feature 13: Air-Gap Security Boundary Tests.
 *
 * Verifies:
 * 1. Adjacent valid opcodes (e.g. 45 TEST_FLASH, 47) encode normally while 46 is quarantined.
 * 2. Sub-opcodes or TLVs targeting firmware update functions are blocked.
 * 3. Case-insensitive and uppercase/lowercase DFU UUID comparison safely blocked.
 * 4. Merged release manifest contains strictly 0 network socket permissions.
 * 5. Initial dashboard state starts strictly with 0 connected devices (DISCONNECTED).
 */
class AirGapSecurityBoundaryTest {

    @Test
    fun `test 1 adjacent valid opcodes encode normally while 46, 142, and 154 are quarantined`() {
        // Boundary check around opcode 46
        val frame45 = PupFrameCodec.encodeFrame(counter = 1, opcode = 45) // TEST_FLASH
        assertEquals(3, frame45.size)
        assertEquals(0x01.toByte(), frame45[0])
        assertEquals(0x2D.toByte(), frame45[1]) // 45 = 0x2D
        assertEquals(0x00.toByte(), frame45[2])

        try {
            PupFrameCodec.encodeFrame(counter = 1, opcode = 46)
            fail("Opcode 46 must throw PupSecurityQuarantineException")
        } catch (e: PupSecurityQuarantineException) {
            assertTrue(e.message?.contains("46") == true)
        }

        val frame47 = PupFrameCodec.encodeFrame(counter = 1, opcode = 47)
        assertEquals(3, frame47.size)
        assertEquals(0x2F.toByte(), frame47[1]) // 47 = 0x2F

        // Boundary check around opcode 142
        val frame141 = PupFrameCodec.encodeFrame(counter = 2, opcode = 141)
        assertEquals(3, frame141.size)

        try {
            PupFrameCodec.encodeFrame(counter = 2, opcode = 142)
            fail("Opcode 142 must throw PupSecurityQuarantineException")
        } catch (e: PupSecurityQuarantineException) {
            assertTrue(e.message?.contains("142") == true)
        }

        val frame143 = PupFrameCodec.encodeFrame(counter = 2, opcode = 143)
        assertEquals(3, frame143.size)

        // Boundary check around opcode 154
        val frame153 = PupFrameCodec.encodeFrame(counter = 3, opcode = 153)
        assertEquals(3, frame153.size)

        try {
            PupFrameCodec.encodeFrame(counter = 3, opcode = 154)
            fail("Opcode 154 must throw PupSecurityQuarantineException")
        } catch (e: PupSecurityQuarantineException) {
            assertTrue(e.message?.contains("154") == true)
        }

        val frame155 = PupFrameCodec.encodeFrame(counter = 3, opcode = 155)
        assertEquals(3, frame155.size)
    }

    @Test
    fun `test 2 sub-opcodes or query variants targeting firmware update functions are blocked`() {
        // Query bit set on quarantined opcodes
        for (quarantined in PupConstants.QUARANTINED_OPCODES) {
            val wireOpcodeWithQuery = quarantined or PupConstants.QUERY_BIT_MASK
            try {
                PupFrameCodec.encodeFrame(counter = 10, opcode = wireOpcodeWithQuery)
                fail("Quarantined opcode $quarantined with query bit must throw PupSecurityQuarantineException")
            } catch (e: PupSecurityQuarantineException) {
                assertTrue(e.message?.contains(quarantined.toString()) == true)
            }

            try {
                PupFrameCodec.encodePacket(counter = 11, opcode = quarantined, isQuery = true)
                fail("Quarantined opcode $quarantined with isQuery=true must throw PupSecurityQuarantineException")
            } catch (e: PupSecurityQuarantineException) {
                assertTrue(e.message?.contains(quarantined.toString()) == true)
            }
        }
    }

    @Test
    fun `test 3 case-insensitive and uppercase lowercase DFU UUID comparison safely blocked`() {
        for (dfuUuid in ProfotoGattProfile.FORBIDDEN_DFU_CHAR_UUIDS) {
            val lowerString = dfuUuid.toString().lowercase()
            val upperString = dfuUuid.toString().uppercase()

            val fromLower = UUID.fromString(lowerString)
            val fromUpper = UUID.fromString(upperString)

            // Both lower and upper UUID parses must evaluate to the same quarantined UUID
            assertEquals(dfuUuid, fromLower)
            assertEquals(dfuUuid, fromUpper)

            var blockedLower = false
            try {
                ProfotoGattProfile.assertSafeCharacteristicUuid(fromLower)
            } catch (_: SecurityException) {
                blockedLower = true
            }
            assertTrue("Lowercase DFU UUID $lowerString must be blocked", blockedLower)

            var blockedUpper = false
            try {
                ProfotoGattProfile.assertSafeCharacteristicUuid(fromUpper)
            } catch (_: SecurityException) {
                blockedUpper = true
            }
            assertTrue("Uppercase DFU UUID $upperString must be blocked", blockedUpper)
        }
    }

    @Test
    fun `test 4 manifest across project contains strictly 0 network socket permissions`() {
        val rootDir = File(".").canonicalFile
        val manifestFiles = rootDir.walkTopDown().filter {
            it.isFile && it.name == "AndroidManifest.xml" && !it.path.contains("/build/")
        }.toList()

        assertTrue("At least one AndroidManifest.xml must exist in project", manifestFiles.isNotEmpty())

        val forbiddenPermissions = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        )

        for (manifest in manifestFiles) {
            val content = manifest.readText()
            for (forbidden in forbiddenPermissions) {
                assertFalse(
                    "Air-gap violation in ${manifest.path}: contains $forbidden",
                    content.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun `test 5 initial dashboard state starts strictly with 0 connected devices DISCONNECTED`() {
        val harness = E2ETestHarness()
        val initialState = harness.orchestrator.dashboardState.value

        // Zero fake connected devices on launch
        assertTrue("Initial connectedUnits must be empty", initialState.connectedUnits.isEmpty())
        assertEquals("Initial activeReadyCount must be 0", 0, initialState.activeReadyCount)
        assertFalse("Bench simulator must be disabled by default", initialState.benchSimulatorEnabled)

        // All kit devices start DISCONNECTED
        val kit = VirtualBenchFixtures.createKit()
        assertEquals(4, kit.size)
        for (device in kit) {
            assertFalse(
                "Device ${device.address} must not be present in connectedUnits on launch",
                initialState.connectedUnits.any { it.address == device.address },
            )
        }
    }
}
