package app.prft.lightctrl.e2e.harness

import app.prft.lightctrl.ble.BlePeerLink
import app.prft.lightctrl.ble.RawGattEvent
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID

/**
 * Extension helper to provide encodeFrame on PupFrameCodec matching task specification.
 */
fun PupFrameCodec.encodeFrame(
    counter: Int,
    opcode: Int,
    payload: ByteArray = ByteArray(0),
    isQuery: Boolean? = null,
): ByteArray = encodePacket(counter, opcode, payload, isQuery)

data class RecordedBleWrite(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val bytes: ByteArray,
    val withResponse: Boolean,
)

/**
 * Decorator around BlePeerLink (e.g. VirtualProfotoPeerLink) that records all outgoing
 * GATT writes and incoming characteristic notifications for verification.
 */
class RecordingBlePeerLink(
    val delegate: BlePeerLink,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : BlePeerLink by delegate {

    private val _recordedFrames = Collections.synchronizedList(mutableListOf<ByteArray>())
    val recordedFrames: List<ByteArray>
        get() = synchronized(_recordedFrames) { _recordedFrames.toList() }

    private val _recordedWrites = Collections.synchronizedList(mutableListOf<RecordedBleWrite>())
    val recordedWrites: List<RecordedBleWrite>
        get() = synchronized(_recordedWrites) { _recordedWrites.toList() }

    private val _recordedNotifications = Collections.synchronizedList(mutableListOf<ByteArray>())
    val recordedNotifications: List<ByteArray>
        get() = synchronized(_recordedNotifications) { _recordedNotifications.toList() }

    init {
        scope.launch {
            delegate.events.collect { event ->
                if (event is RawGattEvent.CharacteristicNotified) {
                    _recordedNotifications.add(event.payload.copyOf())
                }
            }
        }
    }

    override suspend fun writeCharacteristicNoResponse(
        serviceUuid: UUID,
        charUuid: UUID,
        bytes: ByteArray,
    ): Boolean {
        val copy = bytes.copyOf()
        _recordedFrames.add(copy)
        _recordedWrites.add(RecordedBleWrite(serviceUuid, charUuid, copy, withResponse = false))
        return delegate.writeCharacteristicNoResponse(serviceUuid, charUuid, bytes)
    }

    override suspend fun writeCharacteristicWithResponse(
        serviceUuid: UUID,
        charUuid: UUID,
        bytes: ByteArray,
    ): Boolean {
        val copy = bytes.copyOf()
        _recordedFrames.add(copy)
        _recordedWrites.add(RecordedBleWrite(serviceUuid, charUuid, copy, withResponse = true))
        return delegate.writeCharacteristicWithResponse(serviceUuid, charUuid, bytes)
    }

    fun decodeRecordedFrames(): List<Pair<PupPacket, PupDecodedMessage>> {
        return recordedFrames.mapNotNull { frame ->
            runCatching { PupFrameCodec.decodeMessage(frame) }.getOrNull()
        }
    }

    fun decodeRecordedNotifications(): List<Pair<PupPacket, PupDecodedMessage>> {
        return recordedNotifications.mapNotNull { frame ->
            runCatching { PupFrameCodec.decodeMessage(frame) }.getOrNull()
        }
    }

    fun clear() {
        _recordedFrames.clear()
        _recordedWrites.clear()
        _recordedNotifications.clear()
    }
}
