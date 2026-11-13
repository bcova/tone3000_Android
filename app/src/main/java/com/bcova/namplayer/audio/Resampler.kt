package com.bcova.namplayer.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Linear-interpolation resampler to 48 kHz that keeps its place between buffers. */
class Resampler(private val targetRate: Int = TrackLibrary.TARGET_RATE) {
    private var pos = 0.0 // position in the input stream, relative to the current buffer
    private var prevL = 0f
    private var prevR = 0f
    private val outBuf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

    fun process(input: FloatArray, inRate: Int, sink: java.io.OutputStream): Long {
        val frames = input.size / 2
        if (frames == 0) return 0
        val step = inRate.toDouble() / targetRate
        var written = 0L
        // Sample at fractional input positions; index -1 refers to the previous buffer's last frame.
        // Needs input[i] and input[i + 1]; anything later waits for the next buffer.
        while (pos < frames - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val l0 = if (i < 0) prevL else input[i * 2]
            val r0 = if (i < 0) prevR else input[i * 2 + 1]
            val l1 = input[(i + 1).coerceAtMost(frames - 1) * 2]
            val r1 = input[(i + 1).coerceAtMost(frames - 1) * 2 + 1]
            put(l0 + (l1 - l0) * frac, sink)
            put(r0 + (r1 - r0) * frac, sink)
            written++
            pos += step
        }
        prevL = input[(frames - 1) * 2]
        prevR = input[(frames - 1) * 2 + 1]
        pos -= frames // keep the fraction for the next buffer
        flush(sink)
        return written
    }

    private fun put(v: Float, sink: java.io.OutputStream) {
        if (outBuf.remaining() < 2) flush(sink)
        outBuf.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
    }

    private fun flush(sink: java.io.OutputStream) {
        sink.write(outBuf.array(), 0, outBuf.position())
        outBuf.clear()
    }
}
