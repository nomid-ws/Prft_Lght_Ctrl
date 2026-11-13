package app.prft.lightctrl.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfotoHal"

/**
 * Production Android BLE Hardware Abstraction Layer over native android.bluetooth.BluetoothGatt.
 *
 * - Scans simultaneously for Primary SIG Service (0xFDBB: B10X, A2, Connect Pro) and
 *   Legacy 128-bit Base Service (1d944db4-...: Profoto Connect, early B10).
 * - Dispatches all Binder IPC callbacks onto a serialized single-thread BleCallbackDispatcher.
 * - Never initializes fake connected state.
 */
@Singleton
@SuppressLint("MissingPermission")
class AndroidBleAdapterGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : BleAdapterGateway {

    private val bleDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ProfotoBleCallbackThread").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + bleDispatcher)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    override val isBluetoothAvailable: Boolean
        get() = adapter != null

    private val _isBluetoothEnabled = MutableStateFlow(adapter?.isEnabled == true)
    override val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<DiscoveredProfotoDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<DiscoveredProfotoDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    override val scanError: StateFlow<String?> = _scanError.asStateFlow()

    @Volatile
    private var broadScanMode: Boolean = false

    @Volatile
    private var didAttemptAsyncFallback: Boolean = false

    private val deviceMap = LinkedHashMap<String, DiscoveredProfotoDevice>()
    private val seenNonProfotoDiagnosticLog = HashSet<String>()

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val enabled = (state == BluetoothAdapter.STATE_ON)
                _isBluetoothEnabled.value = enabled
                if (!enabled) {
                    _isScanning.value = false
                } else {
                    _scanError.value = null
                    hydrateSystemConnectedAndBondedProfotoDevices()
                }
            }
        }
    }

    init {
        try {
            context.registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to register Bluetooth state receiver: ${t.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scope.launch {
                val record = result.scanRecord
                val serviceUuids = record?.serviceUuids?.map { it.uuid }.orEmpty()
                val solicitationUuids = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    record?.serviceSolicitationUuids?.map { it.uuid }.orEmpty()
                } else {
                    emptyList()
                }
                val serviceDataMap = record?.serviceData.orEmpty()
                val serviceDataKeys = serviceDataMap.keys.map { it.uuid }
                val allUuids = serviceUuids + solicitationUuids + serviceDataKeys

                val fdbbData = record?.getServiceData(ParcelUuid(ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID))
                val legacyData = record?.getServiceData(ParcelUuid(ProfotoGattProfile.LEGACY_BASE_SERVICE_UUID))
                val (rawAdUuid, rawAdProductId) = parseRawProfotoAdBytes(record?.bytes)

                // Exact productId extraction matching Profoto Control d8/s.java:126-136 + raw AD fallback
                val advertisedProductId = when {
                    fdbbData != null && fdbbData.size > 1 -> fdbbData[1].toInt() and 0xFF
                    legacyData != null && legacyData.isNotEmpty() -> legacyData[0].toInt() and 0xFF
                    rawAdProductId > 0 -> rawAdProductId
                    else -> 0
                }

                val matchedUuid = when {
                    fdbbData != null || ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID in allUuids ->
                        ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID
                    legacyData != null || ProfotoGattProfile.LEGACY_BASE_SERVICE_UUID in allUuids ->
                        ProfotoGattProfile.LEGACY_BASE_SERVICE_UUID
                    rawAdUuid != null -> rawAdUuid
                    else -> null
                }

                val rawName = (result.device?.name ?: record?.deviceName).orEmpty().trim()
                val looksLikeProfoto = matchedUuid != null ||
                    advertisedProductId > 0 ||
                    looksLikeProfotoName(rawName)

                val addr = result.device?.address ?: return@launch
                if (!looksLikeProfoto) {
                    if (seenNonProfotoDiagnosticLog.add(addr)) {
                        Log.d(
                            TAG,
                            "Ignored non-Profoto BLE adv: addr=$addr name='$rawName' " +
                                "legacy=${result.isLegacy} uuids=$allUuids rssi=${result.rssi}",
                        )
                    }
                    return@launch
                }

                val prev = deviceMap[addr]
                val resolvedProductId = if (advertisedProductId > 0) {
                    advertisedProductId
                } else {
                    prev?.advertisedProductId ?: 0
                }
                val resolvedName = rawName.ifBlank {
                    prev?.advertisedName ?: "Profoto (${addr.takeLast(5)})"
                }

                val entry = DiscoveredProfotoDevice(
                    address = addr,
                    advertisedName = resolvedName,
                    rssi = result.rssi,
                    advertisedServiceUuid = matchedUuid ?: prev?.advertisedServiceUuid,
                    advertisedProductId = resolvedProductId,
                    isSimulated = false,
                    lastSeenEpochMs = System.currentTimeMillis(),
                )
                if (prev == null || prev.advertisedProductId != resolvedProductId) {
                    Log.i(
                        TAG,
                        "Discovered Profoto BLE: addr=$addr name='$resolvedName' productId=$resolvedProductId " +
                            "uuid=${entry.advertisedServiceUuid} rssi=${result.rssi}",
                    )
                }
                deviceMap[addr] = entry
                // Preserve first-seen discovery order so cards never jump under thumb on 1 dBm RSSI updates
                _scannedDevices.value = deviceMap.values.toList()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                Log.w(TAG, "BLE scan already started (code 1); keeping active state")
                _isScanning.value = true
                return
            }
            Log.e(TAG, "BLE Scan failed with code: $errorCode (broad=$broadScanMode, fallbackTried=$didAttemptAsyncFallback)")
            if (!didAttemptAsyncFallback && errorCode != ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
                didAttemptAsyncFallback = true
                val cb: ScanCallback = this
                scope.launch {
                    val fallbackScanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
                    if (fallbackScanner == null) {
                        _isScanning.value = false
                        _scanError.value = describeScanFailure(errorCode)
                        return@launch
                    }
                    runCatching { fallbackScanner.stopScan(cb) }
                    try {
                        val fallbackFilters = if (broadScanMode) buildProfotoScanFilters() else null
                        val fallbackSettings = ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .setReportDelay(0L)
                            .build()
                        fallbackScanner.startScan(fallbackFilters, fallbackSettings, cb)
                        _isScanning.value = true
                        Log.i(TAG, "Recovered BLE scan via fallback mode after code $errorCode")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Fallback BLE scan failed: ${t.message}")
                        _isScanning.value = false
                        _scanError.value = describeScanFailure(errorCode)
                    }
                }
                return
            }
            _isScanning.value = false
            _scanError.value = describeScanFailure(errorCode)
        }
    }

    override fun refreshAdapterState() {
        _isBluetoothEnabled.value = adapter?.isEnabled == true
    }

    override fun clearScanError() {
        _scanError.value = null
    }

    override fun setBroadScanMode(enabled: Boolean) {
        if (broadScanMode == enabled) return
        broadScanMode = enabled
        if (_isScanning.value) {
            stopScan()
            startScan()
        }
    }

    override fun startScan() {
        refreshAdapterState()
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            _isScanning.value = false
            return
        }
        hydrateSystemConnectedAndBondedProfotoDevices()
        if (_isScanning.value) return

        _scanError.value = null
        didAttemptAsyncFallback = false
        seenNonProfotoDiagnosticLog.clear()

        try {
            if (broadScanMode) {
                val broadSettings = buildBroadCompatScanSettings()
                try {
                    scanner.startScan(null as List<ScanFilter>?, broadSettings, scanCallback)
                } catch (extErr: Throwable) {
                    Log.w(TAG, "Broad 1M scan rejected (${extErr.message}); falling back to standard filtered 1M scan")
                    scanner.startScan(buildProfotoScanFilters(), buildStandardScanSettings(), scanCallback)
                }
                Log.i(TAG, "Started Broad 1M Profoto BLE scan (unfiltered 1M legacy+ext)")
            } else {
                // Default: Hardware-filtered 1M Legacy-compatible scan matching Profoto Control (d8/s.java).
                // Satisfies Samsung One UI neverForLocation filter rules and avoids Coded PHY radio starvation.
                val filters = buildProfotoScanFilters()
                val settings = buildStandardScanSettings()
                scanner.startScan(filters, settings, scanCallback)
                Log.i(TAG, "Started Filtered 1M Profoto BLE scan (${filters.size} UUID/ServiceData filters)")
            }
            _isScanning.value = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start BLE scan: ${t.message}")
            _isScanning.value = false
            _scanError.value = "BLE scan could not start: ${t.message ?: "Bluetooth error"}"
        }
    }

    private fun hydrateSystemConnectedAndBondedProfotoDevices() {
        scope.launch {
            val managedUuids = ProfotoGattProfile.getAllManagedServiceUuids()
            val candidates = LinkedHashMap<String, BluetoothDevice>()
            runCatching {
                bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)?.forEach { d ->
                    d.address?.let { candidates[it] = d }
                }
            }
            runCatching {
                adapter?.bondedDevices?.forEach { d ->
                    if (d.type == BluetoothDevice.DEVICE_TYPE_LE || d.type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                        d.address?.let { candidates.putIfAbsent(it, d) }
                    }
                }
            }
            var changed = false
            for ((addr, dev) in candidates) {
                if (deviceMap.containsKey(addr)) continue
                val name = runCatching { dev.name.orEmpty().trim() }.getOrDefault("")
                val matchedKnownUuid = runCatching {
                    dev.uuids?.firstOrNull { it.uuid in managedUuids }?.uuid
                }.getOrNull()
                if (!looksLikeProfotoName(name) && matchedKnownUuid == null) continue
                deviceMap[addr] = DiscoveredProfotoDevice(
                    address = addr,
                    advertisedName = name.ifBlank { "Profoto (${addr.takeLast(5)})" },
                    rssi = -55,
                    advertisedServiceUuid = matchedKnownUuid,
                    advertisedProductId = 0,
                    isSimulated = false,
                    lastSeenEpochMs = System.currentTimeMillis(),
                )
                Log.i(TAG, "Hydrated OS-known Profoto BLE device: addr=$addr name='$name' uuid=$matchedKnownUuid")
                changed = true
            }
            if (changed) {
                _scannedDevices.value = deviceMap.values.toList()
            }
        }
    }

    private fun looksLikeProfotoName(rawName: String): Boolean {
        if (rawName.isBlank()) return false
        return rawName.contains("Profoto", ignoreCase = true) ||
            rawName.contains("Connect", ignoreCase = true) ||
            rawName.startsWith("CP-", ignoreCase = true) ||
            rawName.startsWith("B10", ignoreCase = true) ||
            rawName.startsWith("B20", ignoreCase = true) ||
            rawName.startsWith("B30", ignoreCase = true) ||
            rawName.startsWith("D30", ignoreCase = true) ||
            rawName.startsWith("Pro-", ignoreCase = true) ||
            rawName.startsWith("ProPanel", ignoreCase = true) ||
            rawName.startsWith("A2", ignoreCase = true) ||
            rawName.startsWith("A10", ignoreCase = true) ||
            rawName.startsWith("C1", ignoreCase = true) ||
            rawName.startsWith("L600", ignoreCase = true) ||
            rawName.startsWith("L1600", ignoreCase = true)
    }

    /**
     * Direct BLE AD-structure fallback parser for 0xFDBB (little-endian BB FD) and
     * legacy 128-bit 1d944db4-58a0-4177-84da-e29bf5c4630d in case Android ScanRecord
     * omits ServiceData on split Extended Advertising PDUs.
     */
    private fun parseRawProfotoAdBytes(bytes: ByteArray?): Pair<UUID?, Int> {
        if (bytes == null || bytes.size < 4) return null to 0
        var i = 0
        var matchedUuid: UUID? = null
        var productId = 0
        while (i + 1 < bytes.size) {
            val len = bytes[i].toInt() and 0xFF
            if (len == 0 || i + 1 + len > bytes.size) break
            val adType = bytes[i + 1].toInt() and 0xFF
            val valStart = i + 2
            val valLen = len - 1
            when (adType) {
                0x16 -> { // 16-bit Service Data: [UUID_LO, UUID_HI, flags, productId, ...]
                    if (valLen >= 2 &&
                        (bytes[valStart].toInt() and 0xFF) == 0xBB &&
                        (bytes[valStart + 1].toInt() and 0xFF) == 0xFD
                    ) {
                        matchedUuid = ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID
                        if (valLen >= 4) {
                            productId = bytes[valStart + 3].toInt() and 0xFF
                        }
                    }
                }
                0x02, 0x03 -> { // Incomplete / Complete List of 16-bit Service Class UUIDs
                    var k = 0
                    while (k + 1 < valLen) {
                        if ((bytes[valStart + k].toInt() and 0xFF) == 0xBB &&
                            (bytes[valStart + k + 1].toInt() and 0xFF) == 0xFD
                        ) {
                            if (matchedUuid == null) matchedUuid = ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID
                        }
                        k += 2
                    }
                }
                0x21, 0x06, 0x07 -> { // 128-bit Service Data or 128-bit Service UUIDs
                    if (valLen >= 16 && matchesLegacyUuidLittleEndian(bytes, valStart)) {
                        if (matchedUuid == null) matchedUuid = ProfotoGattProfile.LEGACY_BASE_SERVICE_UUID
                        if (adType == 0x21 && valLen >= 17 && productId == 0) {
                            productId = bytes[valStart + 16].toInt() and 0xFF
                        }
                    }
                }
            }
            i += 1 + len
        }
        return matchedUuid to productId
    }

    private fun matchesLegacyUuidLittleEndian(buf: ByteArray, offset: Int): Boolean {
        val expected = byteArrayOf(
            0x0D, 0x63, 0xC4.toByte(), 0xF5.toByte(),
            0x9B.toByte(), 0xE2.toByte(), 0xDA.toByte(), 0x84.toByte(),
            0x77, 0x41, 0xA0.toByte(), 0x58,
            0xB4.toByte(), 0x4D, 0x94.toByte(), 0x1D,
        )
        for (idx in expected.indices) {
            if (buf[offset + idx] != expected[idx]) return false
        }
        return true
    }

    override fun stopScan() {
        if (!_isScanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "stopScan error: ${t.message}")
        } finally {
            _isScanning.value = false
        }
    }

    override fun openPeerLink(device: DiscoveredProfotoDevice): BlePeerLink {
        val btDevice: BluetoothDevice = adapter?.getRemoteDevice(device.address)
            ?: throw IllegalStateException("BluetoothAdapter unavailable for ${device.address}")
        return AndroidGattPeerLink(context, btDevice, scope)
    }

    fun injectOrClearSimulatedDevices(simulated: List<DiscoveredProfotoDevice>) {
        scope.launch {
            val realOnly = deviceMap.filterValues { !it.isSimulated }
            deviceMap.clear()
            deviceMap.putAll(realOnly)
            simulated.forEach { deviceMap[it.address] = it }
            _scannedDevices.update { deviceMap.values.sortedByDescending { d -> d.rssi } }
        }
    }

    companion object {
        /**
         * Explicit BLE ScanFilters covering all Profoto advertisement formats:
         * - 16-bit Service Data 0xFDBB (A2, A10, B10X, Pro-D3, Connect Pro, modern Connect)
         * - 16-bit Service UUID 0xFDBB
         * - 128-bit Legacy Service Data & UUID 1d944db4-58a0-4177-84da-e29bf5c4630d (early Connect, B10, C1)
         * - 128-bit AirX Auth Service UUID bbd75314-ddaa-4b61-a7df-c6eedb79c152
         *
         * Supplying explicit ServiceData/ServiceUuid filters exempts the scan from Samsung One UI
         * unfiltered scan suppression when BLUETOOTH_SCAN uses neverForLocation.
         */
        fun buildProfotoScanFilters(): List<ScanFilter> {
            val fdbb = ParcelUuid(ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID)
            val legacy = ParcelUuid(ProfotoGattProfile.LEGACY_BASE_SERVICE_UUID)
            val auth = ParcelUuid(ProfotoGattProfile.AUTH_SERVICE_UUID)
            val emptyBytes = ByteArray(0)
            return listOf(
                ScanFilter.Builder().setServiceData(fdbb, emptyBytes, emptyBytes).build(),
                ScanFilter.Builder().setServiceUuid(fdbb).build(),
                ScanFilter.Builder().setServiceData(legacy, emptyBytes, emptyBytes).build(),
                ScanFilter.Builder().setServiceUuid(legacy).build(),
                ScanFilter.Builder().setServiceUuid(auth).build(),
            )
        }

        fun buildStandardScanSettings(): ScanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0L)
                .build()

        fun buildBroadCompatScanSettings(): ScanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0L)
                .setLegacy(false)
                .setPhy(BluetoothDevice.PHY_LE_1M)
                .build()

        fun describeScanFailure(errorCode: Int): String = when (errorCode) {
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                "BLE scanner registration failed (code 2) — toggle phone Bluetooth Off/On and retry"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
                "Bluetooth stack internal scan error (code 3) — toggle phone Bluetooth Off/On and retry"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
                "BLE scan mode unsupported on this device (code 4)"
            5 -> // SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
                "Bluetooth hardware scan slots full (code 5) — close other BLE apps and retry"
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                "Android paused rapid BLE scan restarts — wait 10s and tap Scan Profoto BLE"
            else ->
                "BLE scan failed (code $errorCode) — verify Bluetooth is enabled and retry"
        }
    }
}

