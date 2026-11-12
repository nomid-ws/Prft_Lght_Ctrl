package app.prft.lightctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.prft.lightctrl.domain.ConnectedProfotoUnit
import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.BatteryChemistry
import app.prft.lightctrl.protocol.DisplayBrightnessLevel
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import app.prft.lightctrl.ui.theme.StudioColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteSettingsSheet(
    unit: ConnectedProfotoUnit,
    onSyncModeChange: (SyncTriggerMode) -> Unit,
    onAfAssistChange: (AfAssistMode) -> Unit,
    onRfPowerChange: (RfPowerLevel) -> Unit,
    onSleepTimeoutChange: (minutes: Int) -> Unit,
    onBrightnessChange: (brightnessCode: Int) -> Unit,
    onBatteryTypeChange: (batteryTypeCode: Int) -> Unit,
    onSoundsToggle: (Boolean) -> Unit,
    onChannelChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Remote Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioColors.TextPrimary,
                    )
                    Text(
                        text = if (unit.hasNickname) {
                            "${unit.displayTitle} · ${unit.hardwareModelName}"
                        } else {
                            "${unit.hardwareModelName} · Ch ${unit.airChannel}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.AirCyan,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Remote Settings",
                        tint = StudioColors.TextSecondary,
                    )
                }
            }

            HorizontalDivider(color = StudioColors.SubtleOutline)

            // 1. Sync Trigger Mode
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Sync Mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (mode in SyncTriggerMode.entries) {
                        FilterChip(
                            selected = unit.syncMode == mode,
                            onClick = { onSyncModeChange(mode) },
                            label = { Text(mode.label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            // 2. AF-Assist Mode
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "AF-Assist Illuminator",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (af in AfAssistMode.entries) {
                        FilterChip(
                            selected = unit.afAssistMode == af,
                            onClick = { onAfAssistChange(af) },
                            label = { Text(af.label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            // 3. RF Power Selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "RF Power Output",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (rf in RfPowerLevel.entries) {
                        FilterChip(
                            selected = unit.rfPowerLevel == rf,
                            onClick = { onRfPowerChange(rf) },
                            label = { Text(rf.label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            // 4. Auto-Off / Sleep Timeout
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Auto-Off / Sleep Timeout",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )
                val sleepOptions = listOf(5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min", 0 to "OFF")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for ((mins, label) in sleepOptions) {
                        FilterChip(
                            selected = unit.autoOffMinutes == mins,
                            onClick = { onSleepTimeoutChange(mins) },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            // 5. Display Brightness
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Display Brightness",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (lvl in DisplayBrightnessLevel.entries) {
                        FilterChip(
                            selected = unit.displayBrightness == lvl,
                            onClick = { onBrightnessChange(lvl.code) },
                            label = { Text(lvl.label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            // 6. Battery Chemistry
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Battery Chemistry",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val batteryOptions = listOf(
                        BatteryChemistry.ALKALINE,
                        BatteryChemistry.NIMH,
                        BatteryChemistry.LITHIUM,
                        BatteryChemistry.AUTO_DETECT,
                    )
                    for (chem in batteryOptions) {
                        FilterChip(
                            selected = unit.batteryChemistry == chem,
                            onClick = { onBatteryTypeChange(chem.code) },
                            label = { Text(chem.label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            // 7. Control Sounds Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioColors.ControlContainer, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Control Sounds",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.TextPrimary,
                    )
                    Text(
                        text = "Audible feedback for remote clicks and adjustments",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                    )
                }
                Switch(
                    checked = unit.controlSoundsEnabled,
                    onCheckedChange = onSoundsToggle,
                    modifier = Modifier.size(width = 56.dp, height = 48.dp),
                )
            }

            // 8. Fast 1–100 Channel Pad / Picker
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "AirX Channel",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.TextPrimary,
                    )
                    Text(
                        text = "CH ${unit.airChannel}",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = StudioColors.FlashAmber,
                    )
                }

                // Steppers row: -10, -1, +1, +10
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onChannelChange((unit.airChannel - 10).coerceAtLeast(1)) },
                        enabled = unit.airChannel > 1,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("-10") }
                    FilledTonalButton(
                        onClick = { onChannelChange((unit.airChannel - 1).coerceAtLeast(1)) },
                        enabled = unit.airChannel > 1,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("-1") }
                    FilledTonalButton(
                        onClick = { onChannelChange((unit.airChannel + 1).coerceAtMost(unit.maxAirChannel)) },
                        enabled = unit.airChannel < unit.maxAirChannel,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("+1") }
                    FilledTonalButton(
                        onClick = { onChannelChange((unit.airChannel + 10).coerceAtMost(unit.maxAirChannel)) },
                        enabled = unit.airChannel < unit.maxAirChannel,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("+10") }
                }

                // Slider
                Slider(
                    value = unit.airChannel.toFloat(),
                    onValueChange = { onChannelChange(it.roundToInt()) },
                    valueRange = 1f..unit.maxAirChannel.toFloat().coerceAtLeast(1f),
                    steps = (unit.maxAirChannel - 2).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Quick Tens Jump Chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val quickChannels = listOf(1, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100).filter { it <= unit.maxAirChannel }
                    for (ch in quickChannels) {
                        FilterChip(
                            selected = unit.airChannel == ch,
                            onClick = { onChannelChange(ch) },
                            label = { Text("Ch $ch") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
    }
}
