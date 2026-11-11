package app.prft.lightctrl.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.prft.lightctrl.domain.ConnectedProfotoUnit
import app.prft.lightctrl.domain.StudioDashboardState
import app.prft.lightctrl.domain.StudioGroupChannelState
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.DeviceArchetype
import app.prft.lightctrl.protocol.FlashMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.ui.theme.StudioColors
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
import kotlin.math.roundToInt

private const val MIN_TENTH_STOPS = 10  // 1.0 f-stops
private const val MAX_TENTH_STOPS = 100 // 10.0 f-stops

/**
 * Non-scrolling 6-Channel Studio Mixing Console (Groups A–F).
 *
 * - Eliminates vertical scrolling completely: fits 100% inside viewport height on both
 *   Samsung Galaxy Z Fold 7 Cover (~21:9) and Unfolded Inner (~7:6) displays.
 * - Integrates an Active Remote bar at top when Connect Pro or Connect is linked (showing Nickname + Model).
 * - Anti-thumb-occlusion: live 0.1-stop monospace readout sits at the top of each strip above finger travel.
 * - WCAG 2.2 SC 2.5.7 & Touch Geometry: every interactive target >= 48x48dp with >= 8dp separation,
 *   pairing smooth vertical fader drag with single-tap +/- 0.1-stop steppers and ON/OFF mute pills.
 */
