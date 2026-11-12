package app.prft.lightctrl.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

open class PupCodecException(message: String) : IllegalArgumentException(message)

class PupSecurityQuarantineException(opcode: Int) :
    SecurityException("CRITICAL SAFETY QUARANTINE: Refusing to encode firmware/DFU opcode $opcode")

/**
 * Thread-safe rolling sequence counter strictly cycling 0..239 (modulo 240).
 */
class SequenceCounter(initial: Int = 0) {
    private val ref = AtomicInteger(floorMod240(initial))

    fun next(): Int = ref.getAndUpdate { (it + 1) % PupConstants.SEQUENCE_MODULO }

    val current: Int get() = ref.get()

    fun set(value: Int) {
        ref.set(floorMod240(value))
    }

    companion object {
        fun floorMod240(v: Int): Int = Math.floorMod(v, PupConstants.SEQUENCE_MODULO)
    }
}

data class PupPacket(
    val counter: Int,
    val opcode: Int,
    val payload: ByteArray,
    val isQuery: Boolean,
    val rawOpcode: Int,
) {
    val normalizedCounter: Int = SequenceCounter.floorMod240(counter)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PupPacket) return false
        return normalizedCounter == other.normalizedCounter &&
            opcode == other.opcode &&
            isQuery == other.isQuery &&
            rawOpcode == other.rawOpcode &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = normalizedCounter
        result = 31 * result + opcode
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + isQuery.hashCode()
        result = 31 * result + rawOpcode
        return result
    }
}

/**
 * Decoded strongly-typed PUP message representations.
 */
sealed interface PupDecodedMessage {
    data class Query(val baseOpcode: Int, val headId: Int?, val rawPayload: ByteArray) : PupDecodedMessage
    data class Ack(val acknowledgedCounter: Int) : PupDecodedMessage
    data class Nack(val rejectedCounter: Int) : PupDecodedMessage

    // Flash & Energy
    data class FlashPower(val tenthStops: Int) : PupDecodedMessage {
        val fStops: Float get() = tenthStops / 10f
    }
    data class FlashPowerRange(val minTenthStops: Int, val maxTenthStops: Int) : PupDecodedMessage
    data class PxTtlBias(val tenthStops: Int) : PupDecodedMessage {
        val ev: Float get() = tenthStops / 10f
    }
    data class PxTtlBiasRange(val minTenthStops: Int, val maxTenthStops: Int) : PupDecodedMessage
    data class FlashPowerV2(val headId: Int, val tenthStops: Int) : PupDecodedMessage {
        val fStops: Float get() = tenthStops / 10f
    }
    data class FlashPowerConstraintV2(
        val headId: Int,
        val minTenthStops: Int,
        val maxTenthStops: Int,
    ) : PupDecodedMessage
    data class FreezeState(val enabled: Boolean) : PupDecodedMessage
    data class HeadPowerState(val enabled: Boolean) : PupDecodedMessage
    data class HeadPowerStateV2(val headId: Int, val enabled: Boolean) : PupDecodedMessage
    data class HeadConnectedV2(val headId: Int, val connected: Boolean) : PupDecodedMessage
    data class DimDuringRecharge(val enabled: Boolean) : PupDecodedMessage
    data class ReadySoundState(val enabled: Boolean) : PupDecodedMessage
    data class IrSlaveState(val enabled: Boolean) : PupDecodedMessage
    data class CaptureModeState(val isVideoPriority: Boolean) : PupDecodedMessage

    // Continuous / Modeling Light (A2, B10X, B10X Plus)
    data class VideoLightState(val enabled: Boolean) : PupDecodedMessage
    data class VideoLightStateV2(val headId: Int, val enabled: Boolean) : PupDecodedMessage
    data class VideoLightMode(val mode: VideoLightPowerMode) : PupDecodedMessage
    data class VideoLightBrightness(val intensity: Int) : PupDecodedMessage
    data class VideoLightBrightnessRange(val minIntensity: Int, val maxIntensity: Int) : PupDecodedMessage
    data class VideoLightCct(val kelvin: Int) : PupDecodedMessage
    data class VideoLightCctRange(val minKelvin: Int, val maxKelvin: Int) : PupDecodedMessage

