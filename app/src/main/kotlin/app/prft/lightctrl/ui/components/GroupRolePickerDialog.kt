package app.prft.lightctrl.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.ui.theme.StudioColors

val STUDIO_GROUP_ROLE_SUGGESTIONS = listOf("Key", "Fill", "Rim", "Hair", "Background", "Kicker", "Top")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupRolePickerDialog(
    group: AirGroup,
    currentRole: String?,
    onSaveRole: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var textInput by rememberSaveable { mutableStateOf(currentRole.orEmpty()) }
    val commonRoles = STUDIO_GROUP_ROLE_SUGGESTIONS

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioColors.SheetElevated,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "Group ${group.label} Role",
                style = MaterialTheme.typography.titleMedium,
                color = StudioColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Select common studio role or enter custom label:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    commonRoles.forEach { role ->
                        FilterChip(
                            selected = textInput.equals(role, ignoreCase = true),
                            onClick = { textInput = role },
                            label = { Text(role) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Custom Role (e.g. Accent)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSaveRole(textInput.trim().takeIf { it.isNotEmpty() })
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Save", color = StudioColors.TextPrimary)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!currentRole.isNullOrBlank() || textInput.isNotBlank()) {
                    TextButton(
                        onClick = {
                            onSaveRole(null)
                            onDismiss()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Clear", color = StudioColors.StudioRed)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel", color = StudioColors.TextSecondary)
                }
            }
        },
    )
}
