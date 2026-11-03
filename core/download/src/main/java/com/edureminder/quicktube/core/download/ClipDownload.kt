package com.edureminder.quicktube.core.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
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
import androidx.media3.muxer.Mp4Muxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads one part of a video ("cut"): only the bytes from about [startUs] to [endUs] are fetched. Media3's extractors
 * read the stream over HTTP range requests, jump to the keyframe before [startUs] using the stream's own index (the
 * DASH `sidx`, the MP4 sample table, WebM cues), and the samples up to [endUs] go into an MP4 whose edit list starts
 * playback exactly at [startUs] and ends exactly at [endUs] (no re-encoding).
 */
@OptIn(UnstableApi::class)
internal class ClipDownload(
    private val http: OkHttpClient,
    private val request: (url: String, range: String) -> Request,
    private val startUs: Long,
    private val endUs: Long,
) {
    /** Writes the clip of [videoUrl] (+ [audioUrl] when separate) to [out] (.mp4, or .m4a for audio only). */
    suspend fun run(
        videoUrl: String, audioUrl: String?, audioOnly: Boolean, out: File, videoBytes: AtomicLong, audioBytes: AtomicLong, onBytes: (Int) -> Unit,
    ) {
        val sources = buildList {
            add(videoUrl to videoBytes)
            if (audioUrl != null) add(audioUrl to audioBytes)
        }
        val readers = mutableListOf<ClipTrackReader>()
        try {
            if (audioUrl == null) {
                // One file holding everything (muxed MP4, or an audio-only stream): one reader per track type it has.
                for (type in if (audioOnly) listOf(C.TRACK_TYPE_AUDIO) else listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
                    val r = runCatching { ClipTrackReader(HttpRangeReader(videoUrl, videoBytes, onBytes), type) }.getOrElse { e ->
                        if (e is HttpStatusException) throw e else null
                    }
                    r?.let { readers += it }
                }
            } else for ((url, counter) in sources) {
                val type = if (url == videoUrl) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
                readers += ClipTrackReader(HttpRangeReader(url, counter, onBytes), type)
            }
            if (readers.isEmpty()) throw IOException("No playable tracks")
            val mimes = readers.map { it.format.sampleMimeType.orEmpty() }
            if (!mimes.all { it in Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES || it in Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES }) {
                throw UnsupportedCodecException("This format (${mimes.joinToString(" + ") { it.substringAfter('/') }}) can't be cut")
            }
            readers.forEach { it.seekToStart() }
            val rotation = readers.firstOrNull { it.isVideo }?.format?.rotationDegrees ?: 0
            StreamingMp4Writer(out, readers.map { it.format }, rotation, clipDurationUs = endUs - startUs).use { writer ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    // Interleaved by time, like a normal merge.
                    var best = -1
                    var bestTime = Long.MAX_VALUE
                    for (i in readers.indices) {
                        val t = readers[i].nextTime() ?: continue
                        if (t < bestTime) { best = i; bestTime = t }
                    }
                    if (best < 0) break
                    val s = readers[best].take()
                    writer.writeSample(best, s.timeUs - startUs, s.keyFrame, s.data)
                }
                writer.finish()
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            readers.forEach { runCatching { it.close() } }
        }
    }

    private class Sample(val timeUs: Long, val keyFrame: Boolean, val data: ByteArray)

    /** One track of a remote stream, from the keyframe before the clip's start to its end. */
    private inner class ClipTrackReader(private val reader: HttpRangeReader, private val trackType: Int) : Closeable {
        val isVideo = trackType == C.TRACK_TYPE_VIDEO
        private var input: ExtractorInput
        private val position = PositionHolder()
        private val queue = ArrayDeque<Sample>()
        /** Video read before the start: kept from the latest keyframe, dropped once a newer keyframe still comes before it. */
        private val preroll = ArrayList<Sample>()
        private var started = false
        private var finished = false
        private var ended = false
        private var seekMap: SeekMap? = null
        private var collector: Collector? = null
        private var tracksKnown = false
        private val extractor: Extractor

        init {
            try {
                extractor = DefaultExtractorsFactory().createExtractors().firstOrNull { e ->
                    reader.seek(0)
                    runCatching { e.sniff(DefaultExtractorInput(reader, 0, reader.length)) }.getOrDefault(false).also { if (!it) e.release() }
                } ?: throw IOException("Unrecognized media stream")
                reader.seek(0)
                input = DefaultExtractorInput(reader, 0, reader.length)
                extractor.init(object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput {
                        val keep = type == trackType && collector == null
                        return Collector(keep).also { if (keep) collector = it }
                    }
                    override fun endTracks() { tracksKnown = true }
                    override fun seekMap(seekMap: SeekMap) { this@ClipTrackReader.seekMap = seekMap }
                })
                // The track's format and the stream's index come from its header.
                // (Stops as soon as the file's tracks are known not to include this one: never reads the whole stream.)
                while ((collector?.fmt == null || seekMap == null) && !(tracksKnown && collector == null) && pump()) Unit
                if (collector?.fmt == null) throw IOException("No ${if (isVideo) "video" else "audio"} track")
            } catch (e: Throwable) {
                reader.close()
                throw e
            }
        }

        val format: Format get() = collector!!.fmt!!

        /** Jumps to the index point (a keyframe) at or before the clip's start; without an index, reads up to it. */
        fun seekToStart() {
            val map = seekMap ?: return
            if (!map.isSeekable || startUs <= 0) return
            val point = map.getSeekPoints(startUs).first
            extractor.seek(point.position, point.timeUs)
            reader.seek(point.position)
            input = DefaultExtractorInput(reader, point.position, reader.length)
            queue.clear()
            ended = false
        }

        private fun pump(): Boolean {
            if (ended) return false
            when (extractor.read(input, position)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> {
                    reader.seek(position.position)
                    input = DefaultExtractorInput(reader, position.position, reader.length)
                }
                else -> ended = true
            }
            return !ended
        }

        /** Time of the next sample of the clip, or null when the track's part is done. */
        fun nextTime(): Long? {
            while (!finished) {
                if (started && preroll.isNotEmpty()) return preroll.first().timeUs
                val s = queue.removeFirstOrNull() ?: if (pump()) continue else { finished = true; break }
                accept(s)
            }
            return preroll.firstOrNull()?.timeUs
        }

        fun take(): Sample = preroll.removeAt(0)

        /** Decides what [s] means for the clip; samples to write go to [preroll] once [started]. */
        private fun accept(s: Sample) {
            if (isVideo) {
                // Stop at the first keyframe past the end: the frames before it may still be needed to decode the
                // last frames shown (the edit list hides everything after the end).
                if (s.keyFrame && s.timeUs >= endUs) { finished = true; return }
                if (s.timeUs > endUs + MAX_TAIL_US) { finished = true; return }
                if (!started) {
                    if (s.keyFrame && s.timeUs <= startUs) preroll.clear()
                    // Nothing decodable before the first keyframe.
                    if (preroll.isEmpty() && !s.keyFrame) return
                    preroll += s
                    if (s.timeUs >= startUs) started = true
                } else preroll += s
            } else {
                if (s.timeUs >= endUs) { finished = true; return }
                // Audio frames are all independent: the clip's sound starts at (just before) its start.
                if (s.timeUs < startUs - AUDIO_LEAD_US) return
                started = true
                preroll += s
            }
        }

        override fun close() {
            runCatching { extractor.release() }
            reader.close()
        }

        /** Collects one track's samples; tracks we don't need are read and dropped. */
        private inner class Collector(val keep: Boolean) : TrackOutput {
            var fmt: Format? = null
            private var buf = ByteArray(if (keep) 1 shl 18 else 0)
            private var len = 0
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
                val start = len - offset - size
                queue.addLast(Sample(timeUs, flags and C.BUFFER_FLAG_KEY_FRAME != 0, buf.copyOfRange(start, start + size)))
                System.arraycopy(buf, start + size, buf, start, offset)
                len -= size
            }
        }
    }

    /**
     * A remote file read through bounded range requests ([CHUNK] at a time: YouTube throttles open-ended reads), with
     * seeking. Bytes read are added to [counter] and reported to [onBytes] (speed meter, rate limits).
     */
    private inner class HttpRangeReader(
        private val url: String,
        private val counter: AtomicLong,
        private val onBytes: (Int) -> Unit,
    ) : DataReader, Closeable {
        var length: Long = C.LENGTH_UNSET.toLong()
            private set
        private var buf = ByteArray(0)
        private var bufStart = 0L
        private var at = 0L

        fun seek(to: Long) { at = to }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (this.length >= 0 && at >= this.length) return C.RESULT_END_OF_INPUT
            if (at < bufStart || at >= bufStart + buf.size) {
                if (!fill()) return C.RESULT_END_OF_INPUT
            }
            val from = (at - bufStart).toInt()
            val n = minOf(length, buf.size - from)
            System.arraycopy(buf, from, target, offset, n)
            at += n
            return n
        }

        /** Fetches [CHUNK] bytes from [at]; false at the end of the file. */
        private fun fill(): Boolean {
            val end = if (length >= 0) minOf(at + CHUNK, length) - 1 else at + CHUNK - 1
            if (end < at) return false
            http.newCall(request(url, "bytes=$at-$end")).execute().use { r ->
                if (r.code == 416) return false
                if (!r.isSuccessful) throw HttpStatusException(r.code)
                if (r.code != 206 && at > 0) throw IOException("This server can't send part of the video")
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { length = it }
                val bytes = r.body.bytes()
                if (r.code != 206) length = bytes.size.toLong()
                buf = bytes
                bufStart = at
                counter.addAndGet(bytes.size.toLong())
                onBytes(bytes.size)
                return bytes.isNotEmpty()
            }
        }

        override fun close() { buf = ByteArray(0) }
    }

    private companion object {
        const val CHUNK = 2L shl 20
        /** Audio frames that start up to this long before the clip are kept (one frame overlaps the start). */
        const val AUDIO_LEAD_US = 50_000L
        /** A keyframe after the end never came: stop anyway (very long GOP). */
        const val MAX_TAIL_US = 15_000_000L
    }
}
