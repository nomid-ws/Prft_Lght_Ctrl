package app.prft.lightctrl.e2e.tier1

import app.prft.lightctrl.ble.BleConnectionStage
import app.prft.lightctrl.ble.ProfotoBleConnectionFsm
import app.prft.lightctrl.ble.ProfotoGattProfile
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.e2e.harness.FakeBleGateway
import app.prft.lightctrl.e2e.harness.RecordingBlePeerLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Tier 1 — Feature 6: Working Tree BLE Fixes Coverage Tests.
 *
 * Verifies:
 * 1. Connect Pro connects and resolves activeService = AUTH_SERVICE_UUID (bbd75314-ddaa-4b61-a7df-c6eedb79c152).
 * 2. Connect Pro sends "Profoto Client" token write to AUTH_CLIENT_ID_CHAR_UUID.
 * 3. Connection FSM successfully reaches BleConnectionStage.Ready.
 * 4. "FIRE" button string label is present in StudioDashboardScreen.kt.
 * 5. B10X Plus / A2 monolights resolve standard PRIMARY_SIG_SERVICE_UUID without hitting auth service.
 * 6. StudioOrchestrator integrates Connect Pro through FakeBleGateway and reaches Ready.
 */
class WorkingTreeBleFixesCoverageTest {

    @Test
    fun `test 1 Connect Pro connects and resolves activeService = AUTH_SERVICE_UUID`() = runBlocking {
        val kit = VirtualBenchFixtures.createKit()
        val connectPro = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        val peerLink = VirtualBenchFixtures.createPeerLink(connectPro, fastTimings = true)
        val recordingLink = RecordingBlePeerLink(peerLink)
        val fsm = ProfotoBleConnectionFsm(
            device = connectPro,
            link = recordingLink,
            stageTimeoutMs = 2_000L,
            cacheSettleMs = 0L,
        )

        val success = fsm.connectAndAuthenticate()
        assertTrue("Connect Pro FSM connection must succeed", success)

        val stage = fsm.stage.value
        assertTrue("Stage must be Ready but was $stage", stage is BleConnectionStage.Ready)

        val readyStage = stage as BleConnectionStage.Ready
        val expectedAuthUuid = UUID.fromString("bbd75314-ddaa-4b61-a7df-c6eedb79c152")
        assertEquals("activeService must match AUTH_SERVICE_UUID", ProfotoGattProfile.AUTH_SERVICE_UUID, readyStage.activeServiceUuid)
        assertEquals("activeService must match bbd75314-ddaa-4b61-a7df-c6eedb79c152", expectedAuthUuid, readyStage.activeServiceUuid)

        fsm.disconnect()
    }

    @Test
    fun `test 2 Connect Pro sends Profoto Client token write to AUTH_CLIENT_ID_CHAR_UUID`() = runBlocking {
        val kit = VirtualBenchFixtures.createKit()
        val connectPro = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        val peerLink = VirtualBenchFixtures.createPeerLink(connectPro, fastTimings = true)
        val recordingLink = RecordingBlePeerLink(peerLink)
        val fsm = ProfotoBleConnectionFsm(
            device = connectPro,
            link = recordingLink,
            stageTimeoutMs = 2_000L,
            cacheSettleMs = 0L,
        )

        assertTrue(fsm.connectAndAuthenticate())

        // Find the characteristic write to AUTH_CLIENT_ID_CHAR_UUID
        val authWrite = recordingLink.recordedWrites.firstOrNull {
            it.characteristicUuid == ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID
        }
        assertNotNull("Must record write to AUTH_CLIENT_ID_CHAR_UUID", authWrite)
        assertEquals("Profoto Client", authWrite!!.bytes.toString(Charsets.UTF_8))
        assertTrue("Client handshake must be write with response", authWrite.withResponse)
        assertEquals(ProfotoGattProfile.AUTH_SERVICE_UUID, authWrite.serviceUuid)

        fsm.disconnect()
    }