@Composable
fun VerticalGroupFaderBank(
    state: StudioDashboardState,
    edgeMargin: Dp,
    onMasterAllTrim: (deltaTenths: Int) -> Unit,
    onGroupPowerChange: (group: AirGroup, tenthStops: Int) -> Unit,
    onGroupMuteToggle: (group: AirGroup) -> Unit,
    onRemoteChannelChange: (address: String, channel: Int) -> Unit = { _, _ -> },
    onRemoteToggleMode: (unit: ConnectedProfotoUnit) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val horizontalPad = if (edgeMargin > 16.dp) edgeMargin else 10.dp
    val masterEnabled = state.activeReadyCount > 0
    val activeRemote = state.connectedUnits.firstOrNull {
        it.isReady && (
            it.archetype == DeviceArchetype.CONNECT_PRO_REMOTE ||
                it.archetype == DeviceArchetype.CONNECT_REMOTE
            )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPad, vertical = 6.dp),
    ) {
        // Optional Active Camera Remote Strip (Nickname + Hardware Model Always Visible)
        if (activeRemote != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioColors.CardSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, StudioColors.SubtleOutline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeRemote.displayTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val modeText = activeRemote.remoteMode.label
                    val subPrefix = if (activeRemote.hasNickname) "${activeRemote.hardwareModelName} · " else ""
                    Text(
                        text = "${subPrefix}Ch ${activeRemote.airChannel} · $modeText",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeRemote.archetype == DeviceArchetype.CONNECT_REMOTE) {
                        FilledTonalButton(
                            onClick = { onRemoteToggleMode(activeRemote) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .widthIn(min = 52.dp),
                        ) {
                            val badge = when (activeRemote.remoteMode) {
                                RemoteOperatingMode.AUTO_TTL -> "AUTO"
                                RemoteOperatingMode.MANUAL -> "MAN"
                                RemoteOperatingMode.OFF -> "OFF"
                            }
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = { onRemoteChannelChange(activeRemote.address, activeRemote.airChannel - 1) },
                        enabled = activeRemote.airChannel > activeRemote.minAirChannel,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .widthIn(min = 48.dp),
                    ) { Text("Ch-") }
                    FilledTonalButton(
                        onClick = { onRemoteChannelChange(activeRemote.address, activeRemote.airChannel + 1) },
                        enabled = activeRemote.airChannel < activeRemote.maxAirChannel,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .widthIn(min = 48.dp),
                    ) { Text("Ch+") }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // 1. Compact Top Master Ratio Trim Bar (All Groups)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StudioColors.CardSurface, RoundedCornerShape(12.dp))
                .border(1.dp, StudioColors.SubtleOutline, RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "ALL",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = if (masterEnabled) StudioColors.TextPrimary else StudioColors.TextMuted,
                )
            }

            MasterStepButton(
                label = "-1.0",
                enabled = masterEnabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMasterAllTrim(-10)
                },
                modifier = Modifier.weight(1f),
            )
            MasterStepButton(
                label = "-0.1",
                enabled = masterEnabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMasterAllTrim(-1)
                },
                modifier = Modifier.weight(1f),
            )
            MasterStepButton(
                label = "+0.1",
                enabled = masterEnabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMasterAllTrim(1)
                },
                modifier = Modifier.weight(1f),
            )
            MasterStepButton(
                label = "+1.0",
                enabled = masterEnabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMasterAllTrim(10)
                },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. 6 Side-by-Side Vertical Channel Faders (A..F) — Zero Vertical Scroll
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.groupChannels.forEach { channel ->
                VerticalGroupFaderStrip(
                    channel = channel,
                    onPowerChange = { tenths -> onGroupPowerChange(channel.group, tenths) },
                    onMuteToggle = { onGroupMuteToggle(channel.group) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun MasterStepButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun VerticalGroupFaderStrip(
    channel: StudioGroupChannelState,
    onPowerChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var localTenthStops by remember { mutableIntStateOf(channel.powerTenthStops) }

    LaunchedEffect(channel.powerTenthStops, isDragging) {
        if (!isDragging) {
            localTenthStops = channel.powerTenthStops
        }
    }

    val interactive = channel.hasActiveHardware && channel.isEnabled
    val readoutText = when {
        !channel.hasActiveHardware -> "--"
        !channel.isEnabled -> "OFF"
        else -> String.format(Locale.US, "%.1f", localTenthStops / 10.0)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top Group Letter + Anti-Occlusion Readout Pill
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (isDragging) StudioColors.TextPrimary else StudioColors.CardSurface,
                    shape = RoundedCornerShape(10.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (isDragging || interactive) StudioColors.TextPrimary else StudioColors.SubtleOutline,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(vertical = 6.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = channel.group.label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                ),
                color = when {
                    isDragging -> StudioColors.CanvasBase
                    channel.hasActiveHardware -> StudioColors.TextPrimary
                    else -> StudioColors.TextMuted
                },
                maxLines = 1,
            )
            Text(
                text = readoutText,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
                color = when {
                    isDragging -> StudioColors.CanvasBase
                    interactive -> StudioColors.TextPrimary
                    else -> StudioColors.TextSecondary
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Single-Tap +0.1 Stepper (WCAG 2.2 SC 2.5.7)
        FilledTonalButton(
            onClick = {
                val next = (localTenthStops + 1).coerceAtMost(MAX_TENTH_STOPS)
                localTenthStops = next
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPowerChange(next)
            },
            enabled = interactive && localTenthStops < MAX_TENTH_STOPS,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics { contentDescription = "Group ${channel.group.label} plus 0.1 stop" },
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Precision Monochrome Vertical Studio Fader Track
        VerticalStudioFaderCanvas(
            groupLabel = channel.group.label,
            valueTenthStops = localTenthStops,
            enabled = interactive,
            onDragActiveChange = { isDragging = it },
            onValueChange = { snapped ->
                if (snapped != localTenthStops) {
                    if (snapped % 10 == 0) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    localTenthStops = snapped
                    onPowerChange(snapped)
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Single-Tap -0.1 Stepper (WCAG 2.2 SC 2.5.7)
        FilledTonalButton(
            onClick = {
                val next = (localTenthStops - 1).coerceAtLeast(MIN_TENTH_STOPS)
                localTenthStops = next
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPowerChange(next)
            },
            enabled = interactive && localTenthStops > MIN_TENTH_STOPS,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics { contentDescription = "Group ${channel.group.label} minus 0.1 stop" },
        ) {
            Text(
                text = "-",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom Group ON / MUTE Toggle
        OutlinedButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onMuteToggle()
            },
            enabled = channel.hasActiveHardware,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (interactive) StudioColors.TextPrimary else StudioColors.SubtleOutline,
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (interactive) StudioColors.TextPrimary else StudioColors.CardSurface,
                contentColor = if (interactive) StudioColors.CanvasBase else StudioColors.TextSecondary,
                disabledContainerColor = StudioColors.CardSurface,
                disabledContentColor = StudioColors.TextMuted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics { contentDescription = "Toggle Group ${channel.group.label}" },
        ) {
            Text(
                text = if (interactive) "ON" else "OFF",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun VerticalStudioFaderCanvas(
    groupLabel: String,
    valueTenthStops: Int,
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun yToTenthStops(y: Float, heightPx: Float, padPx: Float): Int {
        val usable = (heightPx - 2f * padPx).coerceAtLeast(1f)
        val clampedY = y.coerceIn(padPx, heightPx - padPx)
        val normalizedFromBottom = 1f - ((clampedY - padPx) / usable)
        val raw = MIN_TENTH_STOPS + normalizedFromBottom * (MAX_TENTH_STOPS - MIN_TENTH_STOPS)
        return raw.roundToInt().coerceIn(MIN_TENTH_STOPS, MAX_TENTH_STOPS)
    }

    val gestureModifier = if (enabled) {
        Modifier
            .pointerInput(groupLabel) {
                val padPx = 14.dp.toPx()
                detectTapGestures { offset ->
                    val snapped = yToTenthStops(offset.y, size.height.toFloat(), padPx)
                    onValueChange(snapped)
                }
            }
            .pointerInput(groupLabel, "drag") {
                val padPx = 14.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        onDragActiveChange(true)
                        onValueChange(yToTenthStops(offset.y, size.height.toFloat(), padPx))
                    },
                    onDragEnd = { onDragActiveChange(false) },
                    onDragCancel = { onDragActiveChange(false) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        onValueChange(yToTenthStops(change.position.y, size.height.toFloat(), padPx))
                    },
                )
            }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .background(StudioColors.CardSurface, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (enabled) StudioColors.SubtleOutline else StudioColors.ControlContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .semantics { contentDescription = "Group $groupLabel vertical power fader" }
            .then(gestureModifier),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padY = 14.dp.toPx()
            val usableHeight = (size.height - 2f * padY).coerceAtLeast(1f)
            val fraction = ((valueTenthStops - MIN_TENTH_STOPS).toFloat() / (MAX_TENTH_STOPS - MIN_TENTH_STOPS))
                .coerceIn(0f, 1f)
            val handleCenterY = padY + (1f - fraction) * usableHeight

            val centerX = size.width / 2f
            val slotWidth = 8.dp.toPx().coerceAtMost(size.width * 0.28f)
            val tickHalfWidth = (size.width * 0.34f).coerceAtLeast(slotWidth)

            // 1. Full-stop scale ticks (stops 2..9)
            for (stop in 2..9) {
                val stopFrac = (stop * 10 - MIN_TENTH_STOPS).toFloat() / (MAX_TENTH_STOPS - MIN_TENTH_STOPS)
                val tickY = padY + (1f - stopFrac) * usableHeight
                drawLine(
                    color = StudioColors.SubtleOutline,
                    start = Offset(centerX - tickHalfWidth, tickY),
                    end = Offset(centerX + tickHalfWidth, tickY),
                    strokeWidth = if (stop == 5) 1.6.dp.toPx() else 1.dp.toPx(),
                )
            }

            // 2. Recessed center fader slot
            drawRoundRect(
                color = StudioColors.CanvasBase,
                topLeft = Offset(centerX - slotWidth / 2f, padY),
                size = Size(slotWidth, usableHeight),
                cornerRadius = CornerRadius(slotWidth / 2f, slotWidth / 2f),
            )

            // 3. Active monochrome level fill rising from bottom
            val fillHeight = (size.height - padY - handleCenterY).coerceAtLeast(0f)
            if (fillHeight > 0f) {
                drawRoundRect(
                    color = if (enabled) StudioColors.TextPrimary else StudioColors.ControlContainer,
                    topLeft = Offset(centerX - slotWidth / 2f, handleCenterY),
                    size = Size(slotWidth, fillHeight),
                    cornerRadius = CornerRadius(slotWidth / 2f, slotWidth / 2f),
                )
            }

            // 4. Precision studio fader cap with center hairline
            val capWidth = (size.width - 10.dp.toPx()).coerceAtLeast(24.dp.toPx())
            val capHeight = 18.dp.toPx()
            drawRoundRect(
                color = if (enabled) StudioColors.FlashAmber else StudioColors.SubtleOutline,
                topLeft = Offset(centerX - capWidth / 2f, handleCenterY - capHeight / 2f),
                size = Size(capWidth, capHeight),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
            )
            drawLine(
                color = StudioColors.CanvasBase,
                start = Offset(centerX - capWidth * 0.32f, handleCenterY),
                end = Offset(centerX + capWidth * 0.32f, handleCenterY),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}
