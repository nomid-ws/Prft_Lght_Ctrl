package app.prft.lightctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.prft.lightctrl.domain.ConnectedProfotoUnit
import app.prft.lightctrl.domain.PowerScaleConverter
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.DisplayOrientationMode
import app.prft.lightctrl.protocol.MonolightZoomMode
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.VideoLightPowerMode
import app.prft.lightctrl.ui.theme.StudioColors
import kotlin.math.roundToInt

/**
 * Responsive Strobe / Monolight Control Card supporting:
 * - Profoto B10X Plus ("B10X Pro" 500 Ws · 3250 lm Bi-Color LED)
 * - Profoto B10X (250 Ws · 3250 lm Bi-Color LED)
 * - Profoto B10 / B10 Plus
 * - Profoto A2 (100 Ws Compact Monolight, V2 Head Opcodes)
 *
 * Displays custom user Nickname as primary label while permanently keeping Hardware Model visible.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MonolightControlCard(
    unit: ConnectedProfotoUnit,
    onPowerChange: (Int) -> Unit,
    onHeadToggle: (Boolean) -> Unit,
    onFreezeToggle: (Boolean) -> Unit,
    onTestFlash: () -> Unit,
    onVideoEnable: (Boolean) -> Unit,
    onVideoIntensityPercent: (Int) -> Unit,
    onVideoKelvin: (Int) -> Unit,
    onVideoMode: (VideoLightPowerMode) -> Unit,
    onAirGroupChange: (AirGroup) -> Unit,
    onAirChannelChange: (Int) -> Unit,
    onCaptureVideoPriority: (Boolean) -> Unit,
    onIrSlaveToggle: (Boolean) -> Unit,
    onReadySoundToggle: (Boolean) -> Unit,
    onDisplayOrientation: (DisplayOrientationMode) -> Unit,
    onDisplayBrightness: (DisplayBrightnessLevel) -> Unit,
    onRenameAlias: (String?) -> Unit,
    onEditNickname: () -> Unit,
    onRetryConnect: () -> Unit,
    onDisconnect: () -> Unit,
    isDualPowerReadoutEnabled: Boolean = false,
    onZoomChange: (mode: MonolightZoomMode, position: Int?) -> Unit = { _, _ -> },
    onReadySignalModeChange: (ReadySignalMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showRigSheet by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Header: Nickname Primary Label + Hardware Model Always Visible + Edit Nickname + Telemetry
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = unit.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.TextPrimary,
                    )
                    val modelPrefix = if (unit.hasNickname) "${unit.hardwareModelName} · " else ""
                    Text(
                        text = "${modelPrefix}${unit.modelSpec.ratedOutputLabel} · Grp ${unit.airGroup.label} · Ch ${unit.airChannel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextSecondary,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onEditNickname,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit nickname for ${unit.displayTitle}",
                            tint = StudioColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    TelemetryBadgeCluster(unit = unit)
                }
            }

            if (unit.isFailed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioColors.ControlContainer, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "BLE link interrupted",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextSecondary,
                    )
                    FilledTonalButton(
                        onClick = onRetryConnect,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Reconnect")
                    }
                }
            }

            if (unit.isSharedWithActiveRemoteChannel) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioColors.ControlContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "AirX Ch ${unit.airChannel} linked to active Remote — Master group trims route via Remote",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioColors.AirCyan,
                    )
                }
            }

            unit.lastWarningMessage?.let { warn ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioColors.StudioRed.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = warn,
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioColors.StudioRed,
                    )
                }
            }

            // 2. Flash Tenth-Stop Power Scrubber
            val dualReadout = if (isDualPowerReadoutEnabled) {
                val maxWs = unit.modelSpec.maxWattSeconds
                val ws = if (maxWs != null && maxWs > 0) PowerScaleConverter.formatWattSeconds(maxWs, unit.flashPowerTenthStops) else null
                val frac = PowerScaleConverter.formatPowerFraction(unit.flashPowerTenthStops)
                if (ws != null) "$ws · $frac" else frac
            } else null

            TenthStopPowerSlider(
                label = if (unit.headEnabled) "Flash Energy (f-stops)" else "Flash Head Muted",
                valueTenthStops = unit.flashPowerTenthStops,
                minTenthStops = unit.minFlashTenthStops,
                maxTenthStops = unit.maxFlashTenthStops,
                enabled = unit.isReady && unit.headEnabled,
                secondaryReadout = dualReadout,
                onValueChanged = onPowerChange,
            )

            // Quick Toggles + Fixture Test Trigger (FlowRow avoids horizontal overflow on Fold 7 Cover)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = unit.headEnabled,
                    onClick = { onHeadToggle(!unit.headEnabled) },
                    enabled = unit.isReady,
                    label = { Text(if (unit.headEnabled) "Flash ON" else "Flash OFF") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = unit.freezeEnabled,
                    onClick = { onFreezeToggle(!unit.freezeEnabled) },
                    enabled = unit.isReady,
                    label = { Text(if (unit.freezeEnabled) "Freeze HSS" else "Normal Pulse") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                OutlinedButton(
                    onClick = onTestFlash,
                    enabled = unit.isReady && unit.headEnabled,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Test Head", style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalButton(
                    onClick = { showRigSheet = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Rig & Air", style = MaterialTheme.typography.labelLarge)
                }
            }

            HorizontalDivider(color = StudioColors.SubtleOutline)

            // 3. Continuous / Modeling LED Section
            ContinuousLedControlsSection(
                unit = unit,
                onVideoEnable = onVideoEnable,
                onVideoIntensityPercent = onVideoIntensityPercent,
                onVideoKelvin = onVideoKelvin,
                onVideoMode = onVideoMode,
            )
        }
    }

    if (showRigSheet) {
        MonolightRigBottomSheet(
            unit = unit,
            onDismiss = { showRigSheet = false },
            onAirGroupChange = onAirGroupChange,
            onAirChannelChange = onAirChannelChange,
            onCaptureVideoPriority = onCaptureVideoPriority,
            onIrSlaveToggle = onIrSlaveToggle,
            onReadySoundToggle = onReadySoundToggle,
            onZoomChange = onZoomChange,
            onReadySignalModeChange = onReadySignalModeChange,
            onDisplayOrientation = onDisplayOrientation,
            onDisplayBrightness = onDisplayBrightness,
            onRenameAlias = onRenameAlias,
            onDisconnect = {
                showRigSheet = false
                onDisconnect()
            },
        )
    }
}

/**
 * Dedicated Continuous / Modeling Light Card for the official-style LED Console tab.
 * Keeps Nickname primary and Hardware Model permanently visible.
 */
