package com.bcova.namplayer.audio

/**
 * Kotlin side of the C++ audio engine (see app/src/main/cpp/native-lib.cpp).
 *
 * Signal chain: guitar in -> input gain -> NAM amp model -> cabinet IR -> output gain -> headphones.
 *
 * `loadModel`/`loadIr` are slow (they parse and warm up the model): call them off the main thread.
 * They return null on success, or an error message.
 */
object NativeAudio {
    init {
        System.loadLibrary("ampengine")
    }

    external fun start(): Boolean
    external fun stop()
    external fun isRunning(): Boolean
    external fun statusText(): String

    external fun loadModel(path: String): String?
    external fun loadIr(path: String): String?
    external fun clearModel()
    external fun clearIr()

    external fun setInputGainDb(db: Float)
    external fun setOutputGainDb(db: Float)
    external fun setModelEnabled(enabled: Boolean)
    external fun setIrEnabled(enabled: Boolean)
    external fun setNormalize(enabled: Boolean)

    /** Peak level (linear) since the last call. */
    external fun takeInputPeak(): Float
    external fun takeOutputPeak(): Float
}
