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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.bcova.namplayer.t3k.BlockKind
import com.bcova.namplayer.t3k.BlockTarget
import kotlin.math.roundToInt

/**
 * The player: a vertical signal chain (input → blocks… → output) plus levels and volume.
 * Each block is where you add or change a tone (TONE3000 design rule 1: the entry point).
 */
@Composable
fun PlayerScreen(vm: AmpViewModel, onBrowse: (BlockTarget) -> Unit, signedIn: Boolean, onSignOut: () -> Unit) {
    val context = LocalContext.current
    var showAddSheet by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleAudio()
    }
    // Which block the phone file picker is choosing for.
    var pickingFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // .nam has no standard file type, so allow any file and check the extension after picking.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val block = vm.blocks.firstOrNull { it.id == pickingFor }
        if (uri != null && block != null) vm.loadFromPhone(block, uri)
        pickingFor = null
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

        vm.blocks.forEachIndexed { index, block ->
            ChainArrow()
            key(block.id) {
                SignalBlock(
                    block = block,
                    isFirst = index == 0,
                    isLast = index == vm.blocks.lastIndex,
                    onBrowse = { onBrowse(BlockTarget(block.id, block.kind)) },
                    onPhoneFile = {
                        pickingFor = block.id
                        filePicker.launch(arrayOf("*/*"))
                    },
                    onToggle = { vm.setEnabled(block, it) },
                    onMove = { vm.move(block, it) },
                    onClear = { vm.clearBlock(block) },
                    onRemove = { vm.removeBlock(block) },
                )
            }
        }
        ChainArrow()
        if (vm.canAddBlock) {
            OutlinedButton(onClick = { showAddSheet = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add block")
            }
            ChainArrow()
        }

        Meter("Out", vm.outputLevelDb)
        CpuMeter(vm.cpuLoad, vm.running)

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

    if (showAddSheet) {
        AddBlockSheet(
            onDismiss = { showAddSheet = false },
            onPick = { kind ->
                showAddSheet = false
                vm.addBlock(kind)?.let { onBrowse(BlockTarget(it.id, it.kind)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBlockSheet(onDismiss: () -> Unit, onPick: (BlockKind) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Add a block",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            BlockKind.entries.forEach { kind ->
                Column(
                    Modifier.fillMaxWidth().clickable { onPick(kind) }.padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(kind.label, style = MaterialTheme.typography.titleSmall)
                    Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "Each capture uses phone power. Watch the CPU meter: near 100% the sound will crackle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CpuMeter(load: Float, running: Boolean) {
    val pct = (load * 100).roundToInt()
    val color = when {
        load > 0.85f -> MaterialTheme.colorScheme.error
        load > 0.6f -> Color(0xFFFDD835)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        if (running) "CPU $pct%" + (if (load > 0.85f) " · too high, use fewer or lighter captures" else "") else "CPU –",
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
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
    block: ChainBlock,
    isFirst: Boolean,
    isLast: Boolean,
    onBrowse: () -> Unit,
    onPhoneFile: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onClear: () -> Unit,
    onRemove: () -> Unit,
) {
    val tone = block.tone
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(block.kind.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (block.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (!block.isEmpty) Switch(checked = block.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Block options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!isFirst) DropdownMenuItem(text = { Text("Move up") }, onClick = { menuOpen = false; onMove(-1) })
                        if (!isLast) DropdownMenuItem(text = { Text("Move down") }, onClick = { menuOpen = false; onMove(1) })
                        DropdownMenuItem(text = { Text("Load from phone") }, onClick = { menuOpen = false; onPhoneFile() })
                        if (!block.isEmpty) DropdownMenuItem(text = { Text("Empty this block") }, onClick = { menuOpen = false; onClear() })
                        DropdownMenuItem(text = { Text("Remove block") }, onClick = { menuOpen = false; onRemove() })
                    }
                }
            }

            when {
                tone != null -> Row(
                    Modifier.clickable(onClick = onBrowse).alpha(if (block.enabled) 1f else 0.4f),
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
                !block.isEmpty -> Text(
                    block.fileName ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (block.enabled) 1f else 0.4f),
                )
                else -> Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onBrowse)
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(block.kind.hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            BrowseT3kButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth(), fullLogo = true)
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
