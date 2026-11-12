package app.prft.lightctrl.domain

import app.prft.lightctrl.ble.BleConnectionStage
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.AirProtocolMode
import app.prft.lightctrl.protocol.BatteryChemistry
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.DisplayOrientationMode
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.HardwareCatalog
import app.prft.lightctrl.protocol.HardwareModelSpec
import app.prft.lightctrl.protocol.MonolightZoomMode
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import app.prft.lightctrl.protocol.VideoLightPowerMode

/**
 * Immutable reactive state for a single Profoto fixture or remote session.
 */
data class ConnectedProfotoUnit(
    val device: DiscoveredProfotoDevice,
    val customAlias: String? = null,
    val bleLocalName: String? = null,
    val stage: BleConnectionStage = BleConnectionStage.Disconnected,
    val productId: Int = device.advertisedProductId,
    val modelSpec: HardwareModelSpec = HardwareCatalog.resolve(device.advertisedProductId, device.advertisedName),
    val firmwareVersion: String? = null,
    val serialNumber: String? = null,

    // Air Radio
    val airSyncEnabled: Boolean = true,
    val airChannel: Int = 1,
    val minAirChannel: Int = 1,
    val maxAirChannel: Int = modelSpec.maxAirChannelDefault,
    val airGroup: AirGroup = AirGroup.GROUP_A,

    // Monolight Flash & Speed (B10X Plus, B10X, B10, A2)
    val usesV2HeadOpcodes: Boolean = false,
    val primaryHeadId: Int = 0,
    val flashPowerTenthStops: Int = 70, // 7.0 stops
    val minFlashTenthStops: Int = 10,   // 1.0 stops
    val maxFlashTenthStops: Int = 100,  // 10.0 stops
    val headEnabled: Boolean = true,
    val freezeEnabled: Boolean = false,
    val captureVideoPriority: Boolean = false,
    val irSlaveEnabled: Boolean = false,
    val readySoundEnabled: Boolean = true,
    val dimDuringRecharge: Boolean = true,

    // Continuous / Modeling Light (B10X Plus 3250lm, B10X, A2)
    val videoLightEnabled: Boolean = false,
    val videoLightMode: VideoLightPowerMode = VideoLightPowerMode.FREE,
    val hasVideoDimming: Boolean = modelSpec.supportsVideoDimming,
    val hasBiColorCct: Boolean = modelSpec.supportsBiColorCct,
    val videoLightIntensity: Int = 500,
    val minVideoIntensity: Int = 10,
    val maxVideoIntensity: Int = 1000,
    val videoLightKelvin: Int = 5200,
    val minKelvin: Int = 3000,
    val maxKelvin: Int = 6500,

    // Connect Pro & Connect Remote Console
    val airProtocolMode: AirProtocolMode = AirProtocolMode.AIR_2,
    val flashMode: FlashMode = FlashMode.MANUAL,
    val remoteMode: RemoteOperatingMode = RemoteOperatingMode.AUTO_TTL,
    val ttlAutoEnabled: Boolean = true,
    val ttlEvCompTenthStops: Int = 0,
    val groupPowersTenthStops: List<Int> = listOf(80, 75, 70, 65, 60, 55),
    val airHeadMask: Int = 0x3F,      // Bits 0..5 = Groups A..F active
    val airModelLightMask: Int = 0x00,
    val batteryChemistry: BatteryChemistry = BatteryChemistry.AUTO_DETECT,

    // Per-Group TTL EV Bias (Groups A..C)
    val groupTtlBiasesTenthStops: Map<AirGroup, Int> = mapOf(
        AirGroup.GROUP_A to 0,
        AirGroup.GROUP_B to 0,
        AirGroup.GROUP_C to 0,
    ),

    // Remote Settings & Advanced Triggers
    val syncMode: SyncTriggerMode = SyncTriggerMode.FIRST,
    val afAssistMode: AfAssistMode = AfAssistMode.OFF,
    val rfPowerLevel: RfPowerLevel = RfPowerLevel.P4,
    val rfPowerRange: IntRange? = null,
    val autoOffMinutes: Int = 30,
    val autoStandbyMinutes: Int = 15,
    val controlSoundsEnabled: Boolean = true,

    // Monolight Zoom & Ready Signal Mode
    val zoomMode: MonolightZoomMode = MonolightZoomMode.AUTO,
    val zoomValue: Int? = null,
    val zoomRange: IntRange? = null,
    val readySignalMode: ReadySignalMode = ReadySignalMode.SOUND,

    // Display, Power & Live Telemetry
    val displayBrightness: DisplayBrightnessLevel = DisplayBrightnessLevel.HIGH,
    val displayOrientation: DisplayOrientationMode = DisplayOrientationMode.AUTO,
    val batteryBars: Int = 4,
    val chargerPluggedIn: Boolean = false,
    val recycleRemainingMs: Long = 0L,
    val lastWarningMessage: String? = null,
    val isSharedWithActiveRemoteChannel: Boolean = false,
) {
    val address: String get() = device.address
    val hasNickname: Boolean get() = !customAlias.isNullOrBlank()
    val hardwareModelName: String get() = modelSpec.marketingName
    val displayTitle: String
        get() = customAlias?.takeIf { it.isNotBlank() } ?: modelSpec.marketingName
    val archetype: DeviceArchetype get() = modelSpec.archetype
    val isReady: Boolean get() = stage is BleConnectionStage.Ready
    val isFailed: Boolean get() = stage is BleConnectionStage.Failed
    val flashFStops: Float get() = flashPowerTenthStops / 10f
    val ttlEvComp: Float get() = ttlEvCompTenthStops / 10f
    val supportsZoom: Boolean get() = zoomRange != null || zoomValue != null

    val videoIntensityPercent: Int
        get() {
            val span = (maxVideoIntensity - minVideoIntensity).coerceAtLeast(1)
            return (((videoLightIntensity - minVideoIntensity).toFloat() / span) * 100f)
                .toInt().coerceIn(0, 100)
        }
}

