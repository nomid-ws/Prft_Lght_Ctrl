package app.prft.lightctrl.e2e.tier2

import app.prft.lightctrl.ble.BleConnectionStage
import app.prft.lightctrl.ble.BlePeerLink
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.ble.ProfotoBleConnectionFsm
import app.prft.lightctrl.ble.ProfotoGattProfile
import app.prft.lightctrl.ble.RawGattEvent
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.e2e.harness.E2ETestHarness
import app.prft.lightctrl.e2e.harness.RecordingBlePeerLink
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.PupConstants
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Tier 2 — Feature 6: Working Tree BLE Fixes Boundary Tests.
 *
 * Verifies:
 * 1. Malformed or unrecognized service UUID falls back cleanly to disconnect without crash.
 * 2. Peripheral drop immediately after auth token write handles disconnection cleanly.
 * 3. Rapid connect/disconnect cycles on Connect Pro preserve state consistency.
 * 4. Multiple simultaneous peer links (Connect Pro + B10X Plus + A2) maintain isolated active service routing.
 * 5. GATT cache refresh cleanly re-authenticates Connect Pro.
 */
class WorkingTreeBleFixesBoundaryTest {

    @Test
    fun `test 1 malformed or unrecognized service UUID falls back cleanly to disconnect without crash`() = runBlocking {
        val dummyDevice = DiscoveredProfotoDevice(
            address = "AA:BB:CC:DD:EE:FF",
            advertisedName = "Unknown Device",
            rssi = -60,
            advertisedServiceUuid = UUID.randomUUID(),
        )

        // Peer link returning only an unknown non-Profoto service or DFU service
        val malformedLink = object : BlePeerLink {
            override val address: String = dummyDevice.address
            private val _events = MutableSharedFlow<RawGattEvent>(extraBufferCapacity = 16)
            override val events: Flow<RawGattEvent> = _events.asSharedFlow()

            override suspend fun connect() {
                _events.emit(RawGattEvent.Connected)
            }
            override suspend fun requestMtu(mtu: Int): Boolean {
                _events.emit(RawGattEvent.MtuChanged(mtu, 0))
                return true
            }
            override suspend fun refreshGattCache(): Boolean = true
            override suspend fun discoverServices(): Boolean {
                // Emits only quarantined DFU service and random service, missing PUP characteristics
                _events.emit(
                    RawGattEvent.ServicesDiscovered(
                        serviceUuids = setOf(
                            ProfotoGattProfile.FORBIDDEN_DFU_SERVICE_UUIDS.first(),
                            UUID.fromString("11112222-3333-4444-5555-666677778888"),
                        ),
                        characteristicUuids = emptySet(),
                        status = 0,
                    )
                )
                return true
            }
            override suspend fun writeCharacteristicWithResponse(serviceUuid: UUID, charUuid: UUID, bytes: ByteArray): Boolean = false
            override suspend fun writeCharacteristicNoResponse(serviceUuid: UUID, charUuid: UUID, bytes: ByteArray): Boolean = false
            override suspend fun enableNotifications(serviceUuid: UUID, charUuid: UUID, cccdUuid: UUID): Boolean = false
            override suspend fun close() {
                _events.emit(RawGattEvent.Disconnected(0))
            }
        }

        val fsm = ProfotoBleConnectionFsm(
            device = dummyDevice,
            link = malformedLink,
            stageTimeoutMs = 500L,
            cacheSettleMs = 0L,
        )

        val result = fsm.connectAndAuthenticate()
        assertFalse("Malformed services must reject connection without crash", result)
        assertTrue("Stage must reflect failure cleanly", fsm.stage.value is BleConnectionStage.Failed)

        fsm.disconnect()
        assertEquals(BleConnectionStage.Disconnected, fsm.stage.value)
    }

