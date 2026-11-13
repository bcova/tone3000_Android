package com.bcova.namplayer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** A backing track converted for streaming. */
@Serializable
data class BackingTrack(val id: String, val name: String, val seconds: Double)

/**
 * Converts songs from your phone (MP3, M4A/AAC, WAV, OGG, FLAC…) into the simple format the audio
 * engine streams (48 kHz, stereo, 16-bit), and keeps a list of them in app storage.
 *
 * Analogy: like ripping a CD once, so it plays instantly every time after.
 */
class TrackLibrary(context: Context) {
    private val dir = File(context.filesDir, "tracks").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val resolver = context.contentResolver

    fun list(): List<BackingTrack> =
        runCatching { json.decodeFromString<List<BackingTrack>>(index.readText()) }.getOrDefault(emptyList())

    fun pcmFile(track: BackingTrack) = File(dir, "${track.id}.pcm")

    fun delete(track: BackingTrack) {
        pcmFile(track).delete()
        save(list().filterNot { it.id == track.id })
    }

    private fun save(tracks: List<BackingTrack>) {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(tracks))
        tmp.renameTo(index)
    }

    /** Decodes [uri] into a new track. [progress] gets 0..1. Runs for a few seconds: call off the main thread. */
    fun import(uri: Uri, progress: (Float) -> Unit): BackingTrack {
        val name = displayName(uri).substringBeforeLast('.')
        val id = UUID.randomUUID().toString()
        val out = File(dir, "$id.pcm")
        try {
            val frames = decode(uri, out, progress)
            if (frames < TARGET_RATE / 10) throw IOException("That file has no playable audio.")
            val track = BackingTrack(id, name, frames.toDouble() / TARGET_RATE)
            save(list() + track)
            return track
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Track"

    /** MediaExtractor + MediaCodec -> 48 kHz stereo 16-bit PCM file. Returns frames written. */
    private fun decode(uri: Uri, out: File, progress: (Float) -> Unit): Long {
        val extractor = MediaExtractor()
        resolver.openFileDescriptor(uri, "r").use { fd ->
            requireNotNull(fd) { "Couldn't open the file." }
            extractor.setDataSource(fd.fileDescriptor)
        }
        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("That file has no audio.")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val resampler = Resampler()
            var framesOut = 0L
            BufferedOutputStream(out.outputStream(), 1 shl 16).use { sink ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                if (durationUs > 0) progress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val stereo = toStereo(buf, channels, pcmFloat)
                            framesOut += resampler.process(stereo, inRate, sink)
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
            codec.stop()
            codec.release()
            progress(1f)
            return framesOut
        } finally {
            extractor.release()
        }
    }

    /** Interleaved stereo floats from any channel count (mono is doubled; extra channels dropped). */
    private fun toStereo(buf: ByteBuffer, channels: Int, isFloat: Boolean): FloatArray {
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = buf.remaining() / (bytesPerSample * channels)
        val outArr = FloatArray(frames * 2)
        for (i in 0 until frames) {
            fun sample(): Float = if (isFloat) buf.float else buf.short / 32768f
            val l = sample()
            val r = if (channels >= 2) sample() else l
            repeat(channels - minOf(channels, 2)) { sample() }
            outArr[i * 2] = l
            outArr[i * 2 + 1] = r
        }
        return outArr
    }

    companion object {
        const val TARGET_RATE = 48_000
    }
}
