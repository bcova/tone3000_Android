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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.bcova.namplayer.audio.PedalController
import com.bcova.namplayer.rig.PedalAction
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bcova.namplayer.rig.LoadedTone

@Composable
fun AppRoot(amp: AmpViewModel = viewModel(), t3k: T3kViewModel = viewModel()) {
    BackHandler(enabled = t3k.backStack.size > 1) { t3k.back() }

    // Footswitch "Tuner on/off": open or close the tuner from any screen.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        PedalController.get(context).actions.collect { action ->
            if (action == PedalAction.TUNER) {
                if (t3k.screen == Screen.Tuner) t3k.back() else t3k.push(Screen.Tuner)
            }
        }
    }

    // The outer frame keeps content clear of the status/navigation bars once, for every screen.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val s = t3k.screen) {
                Screen.Player -> PlayerScreen(
                    vm = amp,
                    onBrowse = t3k::openBrowser,
                    onTuner = { t3k.push(Screen.Tuner) },
                    onSettings = { t3k.push(Screen.Settings) },
                    onLive = { t3k.push(Screen.Live) },
                    signedIn = t3k.signedIn,
                    onSignOut = t3k::signOut,
                )
                is Screen.Partnership -> PartnershipScreen(s.target, t3k)
                is Screen.Library -> LibraryScreen(s.target, t3k)
                is Screen.Search -> SearchScreen(s.target, t3k)
                Screen.Tuner -> TunerScreen(amp, onBack = { t3k.back() })
                Screen.Settings -> SettingsScreen(amp, onBack = { t3k.back() })
                Screen.Live -> LiveScreen(amp, onTuner = { t3k.push(Screen.Tuner) }, onBack = { t3k.back() })
                is Screen.ToneDetail -> ToneDetailScreen(t3k, onInstall = {
                    t3k.installSelected { file, tone, model, target ->
                        // Load into the block the user was choosing for.
                        amp.loadFile(
                            target.blockId,
                            target.kind,
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
