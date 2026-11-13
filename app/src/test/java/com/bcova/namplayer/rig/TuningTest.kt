package com.bcova.namplayer.rig

import org.junit.Assert.assertEquals
import org.junit.Test

class TuningTest {
    @Test
    fun namesNotesAndCents() {
        fun check(hz: Float, note: String, octave: Int, cents: Float) {
            val r = noteFor(hz)
            assertEquals(note, r.note)
            assertEquals(octave, r.octave)
            assertEquals(cents, r.cents, 0.5f)
        }
        check(440f, "A", 4, 0f)
        check(82.41f, "E", 2, 0f) // low E string
        check(329.63f, "E", 4, 0f) // high E string
        check(30.87f, "B", 0, 0f) // 7-string low B
        check(110f * 1.0058f, "A", 2, 10f) // 10 cents sharp
        check(196f / 1.0116f, "G", 3, -20f) // 20 cents flat
        check(261.63f, "C", 4, 0f) // octave boundary
        check(246.94f, "B", 3, 0f)
    }
}
