package app.prft.lightctrl.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.prft.lightctrl.domain.StudioScene
import app.prft.lightctrl.domain.StudioSceneJsonCodec
import app.prft.lightctrl.ui.theme.StudioColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudioScenesSheet(
    savedScenes: List<StudioScene>,
    onSaveCurrentScene: (name: String, notes: String) -> Unit,
    onApplyScene: (StudioScene) -> Unit,
    onDeleteScene: (String) -> Unit,
    onImportScenes: (List<StudioScene>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var saveName by rememberSaveable { mutableStateOf("") }
    var saveNotes by rememberSaveable { mutableStateOf("") }

    var importErrorBanner by remember { mutableStateOf<String?>(null) }
    var actionBanner by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioColors.SheetElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Studio Scenes & Presets",
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioColors.TextPrimary,
                    )
                    Text(
                        text = "${savedScenes.size} saved presets",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextSecondary,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Scenes Sheet",
                        tint = StudioColors.TextSecondary,
                    )
                }
            }

            // Status feedback banner
            if (actionBanner != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioColors.ControlContainer, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    Text(text = actionBanner!!, color = StudioColors.TextPrimary)
                }
            }
            if (importErrorBanner != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioColors.ControlContainer, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    Text(text = importErrorBanner!!, color = StudioColors.StudioRed)
                }
            }

            // Action Bar: Primary Save CTA + Secondary Clipboard Import/Export row
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Save Current Setup", maxLines = 1)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val item = clipMgr.primaryClip?.getItemAt(0)?.text?.toString()
                                if (item.isNullOrBlank()) {
                                    importErrorBanner = "Clipboard is empty"
                                } else {
                                    val imported = StudioSceneJsonCodec.decodeFromJson(item)
                                    onImportScenes(imported)
                                    actionBanner = "Imported ${imported.size} preset(s) from clipboard"
                                    importErrorBanner = null
                                }
                            } catch (e: Exception) {
                                importErrorBanner = "Import failed: ${e.message}"
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Paste JSON", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            if (savedScenes.isNotEmpty()) {
                                val json = StudioSceneJsonCodec.encodeToJson(savedScenes)
                                val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipMgr.setPrimaryClip(ClipData.newPlainText("Studio Presets", json))
                                actionBanner = "Exported ${savedScenes.size} presets to clipboard"
                            }
                        },
                        enabled = savedScenes.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Copy All", maxLines = 1)
                    }
                }
            }

            // Save Scene Inline Form
            if (showSaveDialog) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Save Current Studio Scene", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = saveName,
                            onValueChange = { saveName = it },
                            label = { Text("Preset Name") },
                            placeholder = { Text("e.g. 3-Point Portrait") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = saveNotes,
                            onValueChange = { saveNotes = it },
                            label = { Text("Setup Notes (Optional)") },
                            placeholder = { Text("e.g. 3ft Octa on A, 1x4 Strip on B") },
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { showSaveDialog = false },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Cancel") }
                            Spacer(modifier = Modifier.size(8.dp))
                            FilledTonalButton(
                                onClick = {
                                    val name = saveName.trim().ifEmpty { "Preset ${savedScenes.size + 1}" }
                                    onSaveCurrentScene(name, saveNotes.trim())
                                    saveName = ""
                                    saveNotes = ""
                                    showSaveDialog = false
                                    actionBanner = "Saved preset: $name"
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Save") }
                        }
                    }
                }
            }

            HorizontalDivider(color = StudioColors.SubtleOutline)

            // Saved Scenes List
            if (savedScenes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No saved scenes. Tap 'Save Current Setup' to capture current light ratios.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextSecondary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(savedScenes, key = { it.id }) { scene ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = scene.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = StudioColors.TextPrimary,
                                        )
                                        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(scene.createdAtUtc))
                                        Text(
                                            text = dateStr,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StudioColors.TextSecondary,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        IconButton(
                                            onClick = {
                                                val json = StudioSceneJsonCodec.encodeSingleToJson(scene)
                                                val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipMgr.setPrimaryClip(ClipData.newPlainText("Preset ${scene.name}", json))
                                                actionBanner = "Copied '${scene.name}' to clipboard"
                                            },
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "Export to clipboard",
                                                tint = StudioColors.TextSecondary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                pendingDeleteId = if (pendingDeleteId == scene.id) null else scene.id
                                            },
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete preset",
                                                tint = StudioColors.TextSecondary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }

                                if (pendingDeleteId == scene.id) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedButton(
                                            onClick = { pendingDeleteId = null },
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        ) {
                                            Text("Cancel")
                                        }
                                        Button(
                                            onClick = {
                                                pendingDeleteId = null
                                                onDeleteScene(scene.id)
                                            },
                                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                                containerColor = StudioColors.StudioRed,
                                                contentColor = StudioColors.TextPrimary,
                                            ),
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        ) {
                                            Text("Delete Preset")
                                        }
                                    }
                                }

                                if (scene.setupNotes.isNotBlank()) {
                                    Text(
                                        text = scene.setupNotes,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = StudioColors.TextSecondary,
                                    )
                                }

                                // Group ratios badges
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    for (gp in scene.groupPresets) {
                                        val role = if (!gp.roleLabel.isNullOrBlank()) " (${gp.roleLabel})" else ""
                                        Box(
                                            modifier = Modifier
                                                .background(StudioColors.ControlContainer, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                text = "${gp.group.label}: ${String.format(Locale.US, "%.1f", gp.fStops)}$role",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = StudioColors.TextPrimary,
                                            )
                                        }
                                    }
                                    if (scene.remoteChannel != null) {
                                        Box(
                                            modifier = Modifier
                                                .background(StudioColors.ControlContainer, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                text = "Ch ${scene.remoteChannel}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = StudioColors.AirCyan,
                                            )
                                        }
                                    }
                                }

                                // Recall button
                                Button(
                                    onClick = {
                                        onApplyScene(scene)
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    Text("Recall Scene")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
