package app.prft.lightctrl.ble

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DfuQuarantineSafetyTest {

    @Test
    fun `DFU bootloader characteristics are strictly excluded and blocked`() {
        val managed = ProfotoGattProfile.getAllManagedCharacteristicUuids()
        val forbiddenDfu = UUID.fromString("f7bf3564-fb6d-4e53-88a4-5e37e0326063")

        assertFalse(
            "CRITICAL SAFETY VIOLATION: DFU Bootloader UUID must never be in managed characteristics",
            managed.contains(forbiddenDfu),
        )

        for (dfuUuid in ProfotoGattProfile.FORBIDDEN_DFU_CHAR_UUIDS) {
            var blocked = false
            try {
                ProfotoGattProfile.assertSafeCharacteristicUuid(dfuUuid)
            } catch (_: SecurityException) {
                blocked = true
            }
            assertTrue("Expected $dfuUuid to throw SecurityException", blocked)
        }
    }

    @Test
    fun `10-stage FSM authenticates B10X Plus, A2, Connect Pro, and Connect from Disconnected`() = runBlocking {
        val kit = VirtualBenchFixtures.createKit()
        assertEquals(4, kit.size)

        for (device in kit) {
            val link = VirtualBenchFixtures.createPeerLink(device, fastTimings = true)
            val fsm = ProfotoBleConnectionFsm(
                device = device,
                link = link,
                stageTimeoutMs = 2_000L,
                cacheSettleMs = 0L,
            )

            // Verify zero fake hardware state prior to handshake
            assertEquals(BleConnectionStage.Disconnected, fsm.stage.value)

            val ok = fsm.connectAndAuthenticate()
            assertTrue("Expected handshake + hydration success for ${device.advertisedName} (stage=${fsm.stage.value})", ok)
            assertTrue(fsm.stage.value is BleConnectionStage.Ready)

            // Verify acked MxEnergy / AirChannel command roundtrip
            val sent = fsm.sendCommand(PupOpcodes.AIR_CHANNEL) { seq ->
                PupFrameCodec.encodeAirChannel(seq, 14)
            }
            assertTrue("Expected ACKed channel update on ${device.advertisedName}", sent)

            // Verify immediate TestFlash emits recycle event on strobes
            if (device.address == VirtualBenchFixtures.ADDR_B10X_PLUS) {
                val recycleWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                    fsm.incomingMessages.first { (_, decoded) ->
                        decoded is PupDecodedMessage.FlashRecycleEvent
                    }
                }
                fsm.triggerImmediateTestFlash()
                val (_, msg) = withTimeout(2_000L) { recycleWaiter.await() }
                assertTrue(msg is PupDecodedMessage.FlashRecycleEvent)
            }

            fsm.disconnect()
            assertEquals(BleConnectionStage.Disconnected, fsm.stage.value)
        }
    }
}
