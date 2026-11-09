package com.edureminder.quicktube.core.download

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The audio formats besides MP3 (see [Mp3Encoder]) made from a site's audio: WAV and FLAC from the decoded sound,
 * AAC (ADTS) and Opus (Ogg) by repackaging the site's own stream, so nothing is lost.
 */
internal object AudioConverter {
    val TYPES = setOf("wav", "flac", "aac", "opus")

    /** Converts [input] into [output] by [output]'s extension. */
    suspend fun convert(input: File, output: File) {
        output.delete()
        try {
            when (output.extension.lowercase()) {
                "wav" -> toWav(input, output)
                "flac" -> toFlac(input, output)
                "aac" -> toAdts(input, output)
                "opus" -> toOgg(input, output)
                else -> throw IOException("Unknown audio format ${output.extension}")
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        }
    }

    private fun audioTrack(extractor: MediaExtractor, path: String): Pair<Int, MediaFormat> {
        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
        } ?: throw IOException("No audio track")
        extractor.selectTrack(track)
        return track to extractor.getTrackFormat(track)
    }

    /** Decodes [input] to 16-bit PCM, handing each piece to [onPcm] with the sample rate and channel count. */
    private suspend fun decode(input: File, onPcm: (pcm: ShortArray, rate: Int, channels: Int) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val (_, format) = audioTrack(extractor, input.path)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var float = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) codec.outputFormat.let {
                    channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    float = it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }.order(ByteOrder.nativeOrder())
                        val pcm = if (float) {
                            val f = buf.asFloatBuffer()
                            ShortArray(f.remaining()) { (f.get(it).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
                        } else buf.asShortBuffer().let { s -> ShortArray(s.remaining()).also { s.get(it) } }
                        onPcm(pcm, rate, channels)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    // ---- WAV: a RIFF header (sizes filled in at the end) and the 16-bit samples ----

    private suspend fun toWav(input: File, output: File) {
        var rate = 0
        var channels = 0
        var bytes = 0L
        output.outputStream().buffered(1 shl 16).use { out ->
            out.write(ByteArray(44))
            decode(input) { pcm, r, c ->
                rate = r; channels = c
                val b = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                b.asShortBuffer().put(pcm)
                out.write(b.array())
                bytes += b.capacity()
            }
        }
        if (bytes == 0L) throw IOException("No audio decoded")
        RandomAccessFile(output, "rw").use { it.seek(0); it.write(wavHeader(rate, channels, bytes)) }
    }

    internal fun wavHeader(rate: Int, channels: Int, dataBytes: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt()); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate)
        putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
        put("data".toByteArray()); putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
    }.array()

    // ---- FLAC: the phone's own FLAC encoder; its first output is the "fLaC" stream header ----

    private suspend fun toFlac(input: File, output: File) {
        var encoder: MediaCodec? = null
        val info = MediaCodec.BufferInfo()
        var wroteHeader = false
        var ptsUs = 0L
        var rate = 0
        var channels = 0
        var samples = 0L
        output.outputStream().buffered(1 shl 16).use { out ->
            fun drain(eos: Boolean) {
                val e = encoder ?: return
                var waits = 0
                while (true) {
                    val o = e.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                    // At the end, wait for the last frames (at most ~5 s), else take what's there.
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos || ++waits > 500) return else continue }
                    if (o < 0) continue
                    if (info.size > 0) {
                        val buf = e.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
                        val bytes = ByteArray(info.size).also { buf.get(it) }
                        // The codec-config buffer is the stream header ("fLaC" + STREAMINFO); a few encoders leave the marker out.
                        if (!wroteHeader && (bytes.size < 4 || String(bytes, 0, 4, Charsets.ISO_8859_1) != "fLaC")) out.write("fLaC".toByteArray())
                        wroteHeader = true
                        out.write(bytes)
                    }
                    e.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
            fun feed(pcm: ByteArray, flags: Int) {
                val e = encoder!!
                var at = 0
                while (true) {
                    val i = e.dequeueInputBuffer(10_000)
                    if (i < 0) { drain(false); continue }
                    val buf = e.getInputBuffer(i)!!
                    val n = minOf(buf.remaining(), pcm.size - at)
                    buf.put(pcm, at, n)
                    at += n
                    val done = at >= pcm.size
                    e.queueInputBuffer(i, 0, n, ptsUs, if (done) flags else 0)
                    ptsUs += n / 2 / channels * 1_000_000L / rate
                    drain(false)
                    if (done) return
                }
            }
            try {
                decode(input) { pcm, r, c ->
                    if (encoder == null) {
                        rate = r; channels = c
                        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
                            configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, r, c).apply {
                                setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
                                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
                            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            start()
                        }
                    }
                    val b = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.nativeOrder())
                    b.asShortBuffer().put(pcm)
                    samples += pcm.size / c
                    feed(b.array(), 0)
                }
                if (encoder == null) throw IOException("No audio decoded")
                feed(ByteArray(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            } finally {
                runCatching { encoder?.stop() }
                runCatching { encoder?.release() }
            }
        }
        setFlacLength(output, samples)
    }

