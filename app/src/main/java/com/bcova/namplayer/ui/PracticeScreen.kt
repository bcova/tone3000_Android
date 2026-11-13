package com.bcova.namplayer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

/** Metronome + drum loops, looper, backing tracks. Everything plays through your headphones with the rig. */
@Composable
fun PracticeScreen(amp: AmpViewModel, onBack: () -> Unit, vm: PracticeViewModel = viewModel()) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Practice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!amp.running) {
                Button(onClick = amp::toggleAudio, modifier = Modifier.padding(end = 8.dp)) { Text("Start audio") }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!amp.running) {
                Text(
                    "Audio is off. Start it to hear the metronome, looper and backing tracks.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            RhythmCard(vm)
            LooperCard(vm)
            TrackCard(vm)
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
private fun PracticeCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

// ---- Metronome / drums

@Composable
private fun RhythmCard(vm: PracticeViewModel) {
    PracticeCard("Metronome & drums") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${vm.bpm.roundToInt()}", fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text(" BPM", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.weight(1f))
            // Beat lights
            val beats = if (vm.style == DrumStyle.CLICK) vm.beatsPerBar else 4
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(beats.coerceAtMost(8)) { i ->
                    val lit = vm.beat == i
                    Box(
                        Modifier.size(12.dp).clip(CircleShape).background(
                            when {
                                lit && i == 0 -> MaterialTheme.colorScheme.tertiary
                                lit -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.updateBpm(vm.bpm - 1) }) { Text("−") }
            Slider(value = vm.bpm, onValueChange = { vm.updateBpm(it.roundToInt().toFloat()) }, valueRange = 40f..240f, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.updateBpm(vm.bpm + 1) }) { Text("+") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::toggleRhythm,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = if (vm.rhythmOn) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors(),
            ) { Text(if (vm.rhythmOn) "Stop" else "Play", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = vm::tapTempo, modifier = Modifier.weight(1f).height(52.dp)) { Text("Tap tempo") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DrumStyle.entries.forEach { s ->
                FilterChip(selected = vm.style == s, onClick = { vm.updateStyle(s) }, label = { Text(s.label) })
            }
        }
        if (vm.style == DrumStyle.CLICK) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Beats per bar", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { vm.updateBeats(vm.beatsPerBar - 1) }) { Text("−") }
                Text("${vm.beatsPerBar}", modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { vm.updateBeats(vm.beatsPerBar + 1) }) { Text("+") }
            }
        } else {
            Text("Drum grooves are in 4/4.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VolumeRow("Volume", vm.rhythmVolume, 0f..1.5f, vm::updateRhythmVolume)
        Text(
            "The plain click isn't included in recordings; drum grooves are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Looper

@Composable
private fun LooperCard(vm: PracticeViewModel) {
    PracticeCard("Looper") {
        val (label, color) = when (vm.looperState) {
            LooperState.EMPTY -> "Record" to MaterialTheme.colorScheme.error
            LooperState.RECORDING -> "Play" to Color(0xFF43A047)
            LooperState.PLAYING -> "Overdub" to MaterialTheme.colorScheme.secondary
            LooperState.OVERDUBBING -> "Play" to Color(0xFF43A047)
            LooperState.STOPPED -> "Play" to Color(0xFF43A047)
        }
        Text(
            when (vm.looperState) {
                LooperState.EMPTY -> "Press Record, play a riff, then press Play to loop it."
                LooperState.RECORDING -> "Recording… ${formatTime(vm.loopPosition)}"
                LooperState.PLAYING -> "Looping · ${vm.loopLayers} layer${if (vm.loopLayers == 1) "" else "s"}"
                LooperState.OVERDUBBING -> "Overdubbing a new layer…"
                LooperState.STOPPED -> "Stopped"
            },
            color = if (vm.looperState == LooperState.RECORDING || vm.looperState == LooperState.OVERDUBBING)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (vm.loopLength > 0) {
            LinearProgressIndicator(
                progress = { (vm.loopPosition / vm.loopLength).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Loop length ${formatTime(vm.loopLength)}" + if (vm.rhythmOn) " (snapped to whole bars)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = vm::looperPress,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color),
            shape = RoundedCornerShape(12.dp),
        ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::looperStopPlay, enabled = vm.looperState != LooperState.EMPTY, modifier = Modifier.weight(1f)) {
                Text(if (vm.looperState == LooperState.STOPPED) "Play" else "Stop")
            }
            OutlinedButton(onClick = vm::looperUndo, enabled = vm.canUndo, modifier = Modifier.weight(1f)) { Text("Undo") }
            OutlinedButton(onClick = vm::looperClear, enabled = vm.looperState != LooperState.EMPTY, modifier = Modifier.weight(1f)) { Text("Clear") }
        }
        VolumeRow("Loop volume", vm.looperVolume, 0f..1.5f, vm::updateLooperVolume)
        Text(
            "Up to 60 seconds. With the metronome on, the loop snaps to whole bars. " +
                "Footswitch actions for the looper are in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Backing tracks

@Composable
private fun TrackCard(vm: PracticeViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importTrack) }
    var deleting by remember { mutableStateOf<com.bcova.namplayer.audio.BackingTrack?>(null) }

    PracticeCard("Backing tracks") {
        vm.importProgress?.let { p ->
            Text("Adding song… ${(p * 100).roundToInt()}%")
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = vm.importProgress == null) {
            Text("Add a song from your phone")
        }
        val current = vm.currentTrack
        if (current != null) {
            Text(current.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Slider(
                value = vm.trackPosition.toFloat(),
                onValueChange = { vm.seekTrack(it.toDouble()) },
                valueRange = 0f..vm.trackDuration.toFloat().coerceAtLeast(0.1f),
            )
            Row {
                Text(formatTime(vm.trackPosition), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(formatTime(vm.trackDuration), style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.skip(-10.0) }) { Text("−10s") }
                Button(onClick = vm::toggleTrack, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(if (vm.trackPlaying) "Pause" else "Play", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { vm.skip(10.0) }) { Text("+10s") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = vm::loopButton) {
                    Text(
                        when {
                            vm.loopA == null -> "Set loop start (A)"
                            vm.loopB == null -> "Set loop end (B)"
                            else -> "Clear loop"
                        },
                    )
                }
                Spacer(Modifier.width(12.dp))
                val a = vm.loopA
                val b = vm.loopB
                Text(
                    when {
                        a != null && b != null -> "Looping ${formatTime(a)}–${formatTime(b)}"
                        a != null -> "A = ${formatTime(a)}"
                        else -> "Repeat a section, e.g. a solo"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VolumeRow("Track volume", vm.trackVolume, 0f..1.5f, vm::updateTrackVolume)
        }
        if (vm.tracks.isNotEmpty()) {
            Text("Your songs", style = MaterialTheme.typography.labelLarge)
            vm.tracks.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.selectTrack(t) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (t.id == current?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(formatTime(t.seconds), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { deleting = t }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            }
        }
        Text(
            "MP3, M4A, WAV, FLAC and OGG work. Songs are converted once and kept in the app. " +
                "To jam with YouTube instead, turn on \"Play along with other apps\" in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${t.name}\"?") },
            text = { Text("It's removed from Ampfield only. The original file on your phone stays.") },
            confirmButton = { TextButton(onClick = { vm.deleteTrack(t); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VolumeRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text("${(value * 100).roundToInt()}%", modifier = Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
    }
}
