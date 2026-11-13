package app.prft.lightctrl.ui.dashboard

import androidx.lifecycle.ViewModel
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.domain.GroupPreset
import app.prft.lightctrl.domain.StudioDashboardState
import app.prft.lightctrl.domain.StudioOrchestrator
import app.prft.lightctrl.domain.StudioScene
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.AirProtocolMode
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.DisplayOrientationMode
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.MonolightZoomMode
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import app.prft.lightctrl.protocol.VideoLightPowerMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class StudioViewModel @Inject constructor(
    private val orchestrator: StudioOrchestrator,
) : ViewModel() {

    val state: StateFlow<StudioDashboardState> = orchestrator.dashboardState

    fun refreshBluetooth() = orchestrator.refreshBluetoothState()
    fun startScan() = orchestrator.startScan()
    fun stopScan() = orchestrator.stopScan()
    fun setBenchSimulator(enabled: Boolean) = orchestrator.setBenchSimulatorEnabled(enabled)
    fun dismissBanner() = orchestrator.clearStatusBanner()

    fun connect(device: DiscoveredProfotoDevice) = orchestrator.connectDevice(device)
    fun retryConnect(address: String) = orchestrator.retryConnectDevice(address)
    fun disconnect(address: String) = orchestrator.disconnectDevice(address)
    fun renameAlias(address: String, alias: String?) = orchestrator.renameUnitAlias(address, alias)

    fun selectGroupFilter(group: AirGroup) = orchestrator.setSelectedGroupFilter(group)
    fun triggerMasterTestFlash() = orchestrator.triggerStudioMasterTestFlash()
    fun triggerUnitTestFlash(address: String) = orchestrator.triggerTestFlash(address)
    fun trimStudioGroup(group: AirGroup, deltaTenths: Int) = orchestrator.trimStudioGroupFixtures(group, deltaTenths)
    fun setStudioGroupPower(group: AirGroup, tenthStops: Int) = orchestrator.setStudioGroupPowerTenthStops(group, tenthStops)
    fun toggleStudioGroupMute(group: AirGroup) = orchestrator.toggleStudioGroupMute(group)
    fun toggleStudioGroupModeling(group: AirGroup) = orchestrator.toggleStudioGroupModeling(group)
    fun setAllContinuousLights(enabled: Boolean) = orchestrator.setAllContinuousLightsEnabled(enabled)

    fun setFlashPower(address: String, tenthStops: Int) = orchestrator.setFlashPowerTenthStops(address, tenthStops)
    fun setHeadOn(address: String, enabled: Boolean) = orchestrator.setHeadEnabled(address, enabled)
    fun setFreeze(address: String, enabled: Boolean) = orchestrator.setFreezeEnabled(address, enabled)
    fun setCaptureVideoPriority(address: String, videoPriority: Boolean) = orchestrator.setCaptureModeVideoPriority(address, videoPriority)
    fun setIrSlave(address: String, enabled: Boolean) = orchestrator.setIrSlaveEnabled(address, enabled)
    fun setReadySound(address: String, enabled: Boolean) = orchestrator.setReadySoundEnabled(address, enabled)

    fun setVideoEnable(address: String, enabled: Boolean) = orchestrator.setVideoLightEnabled(address, enabled)
    fun setVideoIntensityPct(address: String, pct: Int) = orchestrator.setVideoLightIntensityPercent(address, pct)
    fun setVideoKelvin(address: String, kelvin: Int) = orchestrator.setVideoLightKelvin(address, kelvin)
    fun setVideoMode(address: String, mode: VideoLightPowerMode) = orchestrator.setVideoLightMode(address, mode)

    fun setDisplayOrientation(address: String, mode: DisplayOrientationMode) = orchestrator.setDisplayOrientation(address, mode)
    fun setDisplayBrightness(address: String, level: DisplayBrightnessLevel) = orchestrator.setDisplayBrightness(address, level)

    fun setAirGroup(address: String, group: AirGroup) = orchestrator.setAirGroup(address, group)
    fun setAirChannel(address: String, channel: Int) = orchestrator.setAirChannel(address, channel)
    fun setAirProtocol(address: String, mode: AirProtocolMode) = orchestrator.setAirProtocolMode(address, mode)
    fun setFlashMode(address: String, mode: FlashMode) = orchestrator.setRemoteFlashMode(address, mode)
    fun setConnectMode(address: String, mode: RemoteOperatingMode) = orchestrator.setConnectOperatingMode(address, mode)
    fun setTtlEvComp(address: String, tenths: Int) = orchestrator.setTtlEvCompensation(address, tenths)

    fun setConnectProGroupPower(address: String, groupIdx: Int, tenths: Int) =
        orchestrator.setConnectProGroupPower(address, groupIdx, tenths)
    fun toggleConnectProGroupMute(address: String, groupIdx: Int) =
        orchestrator.toggleConnectProGroupMute(address, groupIdx)
    fun toggleConnectProGroupModeling(address: String, groupIdx: Int) =
        orchestrator.toggleConnectProGroupModeling(address, groupIdx)
    fun stepRemoteGroupTrim(address: String, group: AirGroup, deltaTenths: Int) =
        orchestrator.stepRemoteAirGroupTrim(address, group, deltaTenths)

    // Preferences & Pinned Kit
    fun setGroupRole(group: AirGroup, role: String?) = orchestrator.setGroupRole(group, role)
    fun setGroupTtlEvBias(address: String, group: AirGroup, tenthStops: Int) =
        orchestrator.setGroupTtlEvBias(address, group, tenthStops)
    fun setDevicePinned(address: String, pinned: Boolean) =
        orchestrator.setDevicePinned(address, pinned)
    fun setVolumeKeyTestFlashEnabled(enabled: Boolean) =
        orchestrator.setVolumeKeyTestFlashEnabled(enabled)
    fun setAutoReconnectPinnedEnabled(enabled: Boolean) =
        orchestrator.setAutoReconnectPinnedEnabled(enabled)
    fun setDualPowerReadoutEnabled(enabled: Boolean) =
        orchestrator.setDualPowerReadoutEnabled(enabled)
    fun setBroadBleScanEnabled(enabled: Boolean) =
        orchestrator.setBroadBleScanEnabled(enabled)

    // Remote Settings
    fun setRemoteSyncMode(address: String, mode: SyncTriggerMode) =
        orchestrator.setRemoteSyncMode(address, mode)
    fun setRemoteAfAssist(address: String, mode: AfAssistMode) =
        orchestrator.setRemoteAfAssist(address, mode)
    fun setRemoteRfPower(address: String, level: RfPowerLevel) =
        orchestrator.setRemoteRfPower(address, level)
    fun setRemoteSleepTimeout(address: String, minutes: Int) =
        orchestrator.setRemoteSleepTimeout(address, minutes)
    fun setRemoteBrightness(address: String, brightness: Int) =
        orchestrator.setRemoteBrightness(address, brightness)
    fun setRemoteBatteryType(address: String, batteryType: Int) =
        orchestrator.setRemoteBatteryType(address, batteryType)
    fun setRemoteSounds(address: String, enabled: Boolean) =
        orchestrator.setRemoteSounds(address, enabled)

    // Monolight Zoom & Ready Signal
    fun setMonolightZoom(address: String, mode: MonolightZoomMode, position: Int? = null) =
        orchestrator.setMonolightZoom(address, mode, position)
    fun setReadySignalMode(address: String, mode: ReadySignalMode) =
        orchestrator.setReadySignalMode(address, mode)

    // Studio Scenes & Presets
    val savedScenes: StateFlow<List<StudioScene>> = orchestrator.savedScenes
    fun saveScene(scene: StudioScene) = orchestrator.saveScene(scene)
    fun deleteScene(sceneId: String) = orchestrator.deleteScene(sceneId)
    fun importScenes(scenes: List<StudioScene>) = orchestrator.importScenes(scenes)
    fun applyScene(scene: StudioScene) = orchestrator.applyScene(scene)

    fun captureCurrentScene(name: String, notes: String = ""): StudioScene {
        val s = state.value
        val activeRemote = s.connectedUnits.firstOrNull {
            it.isReady && (it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE || it.archetype == DeviceArchetype.CONNECT_REMOTE)
        }
        val groupPresets = s.groupChannels.map { ch ->
            GroupPreset(
                group = ch.group,
                powerTenthStops = ch.powerTenthStops,
                isEnabled = ch.isEnabled,
                isModelingEnabled = ch.isModelingEnabled,
                roleLabel = ch.roleLabel,
                ttlEvBiasTenthStops = if (ch.ttlBiasTenthStops != 0) ch.ttlBiasTenthStops else null,
            )
        }
        return StudioScene(
            name = name.trim().ifEmpty { "Studio Preset" },
            setupNotes = notes.trim(),
            groupPresets = groupPresets,
            remoteChannel = activeRemote?.airChannel,
            remoteOperatingMode = activeRemote?.remoteMode,
            syncTriggerMode = activeRemote?.syncMode,
            afAssistMode = activeRemote?.afAssistMode,
            rfPowerLevel = activeRemote?.rfPowerLevel,
        )
    }
}
