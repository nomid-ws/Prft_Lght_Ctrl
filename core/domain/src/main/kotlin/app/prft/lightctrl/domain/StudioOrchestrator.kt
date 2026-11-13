package app.prft.lightctrl.domain

import android.content.Context
import app.prft.lightctrl.ble.AndroidBleAdapterGateway
import app.prft.lightctrl.ble.BleAdapterGateway
import app.prft.lightctrl.ble.BleConnectionStage
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.ble.ProfotoBleConnectionFsm
import app.prft.lightctrl.ble.ProfotoBleForegroundService
import app.prft.lightctrl.ble.ProfotoGattProfile
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.AirProtocolMode
import app.prft.lightctrl.protocol.BatteryChemistry
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.DisplayOrientationMode
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.FlashSchedulingState
import app.prft.lightctrl.protocol.HardwareCatalog
import app.prft.lightctrl.protocol.MonolightZoomMode
import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import app.prft.lightctrl.protocol.VideoLightPowerMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Studio-wide reactive orchestrator managing simultaneous BLE sessions across
 * Profoto B10X Plus, B10X, A2, Connect Pro, and Connect units.
 */
@Singleton
class StudioOrchestrator(
    private val appContext: Context?,
    private val bleGateway: BleAdapterGateway,
    private val preferencesStore: StudioPreferencesStore = StudioPreferencesStore(appContext, Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val groupPacingMs: Long = ProfotoGattProfile.GROUP_INTER_DEVICE_PACING_MS,
    private val allowSimulator: Boolean = true,
) {
    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        bleGateway: BleAdapterGateway,
        preferencesStore: StudioPreferencesStore,
    ) : this(
        appContext = appContext,
        bleGateway = bleGateway,
        preferencesStore = preferencesStore,
        dispatcher = Dispatchers.Default,
        groupPacingMs = ProfotoGattProfile.GROUP_INTER_DEVICE_PACING_MS,
        allowSimulator = BuildConfig.DEBUG,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val nicknameStore = DeviceNicknameStore(appContext, dispatcher)
    private val connectMutex = Mutex()

    private val sessions = LinkedHashMap<String, ActiveSession>()
    private val unitsState = MutableStateFlow<Map<String, ConnectedProfotoUnit>>(emptyMap())
    private val simulatorEnabled = MutableStateFlow(false)
    private val selectedGroupFilter = MutableStateFlow(AirGroup.ALL)
    private val statusBanner = MutableStateFlow<String?>(null)
    private val connectFallbackGroupLevels = MutableStateFlow(listOf(80, 75, 70, 65, 60, 55))
    private val groupWriteJobs = arrayOfNulls<Job>(6)
    private val manuallyDisconnectedMacs = mutableSetOf<String>()
    private val autoConnectingMacs = mutableSetOf<String>()
    private val userDirectedGroupPowers = ConcurrentHashMap<String, MutableMap<Int, Int>>()

    private val studioGroups = listOf(
        AirGroup.GROUP_A,
        AirGroup.GROUP_B,
        AirGroup.GROUP_C,
        AirGroup.GROUP_D,
        AirGroup.GROUP_E,
        AirGroup.GROUP_F,
    )

    private data class ActiveSession(
        val fsm: ProfotoBleConnectionFsm,
        val stageJob: Job,
        val telemetryJob: Job,
    )

    private data class PrefsSnapshot(
        val groupRoles: Map<AirGroup, String>,
        val pinnedKitMacs: Set<String>,
        val isVolumeKeyTestFlashEnabled: Boolean,
        val isAutoReconnectPinnedEnabled: Boolean,
        val isDualPowerReadoutEnabled: Boolean,
        val isBroadBleScanEnabled: Boolean,
    )

    private data class AuxUiState(
        val sim: Boolean,
        val grp: AirGroup,
        val banner: String?,
        val fallbackLevels: List<Int>,
        val nicknames: Map<String, String>,
        val prefs: PrefsSnapshot,
    )

    init {
        scope.launch {
            nicknameStore.nicknames.collect { nicks ->
                unitsState.update { curMap ->
                    if (curMap.isEmpty()) return@update curMap
                    curMap.mapValues { (addr, unit) ->
                        val saved = nicks[DeviceNicknameStore.normalizeAddress(addr)]
                        if (unit.customAlias == saved) unit else unit.copy(customAlias = saved)
                    }
                }
            }
        }

        scope.launch {
            combine(
                bleGateway.scannedDevices,
                preferencesStore.pinnedKitMacs,
                preferencesStore.isAutoReconnectPinnedEnabled,
            ) { scanned, pinned, autoReconnect ->
                Triple(scanned, pinned, autoReconnect)
            }.collect { (scanned, pinned, autoReconnect) ->
                if (autoReconnect && pinned.isNotEmpty()) {
                    for (dev in scanned) {
                        val norm = DeviceNicknameStore.normalizeAddress(dev.address)
                        if (norm in pinned && norm !in manuallyDisconnectedMacs) {
                            val existing = sessions[dev.address]
                            val stage = unitsState.value[dev.address]?.stage
                            val isDisconnected = existing == null ||
                                existing.fsm.stage.value is BleConnectionStage.Disconnected ||
                                existing.fsm.stage.value is BleConnectionStage.Failed
                            if (isDisconnected && stage !is BleConnectionStage.Connecting && norm !in autoConnectingMacs) {
                                autoConnectingMacs.add(norm)
                                scope.launch {
                                    try {
                                        connectDevice(dev)
                                    } finally {
                                        autoConnectingMacs.remove(norm)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            preferencesStore.isBroadBleScanEnabled.collect { enabled ->
                bleGateway.setBroadScanMode(enabled)
            }
        }

        scope.launch {
            bleGateway.scanError.collect { err ->
                if (!err.isNullOrBlank()) {
                    statusBanner.value = err
                }
            }
        }
    }

    private val prefsSnapshotFlow: Flow<PrefsSnapshot> = combine(
        preferencesStore.groupRoles,
        preferencesStore.pinnedKitMacs,
        preferencesStore.isVolumeKeyTestFlashEnabled,
        preferencesStore.isAutoReconnectPinnedEnabled,
        combine(
            preferencesStore.isDualPowerReadoutEnabled,
            preferencesStore.isBroadBleScanEnabled,
        ) { dualPower, broadScan -> Pair(dualPower, broadScan) },
    ) { roles, pinned, volume, autoRec, (dualPower, broadScan) ->
        PrefsSnapshot(roles, pinned, volume, autoRec, dualPower, broadScan)
    }

    val dashboardState: StateFlow<StudioDashboardState> = combine(
        bleGateway.isBluetoothEnabled,
        bleGateway.isScanning,
        bleGateway.scannedDevices,
        unitsState,
        combine(
            simulatorEnabled,
            selectedGroupFilter,
            statusBanner,
            connectFallbackGroupLevels,
            combine(nicknameStore.nicknames, prefsSnapshotFlow) { nicks, p -> Pair(nicks, p) },
        ) { sim, grp, banner, levels, (nicks, p) ->
            AuxUiState(sim, grp, banner, levels, nicks, p)
        },
    ) { btEnabled, scanning, scanned, unitsMap, aux ->
        val units = recomputeChannelCoexistence(
            unitsMap.values.map { u ->
                val saved = aux.nicknames[DeviceNicknameStore.normalizeAddress(u.address)]
                if (u.customAlias == saved) u else u.copy(customAlias = saved)
            }
        )
        StudioDashboardState(
            isBluetoothAvailable = bleGateway.isBluetoothAvailable,
            isBluetoothEnabled = btEnabled,
            isScanning = scanning,
            benchSimulatorEnabled = aux.sim,
            deviceNicknames = aux.nicknames,
            groupRoles = aux.prefs.groupRoles,
            pinnedKitMacs = aux.prefs.pinnedKitMacs,
            isVolumeKeyTestFlashEnabled = aux.prefs.isVolumeKeyTestFlashEnabled,
            isAutoReconnectPinnedEnabled = aux.prefs.isAutoReconnectPinnedEnabled,
            isDualPowerReadoutEnabled = aux.prefs.isDualPowerReadoutEnabled,
            isBroadBleScanEnabled = aux.prefs.isBroadBleScanEnabled,
            scannedDevices = scanned,
            connectedUnits = units,
            groupChannels = buildGroupChannelStates(units, aux.fallbackLevels, aux.prefs.groupRoles),
            selectedGroupFilter = aux.grp,
            statusBanner = aux.banner,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = StudioDashboardState(
            isBluetoothAvailable = bleGateway.isBluetoothAvailable,
            isBluetoothEnabled = bleGateway.isBluetoothEnabled.value,
        ),
    )

    fun refreshBluetoothState() {
        bleGateway.refreshAdapterState()
    }

    fun startScan() {
        manuallyDisconnectedMacs.clear()
        statusBanner.value = null
        bleGateway.clearScanError()
        bleGateway.startScan()
    }

    fun stopScan() {
        bleGateway.stopScan()
    }

    fun setSelectedGroupFilter(group: AirGroup) {
        selectedGroupFilter.value = group
    }

    fun clearStatusBanner() {
        statusBanner.value = null
        bleGateway.clearScanError()
    }

    /**
     * Explicit opt-in bench simulator for testing UI/protocol flows when physical strobes are off.
     * Never auto-enabled at startup and never skips the 10-stage GATT handshake.
     */
    fun setBenchSimulatorEnabled(enabled: Boolean) {
        if (enabled && !allowSimulator) {
            statusBanner.value = "Bench Simulator is disabled in release builds"
            return
        }
        simulatorEnabled.value = enabled
        val androidGw = bleGateway as? AndroidBleAdapterGateway
        if (enabled) {
            androidGw?.injectOrClearSimulatedDevices(VirtualBenchFixtures.createKit())
            statusBanner.value = "Bench Simulator discovered 4 virtual devices (tap Connect to run full GATT handshake)"
        } else {
            // Disconnect any simulated sessions
            scope.launch {
                val simAddresses = unitsState.value.values
                    .filter { it.device.isSimulated }
                    .map { it.address }
                simAddresses.forEach { disconnectDevice(it) }
                androidGw?.injectOrClearSimulatedDevices(emptyList())
            }
        }
    }

    fun connectDevice(device: DiscoveredProfotoDevice, fastSimTimings: Boolean = false) {
        val existing = sessions[device.address]
        if (existing != null && existing.fsm.stage.value !is BleConnectionStage.Failed && existing.fsm.stage.value !is BleConnectionStage.Disconnected) {
            return
        }
        if (existing != null) {
            existing.stageJob.cancel()
            existing.telemetryJob.cancel()
            sessions.remove(device.address)
        }
        userDirectedGroupPowers.remove(device.address)
        statusBanner.value = null

        val peerLink = if (device.isSimulated) {
            VirtualBenchFixtures.createPeerLink(device, fastTimings = fastSimTimings)
        } else {
            bleGateway.openPeerLink(device)
        }

        val fsm = ProfotoBleConnectionFsm(
            device = device,
            link = peerLink,
            dispatcher = dispatcher,
            cacheSettleMs = if (device.isSimulated) 20L else ProfotoGattProfile.GATT_CACHE_SETTLE_MS,
        )

        val savedNickname = nicknameStore.nicknames.value[DeviceNicknameStore.normalizeAddress(device.address)]
        val resolvedSpec = HardwareCatalog.resolve(device.advertisedProductId, device.advertisedName)
        val initialUnit = ConnectedProfotoUnit(
            device = device,
            customAlias = savedNickname,
            productId = device.advertisedProductId,
            stage = BleConnectionStage.Connecting,
            modelSpec = resolvedSpec,
            maxAirChannel = resolvedSpec.maxAirChannelDefault,
        )
        unitsState.update { it + (device.address to initialUnit) }

        val stageJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            fsm.stage.collect { st ->
                unitsState.update { map ->
                    val cur = map[device.address] ?: return@update map
                    map + (device.address to cur.copy(stage = st))
                }
                syncForegroundServiceNotification()
            }
        }

        val telemetryJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            fsm.incomingMessages.collect { (_, msg) ->
                applyIncomingMessage(device.address, msg)
            }
        }

        sessions[device.address] = ActiveSession(fsm, stageJob, telemetryJob)

        scope.launch {
            val connected = if (device.isSimulated) {
                fsm.connectAndAuthenticate(
                    useLegacyV1HydrationFallback = device.usesLegacyService || resolvedSpec.usesLegacyV1Queries,
                )
            } else {
                // Stop active SCAN_MODE_LOW_LATENCY before opening GATT link and serialize BLE handshakes
                // so Android 2.4GHz HCI windows are never starved on simultaneous taps.
                bleGateway.stopScan()
                connectMutex.withLock {
                    fsm.connectAndAuthenticate(
                        useLegacyV1HydrationFallback = device.usesLegacyService || resolvedSpec.usesLegacyV1Queries,
                    )
                }
            }
            if (!connected) {
                val failedStage = fsm.stage.value as? BleConnectionStage.Failed
                stageJob.cancel()
                telemetryJob.cancel()
                sessions.remove(device.address)
                val label = savedNickname ?: resolvedSpec.marketingName
                val detail = failedStage?.let { "${it.stageName}: ${it.reason}" } ?: "GATT link closed"
                statusBanner.value = "Connection failed for $label ($detail)"
            }
            syncForegroundServiceNotification()
        }
    }

    fun retryConnectDevice(address: String) {
        val dev = unitsState.value[address]?.device
            ?: bleGateway.scannedDevices.value.firstOrNull { it.address == address }
            ?: return
        connectDevice(dev)
    }

    fun disconnectDevice(address: String) {
        manuallyDisconnectedMacs.add(DeviceNicknameStore.normalizeAddress(address))
        userDirectedGroupPowers.remove(address)
        val active = sessions.remove(address)
        scope.launch {
            active?.stageJob?.cancel()
            active?.telemetryJob?.cancel()
            active?.fsm?.disconnect()
            unitsState.update { it - address }
            syncForegroundServiceNotification()
        }
    }

    fun renameUnitAlias(address: String, alias: String?) {
        nicknameStore.setNickname(address, alias)
        val cleaned = alias?.trim()?.takeIf { it.isNotEmpty() }
        unitsState.update { map ->
            val cur = map[address] ?: return@update map
            map + (address to cur.copy(customAlias = cleaned))
        }
    }

    // =========================================================================
    // Operational Light & Remote Controls
    // =========================================================================

    fun triggerTestFlash(address: String) {
        sessions[address]?.fsm?.triggerImmediateTestFlash()
    }

    fun triggerStudioMasterTestFlash() {
        val units = dashboardState.value.connectedUnits.filter { it.isReady }
        // Prefer triggering through an active Connect Pro / Connect remote if present, plus standalone strobes
        val remoteChannels = units
            .filter { it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE || it.archetype == DeviceArchetype.CONNECT_REMOTE }
            .map { it.airChannel }
            .toSet()

        units.forEach { unit ->
            val coveredByRemote = unit.archetype == DeviceArchetype.MONOLIGHT && unit.airChannel in remoteChannels
            if (!coveredByRemote) {
                sessions[unit.address]?.fsm?.triggerImmediateTestFlash()
            }
        }
    }

    fun setFlashPowerTenthStops(address: String, tenthStops: Int) {
        val unit = unitsState.value[address] ?: return
        val clamped = tenthStops.coerceIn(unit.minFlashTenthStops, unit.maxFlashTenthStops)
        mutateOptimistically(address) { it.copy(flashPowerTenthStops = clamped) }
        val session = sessions[address] ?: return
        scope.launch {
            val ok = if (unit.usesV2HeadOpcodes) {
                session.fsm.sendCommand(PupOpcodes.MX_ENERGY_V2) { seq ->
                    PupFrameCodec.encodeFlashPowerV2(seq, unit.primaryHeadId, clamped)
                }
            } else {
                val v1Ok = session.fsm.sendCommand(PupOpcodes.MX_ENERGY) { seq ->
                    PupFrameCodec.encodeFlashPower(seq, clamped)
                }
                if (!v1Ok) {
                    // Fallback to V2 head 0 if AirX V2 firmware rejected V1 MxEnergy (18)
                    val v2Ok = session.fsm.sendCommand(PupOpcodes.MX_ENERGY_V2) { seq ->
                        PupFrameCodec.encodeFlashPowerV2(seq, unit.primaryHeadId, clamped)
                    }
                    if (v2Ok) {
                        mutateOptimistically(address) { it.copy(usesV2HeadOpcodes = true) }
                    }
                    v2Ok
                } else {
                    true
                }
            }
            if (!ok) {
                statusBanner.value = "BLE command timeout (flash power) — verify light is in range"
            }
        }
    }

    fun stepFlashPower(address: String, deltaTenthStops: Int) {
        val cur = unitsState.value[address] ?: return
        setFlashPowerTenthStops(address, cur.flashPowerTenthStops + deltaTenthStops)
    }

    fun setHeadEnabled(address: String, enabled: Boolean) {
        val unit = unitsState.value[address] ?: return
        mutateOptimistically(address) { it.copy(headEnabled = enabled) }
        val session = sessions[address] ?: return
        scope.launch {
            val ok = if (unit.usesV2HeadOpcodes) {
                session.fsm.sendCommand(PupOpcodes.HEAD_ON_V2) { seq ->
                    PupFrameCodec.encodeHeadOnV2(seq, unit.primaryHeadId, enabled)
                }
            } else {
                session.fsm.sendCommand(PupOpcodes.HEAD_ON) { seq ->
                    PupFrameCodec.encodeHeadOn(seq, enabled)
                } || session.fsm.sendCommand(PupOpcodes.HEAD_ON_V2) { seq ->
                    PupFrameCodec.encodeHeadOnV2(seq, unit.primaryHeadId, enabled)
                }
            }
            if (!ok) {
                statusBanner.value = "BLE command timeout (head standby) — verify light is in range"
            }
        }
    }

    fun setFreezeEnabled(address: String, enabled: Boolean) {
        mutateOptimistically(address) { it.copy(freezeEnabled = enabled) }
        dispatchCommand(address, PupOpcodes.FREEZE_ENABLE) { seq ->
            PupFrameCodec.encodeFreezeEnable(seq, enabled)
        }
    }

    fun setCaptureModeVideoPriority(address: String, videoPriority: Boolean) {
        mutateOptimistically(address) { it.copy(captureVideoPriority = videoPriority) }
        dispatchCommand(address, PupOpcodes.CAPTURE_MODE) { seq ->
            PupFrameCodec.encodeCaptureMode(seq, videoPriority)
        }
    }

    fun setIrSlaveEnabled(address: String, enabled: Boolean) {
        mutateOptimistically(address) { it.copy(irSlaveEnabled = enabled) }
        dispatchCommand(address, PupOpcodes.IR_SLAVE_ENABLE) { seq ->
            PupFrameCodec.encodeIrSlaveEnable(seq, enabled)
        }
    }

    fun setReadySoundEnabled(address: String, enabled: Boolean) {
        mutateOptimistically(address) { it.copy(readySoundEnabled = enabled) }
        dispatchCommand(address, PupOpcodes.SOUND_ENABLE_READY) { seq ->
            PupFrameCodec.encodeReadySound(seq, enabled)
        }
    }

    // Continuous / Modeling Light (B10X Plus 3250lm, B10X, A2)
    fun setVideoLightEnabled(address: String, enabled: Boolean) {
        val unit = unitsState.value[address] ?: return
        mutateOptimistically(address) { it.copy(videoLightEnabled = enabled) }
        val session = sessions[address] ?: return
        scope.launch {
            val ok = if (unit.usesV2HeadOpcodes) {
                session.fsm.sendCommand(PupOpcodes.VIDEO_LIGHT_ENABLE_V2) { seq ->
                    PupFrameCodec.encodeVideoLightEnableV2(seq, unit.primaryHeadId, enabled)
                } || session.fsm.sendCommand(PupOpcodes.VIDEO_LIGHT_ENABLE) { seq ->
                    PupFrameCodec.encodeVideoLightEnable(seq, enabled)
                }
            } else {
                session.fsm.sendCommand(PupOpcodes.VIDEO_LIGHT_ENABLE) { seq ->
                    PupFrameCodec.encodeVideoLightEnable(seq, enabled)
                } || session.fsm.sendCommand(PupOpcodes.VIDEO_LIGHT_ENABLE_V2) { seq ->
                    PupFrameCodec.encodeVideoLightEnableV2(seq, unit.primaryHeadId, enabled)
                }
            }
            if (!ok) {
                statusBanner.value = "BLE command timeout (LED toggle) — verify light is in range"
            }
        }
    }

    fun setVideoLightMode(address: String, mode: VideoLightPowerMode) {
        mutateOptimistically(address) { it.copy(videoLightMode = mode) }
        dispatchCommand(address, PupOpcodes.VIDEO_LIGHT_POWER_MODE) { seq ->
            PupFrameCodec.encodeVideoLightPowerMode(seq, mode)
        }
    }

    fun setVideoLightIntensityPercent(address: String, percent: Int) {
        val unit = unitsState.value[address] ?: return
        val pct = percent.coerceIn(0, 100)
        val raw = (unit.minVideoIntensity + ((unit.maxVideoIntensity - unit.minVideoIntensity) * (pct / 100f)))
            .roundToInt()
            .coerceIn(unit.minVideoIntensity, unit.maxVideoIntensity)
        mutateOptimistically(address) { it.copy(videoLightIntensity = raw) }
        dispatchCommand(address, PupOpcodes.VIDEO_LIGHT_INTENSITY) { seq ->
            PupFrameCodec.encodeVideoLightIntensity(seq, raw)
        }
    }

    fun setVideoLightKelvin(address: String, kelvin: Int) {
        val unit = unitsState.value[address] ?: return
        val clamped = kelvin.coerceIn(unit.minKelvin, unit.maxKelvin)
        mutateOptimistically(address) { it.copy(videoLightKelvin = clamped) }
        dispatchCommand(address, PupOpcodes.VIDEO_LIGHT_COLOR_TEMPERATURE) { seq ->
            PupFrameCodec.encodeVideoLightCct(seq, clamped)
        }
    }

    fun setDisplayOrientation(address: String, mode: DisplayOrientationMode) {
        mutateOptimistically(address) { it.copy(displayOrientation = mode) }
        dispatchCommand(address, PupOpcodes.DISPLAY_ORIENTATION) { seq ->
            PupFrameCodec.encodeDisplayOrientation(seq, mode)
        }
    }

    fun setDisplayBrightness(address: String, level: DisplayBrightnessLevel) {
        mutateOptimistically(address) { it.copy(displayBrightness = level) }
        dispatchCommand(address, PupOpcodes.DISPLAY_BRIGHTNESS) { seq ->
            PupFrameCodec.encodeDisplayBrightness(seq, level)
        }
    }

    // Air Channel & Group
    fun setAirChannel(address: String, channel: Int) {
        val unit = unitsState.value[address] ?: return
        val safeMin = unit.minAirChannel.coerceIn(1, 100)
        val safeMax = unit.maxAirChannel.coerceIn(safeMin, 100)
        val clamped = channel.coerceIn(safeMin, safeMax)
        mutateOptimistically(address) { it.copy(airChannel = clamped) }
        dispatchCommand(address, PupOpcodes.AIR_CHANNEL) { seq ->
            PupFrameCodec.encodeAirChannel(seq, clamped)
        }
    }

    fun setAirGroup(address: String, group: AirGroup) {
        if (group == AirGroup.ALL) return
        val unit = unitsState.value[address] ?: return
        mutateOptimistically(address) { it.copy(airGroup = group) }
        val session = sessions[address] ?: return
        scope.launch {
            val ok = if (unit.usesV2HeadOpcodes) {
                session.fsm.sendCommand(PupOpcodes.AIR_GROUP_V2) { seq ->
                    PupFrameCodec.encodeAirGroupV2(seq, unit.primaryHeadId, group)
                }
            } else {
                session.fsm.sendCommand(PupOpcodes.AIR_GROUP) { seq ->
                    PupFrameCodec.encodeAirGroup(seq, group)
                } || session.fsm.sendCommand(PupOpcodes.AIR_GROUP_V2) { seq ->
                    PupFrameCodec.encodeAirGroupV2(seq, unit.primaryHeadId, group)
                }
            }
            if (!ok) {
                statusBanner.value = "BLE command timeout (group) — verify light is in range"
            }
        }
    }

    // Connect Pro & Connect Remote Controls
    fun setAirProtocolMode(address: String, mode: AirProtocolMode) {
        val maxCh = if (mode == AirProtocolMode.AIR_1) 20 else 100
        mutateOptimistically(address) {
            it.copy(
                airProtocolMode = mode,
                maxAirChannel = maxCh,
                airChannel = it.airChannel.coerceAtMost(maxCh),
            )
        }
        dispatchCommand(address, PupOpcodes.AIR_PROTOCOL_MODE) { seq ->
            PupFrameCodec.encodeAirProtocolMode(seq, mode)
        }
    }

    fun setRemoteFlashMode(address: String, mode: FlashMode) {
        val unit = unitsState.value[address] ?: return
        if (unit.archetype == DeviceArchetype.CONNECT_REMOTE) {
            statusBanner.value = "Connect operating mode is physically controlled via collar switch"
            return
        }
        val isTtl = (mode == FlashMode.TTL)
        mutateOptimistically(address) {
            it.copy(
                flashMode = mode,
                ttlAutoEnabled = isTtl,
                remoteMode = if (isTtl) RemoteOperatingMode.AUTO_TTL else RemoteOperatingMode.MANUAL,
            )
        }
        dispatchCommand(address, PupOpcodes.FLASH_MODE) { seq ->
            PupFrameCodec.encodeFlashMode(seq, mode)
        }
    }

    fun setConnectOperatingMode(address: String, mode: RemoteOperatingMode) {
        val unit = unitsState.value[address] ?: return
        if (unit.archetype == DeviceArchetype.CONNECT_REMOTE) {
            statusBanner.value = "Connect operating mode is physically controlled via collar switch"
            return
        }
        val isTtl = (mode == RemoteOperatingMode.AUTO_TTL)
        mutateOptimistically(address) {
            it.copy(
                remoteMode = mode,
                ttlAutoEnabled = isTtl,
                flashMode = if (isTtl) FlashMode.TTL else FlashMode.MANUAL,
            )
        }
        dispatchCommand(address, PupOpcodes.REMOTE_MODE) { seq ->
            PupFrameCodec.encodeRemoteMode(seq, mode)
        }
    }

    fun setGroupTtlEvBias(address: String, group: AirGroup, tenthStops: Int) {
        if (group !in setOf(AirGroup.GROUP_A, AirGroup.GROUP_B, AirGroup.GROUP_C, AirGroup.ALL)) {
            statusBanner.value = "TTL EV bias only supported on Groups A..C and ALL"
            return
        }
        val clamped = tenthStops.coerceIn(-20, 20)
        mutateOptimistically(address) { cur ->
            val updatedMap = when (group) {
                AirGroup.ALL -> cur.groupTtlBiasesTenthStops + (AirGroup.ALL to clamped) + (AirGroup.GROUP_A to clamped)
                AirGroup.GROUP_A -> cur.groupTtlBiasesTenthStops + (AirGroup.GROUP_A to clamped) + (AirGroup.ALL to clamped)
                else -> cur.groupTtlBiasesTenthStops + (group to clamped)
            }
            val master = if (group == AirGroup.ALL || group == AirGroup.GROUP_A) clamped else cur.ttlEvCompTenthStops
            cur.copy(groupTtlBiasesTenthStops = updatedMap, ttlEvCompTenthStops = master)
        }
        val session = sessions[address] ?: return
        scope.launch {
            val okBias = session.fsm.sendCommand(PupOpcodes.AIR_PX_BIAS) { seq ->
                PupFrameCodec.encodeAirPxBias(seq, clamped, group)
            }
            val okComp = if (group == AirGroup.ALL) {
                session.fsm.sendCommand(PupOpcodes.AIR_TTL_EV_COMP) { seq ->
                    PupFrameCodec.encodeAirTtlEvComp(seq, clamped)
                }
            } else {
                true
            }
            val ok = okBias && okComp
            if (ok) {
                mutateOptimistically(address) { cur ->
                    val updatedMap = when (group) {
                        AirGroup.ALL -> cur.groupTtlBiasesTenthStops + (AirGroup.ALL to clamped) + (AirGroup.GROUP_A to clamped)
                        AirGroup.GROUP_A -> cur.groupTtlBiasesTenthStops + (AirGroup.GROUP_A to clamped) + (AirGroup.ALL to clamped)
                        else -> cur.groupTtlBiasesTenthStops + (group to clamped)
                    }
                    val master = if (group == AirGroup.ALL || group == AirGroup.GROUP_A) clamped else cur.ttlEvCompTenthStops
                    cur.copy(groupTtlBiasesTenthStops = updatedMap, ttlEvCompTenthStops = master)
                }
            } else {
                statusBanner.value = "BLE command timeout (TTL EV bias) — verify remote is in range"
            }
        }
    }

    fun setTtlEvCompensation(address: String, tenthStops: Int) {
        setGroupTtlEvBias(address, AirGroup.ALL, tenthStops)
    }

    fun setConnectProGroupPower(address: String, groupIndex: Int, tenthStops: Int) {
        val unit = unitsState.value[address] ?: return
        if (groupIndex !in 0..5) return
        val clamped = tenthStops.coerceIn(1, 100)
        val updated = unit.groupPowersTenthStops.toMutableList()
        updated[groupIndex] = clamped
        userDirectedGroupPowers.getOrPut(address) { ConcurrentHashMap() }[groupIndex] = clamped
        mutateOptimistically(address) { it.copy(groupPowersTenthStops = updated) }
        dispatchCommand(address, PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY) { seq ->
            PupFrameCodec.encodeAirAbsoluteGroupEnergy(seq, updated)
        }
    }

    fun setRemoteGroupEnabled(address: String, groupIndex: Int, enabled: Boolean) {
        val unit = unitsState.value[address] ?: return
        if (groupIndex !in 0..5) return
        val bit = 1 shl groupIndex
        val newMask = if (enabled) (unit.airHeadMask or bit) else (unit.airHeadMask and bit.inv())
        if (newMask == unit.airHeadMask) return
        mutateOptimistically(address) { it.copy(airHeadMask = newMask) }
        dispatchCommand(address, PupOpcodes.AIR_HEAD_ON) { seq ->
            PupFrameCodec.encodeAirHeadOnMask(seq, newMask)
        }
    }

    fun setRemoteGroupModeling(address: String, groupIndex: Int, enabled: Boolean) {
        val unit = unitsState.value[address] ?: return
        if (groupIndex !in 0..5) return
        val bit = 1 shl groupIndex
        val newMask = if (enabled) (unit.airModelLightMask or bit) else (unit.airModelLightMask and bit.inv())
        if (newMask == unit.airModelLightMask) return
        mutateOptimistically(address) { it.copy(airModelLightMask = newMask) }
        dispatchCommand(address, PupOpcodes.AIR_MODEL_LIGHT_ON) { seq ->
            PupFrameCodec.encodeAirModelLightOnMask(seq, newMask)
        }
    }

    fun toggleConnectProGroupMute(address: String, groupIndex: Int) {
        val unit = unitsState.value[address] ?: return
        if (groupIndex !in 0..5) return
        val curOn = (unit.airHeadMask and (1 shl groupIndex)) != 0
        setRemoteGroupEnabled(address, groupIndex, !curOn)
    }

    fun toggleConnectProGroupModeling(address: String, groupIndex: Int) {
        val unit = unitsState.value[address] ?: return
        if (groupIndex !in 0..5) return
        val curOn = (unit.airModelLightMask and (1 shl groupIndex)) != 0
        setRemoteGroupModeling(address, groupIndex, !curOn)
    }

    fun stepRemoteAirGroupTrim(address: String, group: AirGroup, deltaTenthStops: Int) {
        val unit = unitsState.value[address] ?: return
        if (unit.archetype == DeviceArchetype.CONNECT_REMOTE) {
            dispatchCommand(address, PupOpcodes.ADJUST_AIR_MX_ENERGY_IF_ABLE) { seq ->
                PupFrameCodec.encodeAdjustAirMxEnergyIfAble(seq, group, deltaTenthStops)
            }
        } else {
            if (group == AirGroup.ALL) {
                val clampedDelta = clampGroupDelta(unit.groupPowersTenthStops, 1, 100, deltaTenthStops)
                if (clampedDelta == 0) return
                val updated = unit.groupPowersTenthStops.map { (it + clampedDelta).coerceIn(1, 100) }
                val map = userDirectedGroupPowers.getOrPut(address) { ConcurrentHashMap() }
                updated.forEachIndexed { idx, p -> map[idx] = p }
                mutateOptimistically(address) { it.copy(groupPowersTenthStops = updated) }
            }
            dispatchCommand(address, PupOpcodes.AIR_ADJUST_ENERGY) { seq ->
                PupFrameCodec.encodeAirAdjustEnergy(seq, group, deltaTenthStops)
            }
        }
    }

    fun setBatteryChemistry(address: String, chemistry: BatteryChemistry) {
        mutateOptimistically(address) { it.copy(batteryChemistry = chemistry) }
        dispatchCommand(address, PupOpcodes.BATTERY_TYPE) { seq ->
            PupFrameCodec.encodeBatteryChemistry(seq, chemistry)
        }
    }

    fun setRemoteSyncMode(address: String, mode: SyncTriggerMode) {
        mutateOptimistically(address) { it.copy(syncMode = mode) }
        dispatchCommand(address, PupOpcodes.SYNC_MODE) { seq ->
            PupFrameCodec.encodeSyncMode(seq, mode)
        }
    }

    fun setRemoteAfAssist(address: String, mode: AfAssistMode) {
        mutateOptimistically(address) { it.copy(afAssistMode = mode) }
        dispatchCommand(address, PupOpcodes.AF_ASSIST_MODE) { seq ->
            PupFrameCodec.encodeAfAssistMode(seq, mode)
        }
    }

    fun setRemoteRfPower(address: String, level: RfPowerLevel) {
        mutateOptimistically(address) { it.copy(rfPowerLevel = level) }
        dispatchCommand(address, PupOpcodes.RMI_RF_POWER) { seq ->
            PupFrameCodec.encodeRfPower(seq, level)
        }
    }

    fun setRemoteSleepTimeout(address: String, minutes: Int) {
        val clamped = minutes.coerceIn(0, 255)
        mutateOptimistically(address) { it.copy(autoOffMinutes = clamped) }
        val session = sessions[address] ?: return
        scope.launch {
            val ok = session.fsm.sendCommand(PupOpcodes.AUTO_OFF_TIMEOUT) { seq ->
                PupFrameCodec.encodeAutoOffTimeout(seq, clamped)
            } || session.fsm.sendCommand(PupOpcodes.AUTO_STANDBY_TIMEOUT) { seq ->
                PupFrameCodec.encodeAutoStandbyTimeout(seq, clamped)
            }
            if (!ok) statusBanner.value = "BLE command timeout (sleep timeout)"
        }
    }

    fun setRemoteBrightness(address: String, brightness: Int) {
        val clamped = brightness.coerceIn(0, 3)
        val level = DisplayBrightnessLevel.fromCode(clamped)
        mutateOptimistically(address) { it.copy(displayBrightness = level) }
        dispatchCommand(address, PupOpcodes.DISPLAY_BRIGHTNESS) { seq ->
            PupFrameCodec.encodeDisplayBrightness(seq, clamped)
        }
    }

    fun setRemoteBatteryType(address: String, batteryType: Int) {
        val chem = BatteryChemistry.fromCode(batteryType)
        mutateOptimistically(address) { it.copy(batteryChemistry = chem) }
        dispatchCommand(address, PupOpcodes.BATTERY_TYPE) { seq ->
            PupFrameCodec.encodeBatteryType(seq, batteryType)
        }
    }

    fun setRemoteSounds(address: String, enabled: Boolean) {
        mutateOptimistically(address) { it.copy(controlSoundsEnabled = enabled) }
        dispatchCommand(address, PupOpcodes.CONTROL_SOUNDS_ENABLE) { seq ->
            PupFrameCodec.encodeControlSounds(seq, enabled)
        }
    }

    fun setMonolightZoom(address: String, mode: MonolightZoomMode, position: Int? = null) {
        mutateOptimistically(address) {
            it.copy(zoomMode = mode, zoomValue = position ?: it.zoomValue)
        }
        val session = sessions[address] ?: return
        scope.launch {
            val ok = session.fsm.sendCommand(PupOpcodes.ZOOM_MODE) { seq ->
                PupFrameCodec.encodeZoomMode(seq, mode)
            }
            if (ok && position != null) {
                session.fsm.sendCommand(PupOpcodes.ZOOM_VALUE) { seq ->
                    PupFrameCodec.encodeZoomValue(seq, position)
                }
            }
            if (!ok) {
                statusBanner.value = "BLE command timeout (zoom)"
            }
        }
    }

    fun setReadySignalMode(address: String, mode: ReadySignalMode) {
        val soundOn = (mode == ReadySignalMode.SOUND || mode == ReadySignalMode.SOUND_AND_DIM)
        val dimOn = (mode == ReadySignalMode.DIM || mode == ReadySignalMode.SOUND_AND_DIM)
        mutateOptimistically(address) {
            it.copy(
                readySignalMode = mode,
                readySoundEnabled = soundOn,
                dimDuringRecharge = dimOn,
            )
        }
        val session = sessions[address] ?: return
        scope.launch {
            val ok = session.fsm.sendCommand(PupOpcodes.READY_SIGNAL_MODE) { seq ->
                PupFrameCodec.encodeReadySignalMode(seq, mode)
            } || run {
                session.fsm.sendCommand(PupOpcodes.SOUND_ENABLE_READY) { seq ->
                    PupFrameCodec.encodeReadySound(seq, soundOn)
                }
                session.fsm.sendCommand(PupOpcodes.DIM_DURING_RECHARGE) { seq ->
                    PupFrameCodec.encodeDimDuringRecharge(seq, dimOn)
                }
            }
            if (!ok) {
                statusBanner.value = "BLE command timeout (ready signal)"
            }
        }
    }

    // Studio Preferences & Master Triggers
    fun setGroupRole(group: AirGroup, role: String?) {
        scope.launch { preferencesStore.setGroupRole(group, role) }
    }

    fun setDevicePinned(address: String, pinned: Boolean) {
        scope.launch { preferencesStore.setDevicePinned(address, pinned) }
    }

    fun setVolumeKeyTestFlashEnabled(enabled: Boolean) {
        scope.launch { preferencesStore.setVolumeKeyTestFlashEnabled(enabled) }
    }

    fun setAutoReconnectPinnedEnabled(enabled: Boolean) {
        scope.launch { preferencesStore.setAutoReconnectPinnedEnabled(enabled) }
    }

    fun setDualPowerReadoutEnabled(enabled: Boolean) {
        scope.launch { preferencesStore.setDualPowerReadoutEnabled(enabled) }
    }

    fun setBroadBleScanEnabled(enabled: Boolean) {
        scope.launch { preferencesStore.setBroadBleScanEnabled(enabled) }
    }

    val savedScenes: StateFlow<List<StudioScene>> = preferencesStore.savedScenes

    fun saveScene(scene: StudioScene) {
        scope.launch { preferencesStore.saveScene(scene) }
    }

    fun deleteScene(sceneId: String) {
        scope.launch { preferencesStore.deleteScene(sceneId) }
    }

    fun importScenes(scenes: List<StudioScene>) {
        scope.launch { preferencesStore.importScenes(scenes) }
    }

    fun triggerMasterTestFlash() {
        triggerStudioMasterTestFlash()
    }

    fun applyScene(scene: StudioScene) {
        scope.launch {
            if (scene.remoteChannel != null) {
                unitsState.value.values.filter { it.isReady }.forEach { unit ->
                    setAirChannel(unit.address, scene.remoteChannel)
                }
            }
            if (scene.remoteOperatingMode != null) {
                unitsState.value.values.filter {
                    it.isReady && it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE
                }.forEach { remote ->
                    setConnectOperatingMode(remote.address, scene.remoteOperatingMode)
                }
            }
            if (scene.syncTriggerMode != null) {
                unitsState.value.values.filter {
                    it.isReady && (it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE || it.archetype == DeviceArchetype.CONNECT_REMOTE)
                }.forEach { remote ->
                    setRemoteSyncMode(remote.address, scene.syncTriggerMode)
                }
            }
            if (scene.afAssistMode != null) {
                unitsState.value.values.filter {
                    it.isReady && (it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE || it.archetype == DeviceArchetype.CONNECT_REMOTE)
                }.forEach { remote ->
                    setRemoteAfAssist(remote.address, scene.afAssistMode)
                }
            }
            if (scene.rfPowerLevel != null) {
                unitsState.value.values.filter {
                    it.isReady && (it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE || it.archetype == DeviceArchetype.CONNECT_REMOTE)
                }.forEach { remote ->
                    setRemoteRfPower(remote.address, scene.rfPowerLevel)
                }
            }
            if (scene.readySignalMode != null) {
                unitsState.value.values.filter {
                    it.isReady && (it.archetype == DeviceArchetype.MONOLIGHT || it.archetype == DeviceArchetype.COMPACT_FLASH)
                }.forEach { strobe ->
                    setReadySignalMode(strobe.address, scene.readySignalMode)
                }
            }
            for (preset in scene.groupPresets) {
                setStudioGroupPowerTenthStops(preset.group, preset.powerTenthStops)
                if (preset.roleLabel != null) {
                    setGroupRole(preset.group, preset.roleLabel)
                }
                val idx = studioGroups.indexOf(preset.group)
                if (idx in 0..5) {
                    val allReady = recomputeChannelCoexistence(unitsState.value.values.toList()).filter { it.isReady }
                    allReady.filter { it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE }.forEach { remote ->
                        setRemoteGroupEnabled(remote.address, idx, preset.isEnabled)
                        setRemoteGroupModeling(remote.address, idx, preset.isModelingEnabled)
                        if (preset.ttlEvBiasTenthStops != null && preset.group in setOf(AirGroup.GROUP_A, AirGroup.GROUP_B, AirGroup.GROUP_C)) {
                            setGroupTtlEvBias(remote.address, preset.group, preset.ttlEvBiasTenthStops)
                        }
                    }
                    allReady.filter { it.archetype == DeviceArchetype.MONOLIGHT && it.airGroup == preset.group }.forEach { strobe ->
                        setHeadEnabled(strobe.address, preset.isEnabled)
                        setVideoLightEnabled(strobe.address, preset.isModelingEnabled)
                    }
                }
            }
            statusBanner.value = "Applied preset: ${scene.name}"
        }
    }

    /**
     * Multi-fixture paced Master/Group Trim across directly connected strobes (B10X Plus + A2),
     * enforcing 30ms inter-device BLE pacing and mixed-head f-stop boundary clamping while
     * avoiding duplicate commands on channels already driven by a BLE-connected Connect Pro remote.
     */
    fun trimStudioGroupFixtures(group: AirGroup, deltaTenthStops: Int) {
        scope.launch {
            val allReady = recomputeChannelCoexistence(unitsState.value.values.toList())
                .filter { it.isReady }

            // 1. Remotes matching active group
            val remotes = allReady.filter {
                it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE ||
                    it.archetype == DeviceArchetype.CONNECT_REMOTE
            }
            remotes.forEachIndexed { idx, remote ->
                if (idx > 0 && groupPacingMs > 0) delay(groupPacingMs)
                stepRemoteAirGroupTrim(remote.address, group, deltaTenthStops)
            }

            // 2. Standalone BLE monolights not already driven over AirX by one of the connected remotes above
            val standaloneStrobes = allReady.filter { unit ->
                unit.archetype == DeviceArchetype.MONOLIGHT &&
                    !unit.isSharedWithActiveRemoteChannel &&
                    (group == AirGroup.ALL || unit.airGroup == group)
            }
            if (standaloneStrobes.isEmpty()) return@launch

            val allowedDelta = standaloneStrobes.fold(deltaTenthStops) { acc, unit ->
                if (acc > 0) {
                    minOf(acc, unit.maxFlashTenthStops - unit.flashPowerTenthStops)
                } else {
                    maxOf(acc, unit.minFlashTenthStops - unit.flashPowerTenthStops)
                }
            }
            if (allowedDelta == 0) return@launch

            standaloneStrobes.forEachIndexed { idx, unit ->
                if ((idx > 0 || remotes.isNotEmpty()) && groupPacingMs > 0) {
                    delay(groupPacingMs)
                }
                val target = (unit.flashPowerTenthStops + allowedDelta)
                    .coerceIn(unit.minFlashTenthStops, unit.maxFlashTenthStops)
                mutateOptimistically(unit.address) { it.copy(flashPowerTenthStops = target) }
                sendMonolightFlashPower(unit, target)
            }
        }
    }

    /**
     * Sets absolute tenth-stop power (10..100 -> 1.0..10.0 f-stops) for a specific Group fader (A..F).
     * Updates UI state immediately at 0ms and conflates rapid drag writes over BLE.
     */
    fun setStudioGroupPowerTenthStops(group: AirGroup, tenthStops: Int) {
        val idx = studioGroups.indexOf(group)
        if (idx !in 0..5) return
        val clamped = tenthStops.coerceIn(1, 100)

        val prevFallback = connectFallbackGroupLevels.value.getOrElse(idx) { 70 }
        val delta = clamped - prevFallback
        connectFallbackGroupLevels.update { list ->
            list.toMutableList().also { it[idx] = clamped }
        }

        val allReady = recomputeChannelCoexistence(unitsState.value.values.toList())
            .filter { it.isReady }
        val connectPros = allReady.filter { it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE }
        val standaloneStrobes = allReady.filter { unit ->
            unit.archetype == DeviceArchetype.MONOLIGHT &&
                !unit.isSharedWithActiveRemoteChannel &&
                unit.airGroup == group
        }
        val connectTriggers = allReady.filter { it.archetype == DeviceArchetype.CONNECT_REMOTE }

        // Immediate optimistic UI projection across Connect Pro & group monolights
        connectPros.forEach { remote ->
            val updated = remote.groupPowersTenthStops.toMutableList().also {
                it[idx] = clamped
            }
            userDirectedGroupPowers.getOrPut(remote.address) { ConcurrentHashMap() }[idx] = clamped
            mutateOptimistically(remote.address) { it.copy(groupPowersTenthStops = updated) }
        }
        standaloneStrobes.forEach { strobe ->
            val target = clamped.coerceIn(strobe.minFlashTenthStops, strobe.maxFlashTenthStops)
            mutateOptimistically(strobe.address) { it.copy(flashPowerTenthStops = target) }
        }

        // Conflated BLE dispatch per group channel so smooth vertical dragging never queues stale GATT frames
        groupWriteJobs[idx]?.cancel()
        groupWriteJobs[idx] = scope.launch {
            if (groupPacingMs > 0) delay(groupPacingMs)

            connectPros.forEachIndexed { rIdx, remote ->
                if (rIdx > 0 && groupPacingMs > 0) delay(groupPacingMs)
                val latestPowers = unitsState.value[remote.address]?.groupPowersTenthStops
                    ?: remote.groupPowersTenthStops
                sessions[remote.address]?.fsm?.sendCommand(PupOpcodes.AIR_ABSOLUTE_GROUP_ENERGY) { seq ->
                    PupFrameCodec.encodeAirAbsoluteGroupEnergy(seq, latestPowers)
                }
            }

            standaloneStrobes.forEachIndexed { sIdx, strobe ->
                if ((sIdx > 0 || connectPros.isNotEmpty()) && groupPacingMs > 0) {
                    delay(groupPacingMs)
                }
                val target = clamped.coerceIn(strobe.minFlashTenthStops, strobe.maxFlashTenthStops)
                sendMonolightFlashPower(strobe, target)
            }

            if (connectPros.isEmpty() && connectTriggers.isNotEmpty() && delta != 0) {
                connectTriggers.forEach { trigger ->
                    sessions[trigger.address]?.fsm?.sendCommand(PupOpcodes.ADJUST_AIR_MX_ENERGY_IF_ABLE) { seq ->
                        PupFrameCodec.encodeAdjustAirMxEnergyIfAble(seq, group, delta)
                    }
                }
            }
        }
    }

    /**
     * Toggles Group ON / MUTE across Connect Pro, Connect, and standalone BLE monolights in [group].
     */
    fun toggleStudioGroupMute(group: AirGroup) {
        val idx = studioGroups.indexOf(group)
        if (idx !in 0..5) return

        val allReady = recomputeChannelCoexistence(unitsState.value.values.toList())
            .filter { it.isReady }
        val airRemotes = allReady.filter {
            it.remoteMode != RemoteOperatingMode.OFF && (
                it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE ||
                    it.archetype == DeviceArchetype.CONNECT_REMOTE
                )
        }
        val standaloneStrobes = allReady.filter { unit ->
            unit.archetype == DeviceArchetype.MONOLIGHT &&
                !unit.isSharedWithActiveRemoteChannel &&
                unit.airGroup == group
        }

        val currentlyEnabled = when {
            standaloneStrobes.isNotEmpty() -> standaloneStrobes.any { it.headEnabled }
            airRemotes.isNotEmpty() -> airRemotes.any { (it.airHeadMask and (1 shl idx)) != 0 }
            else -> false
        }
        val nextEnabled = !currentlyEnabled

        airRemotes.forEach { remote ->
            setRemoteGroupEnabled(remote.address, idx, nextEnabled)
        }
        standaloneStrobes.forEach { strobe ->
            setHeadEnabled(strobe.address, nextEnabled)
        }
    }

    /**
     * Toggles Group Modeling Light across Connect Pro, Connect, and standalone BLE monolights in [group].
     */
    fun toggleStudioGroupModeling(group: AirGroup) {
        val idx = studioGroups.indexOf(group)
        if (idx !in 0..5) return

        val allReady = recomputeChannelCoexistence(unitsState.value.values.toList())
            .filter { it.isReady }
        val airRemotes = allReady.filter {
            it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE ||
                it.archetype == DeviceArchetype.CONNECT_REMOTE
        }
        val standaloneStrobes = allReady.filter { unit ->
            unit.archetype == DeviceArchetype.MONOLIGHT &&
                !unit.isSharedWithActiveRemoteChannel &&
                unit.airGroup == group
        }
        val groupHeads = allReady.filter { it.archetype == DeviceArchetype.MONOLIGHT && it.airGroup == group }

        val currentlyModeling = groupHeads.any { it.videoLightEnabled } ||
            airRemotes.any { (it.airModelLightMask and (1 shl idx)) != 0 }
        val nextModeling = !currentlyModeling

        airRemotes.forEach { remote ->
            setRemoteGroupModeling(remote.address, idx, nextModeling)
        }
        standaloneStrobes.forEach { strobe ->
            setVideoLightEnabled(strobe.address, nextModeling)
        }
    }

    /**
     * Master toggle for all connected continuous/modeling lights and Air remotes in the studio.
     */
    fun setAllContinuousLightsEnabled(enabled: Boolean) {
        val allReady = recomputeChannelCoexistence(unitsState.value.values.toList())
            .filter { it.isReady }
        val airRemotes = allReady.filter {
            it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE ||
                it.archetype == DeviceArchetype.CONNECT_REMOTE
        }
        airRemotes.forEach { remote ->
            val newMask = if (enabled) (remote.airHeadMask and 0x3F) else 0x00
            mutateOptimistically(remote.address) { it.copy(airModelLightMask = newMask) }
            dispatchCommand(remote.address, PupOpcodes.AIR_MODEL_LIGHT_ON) { seq ->
                PupFrameCodec.encodeAirModelLightOnMask(seq, newMask)
            }
        }
        allReady.filter {
            it.archetype == DeviceArchetype.MONOLIGHT ||
                it.archetype == DeviceArchetype.COMPACT_FLASH ||
                it.archetype == DeviceArchetype.CINEMA_LED
        }.forEach { light ->
            setVideoLightEnabled(light.address, enabled)
        }
    }

    private fun buildGroupChannelStates(
        units: List<ConnectedProfotoUnit>,
        fallbackLevels: List<Int>,
        roles: Map<AirGroup, String> = emptyMap(),
    ): List<StudioGroupChannelState> {
        val ready = units.filter { it.isReady }
        val connectPro = ready.firstOrNull {
            it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE && it.remoteMode != RemoteOperatingMode.OFF
        }
        val connectTrigger = ready.firstOrNull {
            it.archetype == DeviceArchetype.CONNECT_REMOTE && it.remoteMode != RemoteOperatingMode.OFF
        }
        val activeRemote = connectPro ?: connectTrigger

        return studioGroups.mapIndexed { idx, grp ->
            val heads = ready.filter { it.archetype == DeviceArchetype.MONOLIGHT && it.airGroup == grp }
            val memberCount = heads.size +
                (if (connectPro != null) 1 else 0) +
                (if (connectTrigger != null) 1 else 0)
            val hasHardware = memberCount > 0

            val tenths = when {
                connectPro != null && (heads.isEmpty() || heads.all { it.isSharedWithActiveRemoteChannel }) ->
                    connectPro.groupPowersTenthStops.getOrElse(idx) { 70 }
                heads.isNotEmpty() -> heads.first().flashPowerTenthStops
                else -> fallbackLevels.getOrElse(idx) { 70 }
            }

            val isEnabled = when {
                heads.any { !it.isSharedWithActiveRemoteChannel } ->
                    heads.filter { !it.isSharedWithActiveRemoteChannel }.any { it.headEnabled }
                connectPro != null -> (connectPro.airHeadMask and (1 shl idx)) != 0
                connectTrigger != null -> (connectTrigger.airHeadMask and (1 shl idx)) != 0
                heads.isNotEmpty() -> heads.any { it.headEnabled }
                else -> false
            }

            val isModelingEnabled = heads.any { it.videoLightEnabled } ||
                (activeRemote != null && (activeRemote.airModelLightMask and (1 shl idx)) != 0)

            val ttlBias = connectPro?.groupTtlBiasesTenthStops?.get(grp) ?: 0

            StudioGroupChannelState(
                group = grp,
                groupIndex = idx,
                hasActiveHardware = hasHardware,
                isEnabled = isEnabled,
                isModelingEnabled = isModelingEnabled,
                powerTenthStops = tenths,
                memberCount = memberCount,
                directStrobeCount = heads.size,
                memberNames = heads.map { it.displayTitle },
                roleLabel = roles[grp],
                ttlBiasTenthStops = ttlBias,
            )
        }
    }

    // =========================================================================
    // Incoming Telemetry & AllSettingsV2 State Hydration
    // =========================================================================

    private fun applyIncomingMessage(address: String, msg: PupDecodedMessage) {
        if (msg is PupDecodedMessage.BulkSettingsDump) {
            msg.result.entries.forEach { entry ->
                entry.decoded?.let { applyIncomingMessage(address, it) }
            }
            return
        }

        unitsState.update { map ->
            val cur = map[address] ?: return@update map
            val next = when (msg) {
                is PupDecodedMessage.HardwareProductId -> {
                    val spec = HardwareCatalog.resolve(msg.productId, cur.device.advertisedName)
                    cur.copy(
                        productId = msg.productId,
                        modelSpec = spec,
                        hasVideoDimming = cur.hasVideoDimming || spec.supportsVideoDimming,
                        hasBiColorCct = cur.hasBiColorCct || spec.supportsBiColorCct,
                        maxAirChannel = if (cur.maxAirChannel == 100) spec.maxAirChannelDefault else cur.maxAirChannel,
                    )
                }
                is PupDecodedMessage.FirmwareDisplayVersion -> cur.copy(firmwareVersion = msg.version)
                is PupDecodedMessage.SerialNumberString -> cur.copy(serialNumber = msg.serial)
                is PupDecodedMessage.BleLocalName -> {
                    cur.copy(bleLocalName = msg.name.takeIf { it.isNotBlank() })
                }
                is PupDecodedMessage.FlashPower -> cur.copy(flashPowerTenthStops = msg.tenthStops)
                is PupDecodedMessage.FlashPowerRange -> cur.copy(
                    minFlashTenthStops = msg.minTenthStops.coerceAtLeast(1),
                    maxFlashTenthStops = msg.maxTenthStops.coerceAtMost(100),
                )
                is PupDecodedMessage.FlashPowerV2 -> cur.copy(
                    usesV2HeadOpcodes = true,
                    primaryHeadId = msg.headId,
                    flashPowerTenthStops = msg.tenthStops,
                )
                is PupDecodedMessage.FlashPowerConstraintV2 -> cur.copy(
                    usesV2HeadOpcodes = true,
                    primaryHeadId = msg.headId,
                    minFlashTenthStops = msg.minTenthStops.coerceAtLeast(1),
                    maxFlashTenthStops = msg.maxTenthStops.coerceAtMost(100),
                )
                is PupDecodedMessage.HeadPowerState -> cur.copy(headEnabled = msg.enabled)
                is PupDecodedMessage.HeadPowerStateV2 -> cur.copy(
                    usesV2HeadOpcodes = true,
                    primaryHeadId = msg.headId,
                    headEnabled = msg.enabled,
                )
                is PupDecodedMessage.FreezeState -> cur.copy(freezeEnabled = msg.enabled)
                is PupDecodedMessage.CaptureModeState -> cur.copy(captureVideoPriority = msg.isVideoPriority)
                is PupDecodedMessage.IrSlaveState -> cur.copy(irSlaveEnabled = msg.enabled)
                is PupDecodedMessage.ReadySoundState -> {
                    val dim = cur.dimDuringRecharge
                    val mode = when {
                        msg.enabled && dim -> ReadySignalMode.SOUND_AND_DIM
                        msg.enabled -> ReadySignalMode.SOUND
                        dim -> ReadySignalMode.DIM
                        else -> ReadySignalMode.OFF
                    }
                    cur.copy(readySoundEnabled = msg.enabled, readySignalMode = mode)
                }
                is PupDecodedMessage.DimDuringRecharge -> {
                    val sound = cur.readySoundEnabled
                    val mode = when {
                        sound && msg.enabled -> ReadySignalMode.SOUND_AND_DIM
                        sound -> ReadySignalMode.SOUND
                        msg.enabled -> ReadySignalMode.DIM
                        else -> ReadySignalMode.OFF
                    }
                    cur.copy(dimDuringRecharge = msg.enabled, readySignalMode = mode)
                }
                is PupDecodedMessage.VideoLightState -> cur.copy(videoLightEnabled = msg.enabled)
                is PupDecodedMessage.VideoLightStateV2 -> cur.copy(videoLightEnabled = msg.enabled)
                is PupDecodedMessage.VideoLightMode -> cur.copy(videoLightMode = msg.mode)
                is PupDecodedMessage.VideoLightBrightness -> cur.copy(
                    hasVideoDimming = true,
                    videoLightIntensity = msg.intensity,
                )
                is PupDecodedMessage.VideoLightBrightnessRange -> cur.copy(
                    hasVideoDimming = true,
                    minVideoIntensity = msg.minIntensity,
                    maxVideoIntensity = msg.maxIntensity.coerceAtLeast(msg.minIntensity + 1),
                )
                is PupDecodedMessage.VideoLightCct -> cur.copy(
                    hasBiColorCct = true,
                    videoLightKelvin = msg.kelvin,
                )
                is PupDecodedMessage.VideoLightCctRange -> cur.copy(
                    hasBiColorCct = true,
                    minKelvin = msg.minKelvin,
                    maxKelvin = msg.maxKelvin.coerceAtLeast(msg.minKelvin + 100),
                )
                is PupDecodedMessage.AirSyncState -> cur.copy(airSyncEnabled = msg.enabled)
                is PupDecodedMessage.AirGroupAssignment -> cur.copy(airGroup = msg.group)
                is PupDecodedMessage.AirGroupAssignmentV2 -> cur.copy(
                    usesV2HeadOpcodes = true,
                    primaryHeadId = msg.headId,
                    airGroup = msg.group,
                )
                is PupDecodedMessage.AirChannelAssignment -> cur.copy(
                    airChannel = msg.channel.coerceIn(cur.minAirChannel, cur.maxAirChannel.coerceAtLeast(cur.minAirChannel)),
                )
                is PupDecodedMessage.AirChannelBounds -> {
                    val safeMin = msg.minChannel.coerceIn(1, 100)
                    val safeMax = msg.maxChannel.coerceIn(safeMin, 100)
                    cur.copy(minAirChannel = safeMin, maxAirChannel = safeMax)
                }
                is PupDecodedMessage.AirProtocol -> cur.copy(airProtocolMode = msg.mode)
                is PupDecodedMessage.StrobeFlashMode -> {
                    val isTtl = (msg.mode == FlashMode.TTL)
                    cur.copy(
                        flashMode = msg.mode,
                        ttlAutoEnabled = isTtl,
                        remoteMode = if (isTtl) RemoteOperatingMode.AUTO_TTL else RemoteOperatingMode.MANUAL,
                    )
                }
                is PupDecodedMessage.RemoteModeState -> {
                    val isTtl = (msg.mode == RemoteOperatingMode.AUTO_TTL)
                    cur.copy(
                        remoteMode = msg.mode,
                        ttlAutoEnabled = isTtl,
                        flashMode = if (isTtl) FlashMode.TTL else FlashMode.MANUAL,
                    )
                }
                is PupDecodedMessage.TtlAutoState -> {
                    cur.copy(
                        ttlAutoEnabled = msg.enabled,
                        remoteMode = if (msg.enabled) RemoteOperatingMode.AUTO_TTL else RemoteOperatingMode.MANUAL,
                        flashMode = if (msg.enabled) FlashMode.TTL else FlashMode.MANUAL,
                    )
                }
                is PupDecodedMessage.AirGroupTtlBias -> {
                    val updatedBiases = when (msg.group) {
                        AirGroup.ALL -> cur.groupTtlBiasesTenthStops + (AirGroup.ALL to msg.tenthStops) + (AirGroup.GROUP_A to msg.tenthStops)
                        AirGroup.GROUP_A -> cur.groupTtlBiasesTenthStops + (AirGroup.GROUP_A to msg.tenthStops) + (AirGroup.ALL to msg.tenthStops)
                        else -> cur.groupTtlBiasesTenthStops + (msg.group to msg.tenthStops)
                    }
                    val master = if (msg.group == AirGroup.ALL || msg.group == AirGroup.GROUP_A) msg.tenthStops else cur.ttlEvCompTenthStops
                    cur.copy(groupTtlBiasesTenthStops = updatedBiases, ttlEvCompTenthStops = master)
                }
                is PupDecodedMessage.AirTtlEvCompensation -> cur.copy(
                    ttlEvCompTenthStops = msg.tenthStops,
                    groupTtlBiasesTenthStops = cur.groupTtlBiasesTenthStops + (AirGroup.ALL to msg.tenthStops) + (AirGroup.GROUP_A to msg.tenthStops),
                )
                is PupDecodedMessage.RemoteSyncMode -> cur.copy(syncMode = msg.mode)
                is PupDecodedMessage.RemoteAfAssist -> cur.copy(afAssistMode = msg.mode)
                is PupDecodedMessage.RemoteRfPower -> cur.copy(rfPowerLevel = msg.level)
                is PupDecodedMessage.RfPowerRange -> cur.copy(rfPowerRange = msg.minDbm..msg.maxDbm)
                is PupDecodedMessage.MonolightZoomState -> cur.copy(
                    zoomMode = msg.mode,
                    zoomValue = msg.value ?: cur.zoomValue,
                    zoomRange = msg.range ?: cur.zoomRange,
                )
                is PupDecodedMessage.ReadySignalState -> {
                    val soundOn = (msg.mode == ReadySignalMode.SOUND || msg.mode == ReadySignalMode.SOUND_AND_DIM)
                    val dimOn = (msg.mode == ReadySignalMode.DIM || msg.mode == ReadySignalMode.SOUND_AND_DIM)
                    cur.copy(
                        readySignalMode = msg.mode,
                        readySoundEnabled = soundOn,
                        dimDuringRecharge = dimOn,
                    )
                }
                is PupDecodedMessage.AutoOffMinutes -> cur.copy(autoOffMinutes = msg.minutes)
                is PupDecodedMessage.AutoStandbyMinutes -> cur.copy(autoStandbyMinutes = msg.minutes)
                is PupDecodedMessage.ControlSoundsState -> cur.copy(controlSoundsEnabled = msg.enabled)
                is PupDecodedMessage.AirHeadMask -> cur.copy(airHeadMask = msg.mask)
                is PupDecodedMessage.AirModelLightMask -> cur.copy(airModelLightMask = msg.mask)
                is PupDecodedMessage.AirAbsoluteGroupPower -> {
                    val userPowers = userDirectedGroupPowers[address]
                    val merged = if (userPowers.isNullOrEmpty()) {
                        msg.groupTenthStops
                    } else {
                        msg.groupTenthStops.mapIndexed { idx, p ->
                            userPowers[idx] ?: p
                        }
                    }
                    cur.copy(groupPowersTenthStops = merged)
                }
                is PupDecodedMessage.AirGroupEnergyFeedback -> cur.copy(
                    groupPowersTenthStops = msg.currentByGroup.take(6).map { v ->
                        if (v in 1..100) v else 50
                    }
                )
                is PupDecodedMessage.DisplayBrightness -> cur.copy(displayBrightness = msg.level)
                is PupDecodedMessage.DisplayOrientation -> cur.copy(displayOrientation = msg.orientation)
                is PupDecodedMessage.BatteryStatus -> cur.copy(batteryBars = msg.bars)
                is PupDecodedMessage.ChargerStatus -> cur.copy(chargerPluggedIn = msg.pluggedIn)
                is PupDecodedMessage.BatteryTypeSetting -> cur.copy(batteryChemistry = msg.chemistry)
                is PupDecodedMessage.FlashRecycleEvent -> {
                    val warn = if (msg.state == FlashSchedulingState.SCHEDULED_TOO_SOON) {
                        "Recycle active — trigger queued/skipped"
                    } else if (msg.state == FlashSchedulingState.INTERNAL_FLASH_ERROR) {
                        "Hardware capacitor fault reported"
                    } else {
                        null
                    }
                    cur.copy(recycleRemainingMs = msg.rechargeTimeMs, lastWarningMessage = warn)
                }
                is PupDecodedMessage.FlashFiredResult -> {
                    cur.copy(lastWarningMessage = msg.error?.description)
                }
                else -> cur
            }
            map + (address to next)
        }
    }

    private fun recomputeChannelCoexistence(units: List<ConnectedProfotoUnit>): List<ConnectedProfotoUnit> {
        val activeRemoteChannels = units
            .filter {
                it.isReady &&
                    it.remoteMode != RemoteOperatingMode.OFF && (
                        it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE ||
                            it.archetype == DeviceArchetype.CONNECT_REMOTE
                        )
            }
            .map { it.airChannel }
            .toSet()

        return units.map { u ->
            val shared = u.isReady &&
                u.archetype == DeviceArchetype.MONOLIGHT &&
                u.airSyncEnabled &&
                u.airChannel in activeRemoteChannels
            if (u.isSharedWithActiveRemoteChannel == shared) u else u.copy(isSharedWithActiveRemoteChannel = shared)
        }
    }

    private fun clampGroupDelta(values: List<Int>, min: Int, max: Int, delta: Int): Int {
        return values.fold(delta) { acc, cur ->
            if (acc > 0) minOf(acc, max - cur) else maxOf(acc, min - cur)
        }
    }

    private fun mutateOptimistically(
        address: String,
        transform: (ConnectedProfotoUnit) -> ConnectedProfotoUnit,
    ) {
        unitsState.update { map ->
            val cur = map[address] ?: return@update map
            map + (address to transform(cur))
        }
    }

    private suspend fun sendMonolightFlashPower(unit: ConnectedProfotoUnit, clamped: Int): Boolean {
        val fsm = sessions[unit.address]?.fsm ?: return false
        return if (unit.usesV2HeadOpcodes) {
            fsm.sendCommand(PupOpcodes.MX_ENERGY_V2) { seq ->
                PupFrameCodec.encodeFlashPowerV2(seq, unit.primaryHeadId, clamped)
            }
        } else {
            fsm.sendCommand(PupOpcodes.MX_ENERGY) { seq ->
                PupFrameCodec.encodeFlashPower(seq, clamped)
            } || fsm.sendCommand(PupOpcodes.MX_ENERGY_V2) { seq ->
                PupFrameCodec.encodeFlashPowerV2(seq, unit.primaryHeadId, clamped)
            }.also { ok ->
                if (ok) mutateOptimistically(unit.address) { it.copy(usesV2HeadOpcodes = true) }
            }
        }
    }

    private fun dispatchCommand(
        address: String,
        opcode: Int,
        builder: (seq: Int) -> ByteArray,
    ) {
        val session = sessions[address] ?: return
        scope.launch {
            val ok = session.fsm.sendCommand(opcode, builder)
            if (!ok) {
                statusBanner.value = "BLE command timeout (opcode $opcode) — verify light is in range"
            }
        }
    }

    private fun syncForegroundServiceNotification() {
        val ctx = appContext ?: return
        val readyCount = unitsState.value.values.count { it.isReady }
        ProfotoBleForegroundService.updateActiveLinkCount(ctx, readyCount)
    }
}
