package app.prft.lightctrl.ble.mock

import app.prft.lightctrl.ble.BlePeerLink
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.ble.ProfotoGattProfile
import app.prft.lightctrl.ble.RawGattEvent
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.AirProtocolMode
import app.prft.lightctrl.protocol.AllSettingsTlvParser
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.FlashSchedulingState
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.SequenceCounter
import app.prft.lightctrl.protocol.VideoLightPowerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * High-fidelity virtual Profoto peripheral simulator for bench/CI verification when
 * physical lights are powered down. Enforces exact GATT handshake ("Profoto Client"),
 * MTU 255, CCCD subscription, AllSettingsV2 TLV streaming, ACK flow control, and
 * realistic capacitor recycle countdown telemetry.
 */
object VirtualBenchFixtures {

    const val ADDR_B10X_PLUS = "SIM:B1:0X:50:0W:01"
    const val ADDR_A2 = "SIM:A2:10:0W:00:02"
    const val ADDR_CONNECT_PRO = "SIM:CP:10:0C:00:03"
    const val ADDR_CONNECT = "SIM:CN:08:CH:00:04"

    fun createKit(): List<DiscoveredProfotoDevice> = listOf(
        DiscoveredProfotoDevice(
            address = ADDR_B10X_PLUS,
            advertisedName = "Profoto B10X Plus [Sim]",
            rssi = -46,
            advertisedServiceUuid = ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID,
            advertisedProductId = 143,
            isSimulated = true,
        ),
        DiscoveredProfotoDevice(
            address = ADDR_A2,
            advertisedName = "Profoto A2 [Sim]",
            rssi = -51,
            advertisedServiceUuid = ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID,
            advertisedProductId = 36,
            isSimulated = true,
        ),
        DiscoveredProfotoDevice(
            address = ADDR_CONNECT_PRO,
            advertisedName = "Connect-7076 [Sim]",
            rssi = -42,
            advertisedServiceUuid = ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID,
            advertisedProductId = 162,
            isSimulated = true,
        ),
        DiscoveredProfotoDevice(
            address = ADDR_CONNECT,
            advertisedName = "Profoto Connect [Sim]",
            rssi = -58,
            advertisedServiceUuid = ProfotoGattProfile.LEGACY_BASE_SERVICE_UUID,
            advertisedProductId = 151,
            isSimulated = true,
        ),
    )

    fun createPeerLink(device: DiscoveredProfotoDevice, fastTimings: Boolean = false): BlePeerLink {
        val productId = when {
            device.advertisedProductId > 0 -> device.advertisedProductId
            device.address == ADDR_B10X_PLUS -> 143
            device.address == ADDR_A2 -> 36
            device.address == ADDR_CONNECT_PRO -> 162
            device.address == ADDR_CONNECT -> 151
            else -> 143
        }
        return VirtualProfotoPeerLink(
            device = device,
            productId = productId,
            recycleDelayMs = if (fastTimings) 25L else 420L,
        )
    }
}