    // Air Radio, Connect Pro & Connect Remote
    data class AirSyncState(val enabled: Boolean) : PupDecodedMessage
    data class AirGroupAssignment(val group: AirGroup) : PupDecodedMessage
    data class AirGroupAssignmentV2(val headId: Int, val group: AirGroup) : PupDecodedMessage
    data class AirChannelAssignment(val channel: Int) : PupDecodedMessage
    data class AirChannelBounds(val minChannel: Int, val maxChannel: Int) : PupDecodedMessage
    data class AirProtocol(val mode: AirProtocolMode) : PupDecodedMessage
    data class StrobeFlashMode(val mode: FlashMode) : PupDecodedMessage
    data class RemoteModeState(val mode: RemoteOperatingMode) : PupDecodedMessage
    data class TtlAutoState(val enabled: Boolean) : PupDecodedMessage
    data class AirTtlEvCompensation(val tenthStops: Int) : PupDecodedMessage {
        val ev: Float get() = tenthStops / 10f
    }
    /** Opcode 131: AirPxBias(int8 biasTenthStops, uint8 groupCode 1..7). */
    data class AirGroupTtlBias(
        val tenthStops: Int,
        val group: AirGroup,
        val counter: Int = 0,
    ) : PupDecodedMessage {
        val ev: Float get() = tenthStops / 10f
    }
    data class AirHeadMask(val mask: Int) : PupDecodedMessage {
        fun isGroupEnabled(groupIndex: Int): Boolean = (mask and (1 shl groupIndex)) != 0
    }
    data class AirModelLightMask(val mask: Int) : PupDecodedMessage {
        fun isGroupModelingOn(groupIndex: Int): Boolean = (mask and (1 shl groupIndex)) != 0
    }
    /** Opcode 177: 6 absolute tenth-stop power bytes for Groups A..F. */
    data class AirAbsoluteGroupPower(val groupTenthStops: List<Int>) : PupDecodedMessage
    /** Opcode 174: relative or target adjustment for a single group. */
    data class AirGroupAdjustment(val group: AirGroup, val deltaOrTargetTenthStops: Int) : PupDecodedMessage
    /** Opcode 136: AdjustAirMxEnergyResponse(success). */
    data class AdjustAirMxEnergyResult(val success: Boolean) : PupDecodedMessage
    /** Opcode 175: 14-byte real-time group status (current & target int8 pairs for A..F + All). */
    data class AirGroupEnergyFeedback(
        val currentByGroup: List<Int>,
        val targetByGroup: List<Int>,
    ) : PupDecodedMessage

    // Remote Settings & Advanced Triggers
    data class RemoteSyncMode(val mode: SyncTriggerMode) : PupDecodedMessage
    data class RemoteAfAssist(val mode: AfAssistMode) : PupDecodedMessage
    data class RemoteRfPower(val level: RfPowerLevel) : PupDecodedMessage
    data class RfPowerRange(val minDbm: Int, val maxDbm: Int) : PupDecodedMessage
    data class MonolightZoomState(
        val mode: MonolightZoomMode = MonolightZoomMode.MANUAL,
        val value: Int? = null,
        val range: IntRange? = null,
    ) : PupDecodedMessage
    data class ReadySignalState(val mode: ReadySignalMode) : PupDecodedMessage

    // Display & Ergonomics (B10X / B10X Plus / Connect Pro)
    data class DisplayBrightness(val level: DisplayBrightnessLevel) : PupDecodedMessage
    data class DisplayOrientation(val orientation: DisplayOrientationMode) : PupDecodedMessage
    data class AutoOffMinutes(val minutes: Int) : PupDecodedMessage
    data class AutoStandbyMinutes(val minutes: Int) : PupDecodedMessage
    data class ControlSoundsState(val enabled: Boolean) : PupDecodedMessage

    // Identity, Battery & Flash Telemetry
    data class BleLocalName(val name: String) : PupDecodedMessage
    data class BatteryStatus(val bars: Int) : PupDecodedMessage {
        val estimatedPercent: Int
            get() = when (bars.coerceIn(0, 4)) {
                0 -> 5
                1 -> 25
                2 -> 50
                3 -> 75
                else -> 100
            }
    }
    data class ChargerStatus(val pluggedIn: Boolean) : PupDecodedMessage
    data class BatteryTypeSetting(val chemistry: BatteryChemistry) : PupDecodedMessage
    data class FirmwareDisplayVersion(val version: String) : PupDecodedMessage
    data class SerialNumberString(val serial: String) : PupDecodedMessage
    data class HardwareProductId(val productId: Int) : PupDecodedMessage
    data class FlashRecycleEvent(
        val state: FlashSchedulingState,
        val rechargeTimeMs: Long,
    ) : PupDecodedMessage {
        val isReady: Boolean get() = state == FlashSchedulingState.SCHEDULED_OK && rechargeTimeMs == 0L
    }
    data class FlashFiredResult(
        val rawState: Int,
        val error: FlashFiredError?,
    ) : PupDecodedMessage {
        val isOk: Boolean get() = error == null
    }
    data class BulkSettingsDump(val result: AllSettingsV2Result) : PupDecodedMessage
    data class RawUnknown(val opcode: Int, val bytes: ByteArray) : PupDecodedMessage
}

object PupFrameCodec {

    fun fStopToTenthStops(fStop: Float): Int =
        (fStop * 10f).roundToInt().coerceIn(1, 100)

    fun evToTenthStops(ev: Float): Int =
        (ev * 10f).roundToInt().coerceIn(-30, 30)

