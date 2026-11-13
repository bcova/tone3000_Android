package com.bcova.namplayer.audio

/**
 * Kotlin side of the C++ audio engine (see app/src/main/cpp/native-lib.cpp).
 *
 * Signal chain: guitar in -> input gain -> block 1 -> block 2 -> ... -> output gain -> headphones.
 * Each block is a .nam capture or a .wav impulse response, identified by an id the app picks.
 *
 * `loadBlock` is slow (it parses and warms up the model): call it off the main thread.
 * It returns null on success, or an error message.
 */
object NativeAudio {
    init {
        System.loadLibrary("ampengine")
    }

    external fun start(): Boolean
    external fun stop()
    external fun isRunning(): Boolean
    external fun statusText(): String

    external fun loadBlock(id: Long, path: String): String?
    external fun removeBlock(id: Long)
    /** Chain order, first to last. */
    external fun setOrder(ids: LongArray)
    external fun setBlockEnabled(id: Long, enabled: Boolean)

    external fun setInputGainDb(db: Float)
    external fun setOutputGainDb(db: Float)
    external fun setNormalize(enabled: Boolean)
    /** Noise gate (like the NAM plugin). */
    external fun setGate(enabled: Boolean, thresholdDb: Float)
    /** EQ knobs 0..10, 5 = flat (like the NAM plugin's tone stack). */
    external fun setEq(enabled: Boolean, bass: Float, middle: Float, treble: Float)

    /** Audio devices (AudioDeviceInfo ids, 0 = automatic). Restarts audio if running. */
    external fun setDevices(inputId: Int, outputId: Int)
    /** Output buffer in bursts: 1 = lowest latency, 2 = normal, 4 = safest. */
    external fun setBufferBursts(bursts: Int)
    /** Estimated guitar-to-headphones delay in ms, or -1. */
    external fun roundTripLatencyMs(): Int

    external fun setBlockLevelDb(id: Long, db: Float)
    external fun setEqBeforeCab(before: Boolean)
    /** Like the NAM plugin's input calibration. [interfaceDbu] = dBu that reads 0 dBFS on your interface. */
    external fun setInputCalibration(enabled: Boolean, interfaceDbu: Float)

    /** Records the output to a 16-bit stereo WAV. Returns null or an error. */
    external fun startRecording(path: String): String?
    /** Returns the recorded length in seconds. */
    external fun stopRecording(): Double
    /** Seconds recorded so far, or -1 when not recording. */
    external fun recordingSeconds(): Double

    /** Tuner mode: mutes the output and analyzes the input. */
    external fun setTunerActive(active: Boolean)
    /** [hz, clarity]. hz = 0 when there's no clear note. Call off the main thread. */
    external fun readTunerPitch(): FloatArray

    /** Peak level (linear) since the last call. */
    external fun takeInputPeak(): Float
    external fun takeOutputPeak(): Float
    /** Highest share of the audio time budget used since the last call (1.0 = 100%). */
    external fun takeCpuLoad(): Float
}
