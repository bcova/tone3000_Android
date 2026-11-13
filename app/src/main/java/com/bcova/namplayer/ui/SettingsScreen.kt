package com.bcova.namplayer.ui

import android.Manifest
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bcova.namplayer.audio.AudioDevice
import com.bcova.namplayer.audio.Devices
import com.bcova.namplayer.audio.PedalController
import com.bcova.namplayer.rig.DeviceChoice
import com.bcova.namplayer.rig.PedalAction
import kotlin.math.roundToInt

/** Audio devices, latency, input calibration, EQ position, and footswitch / MIDI setup. */
@Composable
fun SettingsScreen(vm: AmpViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AudioSection(vm)
            LatencySection(vm)
            ShareSection(vm)
            CalibrationSection(vm)
            EqSection(vm)
            PedalSection()
            BackgroundSection()
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- Devices

@Composable
private fun AudioSection(vm: AmpViewModel) {
    val context = LocalContext.current
    // Re-list devices when something is plugged in or out.
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val am = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { version++ }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { version++ }
        }
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { am.unregisterAudioDeviceCallback(callback) }
    }
    val inputs = remember(version) { Devices.list(context, inputs = true) }
    val outputs = remember(version) { Devices.list(context, inputs = false) }

    SettingsCard("Audio devices") {
        Hint("\"Automatic\" lets Android choose (it usually picks a plugged-in USB interface).")
        Text("Input (guitar)", style = MaterialTheme.typography.labelLarge)
        DeviceList(inputs, vm.inputDevice) { vm.chooseDevices(it, vm.outputDevice) }
        HorizontalDivider()
        Text("Output (headphones)", style = MaterialTheme.typography.labelLarge)
        DeviceList(outputs, vm.outputDevice) { vm.chooseDevices(vm.inputDevice, it) }
    }
}

@Composable
private fun DeviceList(devices: List<AudioDevice>, chosen: DeviceChoice?, onChoose: (DeviceChoice?) -> Unit) {
    val chosenConnected = chosen != null && devices.any { it.type == chosen.type && it.name == chosen.name }
    ChoiceRow("Automatic", selected = chosen == null) { onChoose(null) }
    devices.forEach { d ->
        val selected = chosen?.type == d.type && chosen.name == d.name
        ChoiceRow(d.label, selected, warning = d.warning) { onChoose(DeviceChoice(d.type, d.name)) }
    }
    if (chosen != null && !chosenConnected) {
        Hint("Your chosen device (${chosen.name}) isn't connected, so Automatic is used for now.")
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, warning: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Check,
            contentDescription = if (selected) "Selected" else null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(label)
            warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        }
    }
}

// ---- Latency

@Composable
private fun LatencySection(vm: AmpViewModel) {
    SettingsCard("Latency (delay)") {
        Hint("Smaller buffer = less delay between picking and hearing, but more risk of crackles.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Lowest", 2 to "Normal", 4 to "Safe").forEach { (bursts, label) ->
                FilterChip(selected = vm.bufferBursts == bursts, onClick = { vm.updateBufferBursts(bursts) }, label = { Text(label) })
            }
        }
        Text(
            when {
                !vm.running -> "Start audio to measure the delay."
                vm.latencyMs < 0 -> "Delay: unknown on this device."
                else -> "Delay right now: about ${vm.latencyMs} ms" + when {
                    vm.latencyMs <= 12 -> " (feels instant)"
                    vm.latencyMs <= 20 -> " (good)"
                    else -> " (noticeable; try a USB interface or a smaller buffer)"
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---- Other apps

@Composable
private fun ShareSection(vm: AmpViewModel) {
    SettingsCard("Play along with other apps") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mix with YouTube, Spotify, etc.")
                Hint(
                    "Turn on to hear other apps together with your guitar, e.g. YouTube in split screen or " +
                        "pop-up view. Adds a little delay. Off = lowest latency, other apps may be silent.",
                )
            }
            Switch(checked = vm.sharedAudio, onCheckedChange = vm::updateSharedAudio)
        }
    }
}

// ---- Calibration

@Composable
private fun CalibrationSection(vm: AmpViewModel) {
    SettingsCard("Input calibration") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Calibrate input", modifier = Modifier.weight(1f))
            Switch(checked = vm.calibrate, onCheckedChange = { vm.updateCalibration(enabled = it) })
        }
        Hint(
            "Makes calibrated captures react like the real amp: your pick attack drives them as hard as it " +
                "would the real gear. Set this to your interface's maximum input level in dBu " +
                "(often 6–18 dBu; check its spec sheet for the instrument/Hi-Z input).",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Interface level", modifier = Modifier.weight(1f))
            Text("%.1f dBu".format(vm.interfaceDbu))
        }
        Slider(
            value = vm.interfaceDbu,
            onValueChange = { vm.updateCalibration(dbu = (it * 2).roundToInt() / 2f) },
            valueRange = 0f..24f,
            enabled = vm.calibrate,
        )
        Hint("Only captures that include their recording level are affected (newer NAM captures do).")
    }
}

