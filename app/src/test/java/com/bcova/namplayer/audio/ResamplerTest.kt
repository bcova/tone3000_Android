package com.bcova.namplayer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ResamplerTest {
    private fun sineStereo(freq: Double, rate: Int, frames: Int) = FloatArray(frames * 2) { i ->
        (0.5 * sin(2 * PI * freq * (i / 2) / rate)).toFloat()
    }

    private fun run(input: FloatArray, inRate: Int, chunkFrames: List<Int>): ShortArray {
        val r = Resampler()
        val sink = ByteArrayOutputStream()
        var pos = 0
        for (n in chunkFrames) {
            r.process(input.copyOfRange(pos * 2, (pos + n) * 2), inRate, sink)
            pos += n
        }
        val bytes = ByteBuffer.wrap(sink.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.remaining() / 2) { bytes.short }
    }

    @Test
    fun converts44kTo48kKeepingPitch() {
        val frames = 44_100 // 1 second
        val out = run(sineStereo(1000.0, 44_100, frames), 44_100, listOf(frames))
        val outFrames = out.size / 2
        assertEquals(48_000.0, outFrames.toDouble(), 3.0)
        // Count rising zero crossings on the left channel: ~1000 per second.
        var crossings = 0
        for (i in 1 until outFrames) if (out[(i - 1) * 2] < 0 && out[i * 2] >= 0) crossings++
        assertEquals(1000.0, crossings.toDouble(), 2.0)
    }

    @Test
    fun chunkBoundariesDontMatter() {
        val input = sineStereo(440.0, 44_100, 10_000)
        val whole = run(input, 44_100, listOf(10_000))
        val pieces = run(input, 44_100, listOf(1, 999, 1024, 37, 4000, 3939))
        assertEquals(whole.size, pieces.size)
        val maxDiff = whole.indices.maxOf { abs(whole[it] - pieces[it]) }
        assertTrue("max diff $maxDiff", maxDiff <= 1)
    }

    @Test
    fun sameRatePassesThrough() {
        val input = sineStereo(440.0, 48_000, 2_000)
        val out = run(input, 48_000, listOf(2_000))
        val expected = ShortArray(out.size) { (input[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        assertArrayEquals(expected, out)
    }
}
