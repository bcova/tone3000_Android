package com.bcova.namplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bcova.namplayer.BuildConfig

/** App info and the open-source credits that the bundled libraries' licenses ask for. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Ampfield ${BuildConfig.VERSION_NAME}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A free, open-source NAM player for Android. MIT License, © 2026 bcova.")
                PoweredByT3k()
                Text(
                    "Tones are created by the TONE3000 community and belong to their creators. " +
                        "TONE3000 and its logos are trademarks of TONE3000.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Open-source software used", fontWeight = FontWeight.Bold)
                Credit("Neural Amp Modeler Core", "© Steven Atkinson · MIT License")
                Credit("Oboe", "© Google LLC · Apache License 2.0")
                Credit("Eigen", "Mozilla Public License 2.0")
                Credit("nlohmann/json", "© Niels Lohmann · MIT License")
                Credit("AndroidX, Jetpack Compose, Kotlin, Coil", "Apache License 2.0")
                Text(
                    "Full license texts: github.com/bcova/tone3000_android",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun Credit(name: String, license: String) {
    Column {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
