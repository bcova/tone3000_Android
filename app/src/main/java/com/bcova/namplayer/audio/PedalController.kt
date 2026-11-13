package com.bcova.namplayer.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.bcova.namplayer.rig.AppSettings
import com.bcova.namplayer.rig.PedalAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Footswitches: USB / Bluetooth MIDI pedals and "page turner" pedals that type keys.
 *
 * - MIDI Program Change N  -> preset N (1st preset = program 0)
 * - MIDI CC / note, or a key -> whatever action it's bound to (set with "Learn")
 *
 * CC and note pedals trigger on press (value 64 or more). Set latching pedals to "momentary".
 */
class PedalController private constructor(private val context: Context) {

    private val settings = AppSettings(context)
    private val main = Handler(Looper.getMainLooper())

    private val _actions = MutableSharedFlow<PedalAction>(extraBufferCapacity = 16)
    val actions: SharedFlow<PedalAction> = _actions

    /** Program Change numbers (0-based). */
    private val _programs = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val programs: SharedFlow<Int> = _programs

    private val _bindings = MutableStateFlow(settings.pedalBindings)
    val bindings: StateFlow<Map<String, PedalAction>> = _bindings

    /** Waiting for a pedal press to bind to this action. */
    private val _learning = MutableStateFlow<PedalAction?>(null)
    val learning: StateFlow<PedalAction?> = _learning

    private val _connected = MutableStateFlow<List<String>>(emptyList())
    val connected: StateFlow<List<String>> = _connected

    private val _scanResults = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scanResults: StateFlow<List<BluetoothDevice>> = _scanResults
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val midi: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) context.getSystemService(MidiManager::class.java) else null
    private val openDevices = mutableMapOf<Int, Pair<MidiDevice, MidiOutputPort>>()

    // ---- Bindings

    fun learn(action: PedalAction?) {
        _learning.value = action
    }

    fun unbind(trigger: String) {
        _bindings.value = _bindings.value - trigger
        settings.pedalBindings = _bindings.value
    }

    fun resetBindings() {
        _bindings.value = AppSettings.DEFAULT_BINDINGS
        settings.pedalBindings = _bindings.value
    }

    /** From MainActivity's key handling. Returns true if the key was used. */
    fun onKey(keyCode: Int): Boolean = trigger("key:$keyCode")

    private fun trigger(trigger: String): Boolean {
        val learningAction = _learning.value
        if (learningAction != null) {
            // One trigger per action: drop the action's old trigger(s), then bind.
            _bindings.value = _bindings.value.filterValues { it != learningAction } + (trigger to learningAction)
            settings.pedalBindings = _bindings.value
            _learning.value = null
            return true
        }
        val action = _bindings.value[trigger] ?: return false
        _actions.tryEmit(action)
        return true
    }

    // ---- MIDI

    fun start() {
        val m = midi ?: return
        m.devices.forEach(::open)
        m.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
            override fun onDeviceRemoved(device: MidiDeviceInfo) = close(device.id)
        }, main)
    }

    private fun open(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0 || openDevices.containsKey(info.id)) return
        midi?.openDevice(info, { device -> device?.let { attach(info, it) } }, main)
    }

    private fun attach(info: MidiDeviceInfo, device: MidiDevice) {
        val port = device.openOutputPort(0) ?: return device.close()
        port.connect(Receiver())
        openDevices[device.info.id] = device to port
        refreshConnected()
    }

    private fun close(id: Int) {
        openDevices.remove(id)?.let { (device, port) ->
            runCatching { port.close() }
            runCatching { device.close() }
        }
        refreshConnected()
    }

    private fun refreshConnected() {
        _connected.value = openDevices.values.map { (d, _) ->
            d.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device"
        }
    }

    /** Parses incoming MIDI bytes (running status included). */
    private inner class Receiver : MidiReceiver() {
        private var status = 0
        private val data = IntArray(2)
        private var count = 0

        override fun onSend(msg: ByteArray, offset: Int, length: Int, timestamp: Long) {
            for (i in offset until offset + length) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) continue // real-time bytes (clock etc.)
                if (b >= 0x80) {
                    status = b
                    count = 0
                    continue
                }
                if (status == 0) continue
                data[count++] = b
                val type = status and 0xF0
                val needed = if (type == 0xC0 || type == 0xD0) 1 else 2
                if (count < needed) continue
                count = 0
                val d0 = data[0] // copy now: the array is reused by the next message
                val d1 = data[1]
                when (type) {
                    0xC0 -> main.post { _programs.tryEmit(d0) }
                    0xB0 -> if (d1 >= 64) main.post { trigger("cc:$d0") }
                    0x90 -> if (d1 > 0) main.post { trigger("note:$d0") }
                }
            }
        }
    }

    // ---- Bluetooth MIDI (needs BLUETOOTH_SCAN / BLUETOOTH_CONNECT, or location on Android 11 and older)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            if (_scanResults.value.none { it.address == d.address }) _scanResults.value = _scanResults.value + d
        }
    }

    @SuppressLint("MissingPermission")
    fun startBluetoothScan() {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        _scanResults.value = emptyList()
        _scanning.value = true
        runCatching {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MIDI_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
        }.onFailure { _scanning.value = false }
        main.postDelayed({ stopBluetoothScan() }, 15_000)
    }

    @SuppressLint("MissingPermission")
    fun stopBluetoothScan() {
        if (!_scanning.value) return
        _scanning.value = false
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectBluetooth(device: BluetoothDevice) {
        stopBluetoothScan()
        midi?.openBluetoothDevice(device, { d -> d?.let { attach(it.info, it) } }, main)
    }

    @SuppressLint("MissingPermission")
    fun nameOf(device: BluetoothDevice): String = runCatching { device.name }.getOrNull() ?: device.address

    companion object {
        /** Standard Bluetooth LE MIDI service. */
        private val MIDI_SERVICE: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")

        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: PedalController? = null

        fun get(context: Context): PedalController =
            instance ?: synchronized(this) {
                instance ?: PedalController(context.applicationContext).also { instance = it; it.start() }
            }
    }
}
