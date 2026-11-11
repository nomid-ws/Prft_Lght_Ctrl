package app.prft.lightctrl.ui.dashboard

import androidx.lifecycle.ViewModel
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.domain.StudioDashboardState
import app.prft.lightctrl.domain.StudioOrchestrator
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.AirProtocolMode
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.DisplayOrientationMode
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
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
}