// ---- EQ position

@Composable
private fun EqSection(vm: AmpViewModel) {
    SettingsCard("EQ position") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EQ before the cab")
                Hint("Like the NAM plugin: shapes the amp before the cabinet. Off = EQ at the very end.")
            }
            Switch(checked = vm.eqBeforeCab, onCheckedChange = vm::updateEqBeforeCab)
        }
    }
}

// ---- Footswitch / MIDI

@Composable
private fun PedalSection() {
    val context = LocalContext.current
    val pedals = remember { PedalController.get(context) }
    val bindings by pedals.bindings.collectAsState()
    val learning by pedals.learning.collectAsState()
    val connected by pedals.connected.collectAsState()
    val scanning by pedals.scanning.collectAsState()
    val found by pedals.scanResults.collectAsState()

    val btPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) pedals.startBluetoothScan()
    }
    DisposableEffect(Unit) { onDispose { pedals.learn(null); pedals.stopBluetoothScan() } }

    SettingsCard("Footswitch & MIDI") {
        Hint(
            "Works with USB or Bluetooth MIDI pedals and \"page turner\" pedals. " +
                "MIDI Program Change 1, 2, 3… picks your 1st, 2nd, 3rd… preset.",
        )
        Text(
            if (connected.isEmpty()) "No MIDI devices connected. USB MIDI pedals connect automatically."
            else "Connected: ${connected.joinToString()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { askBt.launch(btPermissions) }, enabled = !scanning) { Text("Find Bluetooth MIDI pedal") }
            if (scanning) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
        }
        found.forEach { device ->
            TextButton(onClick = { pedals.connectBluetooth(device) }) { Text("Connect ${pedals.nameOf(device)}") }
        }

        HorizontalDivider()
        Text("Pedal actions", style = MaterialTheme.typography.labelLarge)
        Hint("Tap Learn, then press the pedal (or key) you want for that action.")
        PedalAction.entries.forEach { action ->
            val triggers = bindings.filterValues { it == action }.keys
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(action.label)
                    Text(
                        if (learning == action) "Press a pedal now…"
                        else triggers.joinToString { triggerLabel(it) }.ifEmpty { "Not set" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (learning == action) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (triggers.isNotEmpty()) {
                    IconButton(onClick = { triggers.forEach(pedals::unbind) }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                }
                TextButton(onClick = { pedals.learn(if (learning == action) null else action) }) {
                    Text(if (learning == action) "Cancel" else "Learn")
                }
            }
        }
        TextButton(onClick = pedals::resetBindings) { Text("Reset to defaults") }
    }
}

private fun triggerLabel(trigger: String): String {
    val (kind, value) = trigger.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    return when (kind) {
        "cc" -> "MIDI CC $value"
        "note" -> "MIDI note $value"
        "key" -> "Key " + (android.view.KeyEvent.keyCodeToString(value.toIntOrNull() ?: 0).removePrefix("KEYCODE_"))
        else -> trigger
    }
}

// ---- Background

@Composable
private fun BackgroundSection() {
    SettingsCard("Playing in the background") {
        Hint(
            "While audio is on, Ampfield shows a \"now playing\" notification so it keeps running when you " +
                "switch apps or lock the screen. Use its Stop button, or Stop in the app, to end it.",
        )
    }
}
