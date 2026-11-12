package app.prft.lightctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.prft.lightctrl.ui.theme.StudioColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ergonomic 0.1-stop power / EV scrubber engineered for studio operation:
 * - Renders live floating readout pill ABOVE the slider track while dragging to prevent thumb occlusion.
 * - Enforces >= 48x48dp touch targets and >= 8dp spacing on single-tap steppers (WCAG 2.2 SC 2.5.7).
 * - Emits tactile haptics on every tenth-stop and full-stop crossing.
 */
@Composable
fun TenthStopPowerSlider(
    label: String,
    valueTenthStops: Int,
    minTenthStops: Int,
    maxTenthStops: Int,
    onValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showSign: Boolean = false,
    coarseStepTenths: Int = 10,
    coarseLabelPrefix: String = "1.0",
    secondaryReadout: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var lastHapticVal by remember { mutableIntStateOf(valueTenthStops) }

    fun formatTenths(v: Int): String {
        val f = v / 10.0
        return if (showSign && v > 0) {
            String.format(Locale.US, "+%.1f", f)
        } else {
            String.format(Locale.US, "%.1f", f)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Anti-occlusion readout row above the touch track
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = StudioColors.TextSecondary,
            )

            Box(
                modifier = Modifier
                    .background(
                        color = if (isDragging) StudioColors.FlashAmber else StudioColors.ControlContainer,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (isDragging) StudioColors.FlashAmber else StudioColors.SubtleOutline,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatTenths(valueTenthStops),
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (isDragging) StudioColors.OnFlashAmber else StudioColors.TextPrimary,
                    )
                    if (!secondaryReadout.isNullOrBlank()) {
                        Text(
                            text = secondaryReadout,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = if (isDragging) StudioColors.OnFlashAmber else StudioColors.AirCyan,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Slider(
            value = valueTenthStops.toFloat(),
            onValueChange = { raw ->
                isDragging = true
                val snapped = raw.roundToInt().coerceIn(minTenthStops, maxTenthStops)
                if (snapped != lastHapticVal) {
                    if (snapped % 10 == 0) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    lastHapticVal = snapped
                    onValueChanged(snapped)
                }
            },
            onValueChangeFinished = {
                isDragging = false
            },
            valueRange = minTenthStops.toFloat()..maxTenthStops.toFloat(),
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = StudioColors.FlashAmber,
                activeTrackColor = StudioColors.FlashAmber,
                inactiveTrackColor = StudioColors.ControlContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "$label slider" },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Single-tap steppers (>= 48dp height, >= 8dp separation)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepButton(
                text = "-$coarseLabelPrefix",
                enabled = enabled && valueTenthStops > minTenthStops,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onValueChanged((valueTenthStops - coarseStepTenths).coerceAtLeast(minTenthStops))
                },
                modifier = Modifier.weight(1f),
            )
            StepButton(
                text = "-0.1",
                enabled = enabled && valueTenthStops > minTenthStops,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onValueChanged((valueTenthStops - 1).coerceAtLeast(minTenthStops))
                },
                modifier = Modifier.weight(1f),
            )
            StepButton(
                text = "+0.1",
                enabled = enabled && valueTenthStops < maxTenthStops,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onValueChanged((valueTenthStops + 1).coerceAtMost(maxTenthStops))
                },
                modifier = Modifier.weight(1f),
            )
            StepButton(
                text = "+$coarseLabelPrefix",
                enabled = enabled && valueTenthStops < maxTenthStops,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onValueChanged((valueTenthStops + coarseStepTenths).coerceAtMost(maxTenthStops))
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StepButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}
