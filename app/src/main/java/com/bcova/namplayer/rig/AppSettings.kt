package com.bcova.namplayer.rig

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A chosen audio device, remembered by type + name (Android's device ids change on replug). */
@Serializable
data class DeviceChoice(val type: Int, val name: String)

/** Footswitch / MIDI actions. */
enum class PedalAction(val label: String) {
    NEXT_PRESET("Next preset"),
    PREV_PRESET("Previous preset"),
    TUNER("Tuner on/off"),
    RECORD("Record on/off"),
    BLOCK_1("Block 1 on/off"),
    BLOCK_2("Block 2 on/off"),
    BLOCK_3("Block 3 on/off"),
    BLOCK_4("Block 4 on/off"),
    BLOCK_5("Block 5 on/off"),
    BLOCK_6("Block 6 on/off"),
    GATE("Noise gate on/off"),
}

/**
 * Settings that belong to the app, not to a preset: devices, latency, calibration,
 * EQ position, footswitch mappings. Stored in private app preferences.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ampfield_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var inputDevice: DeviceChoice?
        get() = prefs.getString("input_device", null)?.let { runCatching { json.decodeFromString<DeviceChoice>(it) }.getOrNull() }
        set(v) = prefs.edit { putString("input_device", v?.let { json.encodeToString(it) }) }

    var outputDevice: DeviceChoice?
        get() = prefs.getString("output_device", null)?.let { runCatching { json.decodeFromString<DeviceChoice>(it) }.getOrNull() }
        set(v) = prefs.edit { putString("output_device", v?.let { json.encodeToString(it) }) }

    var bufferBursts: Int
        get() = prefs.getInt("buffer_bursts", 2)
        set(v) = prefs.edit { putInt("buffer_bursts", v) }

    var calibrate: Boolean
        get() = prefs.getBoolean("calibrate", false)
        set(v) = prefs.edit { putBoolean("calibrate", v) }

    var interfaceDbu: Float
        get() = prefs.getFloat("interface_dbu", 12f)
        set(v) = prefs.edit { putFloat("interface_dbu", v) }

    var eqBeforeCab: Boolean
        get() = prefs.getBoolean("eq_before_cab", false)
        set(v) = prefs.edit { putBoolean("eq_before_cab", v) }

    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(v) = prefs.edit { putBoolean("asked_notifications", v) }

    /** Trigger ("cc:20", "note:60", "key:93") -> action. */
    var pedalBindings: Map<String, PedalAction>
        get() = prefs.getString("pedal_bindings", null)
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?.mapNotNull { (k, v) -> runCatching { k to PedalAction.valueOf(v) }.getOrNull() }
            ?.toMap() ?: DEFAULT_BINDINGS
        set(v) = prefs.edit { putString("pedal_bindings", json.encodeToString(v.mapValues { it.value.name })) }

    companion object {
        /** Common page-turner pedals send Page Up/Down or arrow keys. */
        val DEFAULT_BINDINGS = mapOf(
            "key:93" to PedalAction.NEXT_PRESET, // PAGE_DOWN
            "key:92" to PedalAction.PREV_PRESET, // PAGE_UP
            "key:22" to PedalAction.NEXT_PRESET, // DPAD_RIGHT
            "key:21" to PedalAction.PREV_PRESET, // DPAD_LEFT
        )
    }
}
