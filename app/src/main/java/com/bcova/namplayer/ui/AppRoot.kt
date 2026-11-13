package com.bcova.namplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bcova.namplayer.t3k.Tone

@Composable
fun AppRoot(amp: AmpViewModel = viewModel(), t3k: T3kViewModel = viewModel()) {
    BackHandler(enabled = t3k.backStack.size > 1) { t3k.back() }

    // The outer frame keeps content clear of the status/navigation bars once, for every screen.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val s = t3k.screen) {
                Screen.Player -> PlayerScreen(
                    vm = amp,
                    onBrowse = t3k::openBrowser,
                    signedIn = t3k.signedIn,
                    onSignOut = t3k::signOut,
                )
                is Screen.Partnership -> PartnershipScreen(s.target, t3k)
                is Screen.Library -> LibraryScreen(s.target, t3k)
                is Screen.Search -> SearchScreen(s.target, t3k)
                is Screen.ToneDetail -> ToneDetailScreen(t3k, onInstall = {
                    t3k.installSelected { file, tone, model ->
                        // The tone's format decides the slot: IRs go to the cabinet, NAM to the amp.
                        val slot = if (tone.format == Tone.FORMAT_IR) amp.ir else amp.model
                        amp.loadFile(
                            slot,
                            file,
                            LoadedTone(
                                toneId = tone.id,
                                title = tone.title,
                                imageUrl = tone.imageUrl,
                                gearLabel = tone.gearLabel,
                                formatLabel = tone.formatLabel,
                                creator = tone.user.username,
                                modelName = model.name,
                            ),
                        )
                    }
                })
            }
        }
    }

    (t3k.message ?: amp.message)?.let { msg ->
        val dismiss = { if (t3k.message != null) t3k.dismissMessage() else amp.dismissMessage() }
        AlertDialog(
            onDismissRequest = dismiss,
            confirmButton = { TextButton(onClick = dismiss) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}