@SuppressLint("MissingPermission")
private class AndroidGattPeerLink(
    private val context: Context,
    private val bluetoothDevice: BluetoothDevice,
    private val callbackScope: CoroutineScope,
) : BlePeerLink {

    override val address: String get() = bluetoothDevice.address

    private val _events = MutableSharedFlow<RawGattEvent>(extraBufferCapacity = 128)
    override val events: Flow<RawGattEvent> = _events.asSharedFlow()

    @Volatile
    private var gatt: BluetoothGatt? = null
    private val gattWriteMutex = Mutex()
    @Volatile
    private var pendingCharWrite: CompletableDeferred<Int>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                pendingCharWrite?.complete(status)
            }
            callbackScope.launch {
                Log.d(TAG, "onConnectionStateChange($address): status=$status newState=$newState")
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    _events.emit(RawGattEvent.Connected)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _events.emit(RawGattEvent.Disconnected(status))
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    _events.emit(RawGattEvent.Disconnected(status))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            callbackScope.launch {
                Log.d(TAG, "onMtuChanged($address): mtu=$mtu status=$status")
                _events.emit(RawGattEvent.MtuChanged(mtu, status))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            callbackScope.launch {
                val safeServices = g.services.filterNot { it.uuid in ProfotoGattProfile.FORBIDDEN_DFU_SERVICE_UUIDS }
                val svcUuids = safeServices.map { it.uuid }.toSet()
                val charUuids = safeServices
                    .flatMap { it.characteristics }
                    .map { it.uuid }
                    .filterNot { it in ProfotoGattProfile.FORBIDDEN_DFU_CHAR_UUIDS }
                    .toSet()
                Log.d(TAG, "onServicesDiscovered($address): status=$status services=$svcUuids chars=${charUuids.size}")
                _events.emit(RawGattEvent.ServicesDiscovered(svcUuids, charUuids, status))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingCharWrite?.complete(status)
            callbackScope.launch {
                Log.d(TAG, "onCharacteristicWrite($address): uuid=${characteristic.uuid} status=$status")
                _events.emit(RawGattEvent.CharacteristicWritten(characteristic.uuid, status))
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            callbackScope.launch {
                Log.d(TAG, "onDescriptorWrite($address): char=${descriptor.characteristic.uuid} status=$status")
                _events.emit(RawGattEvent.CccdWritten(descriptor.characteristic.uuid, status))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val copy = value.copyOf()
            callbackScope.launch {
                _events.emit(RawGattEvent.CharacteristicNotified(characteristic.uuid, copy))
            }
        }

        @Deprecated("Fallback for Android 12 (API 31-32)")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val copy = characteristic.value?.copyOf() ?: return
                callbackScope.launch {
                    _events.emit(RawGattEvent.CharacteristicNotified(characteristic.uuid, copy))
                }
            }
        }
    }

    override suspend fun connect() {
        gatt = bluetoothDevice.connectGatt(
            context,
            false,
            callback,
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    override suspend fun requestMtu(mtu: Int): Boolean =
        gatt?.requestMtu(mtu) == true

    override suspend fun refreshGattCache(): Boolean {
        val active = gatt ?: return false
        return try {
            val refreshMethod = active.javaClass.getMethod("refresh")
            (refreshMethod.invoke(active) as? Boolean) ?: false
        } catch (t: Throwable) {
            // Guarded against Android 15/16 Non-SDK hidden API restrictions
            Log.w(TAG, "BluetoothGatt.refresh() unavailable or restricted: ${t.message}")
            false
        }
    }

    override suspend fun discoverServices(): Boolean =
        gatt?.discoverServices() == true

    override suspend fun writeCharacteristicWithResponse(
        serviceUuid: UUID,
        charUuid: UUID,
        bytes: ByteArray,
    ): Boolean = gattWriteMutex.withLock {
        ProfotoGattProfile.assertSafeCharacteristicUuid(charUuid)
        val active = gatt ?: return@withLock false
        val ch = findSafeCharacteristic(active, serviceUuid, charUuid) ?: run {
            Log.e(TAG, "writeCharacteristicWithResponse($address): characteristic $charUuid not found across services")
            return@withLock false
        }
        val writeType = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val done = CompletableDeferred<Int>()
        pendingCharWrite = done
        val started = writeCharCompat(active, ch, bytes, writeType)
        if (!started) {
            pendingCharWrite = null
            Log.w(TAG, "writeCharacteristicWithResponse($address): BluetoothGatt busy/rejected for $charUuid")
            return@withLock false
        }
        val status = withTimeoutOrNull(1_000L) { done.await() }
        pendingCharWrite = null
        status == null || status == BluetoothGatt.GATT_SUCCESS
    }

    override suspend fun writeCharacteristicNoResponse(
        serviceUuid: UUID,
        charUuid: UUID,
        bytes: ByteArray,
    ): Boolean = gattWriteMutex.withLock {
        ProfotoGattProfile.assertSafeCharacteristicUuid(charUuid)
        val active = gatt ?: return@withLock false
        val ch = findSafeCharacteristic(active, serviceUuid, charUuid) ?: run {
            Log.e(TAG, "writeCharacteristicNoResponse($address): characteristic $charUuid not found across services")
            return@withLock false
        }
        val writeType = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val done = CompletableDeferred<Int>()
        pendingCharWrite = done
        val started = writeCharCompat(active, ch, bytes, writeType)
        if (!started) {
            pendingCharWrite = null
            Log.w(TAG, "writeCharacteristicNoResponse($address): BluetoothGatt busy/rejected for $charUuid")
            return@withLock false
        }
        val status = withTimeoutOrNull(1_000L) { done.await() }
        pendingCharWrite = null
        status == null || status == BluetoothGatt.GATT_SUCCESS
    }

    override suspend fun enableNotifications(
        serviceUuid: UUID,
        charUuid: UUID,
        cccdUuid: UUID,
    ): Boolean {
        ProfotoGattProfile.assertSafeCharacteristicUuid(charUuid)
        val active = gatt ?: return false
        val ch = findSafeCharacteristic(active, serviceUuid, charUuid) ?: run {
            Log.e(TAG, "enableNotifications($address): characteristic $charUuid not found across services")
            return false
        }
        if (!active.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(cccdUuid) ?: return false

        val descValue = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            active.writeDescriptor(cccd, descValue) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = descValue
                active.writeDescriptor(cccd)
            }
        }
    }

    /**
     * Locates [charUuid] across all non-DFU GATT services (matching official Profoto Control p109l5/c.java:347-369),
     * because AUTH_CLIENT_ID_CHAR_UUID (c655ad5a-...) resides under service bbd75314-... while PUP TX/RX reside
     * under 1d944db4-... on AirX hardware.
     */
    private fun findSafeCharacteristic(
        active: BluetoothGatt,
        preferredServiceUuid: UUID,
        charUuid: UUID,
    ): BluetoothGattCharacteristic? {
        ProfotoGattProfile.assertSafeCharacteristicUuid(charUuid)
        if (preferredServiceUuid !in ProfotoGattProfile.FORBIDDEN_DFU_SERVICE_UUIDS) {
            active.getService(preferredServiceUuid)?.getCharacteristic(charUuid)?.let { return it }
        }
        for (svc in active.services) {
            if (svc.uuid in ProfotoGattProfile.FORBIDDEN_DFU_SERVICE_UUIDS) continue
            svc.getCharacteristic(charUuid)?.let { return it }
        }
        return null
    }

    override suspend fun close() {
        pendingCharWrite?.complete(-1)
        pendingCharWrite = null
        val active = gatt ?: return
        gatt = null
        try {
            active.disconnect()
        } catch (_: Throwable) {
        }
        try {
            active.close()
        } catch (_: Throwable) {
        }
    }

    private fun writeCharCompat(
        active: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray,
        writeType: Int,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            active.writeCharacteristic(ch, bytes, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = bytes
                active.writeCharacteristic(ch)
            }
        }
    }
}
