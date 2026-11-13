package com.bcova.namplayer.rig

import kotlin.math.log2
import kotlin.math.roundToInt

/** The live tuner reading: nearest note and how far off it is. */
data class TunerReading(val hz: Float, val note: String, val octave: Int, val cents: Float)

private val NOTE_NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

/** Nearest note to [hz] (A4 = 440 Hz) and how far off it is, in cents (-50..+50). */
fun noteFor(hz: Float): TunerReading {
    val midi = 69.0 + 12.0 * log2(hz / 440.0)
    val nearest = midi.roundToInt()
    val cents = ((midi - nearest) * 100.0).toFloat()
    return TunerReading(hz, NOTE_NAMES[((nearest % 12) + 12) % 12], Math.floorDiv(nearest, 12) - 1, cents)
}