    /**
     * The encoder can't know the length up front and writes 0 ("unknown") in STREAMINFO, so players show no duration
     * and can't seek well: put the real number of samples in.
     */
    internal fun setFlacLength(file: File, samples: Long) = RandomAccessFile(file, "rw").use { raf ->
        val head = ByteArray(8).also { raf.readFully(it) }
        // "fLaC", then the first metadata block must be STREAMINFO (type 0).
        if (String(head, 0, 4, Charsets.ISO_8859_1) != "fLaC" || head[4].toInt() and 0x7F != 0) return@use
        // After min/max block size and min/max frame size: 20 bits rate, 3 channels, 5 bits per sample, 36 bits samples.
        raf.seek(18)
        val v = raf.readLong()
        raf.seek(18)
        raf.writeLong((v and 0xFFFFFFFFFL.inv()) or (samples and 0xFFFFFFFFFL))
    }

    // ---- AAC: the M4A's samples, each with a 7-byte ADTS header ----

    private suspend fun toAdts(input: File, output: File) {
        val extractor = MediaExtractor()
        try {
            val (_, format) = audioTrack(extractor, input.path)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) throw IOException("The audio isn't AAC")
            val csd = format.getByteBuffer("csd-0")
            // AudioSpecificConfig: 5 bits object type, 4 bits frequency index, 4 bits channel configuration.
            val profile = csd?.let { (it.get(0).toInt() and 0xF8) shr 3 } ?: MediaCodecInfo.CodecProfileLevel.AACObjectLC
            val freq = csd?.let { ((it.get(0).toInt() and 0x07) shl 1) or ((it.get(1).toInt() and 0x80) shr 7) }
                ?: sampleRateIndex(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            val chan = csd?.let { (it.get(1).toInt() and 0x78) shr 3 } ?: format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val buf = ByteBuffer.allocate(1 shl 20)
            output.outputStream().buffered(1 shl 16).use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    buf.clear()
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) break
                    out.write(adtsHeader(n, profile, freq, chan))
                    out.write(buf.array(), buf.arrayOffset(), n)
                    extractor.advance()
                }
            }
        } finally {
            extractor.release()
        }
    }

    internal fun adtsHeader(payload: Int, profile: Int, freqIndex: Int, channels: Int): ByteArray {
        val len = payload + 7
        val p = (profile - 1).coerceIn(0, 3)
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((p shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    private fun sampleRateIndex(rate: Int) =
        listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350).indexOf(rate).takeIf { it >= 0 } ?: 4

    // ---- Opus: the WebM's Opus packets in an Ogg file (Android's muxer writes Ogg Opus) ----

    private suspend fun toOgg(input: File, output: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            val (_, format) = audioTrack(extractor, input.path)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_OPUS) throw IOException("The audio isn't Opus")
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            val track = muxer.addTrack(format)
            muxer.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(track, buf, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
