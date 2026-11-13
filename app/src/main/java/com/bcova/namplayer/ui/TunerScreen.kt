package com.bcova.namplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

private val InTune = Color(0xFF43A047)

/**
 * Chromatic tuner. While it's open the sound is muted (silent tuning) and the
 * guitar's pitch is shown as the nearest note plus a needle (-50..+50 cents).
 */
@Composable
fun TunerScreen(amp: AmpViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }

    if (hasMic) {
        DisposableEffect(Unit) {
            amp.startTuner()
            onDispose { amp.stopTuner() }
        }
    }

    val reading = amp.tunerReading
    val cents = reading?.cents ?: 0f
    val needle by animateFloatAsState((cents.coerceIn(-50f, 50f)) / 50f, label = "needle")
    val inTune = reading != null && abs(cents) <= 3f
    val accent = when {
        reading == null -> MaterialTheme.colorScheme.onSurfaceVariant
        inTune -> InTune
        abs(cents) <= 15f -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Tuner", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))

        if (!hasMic) {
            Text("The tuner needs microphone access to hear your guitar.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow") }
        } else {
            // Note name, big
            Row(verticalAlignment = Alignment.Bottom) {
                Text(reading?.note ?: "–", fontSize = 120.sp, fontWeight = FontWeight.Bold, color = accent)
                if (reading != null) {
                    Text("${reading.octave}", fontSize = 32.sp, color = accent, modifier = Modifier.padding(bottom = 24.dp))
                }
            }
            Text(
                when {
                    reading == null -> "Play a single string"
                    inTune -> "In tune"
                    cents < 0 -> "${abs(cents).roundToInt()} cents flat · tune up"
                    else -> "${cents.roundToInt()} cents sharp · tune down"
                },
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
            Spacer(Modifier.height(32.dp))

            // Needle meter
            val track = MaterialTheme.colorScheme.surfaceVariant
            val tick = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp)) {
                val mid = size.width / 2
                drawRoundRect(track, Offset(0f, size.height / 2 - 6), Size(size.width, 12f), CornerRadius(6f))
                // In-tune zone (±3 cents)
                val zone = size.width / 2 * (3f / 50f)
                drawRect(InTune.copy(alpha = 0.5f), Offset(mid - zone, size.height / 2 - 6), Size(zone * 2, 12f))
                for (i in -5..5) {
                    val x = mid + i * (size.width / 2) / 5
                    val h = if (i == 0) size.height else size.height * 0.4f
                    drawLine(tick, Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), strokeWidth = if (i == 0) 3f else 1.5f)
                }
                if (reading != null) {
                    val x = mid + needle * size.width / 2
                    drawLine(accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 8f)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("♭ -50", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("+50 ♯", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (reading != null) "%.1f Hz".format(reading.hz) else " ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Sound is muted while tuning. A4 = 440 Hz. Standard tuning: E A D G B E",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
