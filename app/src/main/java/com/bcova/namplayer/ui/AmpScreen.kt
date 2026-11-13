package com.bcova.namplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bcova.namplayer.t3k.TargetSlot
import kotlin.math.roundToInt

/**
 * The player: a vertical signal chain (input → amp → cab → output) plus levels and volume.
 * Each block is where you add or change a tone (TONE3000 design rule 1: the entry point).
 */
@Composable
fun PlayerScreen(vm: AmpViewModel, onBrowse: (TargetSlot) -> Unit, signedIn: Boolean, onSignOut: () -> Unit) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NAM Player", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (signedIn) TextButton(onClick = onSignOut) { Text("Sign out") }
        }

        Button(
            onClick = {
                val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (vm.running || hasMic) vm.toggleAudio()
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = if (vm.running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors(),
        ) {
            Text(if (vm.running) "Stop" else "Start audio", fontWeight = FontWeight.Bold)
        }
        Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Meter("In", vm.inputLevelDb)

        ChainArrow()
        SignalBlock(
            slot = vm.model,
            emptyHint = "Add an amp, pedal or full rig",
            onBrowse = { onBrowse(TargetSlot.AMP) },
            onPhoneFile = { modelPicker.launch(arrayOf("*/*")) },
            onToggle = { vm.setEnabled(vm.model, it) },
            onRemove = { vm.clear(vm.model) },
        )
        ChainArrow()
        SignalBlock(
            slot = vm.ir,
            emptyHint = "Add a cabinet IR (skip for Amp + Cab tones)",
            onBrowse = { onBrowse(TargetSlot.CAB) },
            onPhoneFile = { irPicker.launch(arrayOf("audio/*", "audio/wav", "audio/x-wav")) },
            onToggle = { vm.setEnabled(vm.ir, it) },
            onRemove = { vm.clear(vm.ir) },
        )
        ChainArrow()

        Meter("Out", vm.outputLevelDb)

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
        Text(
            "Use a USB audio interface and headphones. Phone speakers will feed back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChainArrow() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** One block in the signal chain. Shows the loaded TONE3000 tone with its image and T3K mark (design rule 5). */
@Composable
private fun SignalBlock(
    slot: SlotState,
    emptyHint: String,
    onBrowse: () -> Unit,
    onPhoneFile: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val tone = slot.tone
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(slot.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (slot.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (slot.fileName != null) Switch(checked = slot.enabled, onCheckedChange = onToggle)
            }

            when {
                tone != null -> Row(
                    Modifier.clickable(onClick = onBrowse).alpha(if (slot.enabled) 1f else 0.4f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        ToneImage(tone.imageUrl, Modifier.size(72.dp))
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.Black.copy(alpha = 0.8f))
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                        ) { T3kMark(height = 10.dp) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(tone.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (tone.gearLabel.isNotEmpty()) Chip(tone.gearLabel)
                            Chip(tone.formatLabel, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f))
                        }
                        Text(
                            "${tone.modelName} · @${tone.creator}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                slot.fileName != null -> Text(
                    slot.fileName!!,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (slot.enabled) 1f else 0.4f),
                )
                else -> Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onBrowse)
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(emptyHint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            BrowseT3kButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth(), fullLogo = true)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPhoneFile, enabled = !slot.loading) { Text("Load from phone") }
                if (slot.fileName != null) TextButton(onClick = onRemove, enabled = !slot.loading) { Text("Remove") }
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
