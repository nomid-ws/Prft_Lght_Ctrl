package app.prft.lightctrl.ble

import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.BatteryChemistry
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.MonolightZoomMode
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProfotoBleParityTest {

    @Test
    fun `Connect Pro resolves AUTH_SERVICE_UUID and authenticates successfully`() = runBlocking {
        val device = VirtualBenchFixtures.createKit().first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }
        val link = VirtualBenchFixtures.createPeerLink(device, fastTimings = true)
        val fsm = ProfotoBleConnectionFsm(
            device = device,
            link = link,
            stageTimeoutMs = 2_000L,
            cacheSettleMs = 0L,
        )

        assertEquals(BleConnectionStage.Disconnected, fsm.stage.value)
        val ok = fsm.connectAndAuthenticate()
        assertTrue("Connect Pro must authenticate successfully", ok)

        val readyStage = fsm.stage.value as BleConnectionStage.Ready
        assertEquals(
            "Connect Pro must resolve AUTH_SERVICE_UUID (bbd75314-ddaa-4b61-a7df-c6eedb79c152)",
            ProfotoGattProfile.AUTH_SERVICE_UUID,
            readyStage.activeServiceUuid,
        )

        fsm.disconnect()
        assertEquals(BleConnectionStage.Disconnected, fsm.stage.value)
    }

    @Test
    fun `DFU quarantine rejects forbidden DFU services and characteristics`() = runBlocking {
        // Test forbidden characteristic UUID rejection
        for (forbiddenChar in ProfotoGattProfile.FORBIDDEN_DFU_CHAR_UUIDS) {
            var blocked = false
            try {
                ProfotoGattProfile.assertSafeCharacteristicUuid(forbiddenChar)
            } catch (_: SecurityException) {
                blocked = true
            }
            assertTrue("Expected SecurityException on forbidden char UUID $forbiddenChar", blocked)
        }

        val forbiddenDfuService = ProfotoGattProfile.FORBIDDEN_DFU_SERVICE_UUIDS.first()

        // Test mock peer presenting only DFU service UUID
        val dfuDevice = DiscoveredProfotoDevice(
            address = "DF:UU:00:11:22:33",
            advertisedName = "Profoto DFU Target",
            rssi = -50,
            advertisedServiceUuid = forbiddenDfuService,
            advertisedProductId = 143,
            isSimulated = true,
        )

        val dummyLink = object : BlePeerLink {
            override val address: String = dfuDevice.address
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
                        serviceUuids = setOf(forbiddenDfuService),
                        characteristicUuids = setOf(
                            ProfotoGattProfile.PUP_TX_CHAR_UUID,
                            ProfotoGattProfile.PUP_RX_CHAR_UUID,
                        ),
                        status = 0,
                    )
                )
                return true
            }
            override suspend fun writeCharacteristicWithResponse(serviceUuid: UUID, charUuid: UUID, bytes: ByteArray): Boolean = false
            override suspend fun enableNotifications(serviceUuid: UUID, charUuid: UUID, cccdUuid: UUID): Boolean = false
            override suspend fun writeCharacteristicNoResponse(serviceUuid: UUID, charUuid: UUID, bytes: ByteArray): Boolean = false
            override suspend fun close() {
                _events.emit(RawGattEvent.Disconnected(0))
            }
        }

        val fsm = ProfotoBleConnectionFsm(
            device = dfuDevice,
            link = dummyLink,
            stageTimeoutMs = 1_000L,
            cacheSettleMs = 0L,
        )

        val connected = fsm.connectAndAuthenticate()
        assertFalse("Connection must fail when only DFU service is present", connected)
        assertTrue("Stage must be Failed", fsm.stage.value is BleConnectionStage.Failed)
    }

    @Test
    fun `MockBleTransport executes and hydrates Connect Pro remote settings and 0_1 floor`() = runBlocking {
        val device = VirtualBenchFixtures.createKit().first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }
        val link = VirtualBenchFixtures.createPeerLink(device, fastTimings = true)
        val fsm = ProfotoBleConnectionFsm(
            device = device,
            link = link,
            stageTimeoutMs = 2_000L,
            cacheSettleMs = 0L,
        )

        assertTrue(fsm.connectAndAuthenticate())

        // 1. Sync Mode (Opcode 140)
        assertTrue(fsm.sendCommand(PupOpcodes.SYNC_MODE) { seq ->
            PupFrameCodec.encodeSyncMode(seq, SyncTriggerMode.GLOBAL_SHUTTER)
        })

        // 2. AF-Assist Mode (Opcode 160)
        assertTrue(fsm.sendCommand(PupOpcodes.AF_ASSIST_MODE) { seq ->
            PupFrameCodec.encodeAfAssistMode(seq, AfAssistMode.ON)
        })

        // 3. RF Power Level (Opcode 147)
        assertTrue(fsm.sendCommand(PupOpcodes.RMI_RF_POWER) { seq ->
            PupFrameCodec.encodeRfPower(seq, RfPowerLevel.P3)
        })

        // 4. Auto-Off Timeout (Opcode 41)
        assertTrue(fsm.sendCommand(PupOpcodes.AUTO_OFF_TIMEOUT) { seq ->
            PupFrameCodec.encodeAutoOffTimeout(seq, 15)
        })

        // 5. Auto-Standby Timeout (Opcode 40)
        assertTrue(fsm.sendCommand(PupOpcodes.AUTO_STANDBY_TIMEOUT) { seq ->
            PupFrameCodec.encodeAutoStandbyTimeout(seq, 5)
        })

        // 6. Display Brightness (Opcode 39)
        assertTrue(fsm.sendCommand(PupOpcodes.DISPLAY_BRIGHTNESS) { seq ->
            PupFrameCodec.encodeDisplayBrightness(seq, 2)
        })

        // 7. Battery Type (Opcode 180)
        assertTrue(fsm.sendCommand(PupOpcodes.BATTERY_TYPE) { seq ->
            PupFrameCodec.encodeBatteryType(seq, 1)
        })

        // 8. Control Sounds (Opcode 38)
        assertTrue(fsm.sendCommand(PupOpcodes.CONTROL_SOUNDS_ENABLE) { seq ->
            PupFrameCodec.encodeControlSounds(seq, false)
        })

        // 9. Per-Group TTL EV Bias (Opcode 131) for Group B (+1.5 EV = 15 tenths)
        assertTrue(fsm.sendCommand(PupOpcodes.AIR_PX_BIAS) { seq ->
            PupFrameCodec.encodeAirPxBias(seq, 15, AirGroup.GROUP_B)
        })

        // 10. Absolute Group Energy (Opcode 177) with 0.1 remote power floor (1 tenth-stop)
        assertTrue(fsm.sendCommand(PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY) { seq ->
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(seq, listOf(1, 20, 35, 50, 70, 95))
        })

        // Query AllSettingsV2 dump and verify hydrated values
        val dumpWaiter = async(start = CoroutineStart.UNDISPATCHED) {
            fsm.incomingMessages.first { (_, msg) -> msg is PupDecodedMessage.BulkSettingsDump }
        }
        assertTrue(fsm.sendQuery(PupOpcodes.ALL_SETTINGS_V2))
        val (_, dumpMsg) = withTimeout(2_000L) { dumpWaiter.await() }
        val dump = (dumpMsg as PupDecodedMessage.BulkSettingsDump).result

        val syncDecoded = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.RemoteSyncMode>().firstOrNull()
        assertNotNull(syncDecoded)
        assertEquals(SyncTriggerMode.GLOBAL_SHUTTER, syncDecoded?.mode)

        val afDecoded = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.RemoteAfAssist>().firstOrNull()
        assertNotNull(afDecoded)
        assertEquals(AfAssistMode.ON, afDecoded?.mode)

        val rfDecoded = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.RemoteRfPower>().firstOrNull()
        assertNotNull(rfDecoded)
        assertEquals(RfPowerLevel.P3, rfDecoded?.level)

        val autoOffDecoded = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.AutoOffMinutes>().firstOrNull()
        assertNotNull(autoOffDecoded)
        assertEquals(15, autoOffDecoded?.minutes)

        val standbyDecoded = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.AutoStandbyMinutes>().firstOrNull()
        assertNotNull(standbyDecoded)
        assertEquals(5, standbyDecoded?.minutes)

        val brightDecoded = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.DisplayBrightness>().firstOrNull()
        assertNotNull(brightDecoded)
        assertEquals(DisplayBrightnessLevel.HIGH, brightDecoded?.level)

        val batDecoded = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.BatteryTypeSetting>().firstOrNull()
        assertNotNull(batDecoded)
        assertEquals(BatteryChemistry.NIMH, batDecoded?.chemistry)

        val soundsDecoded = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.ControlSoundsState>().firstOrNull()
        assertNotNull(soundsDecoded)
        assertFalse(soundsDecoded?.enabled == true)

        val groupPowersDecoded = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.AirAbsoluteGroupPower>().firstOrNull()
        assertNotNull(groupPowersDecoded)
        assertEquals(listOf(1, 20, 35, 50, 70, 95), groupPowersDecoded?.groupTenthStops)

        fsm.disconnect()
    }

    @Test
    fun `MockBleTransport executes and hydrates Monolight zoom and ready signal mode`() = runBlocking {
        val device = VirtualBenchFixtures.createKit().first { it.address == VirtualBenchFixtures.ADDR_B10X_PLUS }
        val link = VirtualBenchFixtures.createPeerLink(device, fastTimings = true)
        val fsm = ProfotoBleConnectionFsm(
            device = device,
            link = link,
            stageTimeoutMs = 2_000L,
            cacheSettleMs = 0L,
        )

        assertTrue(fsm.connectAndAuthenticate())

        // 1. Zoom Mode (Opcode 161)
        assertTrue(fsm.sendCommand(PupOpcodes.ZOOM_MODE) { seq ->
            PupFrameCodec.encodeZoomMode(seq, MonolightZoomMode.AUTO)
        })

        // 2. Zoom Value (Opcode 163)
        assertTrue(fsm.sendCommand(PupOpcodes.ZOOM_VALUE) { seq ->
            PupFrameCodec.encodeZoomValue(seq, 85)
        })

        // 3. Ready Signal Mode (Opcode 217)
        assertTrue(fsm.sendCommand(PupOpcodes.READY_SIGNAL_MODE) { seq ->
            PupFrameCodec.encodeReadySignalMode(seq, ReadySignalMode.SOUND_AND_DIM)
        })

        // Query AllSettingsV2 dump and verify hydrated values
        val dumpWaiter = async(start = CoroutineStart.UNDISPATCHED) {
            fsm.incomingMessages.first { (_, msg) -> msg is PupDecodedMessage.BulkSettingsDump }
        }
        assertTrue(fsm.sendQuery(PupOpcodes.ALL_SETTINGS_V2))
        val (_, dumpMsg) = withTimeout(2_000L) { dumpWaiter.await() }
        val dump = (dumpMsg as PupDecodedMessage.BulkSettingsDump).result

        val zoomStates = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.MonolightZoomState>()
        assertTrue("Expected MonolightZoomState in dump", zoomStates.isNotEmpty())
        val zoomModeEntry = zoomStates.firstOrNull { it.value == null && it.range == null }
        assertEquals(MonolightZoomMode.AUTO, zoomModeEntry?.mode)
        val zoomValEntry = zoomStates.firstOrNull { it.value != null }
        assertEquals(85, zoomValEntry?.value)

        val readyDecoded = dump.entries.map { it.decoded }.filterIsInstance<PupDecodedMessage.ReadySignalState>().firstOrNull()
        assertNotNull(readyDecoded)
        assertEquals(ReadySignalMode.SOUND_AND_DIM, readyDecoded?.mode)

        fsm.disconnect()
    }

    @Test
    fun `BLE scan failure descriptions map Android ScanCallback error codes cleanly`() {
        assertTrue(AndroidBleAdapterGateway.describeScanFailure(2).contains("code 2"))
        assertTrue(AndroidBleAdapterGateway.describeScanFailure(3).contains("code 3"))
        assertTrue(AndroidBleAdapterGateway.describeScanFailure(4).contains("code 4"))
        assertTrue(AndroidBleAdapterGateway.describeScanFailure(5).contains("code 5"))
        assertTrue(AndroidBleAdapterGateway.describeScanFailure(6).contains("wait 10s"))
    }
}