    @Test
    fun `test 2 peripheral drop immediately after auth token write handles disconnection cleanly`() = runBlocking {
        val connectPro = VirtualBenchFixtures.createKit().first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        // Custom link that drops connection on auth write
        val droppingLink = object : BlePeerLink {
            override val address: String = connectPro.address
            private val _events = MutableSharedFlow<RawGattEvent>(extraBufferCapacity = 16)
            override val events: Flow<RawGattEvent> = _events.asSharedFlow()

            override suspend fun connect() {
                _events.emit(RawGattEvent.Connected)
            }
            override suspend fun requestMtu(mtu: Int): Boolean {
                _events.emit(RawGattEvent.MtuChanged(mtu, 0))
                return true
            }
            override suspend fun refreshGattCache(): Boolean = true
            override suspend fun discoverServices(): Boolean {
                _events.emit(
                    RawGattEvent.ServicesDiscovered(
                        serviceUuids = setOf(ProfotoGattProfile.AUTH_SERVICE_UUID),
                        characteristicUuids = ProfotoGattProfile.getAllManagedCharacteristicUuids(),
                        status = 0,
                    )
                )
                return true
            }
            override suspend fun enableNotifications(serviceUuid: UUID, charUuid: UUID, cccdUuid: UUID): Boolean {
                _events.emit(RawGattEvent.CccdWritten(charUuid, 0))
                return true
            }
            override suspend fun writeCharacteristicWithResponse(serviceUuid: UUID, charUuid: UUID, bytes: ByteArray): Boolean {
                if (charUuid == ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID) {
                    // Simulate immediate link drop / GATT error 19 (link loss)
                    _events.emit(RawGattEvent.CharacteristicWritten(charUuid, 19))
                    _events.emit(RawGattEvent.Disconnected(19))
                    return true
                }
                return true
            }
            override suspend fun writeCharacteristicNoResponse(serviceUuid: UUID, charUuid: UUID, bytes: ByteArray): Boolean = false
            override suspend fun close() {
                _events.emit(RawGattEvent.Disconnected(0))
            }
        }

        val fsm = ProfotoBleConnectionFsm(
            device = connectPro,
            link = droppingLink,
            stageTimeoutMs = 500L,
            cacheSettleMs = 0L,
        )

        val ok = fsm.connectAndAuthenticate()
        assertFalse("Link drop must fail gracefully", ok)
        assertTrue("Stage must be Failed", fsm.stage.value is BleConnectionStage.Failed)

        fsm.disconnect()
        assertEquals(BleConnectionStage.Disconnected, fsm.stage.value)
    }

    @Test
    fun `test 3 rapid connect disconnect cycles on Connect Pro preserve state consistency`() = runBlocking {
        val connectPro = VirtualBenchFixtures.createKit().first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        val peerLink = VirtualBenchFixtures.createPeerLink(connectPro, fastTimings = true)
        val recordingLink = RecordingBlePeerLink(peerLink)
        val fsm = ProfotoBleConnectionFsm(
            device = connectPro,
            link = recordingLink,
            stageTimeoutMs = 1_500L,
            cacheSettleMs = 0L,
        )

        // Perform 4 rapid cycles
        repeat(4) { cycle ->
            assertEquals("Cycle $cycle initial state must be Disconnected", BleConnectionStage.Disconnected, fsm.stage.value)
            val connected = fsm.connectAndAuthenticate()
            assertTrue("Cycle $cycle connection must succeed", connected)
            assertTrue("Cycle $cycle must reach Ready", fsm.stage.value is BleConnectionStage.Ready)

            fsm.disconnect()
            assertEquals("Cycle $cycle final state must be Disconnected", BleConnectionStage.Disconnected, fsm.stage.value)
        }
    }

    @Test
    fun `test 4 multiple simultaneous peer links maintain isolated active service routing`() = runBlocking {
        val kit = VirtualBenchFixtures.createKit()
        val connectPro = kit.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }
        val b10xPlus = kit.first { it.address == VirtualBenchFixtures.ADDR_B10X_PLUS }
        val a2 = kit.first { it.address == VirtualBenchFixtures.ADDR_A2 }

        val linkCp = RecordingBlePeerLink(VirtualBenchFixtures.createPeerLink(connectPro, fastTimings = true))
        val linkB10 = RecordingBlePeerLink(VirtualBenchFixtures.createPeerLink(b10xPlus, fastTimings = true))
        val linkA2 = RecordingBlePeerLink(VirtualBenchFixtures.createPeerLink(a2, fastTimings = true))

        val fsmCp = ProfotoBleConnectionFsm(connectPro, linkCp, stageTimeoutMs = 2000L, cacheSettleMs = 0L)
        val fsmB10 = ProfotoBleConnectionFsm(b10xPlus, linkB10, stageTimeoutMs = 2000L, cacheSettleMs = 0L)
        val fsmA2 = ProfotoBleConnectionFsm(a2, linkA2, stageTimeoutMs = 2000L, cacheSettleMs = 0L)

        // Authenticate all three concurrently
        assertTrue(fsmCp.connectAndAuthenticate())
        assertTrue(fsmB10.connectAndAuthenticate())
        assertTrue(fsmA2.connectAndAuthenticate())

        // Allow initial hydration to settle across all links
        delay(100)

