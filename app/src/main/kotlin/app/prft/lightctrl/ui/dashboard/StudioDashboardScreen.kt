package app.prft.lightctrl.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.prft.lightctrl.BuildConfig
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.domain.ConnectedProfotoUnit
import app.prft.lightctrl.domain.StudioDashboardState
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.HardwareCatalog
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.ui.components.AboutAppSheet
import app.prft.lightctrl.ui.components.ConnectProConsoleCard
import app.prft.lightctrl.ui.components.ConnectTriggerCard
import app.prft.lightctrl.ui.components.ContinuousLedControlCard
import app.prft.lightctrl.ui.components.MonolightControlCard
import app.prft.lightctrl.ui.components.NicknameEditTarget
import app.prft.lightctrl.ui.components.NicknameEditorSheet
import app.prft.lightctrl.ui.components.RemoteAirModelingCard
import app.prft.lightctrl.ui.components.VerticalGroupFaderBank
import app.prft.lightctrl.ui.theme.StudioColors

private enum class StudioNavTab(val label: String) {
    GROUPS("Groups"),
    LIGHTS("Lights"),
    LED("LED"),
    DEVICES("Devices"),
}

private val STUDIO_GROUPS_A_TO_F = listOf(
    AirGroup.GROUP_A,
    AirGroup.GROUP_B,
    AirGroup.GROUP_C,
    AirGroup.GROUP_D,
    AirGroup.GROUP_E,
    AirGroup.GROUP_F,
)

/**
 * Official-Style Organized Profoto Studio Console for Samsung Galaxy Z Fold 7 & Android 12+:
 * - 4 Thumb-Zone Tabs:
 *   1. Groups: Non-scrolling 6-Group (A–F) vertical faders + Active Remote strip.
 *   2. Lights: Connected equipment organized into Remotes & Triggers + Group A..F sections.
 *   3. LED: Dedicated Continuous / Modeling Light console (Master All ON/OFF + Group LED + Fixture cards).
 *   4. Devices: Connected Equipment manager + Nearby BLE scanner with human nicknames & visible Hardware Model.
 */
