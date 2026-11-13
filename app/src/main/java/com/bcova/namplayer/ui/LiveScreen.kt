package com.bcova.namplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Big buttons for playing live: switch presets, toggle blocks, tuner, record. */
@Composable
fun LiveScreen(vm: AmpViewModel, onTuner: () -> Unit, onBack: () -> Unit) {
    val preset = vm.currentPreset
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Live", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                if (vm.running) "CPU ${(vm.cpuLoad * 100).toInt()}%" else "Audio off",
                color = if (vm.running) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }

        // Current preset with big previous / next
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("◀", Modifier.weight(1f)) { vm.stepPreset(-1) }
            Text(
                preset?.name ?: "No preset",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(3f),
            )
            BigButton("▶", Modifier.weight(1f)) { vm.stepPreset(+1) }
        }

        // Preset grid
        if (vm.presets.isEmpty()) {
            Text(
                "Save some presets on the main screen to switch them here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.presets, key = { it.id }) { p ->
                val selected = p.id == preset?.id
                Tile(p.name, selected, MaterialTheme.colorScheme.primary) { vm.loadPreset(p) }
            }
        }

        // Block toggles
        val loaded = vm.blocks.filter { !it.isEmpty }
        if (loaded.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                loaded.take(4).forEach { b ->
                    Tile(
                        (b.tone?.title ?: b.fileName ?: b.kind.label),
                        b.enabled,
                        Color(0xFF43A047),
                        Modifier.weight(1f),
                        caption = b.kind.label.uppercase(),
                    ) { vm.setEnabled(b, !b.enabled) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Tuner", Modifier.weight(1f), onClick = onTuner)
            val rec = vm.recordingSeconds
            BigButton(
                if (rec != null) "● ${formatTime(rec)}" else "Record",
                Modifier.weight(1f),
                color = if (rec != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            ) { vm.toggleRecording() }
            BigButton(if (vm.running) "Stop" else "Start", Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant) {
                vm.toggleAudio()
            }
        }
    }
}

@Composable
private fun BigButton(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(12.dp),
    ) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
private fun Tile(
    text: String,
    on: Boolean,
    onColor: Color,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) onColor.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                text,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (on) Color.Black else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

fun formatTime(seconds: Double): String {
    val s = seconds.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
