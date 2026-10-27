package com.edureminder.quicktube.core.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Keeps a track's timestamps continuous. HLS downloads are segments back to back, and after an
 * EXT-X-DISCONTINUITY (an intro, an ad break) the timestamps restart; the rest of the track then continues one
 * frame after the latest sample instead of playing on top of what came before. B-frame reordering moves
 * timestamps back by far less than a second, so it's left alone.
 */
internal class Timeline {
    private var maxTimeUs = Long.MIN_VALUE
    private var frameUs = 0L
    private var offsetUs = 0L

    fun next(timeUs: Long): Long {
        var t = timeUs + offsetUs
        if (maxTimeUs != Long.MIN_VALUE && (t < maxTimeUs - 1_000_000 || t > maxTimeUs + 10_000_000)) {
            val continued = maxTimeUs + frameUs.coerceAtLeast(1)
            offsetUs += continued - t
            t = continued
        }
        if (t > maxTimeUs) {
            // Smallest step between new highest timestamps = one frame (B-frames make some steps longer).
            if (maxTimeUs != Long.MIN_VALUE && (frameUs == 0L || t - maxTimeUs < frameUs)) frameUs = t - maxTimeUs
            maxTimeUs = t
        }
        return t
    }
}

/** One audio or video track of a file, read sample by sample in decode order. */
internal interface TrackReader : Closeable {
    val format: MediaFormat
    val rotation: Int
    /** Presentation time of the next sample, or null when the track is finished. */
    fun nextTime(): Long?
    /** Copies the next sample into a buffer from [buffer] (sized for it) and fills [info]. */
    fun read(buffer: (Int) -> ByteBuffer, info: MediaCodec.BufferInfo): ByteBuffer
}

/**
 * Media3's extractors running in this process. The platform MediaExtractor parses in the media.extractor
 * service and pays a binder round trip per sample, which made merging a 10-minute video take ~70 s.
 */
@OptIn(UnstableApi::class)
internal class Media3TrackReader private constructor(private val file: File, private val trackType: Int) : TrackReader {

    private class Sample(val timeUs: Long, val flags: Int, val data: ByteArray)