@Composable
fun StudioDashboardScreen(
    state: StudioDashboardState,
    hasBlePermissions: Boolean,
    allowSimulator: Boolean = false,
    onRequestBlePermissions: () -> Unit,
    viewModel: StudioViewModel,
) {
    var currentTabOrdinal by rememberSaveable { mutableIntStateOf(StudioNavTab.DEVICES.ordinal) }
    val currentTab = StudioNavTab.entries[currentTabOrdinal.coerceIn(0, StudioNavTab.entries.lastIndex)]
    var nicknameTarget by remember { mutableStateOf<NicknameEditTarget?>(null) }
    var showAboutSheet by rememberSaveable { mutableStateOf(false) }

    val openUnitNicknameEditor: (ConnectedProfotoUnit) -> Unit = { unit ->
        nicknameTarget = NicknameEditTarget(
            address = unit.address,
            hardwareModelName = unit.hardwareModelName,
            ratedSpecLabel = unit.modelSpec.ratedOutputLabel,
            currentNickname = unit.customAlias,
        )
    }

    val openScannedNicknameEditor: (DiscoveredProfotoDevice) -> Unit = { dev ->
        val spec = HardwareCatalog.resolve(dev.advertisedProductId, dev.advertisedName)
        nicknameTarget = NicknameEditTarget(
            address = dev.address,
            hardwareModelName = spec.marketingName,
            ratedSpecLabel = spec.ratedOutputLabel,
            currentNickname = state.nicknameFor(dev.address),
        )
    }

    Scaffold(
        containerColor = StudioColors.CanvasBase,
        topBar = {
            PassiveTopStatusBar(
                state = state,
                hasBlePermissions = hasBlePermissions,
                allowSimulator = allowSimulator,
                onOpenAbout = { showAboutSheet = true },
                onDismissBanner = viewModel::dismissBanner,
            )
        },
        bottomBar = {
            ThumbZoneBottomDock(
                state = state,
                currentTab = currentTab,
                hasBlePermissions = hasBlePermissions,
                onSelectTab = { currentTabOrdinal = it.ordinal },
                onPrimaryCta = {
                    if (!hasBlePermissions) {
                        onRequestBlePermissions()
                    } else if (currentTab == StudioNavTab.DEVICES || state.activeReadyCount == 0) {
                        currentTabOrdinal = StudioNavTab.DEVICES.ordinal
                        if (state.isScanning) viewModel.stopScan() else viewModel.startScan()
                    } else {
                        viewModel.triggerMasterTestFlash()
                    }
                },
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val isWideInnerScreen = maxWidth >= 600.dp
            val edgeMargin = if (isWideInnerScreen) 24.dp else 16.dp

            when (currentTab) {
                StudioNavTab.GROUPS -> VerticalGroupFaderBank(
                    state = state,
                    edgeMargin = edgeMargin,
                    onMasterAllTrim = { delta -> viewModel.trimStudioGroup(AirGroup.ALL, delta) },
                    onGroupPowerChange = { grp, tenths -> viewModel.setStudioGroupPower(grp, tenths) },
                    onGroupMuteToggle = { grp -> viewModel.toggleStudioGroupMute(grp) },
                    onRemoteChannelChange = { addr, ch -> viewModel.setAirChannel(addr, ch) },
                    onRemoteToggleMode = { remote ->
                        if (remote.archetype == DeviceArchetype.CONNECT_PRO_REMOTE) {
                            val next = if (remote.flashMode == FlashMode.MANUAL) FlashMode.TTL else FlashMode.MANUAL
                            viewModel.setFlashMode(remote.address, next)
                        } else {
                            val next = when (remote.remoteMode) {
                                RemoteOperatingMode.AUTO_TTL -> RemoteOperatingMode.MANUAL
                                RemoteOperatingMode.MANUAL -> RemoteOperatingMode.OFF
                                RemoteOperatingMode.OFF -> RemoteOperatingMode.AUTO_TTL
                            }
                            viewModel.setConnectMode(remote.address, next)
                        }
                    },
                )
                StudioNavTab.LIGHTS -> OrganizedLightsDeckView(
                    state = state,
                    isWideScreen = isWideInnerScreen,
                    edgeMargin = edgeMargin,
                    hasBlePermissions = hasBlePermissions,
                    onOpenScanner = {
                        currentTabOrdinal = StudioNavTab.DEVICES.ordinal
                        if (hasBlePermissions) viewModel.startScan() else onRequestBlePermissions()
                    },
                    onEditNickname = openUnitNicknameEditor,
                    viewModel = viewModel,
                )
                StudioNavTab.LED -> ContinuousLedDeckView(
                    state = state,
                    isWideScreen = isWideInnerScreen,
                    edgeMargin = edgeMargin,
                    hasBlePermissions = hasBlePermissions,
                    onOpenScanner = {
                        currentTabOrdinal = StudioNavTab.DEVICES.ordinal
                        if (hasBlePermissions) viewModel.startScan() else onRequestBlePermissions()
                    },
                    onEditNickname = openUnitNicknameEditor,
                    viewModel = viewModel,
                )
                StudioNavTab.DEVICES -> EquipmentManagerDeckView(
                    state = state,
                    edgeMargin = edgeMargin,
                    hasBlePermissions = hasBlePermissions,
                    allowSimulator = allowSimulator,
                    onRequestPermissions = onRequestBlePermissions,
                    onEditConnectedNickname = openUnitNicknameEditor,
                    onEditScannedNickname = openScannedNicknameEditor,
                    onOpenAbout = { showAboutSheet = true },
                    viewModel = viewModel,
                )
            }
        }
    }

    nicknameTarget?.let { target ->
        NicknameEditorSheet(
            target = target,
            onSave = { alias -> viewModel.renameAlias(target.address, alias) },
            onDismiss = { nicknameTarget = null },
        )
    }

    if (showAboutSheet) {
        AboutAppSheet(
            state = state,
            onDismiss = { showAboutSheet = false },
        )
    }
}

@Composable
private fun PassiveTopStatusBar(
    state: StudioDashboardState,
    hasBlePermissions: Boolean,
    allowSimulator: Boolean,
    onOpenAbout: () -> Unit,
    onDismissBanner: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StudioColors.CanvasBase)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Prft Light Ctrl",
                    style = MaterialTheme.typography.headlineSmall,
                    color = StudioColors.TextPrimary,
                )
                Text(
                    text = when {
                        !hasBlePermissions -> "BLE permission required"
                        !state.isBluetoothEnabled -> "Bluetooth radio OFF"
                        state.activeReadyCount > 0 -> {
                            val names = state.connectedUnits
                                .filter { it.isReady }
                                .joinToString(", ") { it.displayTitle }
                            "${state.activeReadyCount} linked · $names"
                        }
                        state.isScanning -> "Scanning Profoto AirX & AirTTL BLE..."
                        else -> "Disconnected · Ready to scan"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.activeReadyCount > 0) StudioColors.TextPrimary else StudioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.size(8.dp))

            val simActive = allowSimulator && state.benchSimulatorEnabled
            val linkedActive = state.activeReadyCount > 0
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = if (linkedActive) StudioColors.TextPrimary else StudioColors.CardSurface,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = if (linkedActive || simActive) StudioColors.TextPrimary else StudioColors.SubtleOutline,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = when {
                            simActive -> "SIM LAB"
                            linkedActive -> "LINKED (${state.activeReadyCount})"
                            else -> "LIVE BLE"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = if (linkedActive) StudioColors.CanvasBase else StudioColors.TextPrimary,
                    )
                }

                IconButton(
                    onClick = onOpenAbout,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "About Prft Light Ctrl",
                        tint = StudioColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        state.statusBanner?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioColors.SheetElevated, RoundedCornerShape(10.dp))
                    .border(1.dp, StudioColors.SubtleOutline, RoundedCornerShape(10.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismissBanner,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss banner")
                }
            }
        }
    }
}

