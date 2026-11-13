package com.bcova.namplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bcova.namplayer.rig.Preset

/** Save, load, rename and delete presets (a preset = the whole chain + gate + EQ + levels). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsSheet(vm: AmpViewModel, onDismiss: () -> Unit) {
    var naming by remember { mutableStateOf<Preset?>(null) } // preset being renamed
    var savingNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Preset?>(null) }
    val current = vm.currentPreset

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Presets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "A preset saves your whole chain, gate, EQ and volume. Your current setup is also " +
                    "remembered automatically when you close the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { savingNew = true }) { Text("Save as new") }
                if (current != null) {
                    OutlinedButton(onClick = vm::updateCurrentPreset, enabled = vm.presetModified) {
                        Text("Update \"${current.name}\"", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (vm.presets.isEmpty()) {
                Text(
                    "No presets yet. Set up a sound you like, then tap Save as new.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(vm.presets, key = { it.id }) { preset ->
                    val selected = preset.id == current?.id
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.loadPreset(preset); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = if (selected) "Current preset" else null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(preset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                preset.rig.blocks.mapNotNull { it.tone?.title ?: it.fileName }.joinToString(" → ").ifEmpty { "Empty chain" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { naming = preset }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                        IconButton(onClick = { deleting = preset }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                }
            }
        }
    }

    if (savingNew) {
        NameDialog("Save preset", "", onDismiss = { savingNew = false }) { name ->
            vm.savePresetAs(name)
            savingNew = false
        }
    }
    naming?.let { preset ->
        NameDialog("Rename preset", preset.name, onDismiss = { naming = null }) { name ->
            vm.renamePreset(preset, name)
            naming = null
        }
    }
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${preset.name}\"?") },
            text = { Text("This can't be undone. Your current sound won't change.") },
            confirmButton = {
                TextButton(onClick = { vm.deletePreset(preset); deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                singleLine = true,
                placeholder = { Text("e.g. Clean Twin, 5150 Lead") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
