package com.bcova.namplayer.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bcova.namplayer.audio.AudioService
import com.bcova.namplayer.audio.Devices
import com.bcova.namplayer.audio.NativeAudio
import com.bcova.namplayer.audio.PedalController
import com.bcova.namplayer.rig.AppSettings
import com.bcova.namplayer.rig.DeviceChoice
import com.bcova.namplayer.rig.PedalAction
import com.bcova.namplayer.rig.LoadedTone
import com.bcova.namplayer.rig.Preset
import com.bcova.namplayer.rig.Recording
import com.bcova.namplayer.rig.Recordings
import com.bcova.namplayer.rig.RigState
import com.bcova.namplayer.rig.RigStore
import com.bcova.namplayer.rig.SavedBlock
import com.bcova.namplayer.rig.SavedSession
import com.bcova.namplayer.rig.TunerReading
import com.bcova.namplayer.rig.noteFor
import com.bcova.namplayer.t3k.BlockKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.log10
import kotlin.math.max

/** One block in the signal chain. It's empty until a .nam or .wav file is loaded into it. */
class ChainBlock(val id: Long, val kind: BlockKind) {
    var fileName by mutableStateOf<String?>(null)
    var file by mutableStateOf<String?>(null) // name inside the tone library
    var tone by mutableStateOf<LoadedTone?>(null) // null when loaded from a phone file
    var enabled by mutableStateOf(true)
    var levelDb by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    val isEmpty: Boolean get() = fileName == null
}

