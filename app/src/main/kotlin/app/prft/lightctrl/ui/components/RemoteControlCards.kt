package app.prft.lightctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.prft.lightctrl.domain.ConnectedProfotoUnit
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.AirProtocolMode
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.ui.theme.StudioColors
import java.util.Locale

/**
 * Profoto Connect Pro (IDs 161..166) 100-Channel AirX 6-Group Master Console Card.
 * Displays custom Nickname as primary label while permanently keeping Hardware Model (e.g. Nikon) visible.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectProConsoleCard(
    unit: ConnectedProfotoUnit,
    onFlashModeChange: (FlashMode) -> Unit,
    onAirProtocolChange: (AirProtocolMode) -> Unit,
    onAirChannelChange: (Int) -> Unit,
    onTtlEvChange: (Int) -> Unit,
    onGroupPowerChange: (groupIdx: Int, tenthStops: Int) -> Unit,
    onGroupMuteToggle: (groupIdx: Int) -> Unit,
    onGroupModelingToggle: (groupIdx: Int) -> Unit,
    onAllGroupsTrim: (deltaTenthStops: Int) -> Unit,
    onTestFlash: () -> Unit,
    onEditNickname: () -> Unit,
    onRetryConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val batLabel = if (unit.chargerPluggedIn) "AC·${unit.batteryBars}/4" else "BAT ${unit.batteryBars}/4"

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
            // Header: Nickname Primary + Hardware Model Always Visible + Edit Nickname + Test
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
                        text = "${modelPrefix}${unit.airProtocolMode.label} · Ch ${unit.airChannel} · $batLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.AirCyan,
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
                    OutlinedButton(
                        onClick = onTestFlash,
                        enabled = unit.isReady,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Test", style = MaterialTheme.typography.labelLarge)
                    }
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
                    Text("BLE link interrupted", style = MaterialTheme.typography.bodyMedium, color = StudioColors.TextSecondary)
                    FilledTonalButton(onClick = onRetryConnect, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Reconnect")
                    }
                }
            }

            // Mode (synced from Connect Pro hardware collar via Opcode 144) & AirX Protocol Controls
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .background(StudioColors.ControlContainer, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Collar: ${unit.remoteMode.label}",
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                        color = StudioColors.TextPrimary,
                    )
                }
                FilterChip(
                    selected = unit.airProtocolMode == AirProtocolMode.AIR_2,
                    onClick = {
                        val next = if (unit.airProtocolMode == AirProtocolMode.AIR_2) {
                            AirProtocolMode.AIR_1
                        } else {
                            AirProtocolMode.AIR_2
                        }
                        onAirProtocolChange(next)
                    },
                    enabled = unit.isReady,
                    label = { Text(if (unit.airProtocolMode == AirProtocolMode.AIR_2) "AirX (100Ch)" else "Air1 (20Ch)") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }

            // Channel Quick Stepper
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Channel ${unit.airChannel}",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onAirChannelChange(unit.airChannel - 1) },
                        enabled = unit.isReady && unit.airChannel > 1,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Ch -") }
                    FilledTonalButton(
                        onClick = { onAirChannelChange(unit.airChannel + 1) },
                        enabled = unit.isReady && unit.airChannel < unit.maxAirChannel,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Ch +") }
                }
            }

            if (unit.remoteMode == RemoteOperatingMode.AUTO_TTL || unit.flashMode == FlashMode.TTL) {
                TenthStopPowerSlider(
                    label = "AirTTL Exposure Bias (EV)",
                    valueTenthStops = unit.ttlEvCompTenthStops.coerceIn(-20, 20),
                    minTenthStops = -20,
                    maxTenthStops = 20,
                    enabled = unit.isReady,
                    showSign = true,
                    coarseStepTenths = 3,
                    coarseLabelPrefix = "0.3",
                    onValueChanged = onTtlEvChange,
                )
            }

            HorizontalDivider(color = StudioColors.SubtleOutline)

            // 6-Group Matrix (Groups A..F) — Vertical structured rows prevent any narrow-screen overflow
            Text(
                text = "Groups A–F Power & Modeling",
                style = MaterialTheme.typography.labelLarge,
                color = StudioColors.TextSecondary,
            )

            val groupLabels = listOf("A", "B", "C", "D", "E", "F")
            groupLabels.forEachIndexed { idx, grpName ->
                val active = (unit.airHeadMask and (1 shl idx)) != 0
                val modOn = (unit.airModelLightMask and (1 shl idx)) != 0
                val tenths = unit.groupPowersTenthStops.getOrElse(idx) { 70 }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioColors.ControlContainer, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = active,
                            onClick = { onGroupMuteToggle(idx) },
                            enabled = unit.isReady,
                            label = {
                                Text(
                                    text = grpName,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .widthIn(min = 48.dp),
                        )

                        Box(modifier = Modifier.widthIn(min = 52.dp)) {
                            Text(
                                text = if (active) String.format(Locale.US, "%.1f", tenths / 10.0) else "OFF",
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                color = if (active) StudioColors.TextPrimary else StudioColors.TextSecondary,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { onGroupPowerChange(idx, tenths - 1) },
                            enabled = unit.isReady && active && tenths > 10,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .widthIn(min = 48.dp),
                        ) { Text("-") }

                        FilledTonalButton(
                            onClick = { onGroupPowerChange(idx, tenths + 1) },
                            enabled = unit.isReady && active && tenths < 100,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .widthIn(min = 48.dp),
                        ) { Text("+") }

                        FilterChip(
                            selected = modOn,
                            onClick = { onGroupModelingToggle(idx) },
                            enabled = unit.isReady,
                            label = { Text("LED") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            // Master All-Groups Relative Trim
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { onAllGroupsTrim(-5) },
                    enabled = unit.isReady,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) { Text("ALL -0.5") }
                FilledTonalButton(
                    onClick = { onAllGroupsTrim(5) },
                    enabled = unit.isReady,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) { Text("ALL +0.5") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDisconnect,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Disconnect Remote", color = StudioColors.StudioRed)
                }
            }
        }
    }
}

/**
 * Original buttonless Profoto Connect (IDs 150..154) AirTTL Trigger Card.
 * Displays custom Nickname as primary label while permanently keeping Hardware Model (e.g. Nikon) visible.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectTriggerCard(
    unit: ConnectedProfotoUnit,
    onModeChange: (RemoteOperatingMode) -> Unit,
    onTtlEvChange: (Int) -> Unit,
    onAirChannelChange: (Int) -> Unit,
    onRelativeGroupTrim: (AirGroup, Int) -> Unit,
    onGroupMuteToggle: (groupIdx: Int) -> Unit = {},
    onGroupModelingToggle: (groupIdx: Int) -> Unit = {},
    onTestFlash: () -> Unit,
    onEditNickname: () -> Unit,
    onRetryConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val batLabel = if (unit.chargerPluggedIn) "AC·${unit.batteryBars}/4" else "BAT ${unit.batteryBars}/4"

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
                        text = "${modelPrefix}${unit.remoteMode.label} · Ch ${unit.airChannel} (1–${unit.maxAirChannel}) · $batLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.AirCyan,
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
                    OutlinedButton(
                        onClick = onTestFlash,
                        enabled = unit.isReady,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Test", style = MaterialTheme.typography.labelLarge)
                    }
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
                    Text("BLE link interrupted", style = MaterialTheme.typography.bodyMedium, color = StudioColors.TextSecondary)
                    FilledTonalButton(onClick = onRetryConnect, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Reconnect")
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    RemoteOperatingMode.AUTO_TTL,
                    RemoteOperatingMode.MANUAL,
                    RemoteOperatingMode.OFF,
                ).forEach { mode ->
                    FilterChip(
                        selected = unit.remoteMode == mode,
                        onClick = { onModeChange(mode) },
                        enabled = unit.isReady,
                        label = { Text(mode.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            TenthStopPowerSlider(
                label = "AirTTL Exposure Offset (EV)",
                valueTenthStops = unit.ttlEvCompTenthStops.coerceIn(-20, 20),
                minTenthStops = -20,
                maxTenthStops = 20,
                enabled = unit.isReady,
                showSign = true,
                coarseStepTenths = 3,
                coarseLabelPrefix = "0.3",
                onValueChanged = onTtlEvChange,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Air Channel: ${unit.airChannel}", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onAirChannelChange(unit.airChannel - 1) },
                        enabled = unit.isReady && unit.airChannel > 1,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Ch -") }
                    FilledTonalButton(
                        onClick = { onAirChannelChange(unit.airChannel + 1) },
                        enabled = unit.isReady && unit.airChannel < unit.maxAirChannel,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Ch +") }
                }
            }

            HorizontalDivider(color = StudioColors.SubtleOutline)

            Text(
                text = "Groups A–F Standby & Air Modeling",
                style = MaterialTheme.typography.labelLarge,
                color = StudioColors.TextSecondary,
            )

            val groupLabels = listOf("A", "B", "C", "D", "E", "F")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groupLabels.forEachIndexed { idx, grpName ->
                    val active = (unit.airHeadMask and (1 shl idx)) != 0
                    FilterChip(
                        selected = active,
                        onClick = { onGroupMuteToggle(idx) },
                        enabled = unit.isReady,
                        label = { Text("Grp $grpName", fontWeight = FontWeight.Bold) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groupLabels.forEachIndexed { idx, grpName ->
                    val modOn = (unit.airModelLightMask and (1 shl idx)) != 0
                    FilterChip(
                        selected = modOn,
                        onClick = { onGroupModelingToggle(idx) },
                        enabled = unit.isReady,
                        label = { Text("$grpName LED") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { onRelativeGroupTrim(AirGroup.ALL, -5) },
                    enabled = unit.isReady,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) { Text("Air Trim -0.5") }
                FilledTonalButton(
                    onClick = { onRelativeGroupTrim(AirGroup.ALL, 5) },
                    enabled = unit.isReady,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) { Text("Air Trim +0.5") }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDisconnect,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Disconnect Trigger", color = StudioColors.StudioRed)
                }
            }
        }
    }
}

/**
 * Air Radio Modeling Light Console Card for Profoto Connect & Profoto Connect Pro on the LED Tab.
 * Transmits Opcode 138 (AirModelLightOn 16-bit group mask) over BLE -> 2.4 GHz Air radio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteAirModelingCard(
    unit: ConnectedProfotoUnit,
    onGroupModelingToggle: (groupIdx: Int) -> Unit,
    onEditNickname: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeGroups = (0 until 6).count { (unit.airModelLightMask and (1 shl it)) != 0 }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                        text = "${modelPrefix}Air Modeling Radio · Ch ${unit.airChannel} · $activeGroups/6 LED ON",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextSecondary,
                    )
                }

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
            }

            val groupLabels = listOf("A", "B", "C", "D", "E", "F")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groupLabels.forEachIndexed { idx, grpName ->
                    val modOn = (unit.airModelLightMask and (1 shl idx)) != 0
                    FilterChip(
                        selected = modOn,
                        onClick = { onGroupModelingToggle(idx) },
                        enabled = unit.isReady,
                        label = { Text("Grp $grpName LED") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}
