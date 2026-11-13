package com.bcova.namplayer.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bcova.namplayer.audio.NativeAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10
import kotlin.math.max

/** One loadable slot in the signal chain: the amp model or the cabinet IR. */
class SlotState(val label: String, val extension: String) {
    var fileName by mutableStateOf<String?>(null)
    var enabled by mutableStateOf(true)
    var loading by mutableStateOf(false)
}

class AmpViewModel(app: Application) : AndroidViewModel(app) {

    val model = SlotState("Amp model", "nam")
    val ir = SlotState("Cabinet IR", "wav")

    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("Stopped")
        private set
    var message by mutableStateOf<String?>(null)
        private set

    var inputGainDb by mutableFloatStateOf(0f)
        private set
    var outputGainDb by mutableFloatStateOf(-6f)
        private set
    var normalize by mutableStateOf(true)
        private set

    /** Meter levels in dBFS (-60..0). */
    var inputLevelDb by mutableFloatStateOf(METER_FLOOR_DB)
        private set
    var outputLevelDb by mutableFloatStateOf(METER_FLOOR_DB)
        private set

    private var meterJob: Job? = null

    init {
        NativeAudio.setInputGainDb(inputGainDb)
        NativeAudio.setOutputGainDb(outputGainDb)
        NativeAudio.setNormalize(normalize)
    }

    fun toggleAudio() {
        if (running) {
            NativeAudio.stop()
            running = false
            meterJob?.cancel()
            inputLevelDb = METER_FLOOR_DB
            outputLevelDb = METER_FLOOR_DB
        } else {
            running = NativeAudio.start()
            if (!running) message = NativeAudio.statusText()
            if (running) startMeters()
        }
        status = NativeAudio.statusText()
    }

    fun setInputGain(db: Float) {
        inputGainDb = db
        NativeAudio.setInputGainDb(db)
    }

    fun setOutputGain(db: Float) {
        outputGainDb = db
        NativeAudio.setOutputGainDb(db)
    }

    fun setNormalizeEnabled(enabled: Boolean) {
        normalize = enabled
        NativeAudio.setNormalize(enabled)
    }

    fun setEnabled(slot: SlotState, enabled: Boolean) {
        slot.enabled = enabled
        if (slot === model) NativeAudio.setModelEnabled(enabled) else NativeAudio.setIrEnabled(enabled)
    }

    fun clear(slot: SlotState) {
        if (slot === model) NativeAudio.clearModel() else NativeAudio.clearIr()
        slot.fileName = null
    }

    fun dismissMessage() {
        message = null
    }

    /** Copies the picked file into app storage (C++ needs a real file path), then loads it. */
    fun load(slot: SlotState, uri: Uri) {
        viewModelScope.launch {
            slot.loading = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(uri)
                    if (!name.lowercase().endsWith(".${slot.extension}")) {
                        return@runCatching name to "Please pick a .${slot.extension} file (got \"$name\")."
                    }
                    val dir = File(getApplication<Application>().filesDir, slot.extension).apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() } // keep only the current file
                    val file = File(dir, name.replace('/', '_'))
                    getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open the file." }
                        file.outputStream().use { input.copyTo(it) }
                    }
                    val error = if (slot === model) NativeAudio.loadModel(file.path) else NativeAudio.loadIr(file.path)
                    name to error
                }.getOrElse { null to (it.message ?: "Could not read the file.") }
            }
            val (name, error) = result
            if (error == null) slot.fileName = name else message = error
            slot.loading = false
        }
    }

    private fun displayName(uri: Uri): String {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun startMeters() {
        meterJob?.cancel()
        meterJob = viewModelScope.launch {
            while (isActive) {
                inputLevelDb = smooth(inputLevelDb, toDb(NativeAudio.takeInputPeak()))
                outputLevelDb = smooth(outputLevelDb, toDb(NativeAudio.takeOutputPeak()))
                if (!NativeAudio.isRunning()) {
                    // Stopped by the system (e.g. device unplugged and couldn't restart).
                    running = false
                    status = NativeAudio.statusText()
                    message = status
                    inputLevelDb = METER_FLOOR_DB
                    outputLevelDb = METER_FLOOR_DB
                    break
                }
                status = NativeAudio.statusText()
                delay(50)
            }
        }
    }

    override fun onCleared() {
        NativeAudio.stop()
        super.onCleared()
    }

    private companion object {
        const val METER_FLOOR_DB = -60f

        fun toDb(peak: Float): Float = max(METER_FLOOR_DB, 20f * log10(max(peak, 1e-6f)))

        // Fast attack, slow release, like a real meter.
        fun smooth(current: Float, target: Float): Float =
            if (target > current) target else current + (target - current) * 0.15f
    }
}