@Composable
private fun StudioSectionHeader(
    title: String,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = StudioColors.TextPrimary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = StudioColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun EmptyHardwarePromptCard(
    title: String,
    body: String,
    hasBlePermissions: Boolean,
    edgeMargin: Dp,
    onOpenScanner: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = edgeMargin),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = StudioColors.TextSecondary,
                )
                FilledTonalButton(
                    onClick = onOpenScanner,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (hasBlePermissions) "Open Devices & BLE Scanner" else "Grant Bluetooth Permission")
                }
            }
        }
    }
}

/**
 * Organized Lights Deck: separates connected Remotes & Triggers from Strobe Groups A..F,
 * with filter chips showing live unit counts per group.
 */
@Composable
private fun OrganizedLightsDeckView(
    state: StudioDashboardState,
    isWideScreen: Boolean,
    edgeMargin: Dp,
    hasBlePermissions: Boolean,
    onOpenScanner: () -> Unit,
    onEditNickname: (ConnectedProfotoUnit) -> Unit,
    viewModel: StudioViewModel,
) {
    if (state.connectedUnits.isEmpty()) {
        EmptyHardwarePromptCard(
            title = "No Profoto Equipment Connected",
            body = "Open the Devices tab to connect your Profoto Connect, Connect Pro, A2, or B10X Pro lights. Connected units are automatically organized here by Remote and Air Group (A–F).",
            hasBlePermissions = hasBlePermissions,
            edgeMargin = edgeMargin,
            onOpenScanner = onOpenScanner,
        )
        return
    }

    val remotes = state.connectedUnits.filter {
        it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE ||
            it.archetype == DeviceArchetype.CONNECT_REMOTE
    }
    val fixtures = state.connectedUnits.filter {
        it.archetype != DeviceArchetype.CONNECT_PRO_REMOTE &&
            it.archetype != DeviceArchetype.CONNECT_REMOTE
    }
    val groupCounts = fixtures.groupingBy { it.airGroup }.eachCount()

    val visibleGroups = if (state.selectedGroupFilter == AirGroup.ALL) {
        STUDIO_GROUPS_A_TO_F.filter { (groupCounts[it] ?: 0) > 0 }
    } else {
        listOf(state.selectedGroupFilter)
    }

    if (isWideScreen) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = edgeMargin, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(2) }) {
                GroupFilterChipBar(
                    selected = state.selectedGroupFilter,
                    groupCounts = groupCounts,
                    onSelect = viewModel::selectGroupFilter,
                )
            }

            if (state.selectedGroupFilter == AirGroup.ALL && remotes.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    StudioSectionHeader(
                        title = "REMOTES & TRIGGERS (${remotes.size})",
                        subtitle = remotes.joinToString(", ") { "Ch ${it.airChannel}" },
                    )
                }
                items(remotes, key = { "rem_${it.address}" }) { unit ->
                    UnitCardRouter(unit = unit, onEditNickname = { onEditNickname(unit) }, viewModel = viewModel)
                }
            }

            visibleGroups.forEach { grp ->
                val members = fixtures.filter { it.airGroup == grp }
                item(span = { GridItemSpan(2) }, key = "hdr_${grp.name}") {
                    StudioSectionHeader(
                        title = "GROUP ${grp.label} (${members.size})",
                        subtitle = if (members.isNotEmpty()) "Ch ${members.first().airChannel}" else "Empty",
                    )
                }
                if (members.isEmpty()) {
                    item(span = { GridItemSpan(2) }, key = "empty_${grp.name}") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                text = "No lights currently assigned to Group ${grp.label}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioColors.TextSecondary,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                } else {
                    items(members, key = { it.address }) { unit ->
                        UnitCardRouter(unit = unit, onEditNickname = { onEditNickname(unit) }, viewModel = viewModel)
                    }
                }
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = edgeMargin, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                GroupFilterChipBar(
                    selected = state.selectedGroupFilter,
                    groupCounts = groupCounts,
                    onSelect = viewModel::selectGroupFilter,
                )
            }

            if (state.selectedGroupFilter == AirGroup.ALL && remotes.isNotEmpty()) {
                item {
                    StudioSectionHeader(
                        title = "REMOTES & TRIGGERS (${remotes.size})",
                        subtitle = remotes.joinToString(", ") { "Ch ${it.airChannel}" },
                    )
                }
                items(remotes, key = { "rem_${it.address}" }) { unit ->
                    UnitCardRouter(unit = unit, onEditNickname = { onEditNickname(unit) }, viewModel = viewModel)
                }
            }

            visibleGroups.forEach { grp ->
                val members = fixtures.filter { it.airGroup == grp }
                item(key = "hdr_${grp.name}") {
                    StudioSectionHeader(
                        title = "GROUP ${grp.label} (${members.size})",
                        subtitle = if (members.isNotEmpty()) "Ch ${members.first().airChannel}" else "Empty",
                    )
                }
                if (members.isEmpty()) {
                    item(key = "empty_${grp.name}") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                text = "No lights currently assigned to Group ${grp.label}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioColors.TextSecondary,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                } else {
                    items(members, key = { it.address }) { unit ->
                        UnitCardRouter(unit = unit, onEditNickname = { onEditNickname(unit) }, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Continuous / Modeling Light Console (Official Profoto Control Continuous Light tab style).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContinuousLedDeckView(
    state: StudioDashboardState,
    isWideScreen: Boolean,
    edgeMargin: Dp,
    hasBlePermissions: Boolean,
    onOpenScanner: () -> Unit,
    onEditNickname: (ConnectedProfotoUnit) -> Unit,
    viewModel: StudioViewModel,
) {
    val ledCapableUnits = state.connectedUnits.filter {
        it.archetype != DeviceArchetype.CONNECT_REMOTE &&
            it.archetype != DeviceArchetype.CONNECT_PRO_REMOTE
    }
    val airRemotes = state.connectedUnits.filter {
        it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE ||
            it.archetype == DeviceArchetype.CONNECT_REMOTE
    }

    if (ledCapableUnits.isEmpty() && airRemotes.isEmpty()) {
        EmptyHardwarePromptCard(
            title = "No Modeling / Continuous LEDs Linked",
            body = "Connect a Profoto Connect, Connect Pro, A2, or B10X Pro / B10 light to control studio modeling and continuous LED lights from one console.",
            hasBlePermissions = hasBlePermissions,
            edgeMargin = edgeMargin,
            onOpenScanner = onOpenScanner,
        )
        return
    }

    val activeGroupLedCount = state.groupChannels.count { it.isModelingEnabled }
    val headerSubtitle = if (ledCapableUnits.isNotEmpty()) {
        "${ledCapableUnits.count { it.videoLightEnabled }}/${ledCapableUnits.size} FIXTURES · $activeGroupLedCount/6 GRP"
    } else {
        "$activeGroupLedCount/6 AIR GROUPS ON"
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = edgeMargin, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Master Studio LED Bar
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StudioSectionHeader(
                        title = "MASTER MODELING / LED",
                        subtitle = headerSubtitle,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.setAllContinuousLights(true) },
                            enabled = state.activeReadyCount > 0,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text("All LEDs ON")
                        }
                        FilledTonalButton(
                            onClick = { viewModel.setAllContinuousLights(false) },
                            enabled = state.activeReadyCount > 0,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text("All LEDs OFF")
                        }
                    }

                    // Group A..F Modeling Toggles
                    val activeChannels = state.groupChannels.filter { it.hasActiveHardware }
                    if (activeChannels.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            activeChannels.forEach { ch ->
                                FilterChip(
                                    selected = ch.isModelingEnabled,
                                    onClick = { viewModel.toggleStudioGroupModeling(ch.group) },
                                    label = { Text("Grp ${ch.group.label} LED") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (airRemotes.isNotEmpty()) {
            item {
                StudioSectionHeader(
                    title = "AIR MODELING REMOTES (${airRemotes.size})",
                    subtitle = airRemotes.joinToString(", ") { "Ch ${it.airChannel}" },
                )
            }
            items(airRemotes, key = { "air_led_${it.address}" }) { remote ->
                RemoteAirModelingCard(
                    unit = remote,
                    onGroupModelingToggle = { idx -> viewModel.toggleConnectProGroupModeling(remote.address, idx) },
                    onEditNickname = { onEditNickname(remote) },
                )
            }
        }

        if (ledCapableUnits.isNotEmpty()) {
            item {
                StudioSectionHeader(
                    title = "DIRECT CONTINUOUS / MODELING FIXTURES (${ledCapableUnits.size})",
                )
            }
        }

        if (isWideScreen && ledCapableUnits.size > 1) {
            items(ledCapableUnits.chunked(2)) { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    pair.forEach { unit ->
                        ContinuousLedControlCard(
                            unit = unit,
                            onVideoEnable = { viewModel.setVideoEnable(unit.address, it) },
                            onVideoIntensityPercent = { viewModel.setVideoIntensityPct(unit.address, it) },
                            onVideoKelvin = { viewModel.setVideoKelvin(unit.address, it) },
                            onVideoMode = { viewModel.setVideoMode(unit.address, it) },
                            onEditNickname = { onEditNickname(unit) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            items(ledCapableUnits, key = { "led_${it.address}" }) { unit ->
                ContinuousLedControlCard(
                    unit = unit,
                    onVideoEnable = { viewModel.setVideoEnable(unit.address, it) },
                    onVideoIntensityPercent = { viewModel.setVideoIntensityPct(unit.address, it) },
                    onVideoKelvin = { viewModel.setVideoKelvin(unit.address, it) },
                    onVideoMode = { viewModel.setVideoMode(unit.address, it) },
                    onEditNickname = { onEditNickname(unit) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupFilterChipBar(
    selected: AirGroup,
    groupCounts: Map<AirGroup, Int>,
    onSelect: (AirGroup) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == AirGroup.ALL,
            onClick = { onSelect(AirGroup.ALL) },
            label = { Text("All") },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp),
        )
        STUDIO_GROUPS_A_TO_F.forEach { grp ->
            val count = groupCounts[grp] ?: 0
            if (count > 0 || selected == grp) {
                FilterChip(
                    selected = selected == grp,
                    onClick = { onSelect(grp) },
                    label = { Text("Grp ${grp.label} ($count)") },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun UnitCardRouter(
    unit: ConnectedProfotoUnit,
    onEditNickname: () -> Unit,
    viewModel: StudioViewModel,
) {
    when (unit.archetype) {
        DeviceArchetype.CONNECT_PRO_REMOTE -> ConnectProConsoleCard(
            unit = unit,
            onFlashModeChange = { viewModel.setFlashMode(unit.address, it) },
            onAirProtocolChange = { viewModel.setAirProtocol(unit.address, it) },
            onAirChannelChange = { viewModel.setAirChannel(unit.address, it) },
            onTtlEvChange = { viewModel.setTtlEvComp(unit.address, it) },
            onGroupPowerChange = { idx, v -> viewModel.setConnectProGroupPower(unit.address, idx, v) },
            onGroupMuteToggle = { idx -> viewModel.toggleConnectProGroupMute(unit.address, idx) },
            onGroupModelingToggle = { idx -> viewModel.toggleConnectProGroupModeling(unit.address, idx) },
            onAllGroupsTrim = { delta -> viewModel.stepRemoteGroupTrim(unit.address, AirGroup.ALL, delta) },
            onTestFlash = { viewModel.triggerUnitTestFlash(unit.address) },
            onEditNickname = onEditNickname,
            onRetryConnect = { viewModel.retryConnect(unit.address) },
            onDisconnect = { viewModel.disconnect(unit.address) },
        )
        DeviceArchetype.CONNECT_REMOTE -> ConnectTriggerCard(
            unit = unit,
            onModeChange = { viewModel.setConnectMode(unit.address, it) },
            onTtlEvChange = { viewModel.setTtlEvComp(unit.address, it) },
            onAirChannelChange = { viewModel.setAirChannel(unit.address, it) },
            onRelativeGroupTrim = { grp, delta -> viewModel.stepRemoteGroupTrim(unit.address, grp, delta) },
            onGroupMuteToggle = { idx -> viewModel.toggleConnectProGroupMute(unit.address, idx) },
            onGroupModelingToggle = { idx -> viewModel.toggleConnectProGroupModeling(unit.address, idx) },
            onTestFlash = { viewModel.triggerUnitTestFlash(unit.address) },
            onEditNickname = onEditNickname,
            onRetryConnect = { viewModel.retryConnect(unit.address) },
            onDisconnect = { viewModel.disconnect(unit.address) },
        )
        else -> MonolightControlCard(
            unit = unit,
            onPowerChange = { viewModel.setFlashPower(unit.address, it) },
            onHeadToggle = { viewModel.setHeadOn(unit.address, it) },
            onFreezeToggle = { viewModel.setFreeze(unit.address, it) },
            onTestFlash = { viewModel.triggerUnitTestFlash(unit.address) },
            onVideoEnable = { viewModel.setVideoEnable(unit.address, it) },
            onVideoIntensityPercent = { viewModel.setVideoIntensityPct(unit.address, it) },
            onVideoKelvin = { viewModel.setVideoKelvin(unit.address, it) },
            onVideoMode = { viewModel.setVideoMode(unit.address, it) },
            onAirGroupChange = { viewModel.setAirGroup(unit.address, it) },
            onAirChannelChange = { viewModel.setAirChannel(unit.address, it) },
            onCaptureVideoPriority = { viewModel.setCaptureVideoPriority(unit.address, it) },
            onIrSlaveToggle = { viewModel.setIrSlave(unit.address, it) },
            onReadySoundToggle = { viewModel.setReadySound(unit.address, it) },
            onDisplayOrientation = { viewModel.setDisplayOrientation(unit.address, it) },
            onDisplayBrightness = { viewModel.setDisplayBrightness(unit.address, it) },
            onRenameAlias = { viewModel.renameAlias(unit.address, it) },
            onEditNickname = onEditNickname,
            onRetryConnect = { viewModel.retryConnect(unit.address) },
            onDisconnect = { viewModel.disconnect(unit.address) },
        )
    }
}

/**
 * Equipment Manager & BLE Scanner Deck:
 * - Section 1: Connected Equipment (displayed by Nickname with Hardware Model permanently visible).
 * - Section 2: Nearby Profoto BLE Peripherals (resolved via 0xFDBB Product ID + persistent Nicknames).
 */
@Composable
private fun EquipmentManagerDeckView(
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

        // Debug-only Lab Bench Simulator (stripped completely from Release builds)
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
                StudioSectionHeader(
                    title = "CONNECTED EQUIPMENT (${state.connectedUnits.size})",
                    subtitle = "${state.activeReadyCount} READY",
                )
            }
            items(state.connectedUnits, key = { "conn_${it.address}" }) { unit ->
                ConnectedEquipmentRow(
                    unit = unit,
                    onEditNickname = { onEditConnectedNickname(unit) },
                    onRetry = { viewModel.retryConnect(unit.address) },
                    onDisconnect = { viewModel.disconnect(unit.address) },
                )
            }
        }

        item {
            StudioSectionHeader(
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
                ScannedDeviceRow(
                    device = dev,
                    savedNickname = state.nicknameFor(dev.address),
                    onEditNickname = { onEditScannedNickname(dev) },
                    onConnect = { viewModel.connect(dev) },
                )
            }
        }

        // Unobtrusive About footer card at the very bottom of the Devices tab
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
    onEditNickname: () -> Unit,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val batLabel = if (unit.chargerPluggedIn) "AC·${unit.batteryBars}/4" else "BAT ${unit.batteryBars}/4"
    val roleSummary = when (unit.archetype) {
        DeviceArchetype.CONNECT_PRO_REMOTE -> "Ch ${unit.airChannel} · ${unit.remoteMode.label} · $batLabel"
        DeviceArchetype.CONNECT_REMOTE -> "Ch ${unit.airChannel} · ${unit.remoteMode.label} · $batLabel"
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
                Text(
                    text = unit.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )
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
                    ) {
                        Text("Retry")
                    }
                } else {
                    FilledTonalButton(
                        onClick = onDisconnect,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannedDeviceRow(
    device: DiscoveredProfotoDevice,
    savedNickname: String?,
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
                Text(
                    text = primaryTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )
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
                    onClick = onEditNickname,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit nickname for $primaryTitle",
                        tint = StudioColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                FilledTonalButton(
                    onClick = onConnect,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

/**
 * Steven Hoober Lower-Center Natural Thumb Zone Dock:
 * - Exactly ONE solid high-contrast Primary CTA per viewport state.
 * - 4-destination visible bottom tab bar (Groups, Lights, LED, Devices) with icon + label and safe-area padding.
 */
@Composable
private fun ThumbZoneBottomDock(
    state: StudioDashboardState,
    currentTab: StudioNavTab,
    hasBlePermissions: Boolean,
    onSelectTab: (StudioNavTab) -> Unit,
    onPrimaryCta: () -> Unit,
) {
    val navItemColors = NavigationBarItemDefaults.colors(
        indicatorColor = StudioColors.TextPrimary,
        selectedIconColor = StudioColors.CanvasBase,
        selectedTextColor = StudioColors.TextPrimary,
        unselectedIconColor = StudioColors.TextSecondary,
        unselectedTextColor = StudioColors.TextSecondary,
    )

    Surface(
        color = StudioColors.SheetElevated,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 0.5.dp, color = StudioColors.SubtleOutline)
                .navigationBarsPadding()
                .padding(top = 8.dp),
        ) {
            val ctaText = when {
                !hasBlePermissions -> "ALLOW BLUETOOTH ACCESS"
                currentTab == StudioNavTab.DEVICES -> if (state.isScanning) "STOP BLE SCAN" else "SCAN PROFOTO BLE"
                state.activeReadyCount == 0 -> "SCAN PROFOTO BLE"
                else -> "TEST STUDIO FLASH (${state.activeReadyCount})"
            }

            Button(
                onClick = onPrimaryCta,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.FlashAmber,
                    contentColor = StudioColors.OnFlashAmber,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 52.dp),
            ) {
                Icon(
                    imageVector = if (state.activeReadyCount > 0 && currentTab != StudioNavTab.DEVICES) {
                        Icons.Default.Bolt
                    } else {
                        Icons.AutoMirrored.Filled.BluetoothSearching
                    },
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = ctaText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            NavigationBar(
                containerColor = StudioColors.SheetElevated,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = currentTab == StudioNavTab.GROUPS,
                    onClick = { onSelectTab(StudioNavTab.GROUPS) },
                    icon = { Icon(Icons.Default.GridView, contentDescription = "Groups") },
                    label = { Text("Groups", maxLines = 1) },
                    colors = navItemColors,
                )
                NavigationBarItem(
                    selected = currentTab == StudioNavTab.LIGHTS,
                    onClick = { onSelectTab(StudioNavTab.LIGHTS) },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "Lights") },
                    label = { Text("Lights (${state.activeReadyCount})", maxLines = 1) },
                    colors = navItemColors,
                )
                NavigationBarItem(
                    selected = currentTab == StudioNavTab.LED,
                    onClick = { onSelectTab(StudioNavTab.LED) },
                    icon = { Icon(Icons.Default.LightMode, contentDescription = "LED") },
                    label = { Text("LED", maxLines = 1) },
                    colors = navItemColors,
                )
                NavigationBarItem(
                    selected = currentTab == StudioNavTab.DEVICES,
                    onClick = { onSelectTab(StudioNavTab.DEVICES) },
                    icon = { Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = "Devices") },
                    label = { Text("Devices (${state.connectedUnits.size})", maxLines = 1) },
                    colors = navItemColors,
                )
            }
        }
    }
}