class VirtualProfotoPeerLink(
    private val device: DiscoveredProfotoDevice,
    private val productId: Int,
    private val recycleDelayMs: Long = 420L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : BlePeerLink {

    override val address: String get() = device.address

    private val _events = MutableSharedFlow<RawGattEvent>(extraBufferCapacity = 128)
    override val events: Flow<RawGattEvent> = _events.asSharedFlow()

    private val txSeq = SequenceCounter(10)
    private var authenticated = false
    private var notificationsEnabled = false

    // Simulated live peripheral state
    private var flashPowerTenthStops: Int = if (productId == 36) 65 else 80
    private var headEnabled: Boolean = true
    private var freezeEnabled: Boolean = false
    private var videoLightOn: Boolean = productId == 143
    private var videoLightMode: VideoLightPowerMode = VideoLightPowerMode.FREE
    private var videoLightIntensity: Int = 750
    private var videoLightKelvin: Int = 5200
    private var airChannel: Int = 1
    private var airGroup: AirGroup = if (productId == 36) AirGroup.GROUP_B else AirGroup.GROUP_A
    private var groupPowers = MutableList(6) { idx -> (80 - idx * 5).coerceIn(1, 100) }
    private var airHeadMask: Int = 0x3F
    private var airModelLightMask: Int = if (productId in 161..166) 0x03 else 0x00
    private var ttlEvCompTenths: Int = 0
    private var flashMode: FlashMode = FlashMode.MANUAL
    private var remoteMode: RemoteOperatingMode = RemoteOperatingMode.AUTO_TTL
    private var airProtocol: AirProtocolMode = AirProtocolMode.AIR_2

    // Remote Settings & Group Parity State
    private var syncMode: Int = 0 // 0=FIRST, 1=SECOND, 2=X_SYNC, 3=AUTO_FP, 4=GLOBAL_SHUTTER
    private var afAssistMode: Int = 0 // 0=OFF, 1=AUTO, 2=ON
    private var rfPowerLevel: Int = 4 // 1=P1..4=P4
    private var autoOffMinutes: Int = 90
    private var autoStandbyMinutes: Int = 30
    private var displayBrightness: Int = 3
    private var batteryType: Int = 0 // 0=Alkaline, 1=NiMH, 2=Li-Ion
    private var controlSoundsEnabled: Boolean = true
    private val groupTtlBiases = mutableMapOf(
        AirGroup.GROUP_A to 0,
        AirGroup.GROUP_B to 0,
        AirGroup.GROUP_C to 0,
    )
    // Monolight Zoom & Ready Signal State
    private var zoomMode: Int = 0 // 0=MANUAL, 1=AUTO
    private var zoomValue: Int = 50 // 0..100
    private var readySignalMode: Int = 3 // 0=OFF, 1=SOUND, 2=DIM, 3=SOUND_AND_DIM

    private val activeService: UUID = when {
        // Real Connect Pro (IDs 161..166) advertises 0xFDBB in ServiceData, while its GATT table
        // hosts PUP TX/RX + Auth Client ID exclusively inside bbd75314-ddaa-4b61-a7df-c6eedb79c152.
        productId in 161..166 -> ProfotoGattProfile.AUTH_SERVICE_UUID
        else -> device.advertisedServiceUuid ?: ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID
    }

    override suspend fun connect() {
        scope.launch {
            delay(15)
            _events.emit(RawGattEvent.Connected)
        }
    }

    override suspend fun requestMtu(mtu: Int): Boolean {
        scope.launch {
            delay(10)
            _events.emit(RawGattEvent.MtuChanged(mtu.coerceAtMost(255), 0))
        }
        return true
    }

    override suspend fun refreshGattCache(): Boolean = true

    override suspend fun discoverServices(): Boolean {
        scope.launch {
            delay(10)
            _events.emit(
                RawGattEvent.ServicesDiscovered(
                    serviceUuids = setOf(activeService),
                    characteristicUuids = ProfotoGattProfile.getAllManagedCharacteristicUuids(),
                    status = 0,
                )
            )
        }
        return true
    }

    override suspend fun writeCharacteristicWithResponse(
        serviceUuid: UUID,
        charUuid: UUID,
        bytes: ByteArray,
    ): Boolean {
        ProfotoGattProfile.assertSafeCharacteristicUuid(charUuid)
        scope.launch {
            delay(10)
            if (charUuid == ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID) {
                val token = bytes.toString(Charsets.UTF_8)
                if (token == ProfotoGattProfile.HANDSHAKE_TOKEN_ASCII) {
                    authenticated = true
                    _events.emit(RawGattEvent.CharacteristicWritten(charUuid, 0))
                    if (notificationsEnabled) {
                        emitAllSettingsDump()
                    }
                } else {
                    _events.emit(RawGattEvent.CharacteristicWritten(charUuid, 0x05))
                }
            } else {
                _events.emit(RawGattEvent.CharacteristicWritten(charUuid, 0))
            }
        }
        return true
    }

    override suspend fun enableNotifications(
        serviceUuid: UUID,
        charUuid: UUID,
        cccdUuid: UUID,
    ): Boolean {
        ProfotoGattProfile.assertSafeCharacteristicUuid(charUuid)
        scope.launch {
            delay(10)
            notificationsEnabled = true
            _events.emit(RawGattEvent.CccdWritten(charUuid, 0))
        }
        return true
    }

    override suspend fun writeCharacteristicNoResponse(
        serviceUuid: UUID,
        charUuid: UUID,
        bytes: ByteArray,
    ): Boolean {
        ProfotoGattProfile.assertSafeCharacteristicUuid(charUuid)
        if (!authenticated || !notificationsEnabled) return false

        scope.launch {
            handleHostPupPacket(bytes)
        }
        return true
    }

    override suspend fun close() {
        authenticated = false
        notificationsEnabled = false
        _events.tryEmit(RawGattEvent.Disconnected(0))
    }

    private suspend fun handleHostPupPacket(raw: ByteArray) {
        val (pkt, msg) = PupFrameCodec.decodeMessage(raw)

        if (pkt.isQuery) {
            when (pkt.opcode) {
                PupOpcodes.HARDWARE_ID -> emitPup(
                    PupFrameCodec.encodePacket(txSeq.next(), PupOpcodes.HARDWARE_ID, byteArrayOf(productId.toByte()))
                )
                PupOpcodes.ALL_SETTINGS_V2, PupOpcodes.GET_ALL_SETTINGS -> emitAllSettingsDump()
            }
            return
        }

        applyStateMutation(msg)

        // Emit ACK for ackable non-query commands
        if (!pkt.isQuery && pkt.opcode !in PupOpcodes.NON_ACKABLE_OPCODES) {
            delay(8)
            emitPup(PupFrameCodec.encodeAck(txSeq.next(), pkt.counter))
        }

        when (pkt.opcode) {
            PupOpcodes.GET_ALL_SETTINGS -> emitAllSettingsDump()
            PupOpcodes.QUERY_HARDWARE_ID -> emitPup(
                PupFrameCodec.encodePacket(txSeq.next(), PupOpcodes.HARDWARE_ID, byteArrayOf(productId.toByte()))
            )
            PupOpcodes.QUERY_BATTERY_INDICATOR -> emitPup(
                PupFrameCodec.encodePacket(txSeq.next(), PupOpcodes.BATTERY_INDICATOR, byteArrayOf(4))
            )
            PupOpcodes.QUERY_AIR_CHANNEL_RANGE -> emitPup(
                PupFrameCodec.encodePacket(
                    txSeq.next(),
                    PupOpcodes.AIR_CHANNEL_RANGE,
                    byteArrayOf(1, if (productId in 150..154) 8 else 100),
                )
            )
            PupOpcodes.TEST_FLASH -> {
                // Emit recycle start -> countdown -> ready
                val startBuf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                    .put(FlashSchedulingState.SCHEDULED_OK.code.toByte())
                    .putInt(recycleDelayMs.toInt())
                    .array()
                emitPup(PupFrameCodec.encodePacket(txSeq.next(), PupOpcodes.FLASH_SCHEDULING_RECEIVED_EVT, startBuf))
                delay(recycleDelayMs)
                val doneBuf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                    .put(FlashSchedulingState.SCHEDULED_OK.code.toByte())
                    .putInt(0)
                    .array()
                emitPup(PupFrameCodec.encodePacket(txSeq.next(), PupOpcodes.FLASH_SCHEDULING_RECEIVED_EVT, doneBuf))
                // Normal discharge event (rawState = 0xFF -> normal)
                emitPup(PupFrameCodec.encodePacket(txSeq.next(), PupOpcodes.FLASH_FIRED_EVT, byteArrayOf(0xFF.toByte(), 0x00)))
            }
        }
    }

    private suspend fun applyStateMutation(msg: PupDecodedMessage) {
        when (msg) {
            is PupDecodedMessage.FlashPower -> flashPowerTenthStops = msg.tenthStops
            is PupDecodedMessage.FlashPowerV2 -> flashPowerTenthStops = msg.tenthStops
            is PupDecodedMessage.HeadPowerState -> headEnabled = msg.enabled
            is PupDecodedMessage.HeadPowerStateV2 -> headEnabled = msg.enabled
            is PupDecodedMessage.FreezeState -> freezeEnabled = msg.enabled
            is PupDecodedMessage.VideoLightState -> videoLightOn = msg.enabled
            is PupDecodedMessage.VideoLightStateV2 -> videoLightOn = msg.enabled
            is PupDecodedMessage.VideoLightMode -> videoLightMode = msg.mode
            is PupDecodedMessage.VideoLightBrightness -> videoLightIntensity = msg.intensity
            is PupDecodedMessage.VideoLightCct -> videoLightKelvin = msg.kelvin
            is PupDecodedMessage.AirChannelAssignment -> airChannel = msg.channel
            is PupDecodedMessage.AirGroupAssignment -> airGroup = msg.group
            is PupDecodedMessage.AirGroupAssignmentV2 -> airGroup = msg.group
            is PupDecodedMessage.AirAbsoluteGroupPower -> groupPowers = msg.groupTenthStops.toMutableList()
            is PupDecodedMessage.AirGroupAdjustment -> {
                if (msg.group == AirGroup.ALL) {
                    for (i in 0 until 6) {
                        groupPowers[i] = (groupPowers[i] + msg.deltaOrTargetTenthStops).coerceIn(1, 100)
                    }
                } else {
                    val idx = msg.group.bitIndex.coerceIn(0, 5)
                    groupPowers[idx] = (groupPowers[idx] + msg.deltaOrTargetTenthStops).coerceIn(1, 100)
                }
                emitGroupFeedback()
            }
            is PupDecodedMessage.AirHeadMask -> airHeadMask = msg.mask
            is PupDecodedMessage.AirModelLightMask -> airModelLightMask = msg.mask
            is PupDecodedMessage.AirGroupTtlBias -> {
                groupTtlBiases[msg.group] = msg.tenthStops
                if (msg.group == AirGroup.GROUP_A || msg.group == AirGroup.ALL) {
                    ttlEvCompTenths = msg.tenthStops
                }
                if (msg.group == AirGroup.ALL) {
                    groupTtlBiases[AirGroup.GROUP_A] = msg.tenthStops
                    groupTtlBiases[AirGroup.GROUP_B] = msg.tenthStops
                    groupTtlBiases[AirGroup.GROUP_C] = msg.tenthStops
                }
            }
            is PupDecodedMessage.AirTtlEvCompensation -> {
                ttlEvCompTenths = msg.tenthStops
                groupTtlBiases[AirGroup.GROUP_A] = msg.tenthStops
            }
            is PupDecodedMessage.RemoteSyncMode -> syncMode = msg.mode.code
            is PupDecodedMessage.RemoteAfAssist -> afAssistMode = msg.mode.code
            is PupDecodedMessage.RemoteRfPower -> rfPowerLevel = msg.level.code
            is PupDecodedMessage.MonolightZoomState -> {
                if (msg.value != null) {
                    zoomValue = msg.value!!
                } else if (msg.range != null) {
                    // range is read-only capability
                } else {
                    zoomMode = msg.mode.code
                }
            }
            is PupDecodedMessage.ReadySignalState -> readySignalMode = msg.mode.code
            is PupDecodedMessage.AutoOffMinutes -> autoOffMinutes = msg.minutes
            is PupDecodedMessage.AutoStandbyMinutes -> autoStandbyMinutes = msg.minutes
            is PupDecodedMessage.ControlSoundsState -> controlSoundsEnabled = msg.enabled
            is PupDecodedMessage.DisplayBrightness -> displayBrightness = msg.level.code
            is PupDecodedMessage.BatteryTypeSetting -> batteryType = msg.chemistry.code
            is PupDecodedMessage.StrobeFlashMode -> flashMode = msg.mode
            is PupDecodedMessage.RemoteModeState -> remoteMode = msg.mode
            is PupDecodedMessage.AirProtocol -> airProtocol = msg.mode
            is PupDecodedMessage.RawUnknown -> {
                when (msg.opcode) {
                    PupOpcodes.SYNC_MODE -> if (msg.bytes.isNotEmpty()) syncMode = msg.bytes[0].toInt() and 0xFF
                    PupOpcodes.AF_ASSIST_MODE -> if (msg.bytes.isNotEmpty()) afAssistMode = msg.bytes[0].toInt() and 0xFF
                    PupOpcodes.RMI_RF_POWER -> if (msg.bytes.isNotEmpty()) rfPowerLevel = msg.bytes[0].toInt() and 0xFF
                    PupOpcodes.AUTO_STANDBY_TIMEOUT -> if (msg.bytes.isNotEmpty()) autoStandbyMinutes = msg.bytes[0].toInt() and 0xFF
                    PupOpcodes.AUTO_OFF_TIMEOUT -> if (msg.bytes.isNotEmpty()) autoOffMinutes = msg.bytes[0].toInt() and 0xFF
                    PupOpcodes.CONTROL_SOUNDS_ENABLE -> if (msg.bytes.isNotEmpty()) controlSoundsEnabled = msg.bytes[0].toInt() != 0
                    PupOpcodes.ZOOM_MODE -> if (msg.bytes.isNotEmpty()) zoomMode = msg.bytes[0].toInt() and 0xFF
                    PupOpcodes.ZOOM_VALUE -> if (msg.bytes.size >= 2) zoomValue = PupFrameCodec.readUInt16Le(msg.bytes, 0)
                    PupOpcodes.READY_SIGNAL_MODE -> if (msg.bytes.isNotEmpty()) readySignalMode = msg.bytes[0].toInt() and 0xFF
                    PupOpcodes.BATTERY_TYPE -> if (msg.bytes.isNotEmpty()) batteryType = msg.bytes[0].toInt() and 0xFF
                    PupOpcodes.DISPLAY_BRIGHTNESS -> if (msg.bytes.isNotEmpty()) displayBrightness = msg.bytes[0].toInt() and 0xFF
                }
            }
            else -> Unit
        }
    }

    private suspend fun emitAllSettingsDump() {
        val records = mutableListOf<Pair<Int, ByteArray>>(
            PupOpcodes.HARDWARE_ID to byteArrayOf(productId.toByte()),
            PupOpcodes.AIR_CHANNEL to byteArrayOf(airChannel.toByte()),
            PupOpcodes.AIR_CHANNEL_RANGE to byteArrayOf(1, if (productId in 150..154) 8 else 100),
            PupOpcodes.BATTERY_INDICATOR to byteArrayOf(if (productId == 36) 3 else 4),
            PupOpcodes.BATTERY_CHARGER_CONNECTED to byteArrayOf(if (productId == 143) 1 else 0),
        )

        when {
            productId in 161..166 -> {
                records += PupOpcodes.AIR_PROTOCOL_MODE to byteArrayOf(airProtocol.code.toByte())
                records += PupOpcodes.REMOTE_MODE to byteArrayOf(remoteMode.code.toByte())
                records += PupOpcodes.SYNC_MODE to byteArrayOf(syncMode.toByte())
                records += PupOpcodes.AF_ASSIST_MODE to byteArrayOf(afAssistMode.toByte())
                records += PupOpcodes.RMI_RF_POWER to byteArrayOf(rfPowerLevel.toByte())
                records += PupOpcodes.RADIO_MODE_RANGE to byteArrayOf(1, 4)
                records += PupOpcodes.AUTO_OFF_TIMEOUT to byteArrayOf(autoOffMinutes.toByte())
                records += PupOpcodes.AUTO_STANDBY_TIMEOUT to byteArrayOf(autoStandbyMinutes.toByte())
                records += PupOpcodes.DISPLAY_BRIGHTNESS to byteArrayOf(displayBrightness.toByte())
                records += PupOpcodes.BATTERY_TYPE to byteArrayOf(batteryType.toByte())
                records += PupOpcodes.CONTROL_SOUNDS_ENABLE to byteArrayOf(if (controlSoundsEnabled) 1 else 0)
                records += PupOpcodes.AIR_PX_BIAS to byteArrayOf(groupTtlBiases[AirGroup.GROUP_A]?.toByte() ?: 0, AirGroup.GROUP_A.code.toByte())
                records += PupOpcodes.AIR_PX_BIAS to byteArrayOf(groupTtlBiases[AirGroup.GROUP_B]?.toByte() ?: 0, AirGroup.GROUP_B.code.toByte())
                records += PupOpcodes.AIR_PX_BIAS to byteArrayOf(groupTtlBiases[AirGroup.GROUP_C]?.toByte() ?: 0, AirGroup.GROUP_C.code.toByte())
                records += PupOpcodes.AIR_TTL_EV_COMP to byteArrayOf(ttlEvCompTenths.toByte())
                records += PupOpcodes.AIR_HEAD_ON to byteArrayOf((airHeadMask and 0xFF).toByte(), 0x00)
                records += PupOpcodes.AIR_MODEL_LIGHT_ON to byteArrayOf((airModelLightMask and 0xFF).toByte(), 0x00)
                records += PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY to ByteArray(6) { groupPowers[it].toByte() }
            }
            productId in 150..154 -> {
                records += PupOpcodes.REMOTE_MODE to byteArrayOf(remoteMode.code.toByte())
                records += PupOpcodes.TTL_AUTO_ENABLED to byteArrayOf(1)
                records += PupOpcodes.AIR_PX_BIAS to byteArrayOf(ttlEvCompTenths.toByte(), AirGroup.ALL.code.toByte())
                records += PupOpcodes.AIR_TTL_EV_COMP to byteArrayOf(ttlEvCompTenths.toByte())
                records += PupOpcodes.AIR_HEAD_ON to byteArrayOf((airHeadMask and 0xFF).toByte(), 0x00)
                records += PupOpcodes.AIR_MODEL_LIGHT_ON to byteArrayOf((airModelLightMask and 0xFF).toByte(), 0x00)
            }
            else -> {
                val intBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(videoLightIntensity.toShort()).array()
                val rangeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(10).putShort(1000).array()
                val cctBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(videoLightKelvin.toShort()).array()
                val cctRange = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(3000).putShort(6500).array()
                val zoomRangeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(100).array()
                val zoomValBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(zoomValue.toShort()).array()

                records += PupOpcodes.AIR_GROUP to byteArrayOf(airGroup.code.toByte())
                records += PupOpcodes.MX_ENERGY to byteArrayOf(flashPowerTenthStops.toByte())
                records += PupOpcodes.MX_ENERGY_RANGE to byteArrayOf(10, 100)
                records += PupOpcodes.HEAD_ON to byteArrayOf(if (headEnabled) 1 else 0)
                records += PupOpcodes.FREEZE_ENABLE to byteArrayOf(if (freezeEnabled) 1 else 0)
                records += PupOpcodes.VIDEO_LIGHT_ENABLE to byteArrayOf(if (videoLightOn) 1 else 0)
                records += PupOpcodes.VIDEO_LIGHT_POWER_MODE to byteArrayOf(videoLightMode.code.toByte())
                records += PupOpcodes.VIDEO_LIGHT_INTENSITY to intBuf
                records += PupOpcodes.VIDEO_LIGHT_INTENSITY_RANGE to rangeBuf
                records += PupOpcodes.VIDEO_LIGHT_COLOR_TEMPERATURE to cctBuf
                records += PupOpcodes.VIDEO_LIGHT_COLOR_RANGE to cctRange
                records += PupOpcodes.ZOOM_MODE to byteArrayOf(zoomMode.toByte())
                records += PupOpcodes.ZOOM_RANGE to zoomRangeBuf
                records += PupOpcodes.ZOOM_VALUE to zoomValBuf
                records += PupOpcodes.READY_SIGNAL_MODE to byteArrayOf(readySignalMode.toByte())
            }
        }

        val tlv = AllSettingsTlvParser.serialize(records, terminate = true)
        emitPup(PupFrameCodec.encodePacket(txSeq.next(), PupOpcodes.ALL_SETTINGS_V2, tlv))
    }

    private suspend fun emitGroupFeedback() {
        val bytes = ByteArray(14)
        for (i in 0 until 6) {
            bytes[i * 2] = groupPowers[i].toByte()
            bytes[i * 2 + 1] = groupPowers[i].toByte()
        }
        bytes[12] = 0
        bytes[13] = 0
        emitPup(PupFrameCodec.encodePacket(txSeq.next(), PupOpcodes.AIR_ADJUST_ENERGY_STATUS, bytes))
    }

    private suspend fun emitPup(frame: ByteArray) {
        if (!notificationsEnabled) return
        _events.emit(RawGattEvent.CharacteristicNotified(ProfotoGattProfile.PUP_RX_CHAR_UUID, frame))
    }
}
