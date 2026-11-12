package app.prft.lightctrl.e2e.harness

import app.prft.lightctrl.ble.BleAdapterGateway
import app.prft.lightctrl.ble.BlePeerLink
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Fake BleAdapterGateway implementation for hermetic E2E tests.
 * Serves simulated devices from VirtualBenchFixtures.createKit() and
 * wraps opened peer links in RecordingBlePeerLink.
 */
class FakeBleGateway(
    initialDevices: List<DiscoveredProfotoDevice> = VirtualBenchFixtures.createKit().map { it.copy(isSimulated = false) },
    override val isBluetoothAvailable: Boolean = true,
) : BleAdapterGateway {

    private val _isBluetoothEnabled = MutableStateFlow(true)
    override val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _scannedDevices = MutableStateFlow(initialDevices)
    override val scannedDevices: StateFlow<List<DiscoveredProfotoDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val peerLinks = ConcurrentHashMap<String, RecordingBlePeerLink>()

    fun setBluetoothEnabled(enabled: Boolean) {
        _isBluetoothEnabled.value = enabled
    }

    fun updateScannedDevices(devices: List<DiscoveredProfotoDevice>) {
        _scannedDevices.value = devices
    }

    override fun refreshAdapterState() = Unit

    override fun startScan() {
        _isScanning.value = true
    }

    override fun stopScan() {
        _isScanning.value = false
    }

    override fun openPeerLink(device: DiscoveredProfotoDevice): BlePeerLink {
        val virtualLink = VirtualBenchFixtures.createPeerLink(device, fastTimings = true)
        val recordingLink = RecordingBlePeerLink(virtualLink)
        peerLinks[device.address] = recordingLink
        return recordingLink
    }

    fun getRecordingLink(address: String): RecordingBlePeerLink? = peerLinks[address]

    fun getAllRecordingLinks(): Map<String, RecordingBlePeerLink> = peerLinks.toMap()
}
