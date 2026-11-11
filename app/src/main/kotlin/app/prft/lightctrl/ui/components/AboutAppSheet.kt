package app.prft.lightctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.prft.lightctrl.BuildConfig
import app.prft.lightctrl.domain.StudioDashboardState
import app.prft.lightctrl.ui.theme.StudioColors

/**
 * Unobtrusive Monochrome About Sheet showing exact App Version, Build Code,
 * Supported Profoto Hardware Catalog, Live BLE Session status, and Hardware Safety Guarantees.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutAppSheet(
    state: StudioDashboardState,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val buildTrack = if (BuildConfig.DEBUG) "Debug" else "Release"

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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Prft Light Ctrl",
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioColors.TextPrimary,
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE} · $buildTrack)",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = StudioColors.TextPrimary,
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close about sheet",
                        tint = StudioColors.TextSecondary,
                    )
                }
            }

            // Package & Live Session Telemetry
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioColors.CardSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, StudioColors.SubtleOutline, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AboutKeyValueRow("Package", BuildConfig.APPLICATION_ID)
                AboutKeyValueRow(
                    "BLE Radio",
                    if (state.isBluetoothEnabled) "Enabled · ${state.activeReadyCount} linked" else "Disabled",
                )
                AboutKeyValueRow("Protocol", "Profoto PUP GATT (0xFDBB)")
                AboutKeyValueRow("License", "GPL-3.0-or-later")
            }

            // Supported Profoto Equipment
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioColors.CardSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, StudioColors.SubtleOutline, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "SUPPORTED PROFOTO EQUIPMENT",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = StudioColors.TextPrimary,
                )
                Text(
                    text = "• Profoto Connect (AirTTL buttonless remote · Nikon, Canon, Sony, Olympus, Fujifilm)\n" +
                        "• Profoto Connect Pro (100-Ch AirX master remote · Nikon, Canon, Sony, Fujifilm, Leica, ISO)\n" +
                        "• Profoto A2 (100 Ws AirX monolight)\n" +
                        "• Profoto B10X Pro / Plus, B10X, B10, B10 Plus\n" +
                        "• Profoto D30, B20, B30, Pro-D3, Pro-B3, Pro-11, A10, C1 / C1 Plus",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextSecondary,
                )
            }

            // Hardware Safety & Zero-Cloud Guarantee
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioColors.CardSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, StudioColors.SubtleOutline, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "HARDWARE SAFETY & PRIVACY",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = StudioColors.TextPrimary,
                )
                Text(
                    text = "100% local Bluetooth Low Energy control with zero internet permissions, cloud accounts, or telemetry. Firmware/DFU GATT characteristics and PUP opcodes (46, 142, 154) are strictly quarantined.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextSecondary,
                )
            }

            HorizontalDivider(color = StudioColors.SubtleOutline)

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.FlashAmber,
                    contentColor = StudioColors.OnFlashAmber,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = "Done",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AboutKeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = StudioColors.TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = StudioColors.TextPrimary,
        )
    }
}