        // Connect Pro must be isolated on AUTH_SERVICE_UUID
        val cpReady = fsmCp.stage.value as BleConnectionStage.Ready
        assertEquals(ProfotoGattProfile.AUTH_SERVICE_UUID, cpReady.activeServiceUuid)

        // B10X Plus and A2 must be isolated on PRIMARY_SIG_SERVICE_UUID
        val b10Ready = fsmB10.stage.value as BleConnectionStage.Ready
        val a2Ready = fsmA2.stage.value as BleConnectionStage.Ready
        assertEquals(ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID, b10Ready.activeServiceUuid)
        assertEquals(ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID, a2Ready.activeServiceUuid)

        // Clear recorded frames to verify per-device command isolation
        linkCp.clear()
        linkB10.clear()
        linkA2.clear()

        // Helper to extract base opcode from PUP frame
        fun getOpcode(bytes: ByteArray): Int? {
            if (bytes.size < PupConstants.PACKET_HEADER_SIZE) return null
            val rawOpcode = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
            return rawOpcode and PupConstants.BASE_OPCODE_MASK
        }

        // Send AIR_CHANNEL command to Connect Pro
        fsmCp.sendCommand(PupOpcodes.AIR_CHANNEL) { seq -> PupFrameCodec.encodeAirChannel(seq, 22) }
        delay(50)

        val cpAirChannelWrites = linkCp.recordedWrites.filter { getOpcode(it.bytes) == PupOpcodes.AIR_CHANNEL }
        val b10AirChannelWrites = linkB10.recordedWrites.filter { getOpcode(it.bytes) == PupOpcodes.AIR_CHANNEL }
        val a2AirChannelWrites = linkA2.recordedWrites.filter { getOpcode(it.bytes) == PupOpcodes.AIR_CHANNEL }

        assertEquals(1, cpAirChannelWrites.size)
        assertEquals(0, b10AirChannelWrites.size)
        assertEquals(0, a2AirChannelWrites.size)
        assertEquals(ProfotoGattProfile.AUTH_SERVICE_UUID, cpAirChannelWrites.first().serviceUuid)

        // Send AIR_GROUP command to B10X Plus
        fsmB10.sendCommand(PupOpcodes.AIR_GROUP) { seq -> PupFrameCodec.encodeAirGroup(seq, AirGroup.GROUP_C) }
        delay(50)

        val cpAirGroupWrites = linkCp.recordedWrites.filter { getOpcode(it.bytes) == PupOpcodes.AIR_GROUP }
        val b10AirGroupWrites = linkB10.recordedWrites.filter { getOpcode(it.bytes) == PupOpcodes.AIR_GROUP }
        val a2AirGroupWrites = linkA2.recordedWrites.filter { getOpcode(it.bytes) == PupOpcodes.AIR_GROUP }

        assertEquals(0, cpAirGroupWrites.size)
        assertEquals(1, b10AirGroupWrites.size)
        assertEquals(0, a2AirGroupWrites.size)
        assertEquals(ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID, b10AirGroupWrites.first().serviceUuid)

        fsmCp.disconnect()
        fsmB10.disconnect()
        fsmA2.disconnect()
    }

    @Test
    fun `test 5 GATT cache refresh cleanly re-authenticates Connect Pro`() = runBlocking {
        val connectPro = VirtualBenchFixtures.createKit().first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }

        var cacheFlushed = false
        val customLink = object : BlePeerLink by VirtualBenchFixtures.createPeerLink(connectPro, fastTimings = true) {
            override suspend fun refreshGattCache(): Boolean {
                cacheFlushed = true
                return true
            }
        }
        val recordingLink = RecordingBlePeerLink(customLink)

        val fsm = ProfotoBleConnectionFsm(
            device = connectPro,
            link = recordingLink,
            stageTimeoutMs = 2_000L,
            cacheSettleMs = 0L,
        )

        // Connect initially
        assertTrue(fsm.connectAndAuthenticate())
        assertTrue(fsm.stage.value is BleConnectionStage.Ready)

        // Reconnect (invokes disconnectQuietly and re-negotiates)
        assertTrue(fsm.connectAndAuthenticate())
        assertTrue(fsm.stage.value is BleConnectionStage.Ready)
        assertEquals(
            ProfotoGattProfile.AUTH_SERVICE_UUID,
            (fsm.stage.value as BleConnectionStage.Ready).activeServiceUuid,
        )

        fsm.disconnect()
        assertEquals(BleConnectionStage.Disconnected, fsm.stage.value)
    }
}