    /** Collects one track's samples; tracks we don't need are read and dropped. */
    private inner class Collector(val keep: Boolean) : TrackOutput {
        var fmt: Format? = null
        private var buf = ByteArray(if (keep) 1 shl 20 else 0)
        private var len = 0
        private val timeline = Timeline()
        private val scratch by lazy { ByteArray(64 * 1024) }

        override fun format(format: Format) { fmt = format }

        private fun ensure(extra: Int) {
            if (len + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = if (keep) { ensure(length); input.read(buf, len, length) } else input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return n else throw EOFException() }
            if (keep) len += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (!keep) { data.skipBytes(length); return }
            ensure(length)
            data.readBytes(buf, len, length)
            len += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!keep) return
            if (cryptoData != null) throw UnsupportedCodecException("This video is encrypted and can't be saved")
            // The sample is the [size] bytes that end [offset] bytes before what has been written so far.
            val start = len - offset - size
            queue.addLast(Sample(timeline.next(timeUs), flags, buf.copyOfRange(start, start + size)))
            System.arraycopy(buf, start + size, buf, start, offset)
            len -= size
        }
    }

    private val reader = FileReader(file)
    private var length = file.length()
    private var input: ExtractorInput = DefaultExtractorInput(reader, 0, length)
    private val position = PositionHolder()
    private val queue = ArrayDeque<Sample>()
    private var selected: Collector? = null
    private var ended = false
    private lateinit var extractor: Extractor
    private var isTs = false

    init {
        try {
            setUp()
        } catch (e: Throwable) {
            runCatching { reader.close() }
            throw e
        }
    }

    private fun setUp() {
        val sniffed = DefaultExtractorsFactory().createExtractors().firstOrNull { e ->
            reader.seek(0)
            val probe = DefaultExtractorInput(reader, 0, length)
            runCatching { e.sniff(probe) }.getOrDefault(false).also { if (!it) e.release() }
        } ?: run { reader.close(); throw IOException("Unrecognized media file") }
        // HLS parts are many TS segments back to back. The default TS mode treats every segment start as a
        // discontinuity and drops the frame in flight (one per segment); HLS mode reads across them, like ExoPlayer.
        extractor = if (sniffed.underlyingImplementation is TsExtractor) {
            sniffed.release()
            isTs = true
            // Media3's TS H.264/H.265 readers only finish a frame when the next one starts, so the stream's
            // last frame would be lost; a closing packet after the file's end starts that "next" frame.
            if (trackType == C.TRACK_TYPE_VIDEO) TsTail.closingPacket(file)?.let { reader.tail = it; length += it.size }
            TsExtractor(TsExtractor.MODE_HLS, TimestampAdjuster(0), DefaultTsPayloadReaderFactory())
        } else sniffed
        reader.seek(0)
        input = DefaultExtractorInput(reader, 0, length)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val keep = type == trackType && selected == null
                return Collector(keep).also { if (keep) selected = it }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        // Read until the wanted track has announced its format.
        while (selected?.fmt == null && pump()) Unit
        if (selected?.fmt == null) { close(); throw IOException("No ${if (trackType == C.TRACK_TYPE_VIDEO) "video" else "audio"} track") }
    }

    /** The track's Media3 format, for Media3's own muxer. */
    val media3Format: Format get() = selected!!.fmt!!

    override val format: MediaFormat by lazy { MediaFormatUtil.createMediaFormatFromFormat(media3Format) }
    override val rotation: Int get() = media3Format.rotationDegrees

    private fun pump(): Boolean {
        if (ended) return false
        when (extractor.read(input, position)) {
            Extractor.RESULT_CONTINUE -> Unit
            Extractor.RESULT_SEEK -> {
                reader.seek(position.position)
                input = DefaultExtractorInput(reader, position.position, length)
            }
            else -> ended = true
        }
        return !ended
    }

    override fun nextTime(): Long? {
        while (queue.isEmpty() && pump()) Unit
        return queue.firstOrNull()?.timeUs
    }

    override fun read(buffer: (Int) -> ByteBuffer, info: MediaCodec.BufferInfo): ByteBuffer {
        nextTime()
        val s = queue.removeFirst()
        val out = buffer(s.data.size)
        out.clear()
        out.put(s.data)
        out.flip()
        info.set(0, s.data.size, s.timeUs, if (s.flags and C.BUFFER_FLAG_KEY_FRAME != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        return out
    }

    /** The next sample as (time, Media3 flags, bytes); the array is not reused, so a muxer may keep it. */
    fun readSample(): Triple<Long, Int, ByteArray> {
        nextTime()
        val s = queue.removeFirst()
        return Triple(s.timeUs, s.flags, s.data)
    }

    override fun close() {
        runCatching { extractor.release() }
        reader.close()
    }

    /** Buffered random-access file input for the extractor, plus optional [tail] bytes served after the file's end. */
    private class FileReader(file: File) : DataReader, Closeable {
        private val raf = RandomAccessFile(file, "r")
        private val fileLength = raf.length()
        private val buf = ByteArray(512 * 1024)
        private var pos = 0
        private var lim = 0
        /** Absolute position of the next byte returned. */
        private var at = 0L
        var tail = ByteArray(0)

        fun seek(to: Long) {
            at = to; pos = 0; lim = 0
            if (to < fileLength) raf.seek(to)
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (at >= fileLength) {
                val t = (at - fileLength).toInt()
                if (t >= tail.size) return C.RESULT_END_OF_INPUT
                val n = minOf(length, tail.size - t)
                System.arraycopy(tail, t, target, offset, n)
                at += n
                return n
            }
            if (pos == lim) {
                // Large reads skip the buffer.
                val n = if (length >= buf.size) raf.read(target, offset, length) else -2
                if (n >= 0) { at += n; return n }
                if (n == -1) { at = fileLength; return read(target, offset, length) }
                lim = raf.read(buf, 0, buf.size).coerceAtLeast(0)
                pos = 0
                if (lim == 0) { at = fileLength; return read(target, offset, length) }
            }
            val n = minOf(length, lim - pos)
            System.arraycopy(buf, pos, target, offset, n)
            pos += n
            at += n
            return n
        }

        override fun close() = raf.close()
    }

    companion object {
        fun open(file: File, prefix: String): Media3TrackReader =
            Media3TrackReader(file, if (prefix == "video/") C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO)
    }
}

/** The platform MediaExtractor: slower, kept as the fallback for files Media3 can't parse. */
internal class PlatformTrackReader(file: File, prefix: String) : TrackReader {
    private val ex = MediaExtractor()
    override val format: MediaFormat
    override val rotation: Int

    init {
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
            } ?: throw IOException("The downloaded file has no ${prefix.trimEnd('/')} track")
            ex.selectTrack(track)
            format = ex.getTrackFormat(track)
            rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        } catch (e: Throwable) {
            ex.release()
            throw e
        }
    }

    override fun nextTime(): Long? = if (ex.sampleTrackIndex < 0) null else ex.sampleTime

    override fun read(buffer: (Int) -> ByteBuffer, info: MediaCodec.BufferInfo): ByteBuffer {
        val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        val out = buffer(maxOf(maxSize, 4 * 1024 * 1024))
        out.clear()
        val size = ex.readSampleData(out, 0)
        info.set(0, size, ex.sampleTime,
            if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        ex.advance()
        return out
    }

    override fun close() = ex.release()
}
