package com.bcova.namplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

@Composable
fun AmpScreen(vm: AmpViewModel = viewModel()) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleAudio()
    }
    // .nam has no standard file type, so allow any file and check the extension after picking.
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.load(vm.model, it) }
    }
    val irPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.load(vm.ir, it) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("NAM Player", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Plug in a USB audio interface and headphones. Phone speakers will feed back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (vm.running || hasMic) vm.toggleAudio()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(if (vm.running) "Stop" else "Start audio")
            }
            Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Meter("In", vm.inputLevelDb)
            Meter("Out", vm.outputLevelDb)

            SlotCard(vm.model, vm, onPick = { modelPicker.launch(arrayOf("*/*")) })
            SlotCard(vm.ir, vm, onPick = { irPicker.launch(arrayOf("audio/*", "audio/wav", "audio/x-wav")) })

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GainSlider("Input gain", vm.inputGainDb, -20f..20f, vm::setInputGain)
                    GainSlider("Output volume", vm.outputGainDb, -40f..12f, vm::setOutputGain)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Normalize loudness")
                            Text(
                                "Makes different captures play at a similar volume",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = vm.normalize, onCheckedChange = vm::setNormalizeEnabled)
                    }
                }
            }
        }
    }

    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun SlotCard(slot: SlotState, vm: AmpViewModel, onPick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(slot.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = slot.enabled, onCheckedChange = { vm.setEnabled(slot, it) })
            }
            Text(
                slot.fileName ?: "No .${slot.extension} file loaded",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (slot.fileName == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPick, enabled = !slot.loading) { Text("Load .${slot.extension}") }
                if (slot.fileName != null) {
                    OutlinedButton(onClick = { vm.clear(slot) }, enabled = !slot.loading) { Text("Remove") }
                }
                if (slot.loading) CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun GainSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text("${if (value > 0) "+" else ""}${value.roundToInt()} dB")
        }
        Slider(value = value, onValueChange = { onChange(it.roundToInt().toFloat()) }, valueRange = range)
    }
}

@Composable
private fun Meter(label: String, levelDb: Float) {
    val fraction = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    val color = when {
        levelDb > -3f -> Color(0xFFE53935) // clipping risk
        levelDb > -12f -> Color(0xFFFDD835)
        else -> Color(0xFF43A047)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
        Box(
            Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(color))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (levelDb <= -60f) "-∞" else "${levelDb.roundToInt()} dB",
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