    @Test
    fun `test 3 Connection FSM successfully reaches BleConnectionStage Ready`() = runBlocking {
        val kit = VirtualBenchFixtures.createKit()
        val connectPro = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        val link = RecordingBlePeerLink(VirtualBenchFixtures.createPeerLink(connectPro, fastTimings = true))
        val fsm = ProfotoBleConnectionFsm(connectPro, link, stageTimeoutMs = 2000L, cacheSettleMs = 0L)

        // Strict initialization state
        assertEquals(BleConnectionStage.Disconnected, fsm.stage.value)

        val ok = fsm.connectAndAuthenticate()
        assertTrue("Handshake and hydration must complete", ok)

        val finalStage = fsm.stage.value
        assertTrue("Must reach Ready stage, got: $finalStage", finalStage is BleConnectionStage.Ready)
        val ready = finalStage as BleConnectionStage.Ready
        assertEquals(255, ready.mtu)
        assertEquals(ProfotoGattProfile.AUTH_SERVICE_UUID, ready.activeServiceUuid)

        fsm.disconnect()
        assertEquals(BleConnectionStage.Disconnected, fsm.stage.value)
    }

    @Test
    fun `test 4 FIRE button string label is present in StudioDashboardScreen`() {
        val candidatePaths = listOf(
            File("src/main/kotlin/app/prft/lightctrl/ui/dashboard/StudioDashboardScreen.kt"),
            File("app/src/main/kotlin/app/prft/lightctrl/ui/dashboard/StudioDashboardScreen.kt"),
            File("../app/src/main/kotlin/app/prft/lightctrl/ui/dashboard/StudioDashboardScreen.kt"),
        )
        val file = candidatePaths.firstOrNull { it.isFile && it.exists() }
        assertNotNull("StudioDashboardScreen.kt file must exist", file)

        val content = file!!.readText()
        assertTrue("StudioDashboardScreen.kt must declare 'FIRE' CTA button label", content.contains("\"FIRE\""))
        assertTrue("StudioDashboardScreen.kt must contain ctaText branch for FIRE", content.contains("else -> \"FIRE\""))
    }

    @Test
    fun `test 5 B10X Plus and A2 monolights resolve standard PRIMARY_SIG_SERVICE_UUID without hitting auth service`() = runBlocking {
        val kit = VirtualBenchFixtures.createKit()
        val monolights = listOf(
            kit.first { it.address == VirtualBenchFixtures.ADDR_B10X_PLUS },
            kit.first { it.address == VirtualBenchFixtures.ADDR_A2 },
        )

        val expectedSigUuid = UUID.fromString("0000fdbb-0000-1000-8000-00805f9b34fb")
        assertEquals(ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID, expectedSigUuid)

        for (device in monolights) {
            val link = RecordingBlePeerLink(VirtualBenchFixtures.createPeerLink(device, fastTimings = true))
            val fsm = ProfotoBleConnectionFsm(device, link, stageTimeoutMs = 2000L, cacheSettleMs = 0L)

            val success = fsm.connectAndAuthenticate()
            assertTrue("FSM must authenticate for ${device.advertisedName}", success)

            val stage = fsm.stage.value as BleConnectionStage.Ready
            assertEquals(
                "Monolight ${device.advertisedName} must resolve PRIMARY_SIG_SERVICE_UUID",
                ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID,
                stage.activeServiceUuid,
            )
            assertFalse(
                "Monolight ${device.advertisedName} activeService must NOT be AUTH_SERVICE_UUID",
                stage.activeServiceUuid == ProfotoGattProfile.AUTH_SERVICE_UUID,
            )

            fsm.disconnect()
        }
    }

    @Test
    fun `test 6 StudioOrchestrator connects Connect Pro through E2ETestHarness and transitions to Ready`() = runBlocking {
        val harness = E2ETestHarness()
        val connectProAddr = VirtualBenchFixtures.ADDR_CONNECT_PRO

        harness.connectByAddress(connectProAddr)
        harness.awaitDeviceReady(connectProAddr)

        val state = harness.orchestrator.dashboardState.value
        val unit = state.connectedUnits.firstOrNull { it.address == connectProAddr }
        assertNotNull("Connect Pro must be present in connectedUnits", unit)
        assertTrue("Connect Pro must be ready", unit!!.isReady)

        val recordingLink = harness.getRecordingLink(connectProAddr)
        assertNotNull("Recording link must be tracked", recordingLink)
        val writes = recordingLink!!.recordedWrites
        assertTrue("Handshake token must have been written", writes.any {
            it.characteristicUuid == ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID &&
                it.bytes.toString(Charsets.UTF_8) == "Profoto Client"
        })

        harness.disconnectDevice(connectProAddr)
    }
}
