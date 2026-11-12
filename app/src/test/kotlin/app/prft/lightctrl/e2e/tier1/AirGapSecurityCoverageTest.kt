package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.ble.ProfotoGattProfile
import app.prft.lightctrl.e2e.harness.encodeFrame
import app.prft.lightctrl.protocol.PupConstants
import app.prft.lightctrl.protocol.PupFrameCodec
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
 * Tier 1 — Feature 13: Air-Gap Security & Integrity Coverage Tests.
 *
 * Verifies:
 * 1. AndroidManifest contains 0 android.permission.INTERNET.
 * 2. AndroidManifest contains 0 location permissions (neverForLocation flag on BLUETOOTH_SCAN).
 * 3. PupFrameCodec.encodeFrame() throws PupSecurityQuarantineException when opcode 46 is attempted.
 * 4. PupFrameCodec.encodeFrame() throws PupSecurityQuarantineException when opcode 142 or 154 is attempted.
 * 5. ProfotoGattProfile.assertSafeCharacteristicUuid() throws SecurityException when Silabs or Profoto DFU UUIDs are attempted.
 */
class AirGapSecurityCoverageTest {

    private fun findManifestFile(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
        )
        val file = candidates.firstOrNull { it.isFile && it.exists() }
        assertNotNull("AndroidManifest.xml must be found", file)
        return file!!
    }

    @Test
    fun `test 1 AndroidManifest contains strictly zero INTERNET permission`() {
        val manifest = findManifestFile().readText()

        assertFalse(
            "Air-gap violation: AndroidManifest.xml must NEVER contain android.permission.INTERNET",
            manifest.contains("android.permission.INTERNET"),
        )
        assertFalse(
            "Air-gap violation: AndroidManifest.xml must NEVER contain android.permission.ACCESS_NETWORK_STATE",
            manifest.contains("android.permission.ACCESS_NETWORK_STATE"),
        )
        assertFalse(
            "Air-gap violation: AndroidManifest.xml must NEVER contain android.permission.CHANGE_NETWORK_STATE",
            manifest.contains("android.permission.CHANGE_NETWORK_STATE"),
        )
    }

    @Test
    fun `test 2 AndroidManifest contains zero location permissions and sets neverForLocation on BLUETOOTH_SCAN`() {
        val manifest = findManifestFile().readText()

        // Check zero location permissions
        assertFalse(
            "Location privacy violation: manifest must not request ACCESS_FINE_LOCATION",
            manifest.contains("android.permission.ACCESS_FINE_LOCATION"),
        )
        assertFalse(
            "Location privacy violation: manifest must not request ACCESS_COARSE_LOCATION",
            manifest.contains("android.permission.ACCESS_COARSE_LOCATION"),
        )
        assertFalse(
            "Location privacy violation: manifest must not request ACCESS_BACKGROUND_LOCATION",
            manifest.contains("android.permission.ACCESS_BACKGROUND_LOCATION"),
        )

        // Enforce neverForLocation flag
        assertTrue(
            "BLUETOOTH_SCAN must include android:usesPermissionFlags=\"neverForLocation\"",
            manifest.contains("usesPermissionFlags=\"neverForLocation\"") ||
                manifest.contains("usesPermissionFlags=\n            \"neverForLocation\""),
        )
    }

    @Test
    fun `test 3 PupFrameCodec encodeFrame throws PupSecurityQuarantineException when opcode 46 is attempted`() {
        // Opcode 46 = FirmwareArm / bootloader transition
        try {
            PupFrameCodec.encodeFrame(counter = 1, opcode = 46)
            fail("Expected PupSecurityQuarantineException for opcode 46")
        } catch (e: PupSecurityQuarantineException) {
            assertTrue("Exception message should indicate quarantine", e.message?.contains("QUARANTINE") == true)
            assertTrue("Exception message should reference opcode 46", e.message?.contains("46") == true)
        }

        // Also test direct encodePacket
        try {
            PupFrameCodec.encodePacket(counter = 2, opcode = 46)
            fail("Expected PupSecurityQuarantineException for encodePacket with opcode 46")
        } catch (e: PupSecurityQuarantineException) {
            assertTrue(e.message?.contains("46") == true)
        }
    }

    @Test
    fun `test 4 PupFrameCodec encodeFrame throws PupSecurityQuarantineException when opcode 142 or 154 is attempted`() {
        // Opcode 142 = BlobData (firmware payload block transfer)
        try {
            PupFrameCodec.encodeFrame(counter = 3, opcode = 142, payload = byteArrayOf(0x01, 0x02, 0x03))
            fail("Expected PupSecurityQuarantineException for opcode 142")
        } catch (e: PupSecurityQuarantineException) {
            assertTrue(e.message?.contains("142") == true)
        }

        // Opcode 154 = arm_firmware_update
        try {
            PupFrameCodec.encodeFrame(counter = 4, opcode = 154)
            fail("Expected PupSecurityQuarantineException for opcode 154")
        } catch (e: PupSecurityQuarantineException) {
            assertTrue(e.message?.contains("154") == true)
        }

        // Also verify query variants of quarantined opcodes are rejected
        val query142 = 142 or PupConstants.QUERY_BIT_MASK
        try {
            PupFrameCodec.encodeFrame(counter = 5, opcode = query142)
            fail("Expected PupSecurityQuarantineException for query variant of opcode 142")
        } catch (e: PupSecurityQuarantineException) {
            assertTrue(e.message?.contains("142") == true)
        }
    }

    @Test
    fun `test 5 ProfotoGattProfile assertSafeCharacteristicUuid throws SecurityException when Silabs or Profoto DFU UUIDs are attempted`() {
        // Silabs OTA Data: 984227f3-34fc-4045-a5d0-2c581f81a153
        val silabsOtaUuid = UUID.fromString("984227f3-34fc-4045-a5d0-2c581f81a153")
        var blockedSilabs = false
        try {
            ProfotoGattProfile.assertSafeCharacteristicUuid(silabsOtaUuid)
        } catch (_: SecurityException) {
            blockedSilabs = true
        }
        assertTrue("Silabs OTA Data UUID must be quarantined", blockedSilabs)

        // Profoto MCU DFU Bootloader: f7bf3564-fb6d-4e53-88a4-5e37e0326063
        val mcuDfuUuid = UUID.fromString("f7bf3564-fb6d-4e53-88a4-5e37e0326063")
        var blockedMcu = false
        try {
            ProfotoGattProfile.assertSafeCharacteristicUuid(mcuDfuUuid)
        } catch (_: SecurityException) {
            blockedMcu = true
        }
        assertTrue("MCU DFU UUID must be quarantined", blockedMcu)

        // Verify all registered FORBIDDEN_DFU_CHAR_UUIDS are blocked
        for (dfuUuid in ProfotoGattProfile.FORBIDDEN_DFU_CHAR_UUIDS) {
            var blocked = false
            try {
                ProfotoGattProfile.assertSafeCharacteristicUuid(dfuUuid)
            } catch (_: SecurityException) {
                blocked = true
            }
            assertTrue("DFU UUID $dfuUuid must throw SecurityException", blocked)
        }
    }

    @Test
    fun `test 6 ProfotoGattProfile assertSafeCharacteristicUuid permits standard operational characteristics`() {
        val safeUuids = listOf(
            ProfotoGattProfile.PUP_TX_CHAR_UUID,
            ProfotoGattProfile.PUP_RX_CHAR_UUID,
            ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID,
            ProfotoGattProfile.CCCD_UUID,
        )

        for (uuid in safeUuids) {
            // Must not throw any exception
            ProfotoGattProfile.assertSafeCharacteristicUuid(uuid)
        }
    }
}