data class StudioGroupChannelState(
    val group: AirGroup,
    val groupIndex: Int,
    val hasActiveHardware: Boolean,
    val isEnabled: Boolean,
    val isModelingEnabled: Boolean = false,
    val powerTenthStops: Int,
    val memberCount: Int,
    val directStrobeCount: Int = 0,
    val memberNames: List<String> = emptyList(),
    val roleLabel: String? = null,
    val ttlBiasTenthStops: Int = 0,
)

data class StudioDashboardState(
    val isBluetoothAvailable: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val benchSimulatorEnabled: Boolean = false,
    val deviceNicknames: Map<String, String> = emptyMap(),
    val groupRoles: Map<AirGroup, String> = emptyMap(),
    val pinnedKitMacs: Set<String> = emptySet(),
    val isVolumeKeyTestFlashEnabled: Boolean = false,
    val isAutoReconnectPinnedEnabled: Boolean = true,
    val isDualPowerReadoutEnabled: Boolean = false,
    val scannedDevices: List<DiscoveredProfotoDevice> = emptyList(),
    val connectedUnits: List<ConnectedProfotoUnit> = emptyList(),
    val groupChannels: List<StudioGroupChannelState> = listOf(
        AirGroup.GROUP_A,
        AirGroup.GROUP_B,
        AirGroup.GROUP_C,
        AirGroup.GROUP_D,
        AirGroup.GROUP_E,
        AirGroup.GROUP_F,
    ).mapIndexed { idx, grp ->
        StudioGroupChannelState(
            group = grp,
            groupIndex = idx,
            hasActiveHardware = false,
            isEnabled = false,
            powerTenthStops = 70,
            memberCount = 0,
            roleLabel = null,
            ttlBiasTenthStops = 0,
        )
    },
    val selectedGroupFilter: AirGroup = AirGroup.ALL,
    val statusBanner: String? = null,
) {
    val activeReadyCount: Int
        get() = connectedUnits.count { it.isReady }

    fun nicknameFor(address: String): String? =
        deviceNicknames[DeviceNicknameStore.normalizeAddress(address)]
}
