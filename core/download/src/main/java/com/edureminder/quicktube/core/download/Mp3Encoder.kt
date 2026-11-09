package com.edureminder.quicktube.core.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Converts any audio the phone can decode (AAC, Opus, Vorbis…) into an MP3: the platform decoder gives PCM, and
 * jump3r (LAME in plain Java) encodes it. Android has no MP3 encoder of its own.
 */
internal object Mp3Encoder {
    /** True when [file] already is an MP3 (an ID3 tag or an MPEG audio frame at the start). */
    fun isMp3(file: File): Boolean = runCatching {
        val h = ByteArray(3)
        if (file.inputStream().use { it.read(h) } < 3) return false
        (h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) ||
            (h[0].toInt() and 0xFF == 0xFF && h[1].toInt() and 0xE0 == 0xE0 && h[1].toInt() and 0x06 != 0)
    }.getOrDefault(false)

    suspend fun encode(input: File, output: File, kbps: Int = 192) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var lame: LameSession? = null
        try {
            extractor.setDataSource(input.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var float = false
            output.outputStream().buffered(1 shl 16).use { out ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let {
                            channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            float = it.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                it.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            if (info.size > 0) {
                                val session = lame ?: LameSession(channels, rate, kbps).also { lame = it }
                                val pcm = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
                                    .order(ByteOrder.nativeOrder())
                                out.write(session.encode(pcm, channels, float))
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
                out.write(lame?.finish() ?: throw IOException("No audio decoded"))
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** One LAME encoder, wired the way jump3r's own (javax.sound based) LameEncoder does it. */
    private class LameSession(channels: Int, sampleRate: Int, kbps: Int) {
        private val lame = Lame()
        private val gfp: LameGlobalFlags
        /** Interleaved PCM keeps the decoder's channel count; LAME gets at most two. */
        private val outChannels = channels.coerceIn(1, 2)
        private var left = IntArray(0)
        private var right = IntArray(0)
        private var buffer = ByteArray(0)

        init {
            val ga = GainAnalysis(); val bs = BitStream(); val p = Presets(); val qupvt = QuantizePVT(); val qu = Quantize()
            val vbr = VBRTag(); val ver = Version(); val id3 = ID3Tag(); val rv = Reservoir(); val tak = Takehiro()
            val parse = Parse(); val mpg = MPGLib(); val intf = Interface(); val common = Common(); val gaud = GetAudio()
            lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
            bs.setModules(ga, mpg, ver, vbr)
            id3.setModules(bs, ver)
            p.setModules(lame)
            qu.setModules(bs, rv, qupvt, tak)
            qupvt.setModules(tak, rv, lame.enc.psy)
            rv.setModules(bs)
            tak.setModules(qupvt)
            vbr.setModules(lame, bs, ver)
            gaud.setModules(parse, mpg)
            parse.setModules(ver, id3, p)
            mpg.setModules(intf, common)
            intf.setModules(vbr, common)
            gfp = lame.lame_init()
            gfp.num_channels = outChannels
            gfp.in_samplerate = sampleRate
            gfp.mode = if (outChannels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
            gfp.VBR = VbrMode.vbr_off
            gfp.brate = kbps
            // 5 is LAME's default. 7 measured only ~5% faster here (about 10 s per minute of music on a low-end phone).
            gfp.quality = 5
            id3.id3tag_init(gfp)
            gfp.write_id3tag_automatic = false
            // No Xing/Info frame: it's only right once LAME seeks back to rewrite it, which a stream can't.
            gfp.bWriteVbrTag = false
            if (lame.lame_init_params(gfp) < 0) throw IOException("MP3 encoder: unsupported audio ($channels ch, $sampleRate Hz)")
        }

        /** [pcm]: interleaved 16-bit samples, or 32-bit floats when [float]. */
        fun encode(pcm: java.nio.ByteBuffer, channels: Int, float: Boolean): ByteArray {
            val shorts = if (float) null else pcm.asShortBuffer()
            val floats = if (float) pcm.asFloatBuffer() else null
            fun sample(i: Int): Int = if (floats != null) (floats.get(i).coerceIn(-1f, 1f) * Int.MAX_VALUE).toInt() else shorts!!.get(i).toInt() shl 16
            val frames = (shorts?.remaining() ?: floats!!.remaining()) / channels
            if (left.size < frames) { left = IntArray(frames); right = IntArray(frames) }
            for (f in 0 until frames) {
                val l = sample(f * channels)
                left[f] = l
                right[f] = if (channels > 1) sample(f * channels + 1) else l
            }
            val need = (frames * 5) / 4 + 7200
            if (buffer.size < need) buffer = ByteArray(need)
            val n = lame.lame_encode_buffer_int(gfp, left, right, frames, buffer, 0, buffer.size)
            if (n < 0) throw IOException("MP3 encoder error $n")
            return buffer.copyOf(n)
        }

        fun finish(): ByteArray {
            val tail = ByteArray(7200)
            val n = lame.lame_encode_flush(gfp, tail, 0, tail.size)
            lame.lame_close(gfp)
            return tail.copyOf(n.coerceAtLeast(0))
        }
    }
}