@Composable
fun ContinuousLedControlCard(
    unit: ConnectedProfotoUnit,
    onVideoEnable: (Boolean) -> Unit,
    onVideoIntensityPercent: (Int) -> Unit,
    onVideoKelvin: (Int) -> Unit,
    onVideoMode: (VideoLightPowerMode) -> Unit,
    onEditNickname: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = unit.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.TextPrimary,
                    )
                    val modelPrefix = if (unit.hasNickname) "${unit.hardwareModelName} · " else ""
                    Text(
                        text = "${modelPrefix}Grp ${unit.airGroup.label} · Ch ${unit.airChannel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextSecondary,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onEditNickname,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit nickname for ${unit.displayTitle}",
                            tint = StudioColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    TelemetryBadgeCluster(unit = unit)
                }
            }

            ContinuousLedControlsSection(
                unit = unit,
                onVideoEnable = onVideoEnable,
                onVideoIntensityPercent = onVideoIntensityPercent,
                onVideoKelvin = onVideoKelvin,
                onVideoMode = onVideoMode,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContinuousLedControlsSection(
    unit: ConnectedProfotoUnit,
    onVideoEnable: (Boolean) -> Unit,
    onVideoIntensityPercent: (Int) -> Unit,
    onVideoKelvin: (Int) -> Unit,
    onVideoMode: (VideoLightPowerMode) -> Unit,
) {
    val summaryParts = buildList {
        if (unit.hasVideoDimming) add("${unit.videoIntensityPercent}%")
        if (unit.hasBiColorCct) add("${unit.videoLightKelvin} K") else add("${unit.modelSpec.defaultMinKelvin} K")
        if (unit.modelSpec.supportsContinuousPowerModes) add(unit.videoLightMode.label)
    }.joinToString(" · ")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Default.LightMode,
                contentDescription = null,
                tint = if (unit.videoLightEnabled) StudioColors.WarmKelvin else StudioColors.TextSecondary,
            )
            Column {
                Text(
                    text = "Continuous / Modeling LED",
                    style = MaterialTheme.typography.bodyLarge,
                    color = StudioColors.TextPrimary,
                )
                Text(
                    text = summaryParts,
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.TextSecondary,
                )
            }
        }
        Switch(
            checked = unit.videoLightEnabled,
            onCheckedChange = onVideoEnable,
            enabled = unit.isReady,
            modifier = Modifier.size(width = 56.dp, height = 48.dp),
        )
    }

    if (unit.videoLightEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (unit.hasVideoDimming) {
                Text(
                    text = "Brightness: ${unit.videoIntensityPercent}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioColors.TextSecondary,
                )
                Slider(
                    value = unit.videoIntensityPercent.toFloat(),
                    onValueChange = { onVideoIntensityPercent(it.roundToInt()) },
                    valueRange = 1f..100f,
                    enabled = unit.isReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                )
            }

            if (unit.hasBiColorCct) {
                Text(
                    text = "Color Temp: ${unit.videoLightKelvin} K",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioColors.TextSecondary,
                )
                Slider(
                    value = unit.videoLightKelvin.toFloat(),
                    onValueChange = { raw ->
                        val snapped = ((raw / 100f).roundToInt() * 100)
                            .coerceIn(unit.minKelvin, unit.maxKelvin)
                        onVideoKelvin(snapped)
                    },
                    valueRange = unit.minKelvin.toFloat()..unit.maxKelvin.toFloat(),
                    enabled = unit.isReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                )
            }

            if (unit.modelSpec.supportsContinuousPowerModes) {
                val modes = if (unit.hasVideoDimming) {
                    listOf(
                        VideoLightPowerMode.FREE,
                        VideoLightPowerMode.PROPORTIONAL,
                        VideoLightPowerMode.FLICKER_FREE,
                    )
                } else {
                    listOf(
                        VideoLightPowerMode.PROPORTIONAL,
                        VideoLightPowerMode.MAX,
                        VideoLightPowerMode.FREE,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    modes.forEach { mode ->
                        FilterChip(
                            selected = unit.videoLightMode == mode,
                            onClick = { onVideoMode(mode) },
                            enabled = unit.isReady,
                            label = { Text(mode.label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryBadgeCluster(unit: ConnectedProfotoUnit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val recycleReady = unit.recycleRemainingMs <= 0L
        val activeWhiteBadge = unit.isReady && recycleReady
        Box(
            modifier = Modifier
                .background(
                    color = if (activeWhiteBadge) StudioColors.TextPrimary else StudioColors.ControlContainer,
                    shape = RoundedCornerShape(8.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (activeWhiteBadge) StudioColors.TextPrimary else StudioColors.SubtleOutline,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = when {
                    !unit.isReady -> unit.stage::class.simpleName ?: "Sync"
                    recycleReady -> "READY"
                    else -> "${unit.recycleRemainingMs}ms"
                },
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (activeWhiteBadge) StudioColors.CanvasBase else StudioColors.TextPrimary,
            )
        }

        val batteryText = if (unit.chargerPluggedIn) {
            "AC·${unit.batteryBars}/4"
        } else {
            "BAT ${unit.batteryBars}/4"
        }
        Box(
            modifier = Modifier
                .background(StudioColors.ControlContainer, RoundedCornerShape(8.dp))
                .border(1.dp, StudioColors.SubtleOutline, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = batteryText,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (unit.chargerPluggedIn) StudioColors.AirCyan else StudioColors.TextPrimary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MonolightRigBottomSheet(
    unit: ConnectedProfotoUnit,
    onDismiss: () -> Unit,
    onAirGroupChange: (AirGroup) -> Unit,
    onAirChannelChange: (Int) -> Unit,
    onCaptureVideoPriority: (Boolean) -> Unit,
    onIrSlaveToggle: (Boolean) -> Unit,
    onReadySoundToggle: (Boolean) -> Unit,
    onZoomChange: (mode: MonolightZoomMode, position: Int?) -> Unit,
    onReadySignalModeChange: (ReadySignalMode) -> Unit,
    onDisplayOrientation: (DisplayOrientationMode) -> Unit,
    onDisplayBrightness: (DisplayBrightnessLevel) -> Unit,
    onRenameAlias: (String?) -> Unit,
    onDisconnect: () -> Unit,
) {
    var aliasInput by rememberSaveable(unit.address) {
        mutableStateOf(unit.customAlias.orEmpty())
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioColors.SheetElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Sheet Header with explicit 48x48dp Close button (3 dismissal paths)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${unit.displayTitle} — Rig & Air",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${unit.hardwareModelName} · ${unit.modelSpec.ratedOutputLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioColors.TextSecondary,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close sheet")
                }
            }

            // Air Group Assignment (A..F)
            Text("Air Group Assignment", style = MaterialTheme.typography.labelLarge, color = StudioColors.TextSecondary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AirGroup.entries.filter { it != AirGroup.ALL }.forEach { grp ->
                    FilterChip(
                        selected = unit.airGroup == grp,
                        onClick = { onAirGroupChange(grp) },
                        label = { Text("Grp ${grp.label}") },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .widthIn(min = 56.dp),
                    )
                }
            }

            // Air Channel Stepper (1..maxAirChannel)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Air Channel: ${unit.airChannel} (1–${unit.maxAirChannel})",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onAirChannelChange(unit.airChannel - 1) },
                        enabled = unit.airChannel > unit.minAirChannel,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("-1") }
                    FilledTonalButton(
                        onClick = { onAirChannelChange(unit.airChannel + 1) },
                        enabled = unit.airChannel < unit.maxAirChannel,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("+1") }
                    FilledTonalButton(
                        onClick = { onAirChannelChange((unit.airChannel + 10).coerceAtMost(unit.maxAirChannel)) },
                        enabled = unit.airChannel < unit.maxAirChannel,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("+10") }
                }
            }

            // B10X Plus / B10X Hardware Capabilities
            if (unit.modelSpec.supportsDisplayOrientation) {
                Text("Boom / Stand LCD Orientation", style = MaterialTheme.typography.labelLarge, color = StudioColors.TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DisplayOrientationMode.entries.forEach { orient ->
                        FilterChip(
                            selected = unit.displayOrientation == orient,
                            onClick = { onDisplayOrientation(orient) },
                            label = { Text(orient.label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (unit.modelSpec.supportsCaptureModeToggle) {
                    FilterChip(
                        selected = unit.captureVideoPriority,
                        onClick = { onCaptureVideoPriority(!unit.captureVideoPriority) },
                        label = { Text(if (unit.captureVideoPriority) "Priority: Video" else "Priority: Stills") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                if (unit.modelSpec.supportsIrSlave) {
                    FilterChip(
                        selected = unit.irSlaveEnabled,
                        onClick = { onIrSlaveToggle(!unit.irSlaveEnabled) },
                        label = { Text("IR Optical Slave") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            // Monolight Zoom Controls (op=161..163)
            if (unit.supportsZoom) {
                Text(
                    text = "Monolight Zoom (Op 161–163)",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioColors.TextSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = unit.zoomMode == MonolightZoomMode.AUTO,
                        onClick = { onZoomChange(MonolightZoomMode.AUTO, null) },
                        label = { Text("Auto Zoom") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    FilterChip(
                        selected = unit.zoomMode == MonolightZoomMode.MANUAL,
                        onClick = { onZoomChange(MonolightZoomMode.MANUAL, unit.zoomValue ?: 25) },
                        label = { Text("Manual Zoom") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                if (unit.zoomMode == MonolightZoomMode.MANUAL) {
                    val minZ = unit.zoomRange?.first ?: 0
                    val maxZ = unit.zoomRange?.last ?: 100
                    val curZ = (unit.zoomValue ?: minZ).coerceIn(minZ, maxZ)
                    Text("Zoom Position: $curZ", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = curZ.toFloat(),
                        onValueChange = { onZoomChange(MonolightZoomMode.MANUAL, it.roundToInt()) },
                        valueRange = minZ.toFloat()..maxZ.toFloat(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    )
                }
            }

            // Ready Signal Mode Selector (op=217)
            Text(
                text = "Ready Signal Mode (Op 217)",
                style = MaterialTheme.typography.labelLarge,
                color = StudioColors.TextSecondary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReadySignalMode.entries.forEach { mode ->
                    FilterChip(
                        selected = unit.readySignalMode == mode,
                        onClick = { onReadySignalModeChange(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ReadySignalMode.OFF -> "OFF"
                                    ReadySignalMode.SOUND -> "BEEP"
                                    ReadySignalMode.DIM -> "DIM"
                                    ReadySignalMode.SOUND_AND_DIM -> "BEEP + DIM"
                                }
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            Text("Fixture LCD Brightness", style = MaterialTheme.typography.labelLarge, color = StudioColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DisplayBrightnessLevel.entries.forEach { lvl ->
                    FilterChip(
                        selected = unit.displayBrightness == lvl,
                        onClick = { onDisplayBrightness(lvl) },
                        label = { Text(lvl.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            // Persistent Top-Aligned Label + >=16sp Studio Nickname Field
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Equipment Nickname (Model stays visible)", style = MaterialTheme.typography.labelLarge, color = StudioColors.TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = aliasInput,
                        onValueChange = { aliasInput = it },
                        placeholder = { Text(unit.hardwareModelName, color = StudioColors.TextMuted) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = { onRenameAlias(aliasInput.trim().takeIf { it.isNotEmpty() }) },
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) {
                        Text("Save")
                    }
                }
            }

            HorizontalDivider(color = StudioColors.SubtleOutline)

            // Muted destructive disconnect button per Practical UI Destructive Action Rule
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "BLE MAC: ${unit.address}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = StudioColors.TextSecondary,
                )
                TextButton(
                    onClick = onDisconnect,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Disconnect BLE", color = StudioColors.StudioRed)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
