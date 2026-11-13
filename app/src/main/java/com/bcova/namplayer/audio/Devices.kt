package com.bcova.namplayer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.bcova.namplayer.rig.DeviceChoice

/** A device you can pick for input or output. */
data class AudioDevice(val id: Int, val type: Int, val name: String, val label: String, val warning: String?)

object Devices {
    fun list(context: Context, inputs: Boolean): List<AudioDevice> {
        val am = context.getSystemService(AudioManager::class.java)
        val flag = if (inputs) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        return am.getDevices(flag)
            .filter { it.type !in IGNORED }
            .map { d ->
                val kind = typeLabel(d.type)
                val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL } ?: kind
                AudioDevice(
                    id = d.id,
                    type = d.type,
                    name = name,
                    label = if (name == kind) kind else "$name ($kind)",
                    warning = when (d.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER,
                        -> "Bluetooth adds a lot of delay. Wired or USB is best for playing."
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker + microphone will feed back. Use headphones."
                        else -> null
                    },
                )
            }
            .distinctBy { it.type to it.name }
    }

    /** Finds the current id of a remembered device, or 0 (automatic) if it isn't connected. */
    fun resolve(context: Context, choice: DeviceChoice?, inputs: Boolean): Int =
        choice?.let { c -> list(context, inputs).firstOrNull { it.type == c.type && it.name == c.name }?.id } ?: 0

    private val IGNORED = setOf(
        AudioDeviceInfo.TYPE_TELEPHONY,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_FM_TUNER,
        AudioDeviceInfo.TYPE_TV_TUNER,
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
    )

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        else -> "Other"
    }
}
