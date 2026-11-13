package com.bcova.namplayer.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bcova.namplayer.audio.NativeAudio
import com.bcova.namplayer.t3k.BlockKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10
import kotlin.math.max

/** A tone loaded from TONE3000, shown on its signal-chain block (TONE3000 design rule 5). */
data class LoadedTone(
    val toneId: String,
    val title: String,
    val imageUrl: String?,
    val gearLabel: String,
    val formatLabel: String,
    val creator: String,
    val modelName: String,
)

/** One block in the signal chain. It's empty until a .nam or .wav file is loaded into it. */
class ChainBlock(val id: Long, val kind: BlockKind) {
    var fileName by mutableStateOf<String?>(null)
    var tone by mutableStateOf<LoadedTone?>(null) // null when loaded from a phone file
    var enabled by mutableStateOf(true)
    var loading by mutableStateOf(false)
    val isEmpty: Boolean get() = fileName == null
}

class AmpViewModel(app: Application) : AndroidViewModel(app) {

    /** The chain, first to last. Starts as Amp -> Cab, like before. */
    val blocks = mutableStateListOf<ChainBlock>()
    private var nextId = 1L

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
    /** Share of the audio time budget in use (0..1+). Near 1 = crackles. */
    var cpuLoad by mutableFloatStateOf(0f)
        private set

    private var meterJob: Job? = null

    init {
        NativeAudio.setInputGainDb(inputGainDb)
        NativeAudio.setOutputGainDb(outputGainDb)
        NativeAudio.setNormalize(normalize)
        blocks += ChainBlock(nextId++, BlockKind.AMP)
        blocks += ChainBlock(nextId++, BlockKind.CAB)
    }

    // ---- Audio on/off and controls

    fun toggleAudio() {
        if (running) {
            NativeAudio.stop()
            running = false
            meterJob?.cancel()
            resetMeters()
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
        block.tone = null
        block.enabled = true
    }

    fun move(block: ChainBlock, delta: Int) {
        val from = blocks.indexOf(block)
        val to = from + delta
        if (from < 0 || to !in blocks.indices) return
        blocks.removeAt(from)
        blocks.add(to, block)
        pushOrder()
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
     * If that block was removed meanwhile, a new block of [kind] is added. Returns an error or null.
     */
    suspend fun loadFile(blockId: Long, kind: BlockKind, file: File, tone: LoadedTone?): String? {
        val block = blocks.firstOrNull { it.id == blockId } ?: addBlock(kind) ?: return message
        block.loading = true
        try {
            val error = withContext(Dispatchers.IO) { NativeAudio.loadBlock(block.id, file.path) }
            if (error == null) {
                block.fileName = tone?.modelName ?: file.name
                block.tone = tone
                if (!block.enabled) setEnabled(block, true)
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
                    val dir = File(getApplication<Application>().filesDir, "blocks/${block.id}").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() } // keep only the current file
                    val file = File(dir, name.replace('/', '_'))
                    getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open the file." }
                        file.outputStream().use { input.copyTo(it) }
                    }
                    file to null
                }.getOrElse { null to (it.message ?: "Could not read the file.") }
            }
            val (file, error) = result
            if (file == null) {
                message = error
                return@launch
            }
            loadFile(block.id, block.kind, file, tone = null)?.let { message = it }
        }
    }

    private fun displayName(uri: Uri): String {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    // ---- Meters

    private fun startMeters() {
        meterJob?.cancel()
        meterJob = viewModelScope.launch {
            while (isActive) {
                inputLevelDb = smooth(inputLevelDb, toDb(NativeAudio.takeInputPeak()))
                outputLevelDb = smooth(outputLevelDb, toDb(NativeAudio.takeOutputPeak()))
                cpuLoad = smoothLinear(cpuLoad, NativeAudio.takeCpuLoad())
                if (!NativeAudio.isRunning()) {
                    // Stopped by the system (e.g. device unplugged and couldn't restart).
                    running = false
                    status = NativeAudio.statusText()
                    message = status
                    resetMeters()
                    break
                }
                status = NativeAudio.statusText()
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
        NativeAudio.stop()
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
