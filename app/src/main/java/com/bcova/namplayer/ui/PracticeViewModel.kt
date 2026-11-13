package com.bcova.namplayer.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bcova.namplayer.audio.BackingTrack
import com.bcova.namplayer.audio.NativeAudio
import com.bcova.namplayer.audio.PedalController
import com.bcova.namplayer.audio.TrackLibrary
import com.bcova.namplayer.rig.PedalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DrumStyle(val label: String) {
    CLICK("Click"), ROCK("Rock"), FUNK("Funk"), SHUFFLE("Shuffle"), HALF_TIME("Half-time"), BALLAD("Ballad"), METAL("Metal")
}

enum class LooperState(val label: String) {
    EMPTY("Empty"), RECORDING("Recording"), PLAYING("Playing"), OVERDUBBING("Overdubbing"), STOPPED("Stopped")
}

/** Metronome + drum loops, looper, and backing tracks. */
class PracticeViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ampfield_practice", 0)
    private val library = TrackLibrary(app)

    // ---- Metronome / drums
    var rhythmOn by mutableStateOf(false)
        private set
    var bpm by mutableFloatStateOf(prefs.getFloat("bpm", 100f))
        private set
    var style by mutableStateOf(DrumStyle.entries.getOrElse(prefs.getInt("style", 0)) { DrumStyle.CLICK })
        private set
    var beatsPerBar by mutableIntStateOf(prefs.getInt("beats", 4))
        private set
    var rhythmVolume by mutableFloatStateOf(prefs.getFloat("rhythm_volume", 0.7f))
        private set
    var beat by mutableIntStateOf(-1)
        private set
    private val taps = ArrayDeque<Long>()

    // ---- Looper
    var looperState by mutableStateOf(LooperState.EMPTY)
        private set
    var loopLength by mutableDoubleStateOf(0.0)
        private set
    var loopPosition by mutableDoubleStateOf(0.0)
        private set
    var loopLayers by mutableIntStateOf(0)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var looperVolume by mutableFloatStateOf(1f)
        private set

    // ---- Backing tracks
    val tracks = mutableStateListOf<BackingTrack>()
    var currentTrack by mutableStateOf<BackingTrack?>(null)
        private set
    var trackPlaying by mutableStateOf(false)
        private set
    var trackPosition by mutableDoubleStateOf(0.0)
        private set
    var trackDuration by mutableDoubleStateOf(0.0)
        private set
    var trackVolume by mutableFloatStateOf(prefs.getFloat("track_volume", 0.8f))
        private set
    /** A-B loop points in seconds, or null. */
    var loopA by mutableStateOf<Double?>(null)
        private set
    var loopB by mutableStateOf<Double?>(null)
        private set
    /** 0..1 while converting a song, else null. */
    var importProgress by mutableStateOf<Float?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        pushRhythm()
        NativeAudio.trackVolume(trackVolume)
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { library.list() }
            tracks.addAll(list)
        }
        viewModelScope.launch {
            // Poll the engine for the UI: beat light, looper and track positions.
            while (isActive) {
                beat = NativeAudio.rhythmBeat()
                val l = NativeAudio.looperStatus()
                looperState = LooperState.entries.getOrElse(l[0].toInt()) { LooperState.EMPTY }
                loopLength = l[1]
                loopPosition = l[2]
                loopLayers = l[3].toInt()
                canUndo = l[4] > 0.5
                val t = NativeAudio.trackStatus()
                trackPlaying = t[0] > 0.5
                trackPosition = t[1]
                trackDuration = t[2]
                delay(40)
            }
        }
        viewModelScope.launch {
            PedalController.get(app).actions.collect { action ->
                when (action) {
                    PedalAction.LOOPER -> looperPress()
                    PedalAction.LOOPER_STOP -> looperStopPlay()
                    PedalAction.LOOPER_UNDO -> looperUndo()
                    PedalAction.METRONOME -> toggleRhythm()
                    PedalAction.TRACK -> toggleTrack()
                    else -> Unit
                }
            }
        }
    }

    fun dismissMessage() {
        message = null
    }

    // ---- Metronome / drums

    fun toggleRhythm() {
        rhythmOn = !rhythmOn
        pushRhythm()
    }

    fun updateBpm(value: Float) {
        bpm = value.coerceIn(30f, 300f)
        pushRhythm()
    }

    /** Tap the beat a few times; the tempo follows. */
    fun tapTempo() {
        val now = System.currentTimeMillis()
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.addLast(now)
        while (taps.size > 5) taps.removeFirst()
        if (taps.size >= 2) {
            val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
            updateBpm(Math.round(60_000.0 / avg).toFloat())
        }
    }

    fun updateStyle(s: DrumStyle) {
        style = s
        pushRhythm()
    }

    fun updateBeats(beats: Int) {
        beatsPerBar = beats.coerceIn(1, 12)
        pushRhythm()
    }

    fun updateRhythmVolume(v: Float) {
        rhythmVolume = v
        pushRhythm()
    }

    private fun pushRhythm() {
        NativeAudio.setRhythm(rhythmOn, bpm, style.ordinal, beatsPerBar, rhythmVolume)
        prefs.edit {
            putFloat("bpm", bpm)
            putInt("style", style.ordinal)
            putInt("beats", beatsPerBar)
            putFloat("rhythm_volume", rhythmVolume)
        }
    }

    // ---- Looper

    private var looperReady = false

    private fun ensureLooper() {
        if (!looperReady) {
            NativeAudio.looperPrepare() // reserves ~50 MB the first time
            looperReady = true
        }
    }

    fun looperPress() {
        ensureLooper()
        NativeAudio.looperCommand(1)
    }

    fun looperStopPlay() {
        ensureLooper()
        NativeAudio.looperCommand(2)
    }

    fun looperUndo() = NativeAudio.looperCommand(3)

    fun looperClear() = NativeAudio.looperCommand(4)

    fun updateLooperVolume(v: Float) {
        looperVolume = v
        NativeAudio.looperVolume(v)
    }

    // ---- Backing tracks

    fun importTrack(uri: Uri) {
        if (importProgress != null) return
        viewModelScope.launch {
            importProgress = 0f
            val result = withContext(Dispatchers.IO) {
                runCatching { library.import(uri) { p -> importProgress = p } }
            }
            importProgress = null
            result.onSuccess { t ->
                tracks += t
                selectTrack(t)
            }.onFailure { message = "Couldn't add that song: ${it.message ?: "unsupported file"}" }
        }
    }

    fun selectTrack(track: BackingTrack) {
        NativeAudio.trackPlay(false)
        NativeAudio.trackOpen(library.pcmFile(track).path)?.let {
            message = it
            return
        }
        currentTrack = track
        loopA = null
        loopB = null
    }

    fun deleteTrack(track: BackingTrack) {
        if (currentTrack?.id == track.id) {
            NativeAudio.trackClose()
            currentTrack = null
        }
        tracks.removeAll { it.id == track.id }
        viewModelScope.launch(Dispatchers.IO) { library.delete(track) }
    }

    fun toggleTrack() {
        if (currentTrack == null) return
        if (!trackPlaying && trackDuration > 0 && trackPosition >= trackDuration - 0.1) NativeAudio.trackSeek(loopA ?: 0.0)
        NativeAudio.trackPlay(!trackPlaying)
    }

    fun seekTrack(seconds: Double) = NativeAudio.trackSeek(seconds)

    fun skip(deltaSeconds: Double) = NativeAudio.trackSeek((trackPosition + deltaSeconds).coerceIn(0.0, trackDuration))

    fun updateTrackVolume(v: Float) {
        trackVolume = v
        NativeAudio.trackVolume(v)
        prefs.edit { putFloat("track_volume", v) }
    }

    /** Tap once to set A (start), again to set B (end) and start looping, again to clear. */
    fun loopButton() {
        when {
            loopA == null -> loopA = trackPosition
            loopB == null -> {
                val a = loopA!!
                if (trackPosition > a + 0.25) {
                    loopB = trackPosition
                    NativeAudio.trackLoop(a, trackPosition)
                } else {
                    loopA = null // too short: start over
                }
            }
            else -> {
                loopA = null
                loopB = null
                NativeAudio.trackLoop(0.0, 0.0)
            }
        }
    }

    override fun onCleared() {
        NativeAudio.setRhythm(false, bpm, style.ordinal, beatsPerBar, rhythmVolume)
        NativeAudio.trackPlay(false)
        super.onCleared()
    }
}
