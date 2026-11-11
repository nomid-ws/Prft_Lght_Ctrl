package app.prft.lightctrl.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class DiscoveredProfotoDevice(
    val address: String,
    val advertisedName: String,
    val rssi: Int,
    val advertisedServiceUuid: UUID?,
    val advertisedProductId: Int = 0,
    val isSimulated: Boolean = false,
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
) {
    val usesLegacyService: Boolean
        get() = advertisedServiceUuid == ProfotoGattProfile.LEGACY_BASE_SERVICE_UUID ||
            advertisedProductId in 150..154
}

/**
 * 10-stage deterministic BLE connection lifecycle state machine.
 * Always initializes in [Disconnected]; never fakes a connected hardware state.
 */
sealed interface BleConnectionStage {
    data object Disconnected : BleConnectionStage
    data object Connecting : BleConnectionStage
    data object LinkConnected : BleConnectionStage
    data class MtuNegotiated(val mtu: Int) : BleConnectionStage
    data class CacheFlushed(val refreshedViaReflection: Boolean) : BleConnectionStage
    data class ServicesDiscovered(val activeServiceUuid: UUID) : BleConnectionStage
    data object HandshakeAuthenticated : BleConnectionStage
    data object NotificationsSubscribed : BleConnectionStage
    data object HydratingSettings : BleConnectionStage
    data class Ready(val mtu: Int, val activeServiceUuid: UUID) : BleConnectionStage
    data class Failed(val stageName: String, val reason: String) : BleConnectionStage
}

sealed interface RawGattEvent {
    data object Connected : RawGattEvent
    data class Disconnected(val status: Int) : RawGattEvent
    data class MtuChanged(val mtu: Int, val status: Int) : RawGattEvent
    data class ServicesDiscovered(val serviceUuids: Set<UUID>, val characteristicUuids: Set<UUID>, val status: Int) : RawGattEvent
    data class CharacteristicWritten(val uuid: UUID, val status: Int) : RawGattEvent
    data class CccdWritten(val characteristicUuid: UUID, val status: Int) : RawGattEvent
    data class CharacteristicNotified(val uuid: UUID, val payload: ByteArray) : RawGattEvent
}

/**
 * Transport-agnostic GATT peer link implemented by both Android BluetoothGatt and MockBlePeerLink.
 */
interface BlePeerLink {
    val address: String
    val events: Flow<RawGattEvent>

    suspend fun connect()
    suspend fun requestMtu(mtu: Int): Boolean
    suspend fun refreshGattCache(): Boolean
    suspend fun discoverServices(): Boolean
    suspend fun writeCharacteristicWithResponse(serviceUuid: UUID, charUuid: UUID, bytes: ByteArray): Boolean
    suspend fun writeCharacteristicNoResponse(serviceUuid: UUID, charUuid: UUID, bytes: ByteArray): Boolean
    suspend fun enableNotifications(serviceUuid: UUID, charUuid: UUID, cccdUuid: UUID): Boolean
    suspend fun close()
}

interface BleAdapterGateway {
    val isBluetoothAvailable: Boolean
    val isBluetoothEnabled: StateFlow<Boolean>
    val scannedDevices: StateFlow<List<DiscoveredProfotoDevice>>
    val isScanning: StateFlow<Boolean>

    fun refreshAdapterState()
    fun startScan()
    fun stopScan()
    fun openPeerLink(device: DiscoveredProfotoDevice): BlePeerLink
}
