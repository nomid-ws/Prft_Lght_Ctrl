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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import app.prft.lightctrl.ui.theme.StudioColors

data class NicknameEditTarget(
    val address: String,
    val hardwareModelName: String,
    val ratedSpecLabel: String,
    val currentNickname: String?,
)

private val STUDIO_NICKNAME_PRESETS = listOf(
    "Key Light",
    "Fill Light",
    "Rim Light",
    "Background",
    "Main Trigger",
    "Camera B",
)

/**
 * Accessible Monochrome Bottom Sheet for assigning or clearing persistent equipment nicknames
 * while keeping the authoritative Profoto Hardware Model permanently visible.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NicknameEditorSheet(
    target: NicknameEditTarget,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(target.address) {
        mutableStateOf(target.currentNickname.orEmpty())
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
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header with 48x48dp Close button (3 dismissal paths: drag handle, scrim, Close icon)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Equipment Nickname",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close nickname sheet")
                }
            }

            // Read-Only Hardware Model Badge (Model always stays visible)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioColors.CardSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, StudioColors.SubtleOutline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = target.hardwareModelName,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioColors.TextPrimary,
                    )
                    Text(
                        text = "${target.ratedSpecLabel} · ${target.address}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = StudioColors.TextSecondary,
                    )
                }
            }

            // Persistent Top-Aligned Label + 16sp Input Field
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Custom Studio Label",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioColors.TextSecondary,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 36) draft = it },
                    placeholder = {
                        Text(
                            text = "e.g., Key Light or Main Trigger",
                            color = StudioColors.TextMuted,
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            onSave(draft.trim().takeIf { it.isNotEmpty() })
                            onDismiss()
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 1-Tap Studio Role Presets
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Quick Presets",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.TextSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    STUDIO_NICKNAME_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = draft.trim().equals(preset, ignoreCase = true),
                            onClick = { draft = preset },
                            label = { Text(preset) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!target.currentNickname.isNullOrBlank() || draft.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            onSave(null)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp),
                    ) {
                        Text("Reset to Model")
                    }
                }
                Button(
                    onClick = {
                        onSave(draft.trim().takeIf { it.isNotEmpty() })
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.FlashAmber,
                        contentColor = StudioColors.OnFlashAmber,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp),
                ) {
                    Text(
                        text = "Save Label",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
