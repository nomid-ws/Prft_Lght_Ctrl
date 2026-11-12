package app.prft.lightctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.prft.lightctrl.BuildConfig
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.domain.ConnectedProfotoUnit
import app.prft.lightctrl.domain.StudioDashboardState
import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.HardwareCatalog
import app.prft.lightctrl.ui.dashboard.StudioViewModel
import app.prft.lightctrl.ui.theme.StudioColors

@Composable
fun EquipmentSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = StudioColors.TextSecondary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = StudioColors.TextMuted,
            )
        }
    }
}

@Composable
fun EquipmentManagerDeckView(
    state: StudioDashboardState,
    edgeMargin: Dp,
    hasBlePermissions: Boolean,
    allowSimulator: Boolean,
    onRequestPermissions: () -> Unit,
    onEditConnectedNickname: (ConnectedProfotoUnit) -> Unit,
    onEditScannedNickname: (DiscoveredProfotoDevice) -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: StudioViewModel,
) {
    val connectedAddresses = state.connectedUnits.map { it.address }.toSet()
    val nearbyUnconnected = state.scannedDevices.filter { it.address !in connectedAddresses }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = edgeMargin, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (!hasBlePermissions) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Bluetooth Scan & Connect Permission Needed", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Android 12+ requires nearby-device Bluetooth permission (without location tracking) to discover Profoto strobes and remotes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioColors.TextSecondary,
                        )
                        FilledTonalButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Grant Nearby Devices Permission")
                        }
                    }
                }
            }
        }

        if (allowSimulator) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Virtual Kit Simulator (Debug Only)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Advertises virtual B10X Plus, A2, Connect Pro & Connect through full PUP GATT handshake",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioColors.TextSecondary,
                            )
                        }
                        Switch(
                            checked = state.benchSimulatorEnabled,
                            onCheckedChange = viewModel::setBenchSimulator,
                            modifier = Modifier.size(width = 56.dp, height = 48.dp),
                        )
                    }
                }
            }
        }

        if (state.connectedUnits.isNotEmpty()) {
            item {
                EquipmentSectionHeader(
                    title = "CONNECTED EQUIPMENT (${state.connectedUnits.size})",
                    subtitle = "${state.activeReadyCount} READY",
                )
            }
            items(state.connectedUnits, key = { "conn_${it.address}" }) { unit ->
                val isPinned = StudioPreferencesStore.normalizeMac(unit.address) in state.pinnedKitMacs
                ConnectedEquipmentRow(
                    unit = unit,
                    isPinned = isPinned,
                    onTogglePin = { viewModel.setDevicePinned(unit.address, !isPinned) },
                    onEditNickname = { onEditConnectedNickname(unit) },
                    onRetry = { viewModel.retryConnect(unit.address) },
                    onDisconnect = { viewModel.disconnect(unit.address) },
                )
            }
        }

        item {
            EquipmentSectionHeader(
                title = "NEARBY PROFOTO BLE (${nearbyUnconnected.size})",
                subtitle = if (state.isScanning) "SCANNING" else "IDLE",
            )
        }

        if (nearbyUnconnected.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = when {
                                state.isScanning -> "Listening for Profoto BLE Advertisements..."
                                state.connectedUnits.isNotEmpty() -> "All discovered Profoto units are connected"
                                else -> "Scanner Idle"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Tap Scan Profoto BLE below to discover Profoto Connect, Connect Pro, A2, and B10X Pro / B10 units.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioColors.TextSecondary,
                        )
                    }
                }
            }
        } else {
            items(nearbyUnconnected, key = { "scan_${it.address}" }) { dev ->
                val isPinned = StudioPreferencesStore.normalizeMac(dev.address) in state.pinnedKitMacs
                ScannedDeviceRow(
                    device = dev,
                    isPinned = isPinned,
                    savedNickname = state.nicknameFor(dev.address),
                    onTogglePin = { viewModel.setDevicePinned(dev.address, !isPinned) },
                    onEditNickname = { onEditScannedNickname(dev) },
                    onConnect = { viewModel.connect(dev) },
                )
            }
        }

        item(key = "studio_preferences") {
            EquipmentSectionHeader(
                title = "STUDIO PREFERENCES",
                subtitle = "LOCAL ONLY",
            )
        }

        item(key = "studio_preferences_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StudioPreferenceToggleRow(
                        title = "Dual Power Readout (Ws & 1/x)",
                        subtitle = "Show Watt-seconds and fractional stops alongside Profoto 1–10 scale",
                        checked = state.isDualPowerReadoutEnabled,
                        onCheckedChange = viewModel::setDualPowerReadoutEnabled,
                    )
                    StudioPreferenceToggleRow(
                        title = "Volume Key FIRE Trigger",
                        subtitle = "Press hardware Volume Up / Down while in foreground to fire flash",
                        checked = state.isVolumeKeyTestFlashEnabled,
                        onCheckedChange = viewModel::setVolumeKeyTestFlashEnabled,
                    )
                    StudioPreferenceToggleRow(
                        title = "Auto-Reconnect Pinned Kit",
                        subtitle = "Automatically link pinned Profoto units when discovered in BLE range",
                        checked = state.isAutoReconnectPinnedEnabled,
                        onCheckedChange = viewModel::setAutoReconnectPinnedEnabled,
                    )
                }
            }
        }

        item(key = "about_footer") {
            Card(
                onClick = onOpenAbout,
                colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "About Prft Light Ctrl",
                            style = MaterialTheme.typography.bodyLarge,
                            color = StudioColors.TextPrimary,
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) · Supported Equipment & Safety",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioColors.TextSecondary,
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = StudioColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedEquipmentRow(
    unit: ConnectedProfotoUnit,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onEditNickname: () -> Unit,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val batLabel = if (unit.chargerPluggedIn) "AC·${unit.batteryBars}/4" else "BAT ${unit.batteryBars}/4"
    val roleSummary = when (unit.archetype) {
        DeviceArchetype.CONNECT_PRO_REMOTE, DeviceArchetype.CONNECT_REMOTE ->
            "Ch ${unit.airChannel} · ${unit.remoteMode.label} · $batLabel"
        else -> "Grp ${unit.airGroup.label} · Ch ${unit.airChannel} · $batLabel"
    }
    val subtitle = if (unit.hasNickname) {
        "${unit.hardwareModelName} · $roleSummary"
    } else {
        "${unit.modelSpec.ratedOutputLabel} · $roleSummary"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = unit.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.TextPrimary,
                    )
                    if (isPinned) {
                        Box(
                            modifier = Modifier
                                .background(StudioColors.ControlContainer, RoundedCornerShape(4.dp))
                                .border(1.dp, StudioColors.FlashAmber, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "PINNED KIT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = StudioColors.FlashAmber,
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextSecondary,
                )
                Text(
                    text = if (unit.isReady) "LINKED · READY" else (unit.stage::class.simpleName ?: "Connecting"),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = if (unit.isReady) StudioColors.TextPrimary else StudioColors.TextSecondary,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (isPinned) "Unpin from kit" else "Pin to kit",
                        tint = if (isPinned) StudioColors.FlashAmber else StudioColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
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

                if (unit.isFailed) {
                    FilledTonalButton(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Retry") }
                } else {
                    FilledTonalButton(
                        onClick = onDisconnect,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Disconnect") }
                }
            }
        }
    }
}

@Composable
private fun ScannedDeviceRow(
    device: DiscoveredProfotoDevice,
    isPinned: Boolean,
    savedNickname: String?,
    onTogglePin: () -> Unit,
    onEditNickname: () -> Unit,
    onConnect: () -> Unit,
) {
    val spec = HardwareCatalog.resolve(device.advertisedProductId, device.advertisedName)
    val hasNick = !savedNickname.isNullOrBlank()
    val primaryTitle = if (hasNick) savedNickname!! else spec.marketingName
    val subtitle = if (hasNick) {
        "${spec.marketingName} · ${spec.ratedOutputLabel} · ${device.rssi} dBm"
    } else {
        "${spec.ratedOutputLabel} · ${device.advertisedName} · ${device.rssi} dBm"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = primaryTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.TextPrimary,
                    )
                    if (isPinned) {
                        Box(
                            modifier = Modifier
                                .background(StudioColors.ControlContainer, RoundedCornerShape(4.dp))
                                .border(1.dp, StudioColors.FlashAmber, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "PINNED KIT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = StudioColors.FlashAmber,
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextSecondary,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (isPinned) "Unpin from kit" else "Pin to kit",
                        tint = if (isPinned) StudioColors.FlashAmber else StudioColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick = onEditNickname,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit nickname",
                        tint = StudioColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }

                FilledTonalButton(
                    onClick = onConnect,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun StudioPreferenceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = StudioColors.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = StudioColors.TextSecondary,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(width = 56.dp, height = 48.dp),
        )
    }
}