@OptIn(FlowPreview::class)
class AmpViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RigStore(app.filesDir)
    val settings = AppSettings(app)

    // ---- App settings (not part of presets)
    var bufferBursts by mutableIntStateOf(settings.bufferBursts)
        private set
    var calibrate by mutableStateOf(settings.calibrate)
        private set
    var interfaceDbu by mutableFloatStateOf(settings.interfaceDbu)
        private set
    var eqBeforeCab by mutableStateOf(settings.eqBeforeCab)
        private set
    var inputDevice by mutableStateOf(settings.inputDevice)
        private set
    var outputDevice by mutableStateOf(settings.outputDevice)
        private set
    /** Guitar-to-headphones delay in ms, or -1 if unknown. */
    var latencyMs by mutableIntStateOf(-1)
        private set

    // ---- Recording
    /** Seconds recorded so far, or null when not recording. */
    var recordingSeconds by mutableStateOf<Double?>(null)
        private set
    /** The last finished recording, for the Share button. */
    var lastRecording by mutableStateOf<Recording?>(null)
        private set
    /** Rig changes (loading, presets) happen one at a time. */
    private val rigLock = Mutex()

    /** The chain, first to last. */
    val blocks = mutableStateListOf<ChainBlock>()
    private var nextId = 1L

    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("Stopped")
        private set
    var message by mutableStateOf<String?>(null)
        private set
    /** True while the last setup is being restored at startup. */
    var restoring by mutableStateOf(true)
        private set

    var inputGainDb by mutableFloatStateOf(0f)
        private set
    var outputGainDb by mutableFloatStateOf(-6f)
        private set
    var normalize by mutableStateOf(true)
        private set

    // Noise gate and EQ (like the NAM plugin)
    var gateEnabled by mutableStateOf(false)
        private set
    var gateThresholdDb by mutableFloatStateOf(-60f)
        private set
    var eqEnabled by mutableStateOf(true)
        private set
    var bass by mutableFloatStateOf(5f)
        private set
    var middle by mutableFloatStateOf(5f)
        private set
    var treble by mutableFloatStateOf(5f)
        private set

    // Presets
    val presets = mutableStateListOf<Preset>()
    var currentPresetId by mutableStateOf<String?>(null)
        private set
    val currentPreset: Preset? get() = presets.firstOrNull { it.id == currentPresetId }
    /** The current sound differs from the selected preset. */
    val presetModified: Boolean get() = currentPreset?.let { it.rig != snapshot() } ?: false

    /** Meter levels in dBFS (-60..0). */
    var inputLevelDb by mutableFloatStateOf(METER_FLOOR_DB)
        private set
    var outputLevelDb by mutableFloatStateOf(METER_FLOOR_DB)
        private set
    /** Share of the audio time budget in use (0..1+). Near 1 = crackles. */
    var cpuLoad by mutableFloatStateOf(0f)
        private set

    // Tuner
    var tunerReading by mutableStateOf<TunerReading?>(null)
        private set
    private var tunerJob: Job? = null

    private var meterJob: Job? = null

    init {
        NativeAudio.setBufferBursts(bufferBursts)
        NativeAudio.setInputCalibration(calibrate, interfaceDbu)
        NativeAudio.setEqBeforeCab(eqBeforeCab)
        viewModelScope.launch { restoreLastSession() }
        viewModelScope.launch(Dispatchers.IO) { cleanDownloadCache() }
        listenToPedals()
    }

    // ---- Settings

    fun updateBufferBursts(bursts: Int) {
        bufferBursts = bursts
        settings.bufferBursts = bursts
        NativeAudio.setBufferBursts(bursts)
    }

    fun updateCalibration(enabled: Boolean = calibrate, dbu: Float = interfaceDbu) {
        calibrate = enabled
        interfaceDbu = dbu
        settings.calibrate = enabled
        settings.interfaceDbu = dbu
        NativeAudio.setInputCalibration(enabled, dbu)
    }

    fun updateEqBeforeCab(before: Boolean) {
        eqBeforeCab = before
        settings.eqBeforeCab = before
        NativeAudio.setEqBeforeCab(before)
    }

    fun chooseDevices(input: DeviceChoice?, output: DeviceChoice?) {
        inputDevice = input
        outputDevice = output
        settings.inputDevice = input
        settings.outputDevice = output
        applyDevices()
    }

    /** Looks up the remembered devices (ids change when replugged) and tells the engine. */
    fun applyDevices() {
        val app = getApplication<Application>()
        NativeAudio.setDevices(Devices.resolve(app, inputDevice, inputs = true), Devices.resolve(app, outputDevice, inputs = false))
    }

    /** Old TONE3000 downloads: once loaded, tones live in the library, so the download cache can go. */
    private fun cleanDownloadCache() {
        File(getApplication<Application>().cacheDir, "t3k").listFiles()?.forEach { it.delete() }
        File(getApplication<Application>().cacheDir, "import").listFiles()?.forEach { it.delete() }
    }

    // ---- Audio on/off and controls

    fun toggleAudio() {
        if (running) {
            if (recordingSeconds != null) stopRecording()
            NativeAudio.stop()
            AudioService.stop(getApplication())
            running = false
            meterJob?.cancel()
            resetMeters()
        } else {
            applyDevices()
            running = NativeAudio.start()
            if (!running) message = NativeAudio.statusText()
            if (running) {
                AudioService.start(getApplication()) // keep playing in the background
                startMeters()
            }
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

    fun toggleGate(enabled: Boolean) {
        gateEnabled = enabled
        pushGate()
    }

    fun setGateThreshold(db: Float) {
        gateThresholdDb = db
        pushGate()
    }

    fun toggleEq(enabled: Boolean) {
        eqEnabled = enabled
        pushEq()
    }

    fun setEqKnobs(bass: Float = this.bass, middle: Float = this.middle, treble: Float = this.treble) {
        this.bass = bass
        this.middle = middle
        this.treble = treble
        pushEq()
    }

    fun resetEq() = setEqKnobs(5f, 5f, 5f)

    private fun pushGate() = NativeAudio.setGate(gateEnabled, gateThresholdDb)

    private fun pushEq() = NativeAudio.setEq(eqEnabled, bass, middle, treble)

    fun dismissMessage() {
        message = null
    }

    // ---- Chain editing

    val canAddBlock: Boolean get() = blocks.size < MAX_BLOCKS

    fun addBlock(kind: BlockKind): ChainBlock? {
        if (!canAddBlock) {
            message = "The chain is full ($MAX_BLOCKS blocks). Phones usually run out of power before that."
            return null
        }
        val block = ChainBlock(nextId++, kind)
        // New cabs and spaces go at the end; pedals before the first amp; others before the first cab.
        val index = when (kind) {
            BlockKind.PEDAL -> blocks.indexOfFirst { it.kind == BlockKind.AMP }.takeIf { it >= 0 } ?: blocks.size
            BlockKind.AMP, BlockKind.OUTBOARD, BlockKind.ANY ->
                blocks.indexOfFirst { it.kind == BlockKind.CAB || it.kind == BlockKind.SPACE }.takeIf { it >= 0 } ?: blocks.size
            BlockKind.CAB, BlockKind.SPACE -> blocks.size
        }
        blocks.add(index, block)
        pushOrder()
        return block
    }

    fun removeBlock(block: ChainBlock) {
        NativeAudio.removeBlock(block.id)
        blocks.remove(block)
        pushOrder()
    }

    /** Empties a block but keeps it in the chain. */
    fun clearBlock(block: ChainBlock) {
        NativeAudio.removeBlock(block.id)
        block.fileName = null
        block.file = null
        block.tone = null
        block.enabled = true
        block.levelDb = 0f
    }

    fun move(block: ChainBlock, delta: Int) {
        val from = blocks.indexOf(block)
        val to = from + delta
        if (from < 0 || to !in blocks.indices) return
        blocks.removeAt(from)
        blocks.add(to, block)
        pushOrder()
    }

    fun setBlockLevel(block: ChainBlock, db: Float) {
        block.levelDb = db
        NativeAudio.setBlockLevelDb(block.id, db)
    }

    fun setEnabled(block: ChainBlock, enabled: Boolean) {
        block.enabled = enabled
        NativeAudio.setBlockEnabled(block.id, enabled)
    }

    private fun pushOrder() {
        NativeAudio.setOrder(blocks.map { it.id }.toLongArray())
    }

    /**
     * Loads a file that's already on disk (e.g. downloaded from TONE3000) into block [blockId].
     * A copy goes into the tone library so presets keep working offline.
     * If that block was removed meanwhile, a new block of [kind] is added. Returns an error or null.
     */
    suspend fun loadFile(blockId: Long, kind: BlockKind, file: File, tone: LoadedTone?, preferredName: String? = null): String? =
        rigLock.withLock {
            val block = blocks.firstOrNull { it.id == blockId } ?: addBlock(kind) ?: return@withLock message
            loadIntoBlock(block, file, tone, preferredName ?: file.name)
        }

    private suspend fun loadIntoBlock(block: ChainBlock, file: File, tone: LoadedTone?, preferredName: String): String? {
        block.loading = true
        try {
            val stored = withContext(Dispatchers.IO) {
                runCatching { store.importToLibrary(file, preferredName) }
            }.getOrElse { return it.message ?: "Could not save the file." }
            val error = withContext(Dispatchers.IO) { NativeAudio.loadBlock(block.id, stored.path) }
            if (error == null) {
                block.fileName = tone?.modelName ?: preferredName
                block.file = stored.name
                block.tone = tone
                NativeAudio.setBlockEnabled(block.id, block.enabled)
                NativeAudio.setBlockLevelDb(block.id, block.levelDb)
                pushOrder() // a newly loaded block goes where it sits in the list
            }
            return error
        } finally {
            block.loading = false
        }
    }

    /** Copies a file picked on the phone into app storage (C++ needs a real path), then loads it. */
    fun loadFromPhone(block: ChainBlock, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(uri)
                    val lower = name.lowercase()
                    if (!lower.endsWith(".nam") && !lower.endsWith(".wav")) {
                        return@runCatching null to "Please pick a .nam capture or a .wav IR (got \"$name\")."
                    }
                    val tmp = File(getApplication<Application>().cacheDir, "import/${name.replace('/', '_')}")
                    tmp.parentFile?.mkdirs()
                    getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open the file." }
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    tmp to null
                }.getOrElse { null to (it.message ?: "Could not read the file.") }
            }
            val (file, error) = result
            if (file == null) {
                message = error
                return@launch
            }
            loadFile(block.id, block.kind, file, tone = null, preferredName = file.name)?.let { message = it }
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    private fun displayName(uri: Uri): String {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    // ---- Saving: current setup, presets

    /** The current sound as plain data. */
    fun snapshot(): RigState = RigState(
        blocks = blocks.map { SavedBlock(it.kind.name, it.enabled, it.levelDb, it.file, it.fileName, it.tone) },
        inputGainDb = inputGainDb,
        outputGainDb = outputGainDb,
        normalize = normalize,
        gateEnabled = gateEnabled,
        gateThresholdDb = gateThresholdDb,
        eqEnabled = eqEnabled,
        bass = bass,
        middle = middle,
        treble = treble,
    )

    /** Rebuilds the whole rig from [rig]. Returns how many files couldn't be loaded. */
    private suspend fun applyRig(rig: RigState): Int = rigLock.withLock {
        // Empty the engine first.
        blocks.forEach { NativeAudio.removeBlock(it.id) }
        blocks.clear()
        setInputGain(rig.inputGainDb)
        setOutputGain(rig.outputGainDb)
        setNormalizeEnabled(rig.normalize)
        gateEnabled = rig.gateEnabled
        gateThresholdDb = rig.gateThresholdDb
        pushGate()
        eqEnabled = rig.eqEnabled
        setEqKnobs(rig.bass, rig.middle, rig.treble)

        var missing = 0
        for (saved in rig.blocks.take(MAX_BLOCKS)) {
            val kind = runCatching { BlockKind.valueOf(saved.kind) }.getOrDefault(BlockKind.ANY)
            val block = ChainBlock(nextId++, kind).apply {
                enabled = saved.enabled
                levelDb = saved.levelDb
            }
            blocks += block
            val name = saved.file ?: continue
            val file = store.libraryFile(name)
            val error = if (file.exists()) loadIntoBlock(block, file, saved.tone, saved.fileName ?: name) else "missing"
            if (error != null) missing++
        }
        pushOrder()
        missing
    }

    private suspend fun restoreLastSession() {
        val (session, saved) = withContext(Dispatchers.IO) { store.loadSession() to store.loadPresets() }
        presets.addAll(saved)
        // Throw out tone copies that nothing uses anymore (only at startup, when nothing is loading).
        withContext(Dispatchers.IO) {
            val inUse = (session?.rig?.files ?: emptySet()) + saved.flatMap { it.rig.files }
            store.cleanLibrary(inUse)
        }
        if (session != null) {
            currentPresetId = session.currentPresetId?.takeIf { id -> presets.any { it.id == id } }
            val missing = applyRig(session.rig)
            if (missing > 0) message = "$missing tone file(s) from last time couldn't be loaded."
        } else {
            // First launch: an empty Amp -> Cab chain, like before.
            applyRig(RigState(blocks = listOf(SavedBlock(BlockKind.AMP.name), SavedBlock(BlockKind.CAB.name))))
        }
        restoring = false
        startAutosave()
    }

    /** Saves the current setup about a second after anything changes. */
    private fun startAutosave() {
        viewModelScope.launch {
            snapshotFlow { SavedSession(snapshot(), currentPresetId) }
                .drop(1)
                .debounce(800)
                .collect { session -> withContext(Dispatchers.IO) { store.saveSession(session) } }
        }
    }

    fun savePresetAs(name: String) {
        val preset = Preset(UUID.randomUUID().toString(), name.trim().ifBlank { "My preset" }, snapshot())
        presets += preset
        currentPresetId = preset.id
        persistPresets()
    }

    /** Overwrites the selected preset with the current sound. */
    fun updateCurrentPreset() {
        val index = presets.indexOfFirst { it.id == currentPresetId }
        if (index < 0) return
        presets[index] = presets[index].copy(rig = snapshot())
        persistPresets()
    }

    fun renamePreset(preset: Preset, name: String) {
        val index = presets.indexOfFirst { it.id == preset.id }
        if (index < 0 || name.isBlank()) return
        presets[index] = presets[index].copy(name = name.trim())
        persistPresets()
    }

    fun deletePreset(preset: Preset) {
        presets.removeAll { it.id == preset.id }
        if (currentPresetId == preset.id) currentPresetId = null
        persistPresets()
    }

    fun loadPreset(preset: Preset) {
        viewModelScope.launch {
            currentPresetId = preset.id
            val missing = applyRig(preset.rig)
            if (missing > 0) message = "$missing tone file(s) in \"${preset.name}\" couldn't be loaded."
        }
    }

    private fun persistPresets() {
        val copy = presets.toList()
        viewModelScope.launch(Dispatchers.IO) { store.savePresets(copy) }
    }

    // ---- Recording

    fun toggleRecording() = if (recordingSeconds != null) stopRecording() else startRecording()

    fun startRecording() {
        if (!running) {
            message = "Start audio first, then record."
            return
        }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.US).format(java.util.Date())
        val file = File(getApplication<Application>().cacheDir, "rec/Ampfield $stamp.wav").apply { parentFile?.mkdirs() }
        NativeAudio.startRecording(file.path)?.let { message = it; return }
        pendingRecording = file
        recordingSeconds = 0.0
    }

    private var pendingRecording: File? = null

    fun stopRecording() {
        val file = pendingRecording ?: return
        pendingRecording = null
        val seconds = NativeAudio.stopRecording()
        recordingSeconds = null
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { Recordings.publish(getApplication(), file, seconds) } }
            saved.onSuccess { lastRecording = it }.onFailure { message = "Couldn't save the recording: ${it.message}" }
        }
    }

    fun dismissRecording() {
        lastRecording = null
    }

    // ---- Footswitch / MIDI

    private fun listenToPedals() {
        val pedals = PedalController.get(getApplication())
        viewModelScope.launch { pedals.actions.collect(::handlePedal) }
        viewModelScope.launch {
            // MIDI Program Change N -> preset N (in list order).
            pedals.programs.collect { n -> presets.getOrNull(n)?.let(::loadPreset) }
        }
    }

    private fun handlePedal(action: PedalAction) {
        when (action) {
            PedalAction.NEXT_PRESET -> stepPreset(+1)
            PedalAction.PREV_PRESET -> stepPreset(-1)
            PedalAction.RECORD -> toggleRecording()
            PedalAction.GATE -> toggleGate(!gateEnabled)
            PedalAction.TUNER -> Unit // handled by the screen (AppRoot)
            else -> {
                val index = action.ordinal - PedalAction.BLOCK_1.ordinal
                blocks.getOrNull(index)?.takeIf { !it.isEmpty }?.let { setEnabled(it, !it.enabled) }
            }
        }
    }

    fun stepPreset(delta: Int) {
        if (presets.isEmpty()) return
        val current = presets.indexOfFirst { it.id == currentPresetId }
        val next = if (current < 0) (if (delta > 0) 0 else presets.lastIndex)
        else ((current + delta) % presets.size + presets.size) % presets.size
        loadPreset(presets[next])
    }

    // ---- Tuner

    /** Starts tuning: output goes silent, and the reading updates ~20 times a second. */
    fun startTuner() {
        if (!running) toggleAudio()
        NativeAudio.setTunerActive(true)
        tunerJob?.cancel()
        tunerJob = viewModelScope.launch {
            var lastHeard = 0L
            while (isActive) {
                val r = withContext(Dispatchers.Default) { NativeAudio.readTunerPitch() }
                val hz = r[0]
                if (hz > 0f && r[1] > 0.8f) {
                    tunerReading = noteFor(hz)
                    lastHeard = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastHeard > 1500) {
                    tunerReading = null // let the display rest after the note fades
                }
                delay(50)
            }
        }
    }

    fun stopTuner() {
        tunerJob?.cancel()
        tunerJob = null
        tunerReading = null
        NativeAudio.setTunerActive(false)
    }

    // ---- Meters

    private fun startMeters() {
        meterJob?.cancel()
        meterJob = viewModelScope.launch {
            while (isActive) {
                inputLevelDb = smooth(inputLevelDb, toDb(NativeAudio.takeInputPeak()))
                outputLevelDb = smooth(outputLevelDb, toDb(NativeAudio.takeOutputPeak()))
                cpuLoad = smoothLinear(cpuLoad, NativeAudio.takeCpuLoad())
                recordingSeconds = NativeAudio.recordingSeconds().takeIf { it >= 0 }
                if (!NativeAudio.isRunning()) {
                    // Stopped by the system (device unplugged and couldn't restart), or from the notification.
                    running = false
                    status = NativeAudio.statusText()
                    if (status != "Stopped") message = status
                    if (recordingSeconds != null) stopRecording()
                    AudioService.stop(getApplication())
                    resetMeters()
                    break
                }
                status = NativeAudio.statusText()
                latencyMs = NativeAudio.roundTripLatencyMs()
                delay(50)
            }
        }
    }

    private fun resetMeters() {
        inputLevelDb = METER_FLOOR_DB
        outputLevelDb = METER_FLOOR_DB
        cpuLoad = 0f
    }

    override fun onCleared() {
        if (recordingSeconds != null) stopRecording()
        NativeAudio.setTunerActive(false)
        NativeAudio.stop()
        AudioService.stop(getApplication())
        super.onCleared()
    }

    companion object {
        const val MAX_BLOCKS = 8 // matches EngineCore::kMaxBlocks
        private const val METER_FLOOR_DB = -60f
        private fun toDb(peak: Float): Float = max(METER_FLOOR_DB, 20f * log10(max(peak, 1e-6f)))

        // Fast attack, slow release, like a real meter.
        private fun smooth(current: Float, target: Float): Float =
            if (target > current) target else current + (target - current) * 0.15f

        private fun smoothLinear(current: Float, target: Float): Float =
            if (target > current) target else current + (target - current) * 0.05f
    }
}