    /**
     * Encode raw PUP wire packet with hard security quarantine against DFU/firmware opcodes.
     */
    fun encodePacket(
        counter: Int,
        opcode: Int,
        payload: ByteArray = ByteArray(0),
        isQuery: Boolean? = null,
    ): ByteArray {
        if (opcode !in 0..0xFFFF) {
            throw PupCodecException("Opcode out of uint16 range (0..65535): $opcode")
        }
        val baseOpcode = opcode and PupConstants.BASE_OPCODE_MASK
        if (baseOpcode in PupConstants.QUARANTINED_OPCODES) {
            throw PupSecurityQuarantineException(baseOpcode)
        }

        val normCounter = SequenceCounter.floorMod240(counter)
        val queryFlag = isQuery ?: ((opcode and PupConstants.QUERY_BIT_MASK) != 0)
        val wireOpcode = baseOpcode or (if (queryFlag) PupConstants.QUERY_BIT_MASK else 0)

        val buf = ByteBuffer.allocate(PupConstants.PACKET_HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(normCounter.toByte())
        buf.putShort(wireOpcode.toShort())
        buf.put(payload)
        return buf.array()
    }

    fun decodePacket(raw: ByteArray): PupPacket {
        if (raw.size < PupConstants.PACKET_HEADER_SIZE) {
            throw PupCodecException("Truncated PUP packet: expected >= 3 bytes, got ${raw.size}")
        }
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val rawCounter = buf.get().toInt() and 0xFF
        val wireOpcode = buf.short.toInt() and 0xFFFF
        val isQuery = (wireOpcode and PupConstants.QUERY_BIT_MASK) != 0
        val baseOpcode = wireOpcode and PupConstants.BASE_OPCODE_MASK
        val payload = raw.copyOfRange(PupConstants.PACKET_HEADER_SIZE, raw.size)

        return PupPacket(
            counter = SequenceCounter.floorMod240(rawCounter),
            opcode = baseOpcode,
            payload = payload,
            isQuery = isQuery,
            rawOpcode = wireOpcode,
        )
    }

    // =========================================================================
    // Typed Command Encoders
    // =========================================================================

    fun encodeQuery(counter: Int, opcode: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        encodePacket(counter, opcode, payload, isQuery = true)

    fun encodeLegacyV1Query(counter: Int, legacyQueryOpcode: Int): ByteArray =
        encodePacket(counter, legacyQueryOpcode, ByteArray(0), isQuery = false)

    fun encodeGetAllSettingsV1(counter: Int): ByteArray =
        encodePacket(counter, PupOpcodes.GET_ALL_SETTINGS)

    fun encodeQueryAllSettingsV2(counter: Int): ByteArray =
        encodeQuery(counter, PupOpcodes.ALL_SETTINGS_V2)

    fun encodeAck(counter: Int, acknowledgedCounter: Int): ByteArray =
        encodePacket(
            counter,
            PupOpcodes.ACK,
            byteArrayOf(SequenceCounter.floorMod240(acknowledgedCounter).toByte()),
        )

    fun encodeNack(counter: Int, rejectedCounter: Int): ByteArray =
        encodePacket(
            counter,
            PupOpcodes.NACK,
            byteArrayOf(SequenceCounter.floorMod240(rejectedCounter).toByte()),
        )

    fun encodeTestFlash(counter: Int): ByteArray =
        encodePacket(counter, PupOpcodes.TEST_FLASH)

    fun encodeFlashPower(counter: Int, tenthStops: Int): ByteArray {
        if (tenthStops !in 1..100) {
            throw PupCodecException("Flash power tenth-stops out of range (1..100): $tenthStops")
        }
        return encodePacket(counter, PupOpcodes.MX_ENERGY, byteArrayOf(tenthStops.toByte()))
    }

    fun encodeFlashPowerFStops(counter: Int, fStops: Float): ByteArray =
        encodeFlashPower(counter, fStopToTenthStops(fStops))

    fun encodeFlashPowerV2(counter: Int, headId: Int, tenthStops: Int): ByteArray {
        if (headId !in 0..255) throw PupCodecException("Invalid headId: $headId")
        if (tenthStops !in 1..100) throw PupCodecException("Flash power out of range (1..100): $tenthStops")
        return encodePacket(
            counter,
            PupOpcodes.MX_ENERGY_V2,
            byteArrayOf(headId.toByte(), tenthStops.toByte()),
        )
    }

    fun encodeFreezeEnable(counter: Int, enabled: Boolean): ByteArray =
        encodeBoolByte(counter, PupOpcodes.FREEZE_ENABLE, enabled)

    fun encodeHeadOn(counter: Int, enabled: Boolean): ByteArray =
        encodeBoolByte(counter, PupOpcodes.HEAD_ON, enabled)

    fun encodeHeadOnV2(counter: Int, headId: Int, enabled: Boolean): ByteArray =
        encodePacket(
            counter,
            PupOpcodes.HEAD_ON_V2,
            byteArrayOf((headId and 0xFF).toByte(), if (enabled) 1 else 0),
        )

    fun encodeDimDuringRecharge(counter: Int, enabled: Boolean): ByteArray =
        encodeBoolByte(counter, PupOpcodes.DIM_DURING_RECHARGE, enabled)

    fun encodeReadySound(counter: Int, enabled: Boolean): ByteArray =
        encodeBoolByte(counter, PupOpcodes.SOUND_ENABLE_READY, enabled)

    fun encodeIrSlaveEnable(counter: Int, enabled: Boolean): ByteArray =
        encodeBoolByte(counter, PupOpcodes.IR_SLAVE_ENABLE, enabled)

    fun encodeCaptureMode(counter: Int, videoPriority: Boolean): ByteArray =
        encodeBoolByte(counter, PupOpcodes.CAPTURE_MODE, videoPriority)

    // Continuous / Modeling LED (B10X Plus, B10X, A2)
    fun encodeVideoLightEnable(counter: Int, enabled: Boolean): ByteArray =
        encodeBoolByte(counter, PupOpcodes.VIDEO_LIGHT_ENABLE, enabled)

    fun encodeVideoLightEnableV2(counter: Int, headId: Int, enabled: Boolean): ByteArray =
        encodePacket(
            counter,
            PupOpcodes.VIDEO_LIGHT_ENABLE_V2,
            byteArrayOf((headId and 0xFF).toByte(), if (enabled) 1 else 0),
        )

    fun encodeVideoLightPowerMode(counter: Int, mode: VideoLightPowerMode): ByteArray =
        encodePacket(counter, PupOpcodes.VIDEO_LIGHT_POWER_MODE, byteArrayOf(mode.code.toByte()))

    fun encodeVideoLightIntensity(counter: Int, intensity: Int): ByteArray {
        if (intensity !in 0..65535) {
            throw PupCodecException("Video light intensity out of range (0..65535): $intensity")
        }
        return encodeUInt16Le(counter, PupOpcodes.VIDEO_LIGHT_INTENSITY, intensity)
    }

    fun encodeVideoLightCct(counter: Int, kelvin: Int): ByteArray {
        if (kelvin !in 1000..20000) {
            throw PupCodecException("Kelvin CCT out of studio bounds (1000..20000): $kelvin")
        }
        return encodeUInt16Le(counter, PupOpcodes.VIDEO_LIGHT_COLOR_TEMPERATURE, kelvin)
    }

    // Air Radio, Connect Pro & Connect Remote Controls
    fun encodeAirSyncEnable(counter: Int, enabled: Boolean): ByteArray =
        encodeBoolByte(counter, PupOpcodes.AIR_SYNC_ENABLE, enabled)

    fun encodeAirGroup(counter: Int, group: AirGroup): ByteArray =
        encodePacket(counter, PupOpcodes.AIR_GROUP, byteArrayOf(group.code.toByte()))

    fun encodeAirGroupV2(counter: Int, headId: Int, group: AirGroup): ByteArray =
        encodePacket(
            counter,
            PupOpcodes.AIR_GROUP_V2,
            byteArrayOf((headId and 0xFF).toByte(), group.code.toByte()),
        )

    fun encodeAirChannel(counter: Int, channel: Int): ByteArray {
        if (channel !in 1..100) {
            throw PupCodecException("Air channel out of range (1..100): $channel")
        }
        return encodePacket(counter, PupOpcodes.AIR_CHANNEL, byteArrayOf(channel.toByte()))
    }

    fun encodeAirProtocolMode(counter: Int, mode: AirProtocolMode): ByteArray =
        encodePacket(counter, PupOpcodes.AIR_PROTOCOL_MODE, byteArrayOf(mode.code.toByte()))

    fun encodeFlashMode(counter: Int, mode: FlashMode): ByteArray =
        encodePacket(counter, PupOpcodes.FLASH_MODE, byteArrayOf(mode.code.toByte()))

    fun encodeRemoteMode(counter: Int, mode: RemoteOperatingMode): ByteArray =
        encodePacket(counter, PupOpcodes.REMOTE_MODE, byteArrayOf(mode.code.toByte()))

    fun encodeTtlAutoEnable(counter: Int, enabled: Boolean): ByteArray =
        encodeBoolByte(counter, PupOpcodes.TTL_AUTO_ENABLED, enabled)

    fun encodeAirTtlEvComp(counter: Int, tenthStops: Int): ByteArray {
        if (tenthStops !in -50..50) {
            throw PupCodecException("EV compensation tenth-stops out of range (-50..50): $tenthStops")
        }
        return encodePacket(counter, PupOpcodes.AIR_TTL_EV_COMP, byteArrayOf(tenthStops.toByte()))
    }

    fun encodeAirPxBias(
        counter: Int,
        tenthStops: Int,
        group: AirGroup = AirGroup.ALL,
    ): ByteArray {
        if (tenthStops !in -20..20) {
            throw PupCodecException("AirPxBias tenth-stops out of range (-20..20): $tenthStops")
        }
        if (group !in setOf(AirGroup.GROUP_A, AirGroup.GROUP_B, AirGroup.GROUP_C, AirGroup.ALL)) {
            throw PupCodecException("AirPxBias only supports groups A..C (or ALL), got $group")
        }
        return encodePacket(
            counter,
            PupOpcodes.AIR_PX_BIAS,
            byteArrayOf(tenthStops.toByte(), group.code.toByte()),
        )
    }

    fun encodeAirHeadOnMask(counter: Int, mask: Int): ByteArray =
        encodeUInt16Le(counter, PupOpcodes.AIR_HEAD_ON, mask and 0xFFFF)

    fun encodeAirModelLightOnMask(counter: Int, mask: Int): ByteArray =
        encodeUInt16Le(counter, PupOpcodes.AIR_MODEL_LIGHT_ON, mask and 0xFFFF)

    fun encodeAirAdjustEnergy(counter: Int, group: AirGroup, deltaOrTargetTenthStops: Int): ByteArray {
        if (deltaOrTargetTenthStops !in -100..100) {
            throw PupCodecException("Group energy delta/target out of int8 bounds: $deltaOrTargetTenthStops")
        }
        return encodePacket(
            counter,
            PupOpcodes.AIR_ADJUST_ENERGY,
            byteArrayOf(group.code.toByte(), deltaOrTargetTenthStops.toByte()),
        )
    }

    fun encodeAdjustAirMxEnergyIfAble(counter: Int, group: AirGroup, stepTenthStops: Int): ByteArray {
        if (stepTenthStops !in -100..100) {
            throw PupCodecException("Air MxEnergy step out of int8 bounds: $stepTenthStops")
        }
        // Official L7/C0570m3.java + A0/c.java:655: Byte 0 = amount (int8), Byte 1 = group (uint8)
        return encodePacket(
            counter,
            PupOpcodes.ADJUST_AIR_MX_ENERGY_IF_ABLE,
            byteArrayOf(stepTenthStops.toByte(), group.code.toByte()),
        )
    }

    fun encodeAirAbsoluteGroupEnergy(counter: Int, groupTenthStops: List<Int>): ByteArray {
        if (groupTenthStops.size != 6) {
            throw PupCodecException("AirAbsoluteGroupEnergy requires 6 group values (A..F), got ${groupTenthStops.size}")
        }
        val bytes = ByteArray(6) { idx ->
            val v = groupTenthStops[idx]
            if (v !in 1..100) throw PupCodecException("Group $idx power out of range (1..100): $v")
            v.toByte()
        }
        return encodePacket(counter, PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY, bytes)
    }

    fun encodeAirAbsoluteGroupEnergy(counter: Int, groupEnergies: Map<AirGroup, Int>): ByteArray {
        val orderedGroups = listOf(
            AirGroup.GROUP_A, AirGroup.GROUP_B, AirGroup.GROUP_C,
            AirGroup.GROUP_D, AirGroup.GROUP_E, AirGroup.GROUP_F
        )
        val list = orderedGroups.map { grp ->
            val v = groupEnergies[grp] ?: throw PupCodecException("Missing energy for group $grp")
            if (v !in 1..100) throw PupCodecException("Group ${grp.label} power out of range (1..100): $v")
            v
        }
        return encodeAirAbsoluteGroupEnergy(counter, list)
    }

    // Remote Settings & Advanced Controls
    fun encodeSyncMode(counter: Int, mode: SyncTriggerMode): ByteArray =
        encodePacket(counter, PupOpcodes.SYNC_MODE, byteArrayOf(mode.code.toByte()))

    fun encodeAfAssistMode(counter: Int, mode: AfAssistMode): ByteArray =
        encodePacket(counter, PupOpcodes.AF_ASSIST_MODE, byteArrayOf(mode.code.toByte()))

    fun encodeRfPower(counter: Int, level: RfPowerLevel): ByteArray =
        encodePacket(counter, PupOpcodes.RMI_RF_POWER, byteArrayOf(level.code.toByte()))

    fun encodeZoomMode(counter: Int, mode: MonolightZoomMode): ByteArray =
        encodePacket(counter, PupOpcodes.ZOOM_MODE, byteArrayOf(mode.code.toByte()))

    fun encodeZoomValue(counter: Int, position: Int): ByteArray {
        if (position !in 0..65535) throw PupCodecException("Zoom position out of range (0..65535): $position")
        return encodeUInt16Le(counter, PupOpcodes.ZOOM_VALUE, position)
    }

    fun encodeReadySignalMode(counter: Int, mode: ReadySignalMode): ByteArray =
        encodePacket(counter, PupOpcodes.READY_SIGNAL_MODE, byteArrayOf(mode.code.toByte()))

    fun encodeAutoOffTimeout(counter: Int, minutes: Int): ByteArray {
        if (minutes !in 0..255) throw PupCodecException("Auto-off timeout out of range (0..255 min): $minutes")
        return encodePacket(counter, PupOpcodes.AUTO_OFF_TIMEOUT, byteArrayOf(minutes.toByte()))
    }

    fun encodeAutoStandbyTimeout(counter: Int, minutes: Int): ByteArray {
        if (minutes !in 0..255) throw PupCodecException("Auto-standby timeout out of range (0..255 min): $minutes")
        return encodePacket(counter, PupOpcodes.AUTO_STANDBY_TIMEOUT, byteArrayOf(minutes.toByte()))
    }

    fun encodeControlSounds(counter: Int, enabled: Boolean): ByteArray =
        encodeBoolByte(counter, PupOpcodes.CONTROL_SOUNDS_ENABLE, enabled)

    // Display & Battery Chemistry
    fun encodeDisplayBrightness(counter: Int, level: DisplayBrightnessLevel): ByteArray =
        encodePacket(counter, PupOpcodes.DISPLAY_BRIGHTNESS, byteArrayOf(level.code.toByte()))

    fun encodeDisplayBrightness(counter: Int, brightness: Int): ByteArray =
        encodePacket(counter, PupOpcodes.DISPLAY_BRIGHTNESS, byteArrayOf(brightness.coerceIn(0, 3).toByte()))

    fun encodeDisplayOrientation(counter: Int, orientation: DisplayOrientationMode): ByteArray =
        encodePacket(counter, PupOpcodes.DISPLAY_ORIENTATION, byteArrayOf(orientation.code.toByte()))

    fun encodeBatteryChemistry(counter: Int, chemistry: BatteryChemistry): ByteArray =
        encodePacket(counter, PupOpcodes.BATTERY_TYPE, byteArrayOf(chemistry.code.toByte()))

    fun encodeBatteryType(counter: Int, batteryType: Int): ByteArray =
        encodePacket(counter, PupOpcodes.BATTERY_TYPE, byteArrayOf(batteryType.toByte()))

    // =========================================================================
    // Typed Payload Decoders
    // =========================================================================

    fun decodeMessage(raw: ByteArray): Pair<PupPacket, PupDecodedMessage> {
        val packet = decodePacket(raw)
        return packet to decodePayload(packet.opcode, packet.payload, packet.isQuery)
    }

    fun decodePayload(opcode: Int, payload: ByteArray, isQuery: Boolean = false): PupDecodedMessage {
        val query = isQuery || ((opcode and PupConstants.QUERY_BIT_MASK) != 0)
        val baseOp = opcode and PupConstants.BASE_OPCODE_MASK
        if (query) {
            val headId = if (payload.size == 1) (payload[0].toInt() and 0xFF) else null
            return PupDecodedMessage.Query(baseOp, headId, payload)
        }

        return when (baseOp) {
            PupOpcodes.ACK -> {
                requireSize(payload, 1, "ACK")
                PupDecodedMessage.Ack(SequenceCounter.floorMod240(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.NACK -> {
                requireSize(payload, 1, "NACK")
                PupDecodedMessage.Nack(SequenceCounter.floorMod240(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.MX_ENERGY -> {
                requireSize(payload, 1, "MxEnergy")
                PupDecodedMessage.FlashPower(payload[0].toInt())
            }
            PupOpcodes.MX_ENERGY_RANGE -> {
                requireSize(payload, 2, "MxEnergyRange")
                PupDecodedMessage.FlashPowerRange(payload[0].toInt(), payload[1].toInt())
            }
            PupOpcodes.PX_ENERGY -> {
                requireSize(payload, 1, "PxEnergy")
                PupDecodedMessage.PxTtlBias(payload[0].toInt())
            }
            PupOpcodes.PX_ENERGY_RANGE -> {
                requireSize(payload, 2, "PxEnergyRange")
                PupDecodedMessage.PxTtlBiasRange(payload[0].toInt(), payload[1].toInt())
            }
            PupOpcodes.MX_ENERGY_V2 -> {
                requireSize(payload, 2, "MxEnergyV2")
                PupDecodedMessage.FlashPowerV2(payload[0].toInt() and 0xFF, payload[1].toInt())
            }
            PupOpcodes.MX_ENERGY_CONSTRAINT -> {
                requireSize(payload, 3, "MxEnergyConstraint")
                PupDecodedMessage.FlashPowerConstraintV2(
                    headId = payload[0].toInt() and 0xFF,
                    minTenthStops = payload[1].toInt(),
                    maxTenthStops = payload[2].toInt(),
                )
            }
            PupOpcodes.AIR_GROUP_V2 -> {
                requireSize(payload, 2, "AirGroupV2")
                PupDecodedMessage.AirGroupAssignmentV2(
                    headId = payload[0].toInt() and 0xFF,
                    group = AirGroup.fromCode(payload[1].toInt() and 0xFF),
                )
            }
            PupOpcodes.HEAD_ON_V2 -> {
                requireSize(payload, 2, "HeadOnV2")
                PupDecodedMessage.HeadPowerStateV2(
                    headId = payload[0].toInt() and 0xFF,
                    enabled = payload[1].toInt() != 0,
                )
            }
            PupOpcodes.VIDEO_LIGHT_ENABLE_V2 -> {
                requireSize(payload, 2, "VideoLightEnableV2")
                PupDecodedMessage.VideoLightStateV2(
                    headId = payload[0].toInt() and 0xFF,
                    enabled = payload[1].toInt() != 0,
                )
            }
            PupOpcodes.HEAD_CONNECTED -> {
                requireSize(payload, 2, "HeadConnected")
                PupDecodedMessage.HeadConnectedV2(
                    headId = payload[0].toInt() and 0xFF,
                    connected = payload[1].toInt() != 0,
                )
            }
            PupOpcodes.ADJUST_AIR_MX_ENERGY_RESPONSE -> {
                requireSize(payload, 1, "AdjustAirMxEnergyResponse")
                PupDecodedMessage.AdjustAirMxEnergyResult(payload[0].toInt() != 0)
            }
            PupOpcodes.FREEZE_ENABLE -> {
                requireSize(payload, 1, "FreezeEnable")
                PupDecodedMessage.FreezeState(payload[0].toInt() != 0)
            }
            PupOpcodes.HEAD_ON -> {
                requireSize(payload, 1, "HeadOn")
                PupDecodedMessage.HeadPowerState(payload[0].toInt() != 0)
            }
            PupOpcodes.DIM_DURING_RECHARGE -> {
                requireSize(payload, 1, "DimDuringRecharge")
                PupDecodedMessage.DimDuringRecharge(payload[0].toInt() != 0)
            }
            PupOpcodes.SOUND_ENABLE_READY -> {
                requireSize(payload, 1, "SoundEnableReady")
                PupDecodedMessage.ReadySoundState(payload[0].toInt() != 0)
            }
            PupOpcodes.IR_SLAVE_ENABLE -> {
                requireSize(payload, 1, "IrSlaveEnable")
                PupDecodedMessage.IrSlaveState(payload[0].toInt() != 0)
            }
            PupOpcodes.CAPTURE_MODE -> {
                requireSize(payload, 1, "CaptureMode")
                PupDecodedMessage.CaptureModeState(payload[0].toInt() != 0)
            }
            PupOpcodes.VIDEO_LIGHT_ENABLE -> {
                requireSize(payload, 1, "VideoLightEnable")
                PupDecodedMessage.VideoLightState(payload[0].toInt() != 0)
            }
            PupOpcodes.VIDEO_LIGHT_POWER_MODE -> {
                requireSize(payload, 1, "VideoLightPowerMode")
                PupDecodedMessage.VideoLightMode(VideoLightPowerMode.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.VIDEO_LIGHT_INTENSITY -> {
                requireSize(payload, 2, "VideoLightIntensity")
                PupDecodedMessage.VideoLightBrightness(readUInt16Le(payload, 0))
            }
            PupOpcodes.VIDEO_LIGHT_INTENSITY_RANGE -> {
                requireSize(payload, 4, "VideoLightIntensityRange")
                PupDecodedMessage.VideoLightBrightnessRange(readUInt16Le(payload, 0), readUInt16Le(payload, 2))
            }
            PupOpcodes.VIDEO_LIGHT_COLOR_TEMPERATURE -> {
                requireSize(payload, 2, "VideoLightColorTemperature")
                PupDecodedMessage.VideoLightCct(readUInt16Le(payload, 0))
            }
            PupOpcodes.VIDEO_LIGHT_COLOR_RANGE -> {
                requireSize(payload, 4, "VideoLightColorRange")
                PupDecodedMessage.VideoLightCctRange(readUInt16Le(payload, 0), readUInt16Le(payload, 2))
            }
            PupOpcodes.AIR_SYNC_ENABLE -> {
                requireSize(payload, 1, "AirSyncEnable")
                PupDecodedMessage.AirSyncState(payload[0].toInt() != 0)
            }
            PupOpcodes.AIR_GROUP -> {
                requireSize(payload, 1, "AirGroup")
                PupDecodedMessage.AirGroupAssignment(AirGroup.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.AIR_CHANNEL -> {
                requireSize(payload, 1, "AirChannel")
                PupDecodedMessage.AirChannelAssignment(payload[0].toInt() and 0xFF)
            }
            PupOpcodes.AIR_CHANNEL_RANGE -> {
                requireSize(payload, 2, "AirChannelRange")
                PupDecodedMessage.AirChannelBounds(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF)
            }
            PupOpcodes.AIR_PROTOCOL_MODE -> {
                requireSize(payload, 1, "AirProtocolMode")
                PupDecodedMessage.AirProtocol(AirProtocolMode.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.FLASH_MODE -> {
                requireSize(payload, 1, "FlashMode")
                PupDecodedMessage.StrobeFlashMode(FlashMode.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.REMOTE_MODE -> {
                requireSize(payload, 1, "RemoteMode")
                PupDecodedMessage.RemoteModeState(RemoteOperatingMode.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.SYNC_MODE -> {
                requireSize(payload, 1, "SyncMode")
                PupDecodedMessage.RemoteSyncMode(SyncTriggerMode.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.AF_ASSIST_MODE -> {
                requireSize(payload, 1, "AfAssistMode")
                PupDecodedMessage.RemoteAfAssist(AfAssistMode.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.RMI_RF_POWER -> {
                requireSize(payload, 1, "RmiRfPower")
                PupDecodedMessage.RemoteRfPower(RfPowerLevel.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.RADIO_MODE_RANGE -> {
                requireSize(payload, 2, "RadioModeRange")
                PupDecodedMessage.RfPowerRange(payload[0].toInt(), payload[1].toInt())
            }
            PupOpcodes.ZOOM_MODE -> {
                requireSize(payload, 1, "ZoomMode")
                PupDecodedMessage.MonolightZoomState(
                    mode = MonolightZoomMode.fromCode(payload[0].toInt() and 0xFF),
                    value = null,
                    range = null,
                )
            }
            PupOpcodes.ZOOM_RANGE -> {
                requireSize(payload, 4, "ZoomRange")
                PupDecodedMessage.MonolightZoomState(
                    mode = MonolightZoomMode.MANUAL,
                    value = null,
                    range = readUInt16Le(payload, 0)..readUInt16Le(payload, 2),
                )
            }
            PupOpcodes.ZOOM_VALUE -> {
                requireSize(payload, 2, "ZoomValue")
                PupDecodedMessage.MonolightZoomState(
                    mode = MonolightZoomMode.MANUAL,
                    value = readUInt16Le(payload, 0),
                    range = null,
                )
            }
            PupOpcodes.READY_SIGNAL_MODE -> {
                requireSize(payload, 1, "ReadySignalMode")
                PupDecodedMessage.ReadySignalState(ReadySignalMode.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.TTL_AUTO_ENABLED -> {
                requireSize(payload, 1, "TtlAutoEnabled")
                PupDecodedMessage.TtlAutoState(payload[0].toInt() != 0)
            }
            PupOpcodes.AIR_PX_BIAS -> {
                requireSize(payload, 2, "AirPxBias")
                PupDecodedMessage.AirGroupTtlBias(
                    tenthStops = payload[0].toInt(),
                    group = AirGroup.fromCode(payload[1].toInt() and 0xFF),
                )
            }
            PupOpcodes.AIR_TTL_EV_COMP -> {
                requireSize(payload, 1, "AirTtlEvComp")
                PupDecodedMessage.AirTtlEvCompensation(payload[0].toInt()) // signed int8
            }
            PupOpcodes.AIR_HEAD_ON -> {
                requireSize(payload, 2, "AirHeadOn")
                PupDecodedMessage.AirHeadMask(readUInt16Le(payload, 0))
            }
            PupOpcodes.AIR_MODEL_LIGHT_ON -> {
                requireSize(payload, 2, "AirModelLightOn")
                PupDecodedMessage.AirModelLightMask(readUInt16Le(payload, 0))
            }
            PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY -> {
                requireSize(payload, 6, "AirAbsoluteGroupEnergy")
                PupDecodedMessage.AirAbsoluteGroupPower(List(6) { payload[it].toInt() and 0xFF })
            }
            PupOpcodes.AIR_ADJUST_ENERGY -> {
                requireSize(payload, 2, "AirAdjustEnergy")
                PupDecodedMessage.AirGroupAdjustment(
                    group = AirGroup.fromCode(payload[0].toInt() and 0xFF),
                    deltaOrTargetTenthStops = payload[1].toInt(),
                )
            }
            PupOpcodes.AIR_ADJUST_ENERGY_STATUS -> {
                requireSize(payload, 14, "AirAdjustEnergyStatus")
                val currents = List(7) { payload[it * 2].toInt() }
                val targets = List(7) { payload[it * 2 + 1].toInt() }
                PupDecodedMessage.AirGroupEnergyFeedback(currents, targets)
            }
            PupOpcodes.DISPLAY_BRIGHTNESS -> {
                requireSize(payload, 1, "DisplayBrightness")
                PupDecodedMessage.DisplayBrightness(DisplayBrightnessLevel.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.DISPLAY_ORIENTATION -> {
                requireSize(payload, 1, "DisplayOrientation")
                PupDecodedMessage.DisplayOrientation(DisplayOrientationMode.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.CONTROL_SOUNDS_ENABLE -> {
                requireSize(payload, 1, "ControlSoundsEnable")
                PupDecodedMessage.ControlSoundsState(payload[0].toInt() != 0)
            }
            PupOpcodes.AUTO_STANDBY_TIMEOUT -> {
                requireSize(payload, 1, "AutoStandbyTimeout")
                PupDecodedMessage.AutoStandbyMinutes(payload[0].toInt() and 0xFF)
            }
            PupOpcodes.AUTO_OFF_TIMEOUT -> {
                requireSize(payload, 1, "AutoOffTimeout")
                PupDecodedMessage.AutoOffMinutes(payload[0].toInt() and 0xFF)
            }
            PupOpcodes.BLE_DEVICE_NAME -> {
                PupDecodedMessage.BleLocalName(readLengthPrefixedUtf8(payload))
            }
            PupOpcodes.BATTERY_INDICATOR -> {
                requireSize(payload, 1, "BatteryIndicator")
                PupDecodedMessage.BatteryStatus((payload[0].toInt() and 0xFF).coerceAtMost(4))
            }
            PupOpcodes.BATTERY_CHARGER_CONNECTED -> {
                requireSize(payload, 1, "BatteryChargerConnected")
                PupDecodedMessage.ChargerStatus(payload[0].toInt() != 0)
            }
            PupOpcodes.BATTERY_TYPE -> {
                requireSize(payload, 1, "BatteryType")
                PupDecodedMessage.BatteryTypeSetting(BatteryChemistry.fromCode(payload[0].toInt() and 0xFF))
            }
            PupOpcodes.FIRMWARE_VERSION_A6 -> {
                PupDecodedMessage.FirmwareDisplayVersion(readLengthPrefixedUtf8(payload))
            }
            PupOpcodes.SERIAL_NUMBER -> {
                PupDecodedMessage.SerialNumberString(readLengthPrefixedUtf8(payload))
            }
            PupOpcodes.HARDWARE_ID -> {
                requireSize(payload, 1, "HardwareId")
                PupDecodedMessage.HardwareProductId(payload[0].toInt() and 0xFF)
            }
            PupOpcodes.FLASH_SCHEDULING_RECEIVED_EVT -> {
                requireSize(payload, 5, "FlashSchedulingReceivedEvt")
                val state = FlashSchedulingState.fromCode(payload[0].toInt() and 0xFF)
                val ms = readUInt32Le(payload, 1)
                PupDecodedMessage.FlashRecycleEvent(state, ms)
            }
            PupOpcodes.FLASH_FIRED_EVT -> {
                requireSize(payload, 2, "FlashFiredEvt")
                val rawState = readUInt16Le(payload, 0)
                PupDecodedMessage.FlashFiredResult(rawState, FlashFiredError.fromCode(rawState))
            }
            PupOpcodes.ALL_SETTINGS_V2 -> {
                PupDecodedMessage.BulkSettingsDump(AllSettingsTlvParser.parse(payload))
            }
            else -> PupDecodedMessage.RawUnknown(baseOp, payload)
        }
    }

    private fun encodeBoolByte(counter: Int, opcode: Int, enabled: Boolean): ByteArray =
        encodePacket(counter, opcode, byteArrayOf(if (enabled) 1 else 0))

    private fun encodeUInt16Le(counter: Int, opcode: Int, value: Int): ByteArray {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        return encodePacket(counter, opcode, buf.array())
    }

    private fun requireSize(payload: ByteArray, minBytes: Int, label: String) {
        if (payload.size < minBytes) {
            throw PupCodecException("$label payload truncated: expected >= $minBytes bytes, got ${payload.size}")
        }
    }

    fun readUInt16Le(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF)) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    fun readLengthPrefixedUtf8(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val declaredLen = payload[0].toInt() and 0xFF
        val available = (payload.size - 1).coerceAtLeast(0)
        val take = declaredLen.coerceAtMost(available)
        return payload.copyOfRange(1, 1 + take).toString(Charsets.UTF_8)
    }
}
