package com.bcova.namplayer.rig

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A tone loaded from TONE3000, shown on its signal-chain block (TONE3000 design rule 5). */
@Serializable
data class LoadedTone(
    val toneId: String,
    val title: String,
    val imageUrl: String?,
    val gearLabel: String,
    val formatLabel: String,
    val creator: String,
    val modelName: String,
)

/** One block of a saved rig. [file] is a file name inside the tone library folder. */
@Serializable
data class SavedBlock(
    val kind: String,
    val enabled: Boolean = true,
    val file: String? = null,
    val fileName: String? = null,
    val tone: LoadedTone? = null,
)

/** Everything needed to rebuild the sound: chain + gate + EQ + levels. */
@Serializable
data class RigState(
    val blocks: List<SavedBlock> = emptyList(),
    val inputGainDb: Float = 0f,
    val outputGainDb: Float = -6f,
    val normalize: Boolean = true,
    val gateEnabled: Boolean = false,
    val gateThresholdDb: Float = -60f,
    val eqEnabled: Boolean = true,
    val bass: Float = 5f,
    val middle: Float = 5f,
    val treble: Float = 5f,
) {
    val files: Set<String> get() = blocks.mapNotNull { it.file }.toSet()
}

@Serializable
data class Preset(val id: String, val name: String, val rig: RigState)

/** What's remembered between app launches. */
@Serializable
data class SavedSession(val rig: RigState, val currentPresetId: String? = null)

/**
 * Saves the current setup, presets, and copies of loaded tone files in the app's private storage.
 *
 * Analogy: the library folder is your gig bag. Every tone you load gets a copy in it, so presets
 * work offline. Copies that no preset or current setup uses are thrown out when the app starts.
 */
class RigStore(baseDir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val sessionFile = File(baseDir, "session.json")
    private val presetsFile = File(baseDir, "presets.json")
    val libraryDir = File(baseDir, "library").apply { mkdirs() }

    fun loadSession(): SavedSession? = read(sessionFile)
    fun saveSession(session: SavedSession) = write(sessionFile, json.encodeToString(session))

    fun loadPresets(): List<Preset> = read<List<Preset>>(presetsFile) ?: emptyList()
    fun savePresets(presets: List<Preset>) = write(presetsFile, json.encodeToString(presets))

    fun libraryFile(name: String): File = File(libraryDir, name)

    /**
     * Copies [source] into the library (unless it's already there) and returns the stored file.
     * [preferredName] is used when possible; same name + same size counts as the same file.
     */
    fun importToLibrary(source: File, preferredName: String): File {
        if (source.parentFile?.canonicalPath == libraryDir.canonicalPath) return source
        val safe = preferredName.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "tone" }
        val base = safe.substringBeforeLast('.')
        val ext = safe.substringAfterLast('.', "")
        var candidate = File(libraryDir, safe)
        var n = 2
        while (candidate.exists() && candidate.length() != source.length()) {
            candidate = File(libraryDir, if (ext.isEmpty()) "$base-$n" else "$base-$n.$ext")
            n++
        }
        if (!candidate.exists()) {
            val tmp = File(libraryDir, candidate.name + ".part")
            source.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(candidate)) throw java.io.IOException("Could not save ${candidate.name}")
        }
        return candidate
    }

    /** Deletes library files that nothing uses anymore. */
    fun cleanLibrary(inUse: Set<String>) {
        libraryDir.listFiles()?.forEach { f -> if (f.name !in inUse) f.delete() }
    }

    private inline fun <reified T> read(file: File): T? =
        runCatching { if (file.exists()) json.decodeFromString<T>(file.readText()) else null }.getOrNull()

    private fun write(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
