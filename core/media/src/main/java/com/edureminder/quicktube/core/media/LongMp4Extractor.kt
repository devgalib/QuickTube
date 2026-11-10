package com.edureminder.quicktube.core.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.container.Mp4Box
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.BoxParser
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.mp4.Track
import androidx.media3.extractor.text.SubtitleParser
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Arrays

/**
 * [DefaultExtractorsFactory] whose MP4 extractor, for files on the phone, is a [LongMp4Extractor]: very long videos
 * (a 47-hour course) then play instead of running the player out of memory. Online streams keep the stock one, which
 * doesn't have to seek around the file before it starts.
 */
@OptIn(UnstableApi::class)
internal class LongMp4ExtractorsFactory(
    private val context: Context,
    private val default: DefaultExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = default.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val all = default.createExtractors(uri, responseHeaders)
        if (uri.scheme != null && uri.scheme != "file" && uri.scheme != "content") return all
        val locate = { findMoov(uri) }
        return Array(all.size) { i -> all[i].let { if (it.underlyingImplementation is Mp4Extractor) LongMp4Extractor(it, locate) else it } }
    }

    /**
     * Where the moov starts, read straight from the file. A file QuickTube merged in place has two top-level boxes per
     * few seconds of video (~16,000 for 47 hours), and walking them through the player's input reopens the file for
     * each one, which takes a minute.
     */
    private fun findMoov(uri: Uri): LongMp4Extractor.MoovLocation? = runCatching {
        val pfd = if (uri.scheme == "content") context.contentResolver.openFileDescriptor(uri, "r") else null
        val stream = pfd?.let { FileInputStream(it.fileDescriptor) } ?: FileInputStream(uri.path ?: return null)
        try { LongMp4Extractor.findMoov(stream.channel) } finally { stream.close(); pfd?.close() }
    }.getOrNull()

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory) = apply { default.setSubtitleParserFactory(factory) }
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean) =
        apply { default.experimentalSetTextTrackTranscodingEnabled(enabled) }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int) =
        apply { default.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs) }
    override fun setParseHagcMetadata(parse: Boolean) = apply { default.setParseHagcMetadata(parse) }
}

/**
 * Plays regular MP4s too long for Media3's Mp4Extractor. That one keeps four values per sample in memory; a 47-hour
 * video has ~12 million samples (~300 MB) and the app's heap is 192 MB. A file whose moov is small goes to
 * [delegate] unchanged. For a big one this keeps the moov's sample tables in their compact form (run-length times,
 * one size per sample, one offset per chunk, ~4 bytes per sample) and works out each sample's place and time as it
 * reads. Audio and video tracks only; edit lists only shift or delay a track.
 */
@OptIn(UnstableApi::class)
internal class LongMp4Extractor(
    private val delegate: Extractor,
    private val locate: () -> MoovLocation? = { null },
) : Extractor {
    class MoovLocation(val start: Long, val isQuickTime: Boolean)

    private enum class State { Scan, Delegate, Samples }

    private var state = State.Scan
    private lateinit var output: ExtractorOutput
    private val header = ParsableByteArray(16)
    private var isQuickTime = false
    private var located = false
    private var tracks: List<LeanTrack> = emptyList()

    private var current: LeanTrack? = null
    private var stretchTrack: LeanTrack? = null
    private var stretchBytes = 0L
    private var sampleBytesRead = 0
    private var sampleBytesWritten = 0
    private var nalBytesLeft = 0
    private val nalPrefix = ParsableByteArray(4)
    private val nalStartCode = ParsableByteArray(NalUnitUtil.NAL_START_CODE)

    override fun sniff(input: ExtractorInput) = delegate.sniff(input)

    override fun init(output: ExtractorOutput) { this.output = output }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = when (state) {
        State.Scan -> scan(input, seekPosition)
        State.Delegate -> delegate.read(input, seekPosition)
        State.Samples -> readSample(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        when (state) {
            State.Scan -> Unit
            State.Delegate -> delegate.seek(position, timeUs)
            State.Samples -> {
                tracks.forEach { it.cursor.moveTo(it.syncSampleAt(timeUs)) }
                current = null
                stretchTrack = null
                sampleBytesRead = 0; sampleBytesWritten = 0; nalBytesLeft = 0
            }
        }
    }

    override fun release() = delegate.release()

    /** Walks the top-level boxes up to the moov, skipping the media data. */
    private fun scan(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!located) {
            located = true
            locate()?.let {
                isQuickTime = it.isQuickTime
                if (input.position != it.start) { seekPosition.position = it.start; return Extractor.RESULT_SEEK }
            }
        }
        val start = input.position
        val (type, size, headerSize) = readHeader(input, start, topLevel = true)
            ?: throw ParserException.createForMalformedContainer("No moov box", null)
        when (type) {
            Mp4Box.TYPE_ftyp -> {
                input.readFully(header.data, 0, 4)
                header.position = 0
                isQuickTime = header.readInt() == BRAND_QUICKTIME
            }
            Mp4Box.TYPE_moov -> {
                if (size - headerSize <= DELEGATE_MAX_MOOV) {
                    delegate.init(output)
                    state = State.Delegate
                    // Straight to the moov: from the start, Mp4Extractor would walk every box before it, and a file
                    // QuickTube merged in place has hundreds (seconds of reopening the file before the first frame).
                    // QuickTime files start from the top, as their ftyp changes how Mp4Extractor reads the moov.
                    seekPosition.position = if (isQuickTime) 0 else start
                    return Extractor.RESULT_SEEK
                }
                parseMoov(input, start + size)
                state = State.Samples
                return Extractor.RESULT_CONTINUE
            }
        }
        val left = start + size - input.position
        if (left < RELOAD_DISTANCE) input.skipFully(left.toInt()) else { seekPosition.position = start + size; return Extractor.RESULT_SEEK }
        return Extractor.RESULT_CONTINUE
    }

    /** Type, size and header size of the box at [start], or null at the end of the input. */
    private fun readHeader(input: ExtractorInput, start: Long, topLevel: Boolean): Triple<Int, Long, Int>? {
        if (!input.readFully(header.data, 0, 8, topLevel)) return null
        header.position = 0
        var size = header.readUnsignedInt()
        val type = header.readInt()
        var headerSize = 8
        if (size == 1L) {
            input.readFully(header.data, 8, 8)
            header.position = 8
            size = header.readUnsignedLongToLong()
            headerSize = 16
        } else if (size == 0L) {
            val length = input.length
            if (length == C.LENGTH_UNSET.toLong()) throw ParserException.createForMalformedContainer("Box size unknown", null)
            size = length - start
        }
        if (size < headerSize) throw ParserException.createForMalformedContainer("Bad box size", null)
        return Triple(type, size, headerSize)
    }

    private fun parseMoov(input: ExtractorInput, end: Long) {
        val moov = Mp4Box.ContainerBox(Mp4Box.TYPE_moov, end)
        val traks = mutableListOf<Pair<Mp4Box.ContainerBox, Tables>>()
        readBoxes(input, end, moov, null, traks)
        val mvhd = moov.getLeafBoxOfType(Mp4Box.TYPE_mvhd) ?: throw ParserException.createForMalformedContainer("No mvhd", null)
        val parsed = traks.mapNotNull { (trak, tables) ->
            val track = BoxParser.parseTrak(trak, mvhd, C.TIME_UNSET, null, false, isQuickTime) ?: return@mapNotNull null
            if (track.type != C.TRACK_TYPE_VIDEO && track.type != C.TRACK_TYPE_AUDIO) return@mapNotNull null
            if (track.sampleTransformation != Track.TRANSFORMATION_NONE || tables.count == 0) return@mapNotNull null
            tables.check()
            track to tables
        }
        val containerMime = if (parsed.any { it.first.type == C.TRACK_TYPE_VIDEO }) MimeTypes.VIDEO_MP4 else MimeTypes.AUDIO_MP4
        tracks = parsed.mapIndexed { i, (track, tables) ->
            val lean = LeanTrack(track, tables, output.track(i, track.type))
            val durationUs = lean.durationUs
            val format = track.format.buildUpon()
                .setMaxInputSize(tables.maxSize + 3 * 10)
                .setContainerMimeType(containerMime)
            if (track.type == C.TRACK_TYPE_VIDEO && durationUs > 0) format.setFrameRate(tables.count * 1_000_000f / durationUs)
            lean.output.format(format.build())
            lean.output.durationUs(durationUs)
            lean
        }
        if (tracks.isEmpty()) throw ParserException.createForMalformedContainer("No playable tracks", null)
        output.endTracks()
        output.seekMap(LeanSeekMap(tracks))
    }

    /** Reads the boxes up to [end] into [parent], streaming the sample tables into [tables] without keeping them as bytes. */
    private fun readBoxes(
        input: ExtractorInput,
        end: Long,
        parent: Mp4Box.ContainerBox,
        tables: Tables?,
        traks: MutableList<Pair<Mp4Box.ContainerBox, Tables>>,
    ) {
        while (input.position < end) {
            val start = input.position
            val (type, size, headerSize) = readHeader(input, start, topLevel = false)!!
            val bodySize = size - headerSize
            val boxEnd = start + size
            when (type) {
                Mp4Box.TYPE_trak -> {
                    val trak = Mp4Box.ContainerBox(type, boxEnd)
                    val trakTables = Tables()
                    readBoxes(input, boxEnd, trak, trakTables, traks)
                    traks += trak to trakTables
                }
                Mp4Box.TYPE_mdia, Mp4Box.TYPE_minf, Mp4Box.TYPE_stbl, Mp4Box.TYPE_edts -> {
                    val box = Mp4Box.ContainerBox(type, boxEnd)
                    readBoxes(input, boxEnd, box, tables, traks)
                    parent.add(box)
                }
                Mp4Box.TYPE_mvhd, Mp4Box.TYPE_tkhd, Mp4Box.TYPE_mdhd, Mp4Box.TYPE_hdlr, Mp4Box.TYPE_stsd, Mp4Box.TYPE_elst -> {
                    if (bodySize > MAX_LEAF) throw ParserException.createForMalformedContainer("Box too big", null)
                    val data = ParsableByteArray(8 + bodySize.toInt())
                    data.data.let {
                        Util.toByteArray((8 + bodySize).toInt()).copyInto(it, 0)
                        Util.toByteArray(type).copyInto(it, 4)
                        input.readFully(it, 8, bodySize.toInt())
                    }
                    parent.add(Mp4Box.LeafBox(type, data))
                }
                Mp4Box.TYPE_stsz, Mp4Box.TYPE_stco, Mp4Box.TYPE_co64, Mp4Box.TYPE_stsc, Mp4Box.TYPE_stts, Mp4Box.TYPE_ctts,
                Mp4Box.TYPE_stss -> tables?.read(type, BoxReader(input, bodySize))
            }
            skip(input, boxEnd - input.position)
        }
    }

    private fun skip(input: ExtractorInput, bytes: Long) {
        var left = bytes
        while (left > 0) { val n = minOf(left, 1L shl 20).toInt(); input.skipFully(n); left -= n }
    }

    private fun readSample(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // Next is the nearest sample ahead in the file, unless its track is well ahead in time of another: QuickTube's
        // in-place merge leaves all the audio after all the video, which plays only if both are read in step. Each
        // stretch of one track that needed a seek runs for MIN_STRETCH_BYTES at least: the loader only stops to check
        // whether it has buffered enough after reading a megabyte without seeking.
        var track = current
        if (track == null) {
            var earliest: LeanTrack? = null
            for (t in tracks) {
                if (t.cursor.done) continue
                val skip = t.cursor.offset - input.position
                if (skip in 0 until RELOAD_DISTANCE && (track == null || t.cursor.offset < track.cursor.offset)) track = t
                if (earliest == null || t.cursor.timeUs < earliest.cursor.timeUs) earliest = t
            }
            if (earliest == null) return Extractor.RESULT_END_OF_INPUT
            if (track == null || track.cursor.timeUs > earliest.cursor.timeUs + MAX_AHEAD_US) {
                val stretch = stretchTrack
                track = if (stretch != null && !stretch.cursor.done && stretchBytes < MIN_STRETCH_BYTES) stretch else earliest
            }
            if (track !== stretchTrack) { stretchTrack = track; stretchBytes = 0 }
        }
        current = track
        val cursor = track.cursor
        val skipAmount = cursor.offset - input.position + sampleBytesRead
        if (skipAmount < 0 || skipAmount >= RELOAD_DISTANCE) {
            seekPosition.position = cursor.offset + sampleBytesRead
            return Extractor.RESULT_SEEK
        }
        input.skipFully(skipAmount.toInt())
        val size = cursor.size
        val nalLength = track.track.nalUnitLengthFieldLength
        if (nalLength != 0) {
            // NAL units are length-prefixed; decoders want start codes.
            val prefix = nalPrefix.data
            while (sampleBytesRead < size) {
                if (nalBytesLeft == 0) {
                    prefix.fill(0)
                    input.readFully(prefix, 4 - nalLength, nalLength)
                    sampleBytesRead += nalLength
                    nalPrefix.position = 0
                    nalBytesLeft = nalPrefix.readInt()
                    if (nalBytesLeft < 0) throw ParserException.createForMalformedContainer("Invalid NAL length", null)
                    nalStartCode.position = 0
                    track.output.sampleData(nalStartCode, 4)
                    sampleBytesWritten += 4
                } else {
                    val written = track.output.sampleData(input, nalBytesLeft, false)
                    sampleBytesRead += written
                    sampleBytesWritten += written
                    nalBytesLeft -= written
                }
            }
        } else {
            while (sampleBytesRead < size) {
                val written = track.output.sampleData(input, size - sampleBytesRead, false)
                sampleBytesRead += written
                sampleBytesWritten += written
            }
        }
        val flags = if (track.isSync(cursor.sample)) C.BUFFER_FLAG_KEY_FRAME else 0
        track.output.sampleMetadata(cursor.timeUs, flags, sampleBytesWritten, 0, null)
        cursor.advance()
        stretchBytes += size
        current = null
        sampleBytesRead = 0; sampleBytesWritten = 0; nalBytesLeft = 0
        return Extractor.RESULT_CONTINUE
    }

    /** One track's sample tables as stored in the moov, decoded to arrays but not expanded per sample. */
    private class Tables {
        var count = 0
        var fixedSize = 0
        var sizes: CompactInts? = null
        var maxSize = 0
        var chunkOffsets = ChunkOffsets(0)
        var stscFirstChunk = IntArray(0)
        var stscSamples = CompactInts(0)
        var sttsCount = IntArray(0)
        var sttsDelta = IntArray(0)
        var cttsCount = CompactInts(0)
        var cttsOffset = CompactInts(0)
        /** Zero-based key frame indices; null when every sample is one. */
        var sync: IntArray? = null

        fun size(sample: Int) = sizes?.get(sample) ?: fixedSize

        fun read(type: Int, r: BoxReader) {
            r.int() // version and flags
            when (type) {
                Mp4Box.TYPE_stsz -> {
                    fixedSize = r.int()
                    count = r.int()
                    if (fixedSize == 0) sizes = CompactInts(count).also {
                        for (i in 0 until count) { val s = r.int(); if (s < 0) bad(); it[i] = s; if (s > maxSize) maxSize = s }
                    } else maxSize = fixedSize
                }
                Mp4Box.TYPE_stco -> chunkOffsets = ChunkOffsets(r.int()).also { for (i in 0 until it.size) it[i] = r.int().toLong() and 0xFFFFFFFFL }
                Mp4Box.TYPE_co64 -> chunkOffsets = ChunkOffsets(r.int()).also { for (i in 0 until it.size) it[i] = r.long() }
                Mp4Box.TYPE_stsc -> {
                    val n = r.int()
                    stscFirstChunk = IntArray(n); stscSamples = CompactInts(n)
                    for (i in 0 until n) {
                        stscFirstChunk[i] = r.int()
                        stscSamples[i] = r.int().also { if (it <= 0) bad() }
                        r.int()
                    }
                }
                Mp4Box.TYPE_stts -> {
                    val n = r.int()
                    sttsCount = IntArray(n); sttsDelta = IntArray(n)
                    for (i in 0 until n) { sttsCount[i] = r.int().also { if (it < 0) bad() }; sttsDelta[i] = r.int() }
                }
                Mp4Box.TYPE_ctts -> {
                    val n = r.int()
                    cttsCount = CompactInts(n); cttsOffset = CompactInts(n)
                    for (i in 0 until n) { cttsCount[i] = r.int().also { if (it < 0) bad() }; cttsOffset[i] = r.int() }
                }
                Mp4Box.TYPE_stss -> sync = IntArray(r.int()) { r.int() - 1 }
            }
        }

        fun check() { if (chunkOffsets.size == 0 || stscFirstChunk.isEmpty() || sttsCount.isEmpty()) bad() }

        private fun bad(): Nothing = throw ParserException.createForMalformedContainer("Unsupported sample table", null)
    }

    /** Ints kept in one, two or four bytes each, whichever the largest value so far needs (most sizes and counts are small). */
    private class CompactInts(val size: Int) {
        private var bytes: ByteArray? = ByteArray(size)
        private var shorts: ShortArray? = null
        private var ints: IntArray? = null

        operator fun get(i: Int): Int {
            bytes?.let { return it[i].toInt() and 0xFF }
            shorts?.let { return it[i].toInt() and 0xFFFF }
            return ints!![i]
        }

        /** Values are set in order, so only those before [i] are copied when the array widens. */
        operator fun set(i: Int, value: Int) {
            bytes?.let { b ->
                if (value in 0..0xFF) { b[i] = value.toByte(); return }
                shorts = ShortArray(size).also { s -> for (j in 0 until i) s[j] = (b[j].toInt() and 0xFF).toShort() }
                bytes = null
            }
            shorts?.let { s ->
                if (value in 0..0xFFFF) { s[i] = value.toShort(); return }
                ints = IntArray(size).also { n -> for (j in 0 until i) n[j] = s[j].toInt() and 0xFFFF }
                shorts = null
            }
            ints!![i] = value
        }
    }

    /** Chunk offsets as their low 32 bits, plus the chunks where the high bits change (rarely: files are written in order). */
    private class ChunkOffsets(val size: Int) {
        private val low = IntArray(size)
        private var highFrom = IntArray(0)
        private var high = IntArray(0)

        operator fun get(i: Int): Long {
            val k = Arrays.binarySearch(highFrom, i).let { if (it >= 0) it else -it - 2 }
            val h = if (k >= 0) high[k].toLong() else 0L
            return (h shl 32) or (low[i].toLong() and 0xFFFFFFFFL)
        }

        operator fun set(i: Int, value: Long) {
            low[i] = value.toInt()
            val h = (value ushr 32).toInt()
            if (h != (if (high.isEmpty()) 0 else high.last())) {
                highFrom = highFrom.copyOf(highFrom.size + 1).also { it[it.size - 1] = i }
                high = high.copyOf(high.size + 1).also { it[it.size - 1] = h }
            }
        }
    }

    /** Reads big-endian ints of one box body from the input through a small buffer. */
    private class BoxReader(private val input: ExtractorInput, private var left: Long) {
        private val buffer = ByteArray(64 * 1024)
        private var pos = 0
        private var limit = 0

        fun int(): Int {
            if (limit - pos < 4) fill()
            val b = buffer
            val v = (b[pos].toInt() and 0xFF shl 24) or (b[pos + 1].toInt() and 0xFF shl 16) or
                (b[pos + 2].toInt() and 0xFF shl 8) or (b[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }

        fun long() = (int().toLong() shl 32) or (int().toLong() and 0xFFFFFFFFL)

        private fun fill() {
            val rest = limit - pos
            System.arraycopy(buffer, pos, buffer, 0, rest)
            val n = minOf((buffer.size - rest).toLong(), left).toInt()
            if (n + rest < 4) throw ParserException.createForMalformedContainer("Truncated sample table", null)
            input.readFully(buffer, rest, n)
            left -= n
            limit = rest + n
            pos = 0
        }
    }

    private class LeanTrack(val track: Track, val tables: Tables, val output: TrackOutput) {
        /** Media time the first edit starts at, and the empty edits' delay before it. */
        val editStart: Long
        val delayUs: Long

        init {
            var start = 0L
            var delay = 0L
            val durations = track.editListDurations
            val times = track.editListMediaTimes
            if (durations != null && times != null) {
                for (i in 0 until durations.length()) {
                    val time = times.get(i)
                    if (time == -1L) delay += Util.scaleLargeTimestamp(durations.get(i), C.MICROS_PER_SECOND, track.movieTimescale)
                    else { start = time; break }
                }
            }
            editStart = start
            delayUs = delay
        }

        val durationUs: Long = if (track.durationUs != C.TIME_UNSET) track.durationUs else {
            var total = 0L
            for (i in tables.sttsCount.indices) total += tables.sttsCount[i].toLong() * tables.sttsDelta[i]
            Util.scaleLargeTimestamp(total, C.MICROS_PER_SECOND, track.timescale)
        }

        /** Where the loader is; only touched on its thread. */
        val cursor = Cursor(this).apply { moveTo(0) }

        fun isSync(sample: Int) = tables.sync.let { it == null || Arrays.binarySearch(it, sample) >= 0 }

        fun timeUs(mediaTime: Long) =
            Util.scaleLargeTimestamp(mediaTime - editStart, C.MICROS_PER_SECOND, track.timescale) + delayUs

        /** The last key frame at or before [timeUs] (the first one if there's none before). */
        fun syncSampleAt(timeUs: Long): Int {
            val t = Util.scaleLargeTimestamp((timeUs - delayUs).coerceAtLeast(0), track.timescale, C.MICROS_PER_SECOND) + editStart
            var dts = 0L
            var base = 0L
            var sample = tables.count - 1L
            for (i in tables.sttsCount.indices) {
                val span = tables.sttsCount[i].toLong() * tables.sttsDelta[i]
                if (dts + span > t) { sample = base + if (tables.sttsDelta[i] > 0) (t - dts) / tables.sttsDelta[i] else 0; break }
                dts += span
                base += tables.sttsCount[i]
            }
            val index = sample.coerceIn(0, tables.count - 1L).toInt()
            val sync = tables.sync ?: return index
            if (sync.isEmpty()) return index
            val found = Arrays.binarySearch(sync, index)
            return sync[(if (found >= 0) found else -found - 2).coerceAtLeast(0)]
        }
    }

    /** A sample's file offset and times, stepped one sample at a time or recomputed for a seek. */
    private class Cursor(private val track: LeanTrack) {
        private val t = track.tables
        var sample = 0; private set
        var offset = 0L; private set
        private var chunk = 0
        private var chunkSamplesLeft = 0
        private var stscIndex = 0
        private var dts = 0L
        private var sttsIndex = 0
        private var sttsLeft = 0
        private var cttsIndex = 0
        private var cttsLeft = 0

        val done get() = sample >= t.count || chunk >= t.chunkOffsets.size
        val size get() = t.size(sample)
        val timeUs get() = track.timeUs(dts + if (cttsIndex < t.cttsOffset.size) t.cttsOffset[cttsIndex] else 0)

        fun moveTo(target: Int) {
            sample = target
            // Chunk: stsc runs give the samples per chunk from each first chunk on.
            var first = 0L
            var e = 0
            while (true) {
                val chunkStart = t.stscFirstChunk[e] - 1
                val last = e + 1 == t.stscFirstChunk.size
                val chunkEnd = if (last) t.chunkOffsets.size else t.stscFirstChunk[e + 1] - 1
                val samples = (chunkEnd - chunkStart).toLong() * t.stscSamples[e]
                if (target < first + samples || last) {
                    val inRun = target - first
                    chunk = chunkStart + (inRun / t.stscSamples[e]).toInt()
                    val inChunk = (inRun % t.stscSamples[e]).toInt()
                    stscIndex = e
                    chunkSamplesLeft = t.stscSamples[e] - inChunk
                    var off = if (chunk < t.chunkOffsets.size) t.chunkOffsets[chunk] else 0L
                    for (j in target - inChunk until target) off += t.size(j)
                    offset = off
                    break
                }
                first += samples
                e++
            }
            // Decode time from the stts runs, composition offset from the ctts runs.
            var d = 0L
            var base = 0L
            var i = 0
            while (i < t.sttsCount.size && base + t.sttsCount[i] <= target) { d += t.sttsCount[i].toLong() * t.sttsDelta[i]; base += t.sttsCount[i]; i++ }
            sttsIndex = i
            sttsLeft = if (i < t.sttsCount.size) (t.sttsCount[i] - (target - base)).toInt() else 0
            dts = d + if (i < t.sttsCount.size) (target - base) * t.sttsDelta[i] else 0
            base = 0
            i = 0
            while (i < t.cttsCount.size && base + t.cttsCount[i] <= target) { base += t.cttsCount[i]; i++ }
            cttsIndex = i
            cttsLeft = if (i < t.cttsCount.size) (t.cttsCount[i] - (target - base)).toInt() else 0
        }

        fun advance() {
            offset += t.size(sample)
            sample++
            if (--chunkSamplesLeft <= 0) {
                chunk++
                if (stscIndex + 1 < t.stscFirstChunk.size && chunk + 1 >= t.stscFirstChunk[stscIndex + 1]) stscIndex++
                chunkSamplesLeft = t.stscSamples[stscIndex]
                if (chunk < t.chunkOffsets.size) offset = t.chunkOffsets[chunk]
            }
            if (sttsIndex < t.sttsCount.size) {
                dts += t.sttsDelta[sttsIndex]
                while (--sttsLeft <= 0 && ++sttsIndex < t.sttsCount.size) sttsLeft = t.sttsCount[sttsIndex] + 1
            }
            if (cttsIndex < t.cttsCount.size) {
                while (--cttsLeft <= 0 && ++cttsIndex < t.cttsCount.size) cttsLeft = t.cttsCount[cttsIndex] + 1
            }
        }
    }

    /** Seeks to the key frame of the first video track; called on the playback thread, so it uses its own cursors. */
    private class LeanSeekMap(private val tracks: List<LeanTrack>) : SeekMap {
        private val primary = tracks.firstOrNull { it.track.type == C.TRACK_TYPE_VIDEO } ?: tracks.first()
        private val duration = tracks.maxOf { it.durationUs }

        override fun isSeekable() = true
        override fun getDurationUs() = duration

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val main = Cursor(primary).apply { moveTo(primary.syncSampleAt(timeUs)) }
            val time = main.timeUs.coerceAtLeast(0)
            var position = main.offset
            for (other in tracks) if (other !== primary) {
                position = minOf(position, Cursor(other).apply { moveTo(other.syncSampleAt(time)) }.offset)
            }
            return SeekMap.SeekPoints(SeekPoint(time, position))
        }
    }

    companion object {
        /**
         * The moov's place, or null when there's no moov. Box headers are read one by one from the start for a while
         * (a moov at the front comes early), then the moov is looked for as the file's last box, where QuickTube's
         * merge and most muxers put it: on shared storage each header read takes over a millisecond.
         */
        fun findMoov(channel: FileChannel): MoovLocation? {
            val buffer = ByteBuffer.allocate(16)
            val length = channel.size()
            var position = 0L
            var quickTime = false
            var boxes = 0
            var triedEnd = false
            while (position + 8 <= length) {
                if (++boxes > FRONT_BOXES && !triedEnd) {
                    triedEnd = true
                    findMoovAtEnd(channel, length)?.let { return MoovLocation(it, quickTime) }
                }
                buffer.clear()
                if (channel.read(buffer, position) < 8) return null
                val size = boxSize(buffer, position, length) ?: return null
                when (buffer.getInt(4)) {
                    Mp4Box.TYPE_moov -> return MoovLocation(position, quickTime)
                    Mp4Box.TYPE_ftyp -> quickTime = buffer.position() >= 12 && buffer.getInt(8) == BRAND_QUICKTIME
                }
                position += size
            }
            return null
        }

        /** Size of the box whose header (read at [position]) is in [header], or null if it's invalid. */
        private fun boxSize(header: ByteBuffer, position: Long, length: Long): Long? {
            var size = header.getInt(0).toLong() and 0xFFFFFFFFL
            if (size == 1L) {
                if (header.position() < 16) return null
                size = header.getLong(8)
            } else if (size == 0L) size = length - position
            return size.takeIf { it >= 8 }
        }

        /** Start of a moov box that ends the file, searched for in its last [TAIL_SEARCH] bytes. */
        private fun findMoovAtEnd(channel: FileChannel, length: Long): Long? {
            val chunk = 1 shl 20
            val buffer = ByteBuffer.allocate(chunk + 3)
            val header = ByteBuffer.allocate(16)
            val bytes = buffer.array()
            val stop = maxOf(0L, length - TAIL_SEARCH)
            var end = length
            while (end > stop) {
                val start = maxOf(stop, end - chunk)
                // 3 bytes past the chunk, so a "moov" across its end is seen.
                buffer.clear()
                buffer.limit((minOf(length, end + 3) - start).toInt())
                while (buffer.hasRemaining()) if (channel.read(buffer, start + buffer.position()) <= 0) break
                for (i in minOf(buffer.position() - 4, (end - start - 1).toInt()) downTo 0) {
                    if (bytes[i] != 'm'.code.toByte() || bytes[i + 1] != 'o'.code.toByte() || bytes[i + 2] != 'o'.code.toByte() ||
                        bytes[i + 3] != 'v'.code.toByte()
                    ) continue
                    val boxStart = start + i - 4
                    if (boxStart < 0) continue
                    header.clear()
                    channel.read(header, boxStart)
                    if (header.position() >= 8 && boxSize(header, boxStart, length) == length - boxStart) return boxStart
                }
                end = start
            }
            return null
        }

        /** Mp4Extractor handles a moov this small (~2 million samples, ~10 hours of video) within the heap. */
        private const val DELEGATE_MAX_MOOV = 12L * 1024 * 1024
        private const val RELOAD_DISTANCE = 256L * 1024
        /** How far one track may be read ahead of another before switching (one seek per this much media). */
        private const val MAX_AHEAD_US = 5_000_000L
        private const val MIN_STRETCH_BYTES = 2L * 1024 * 1024
        private const val MAX_LEAF = 16 * 1024 * 1024
        /** Top-level boxes read from the front before looking for the moov at the end. */
        private const val FRONT_BOXES = 64
        private const val TAIL_SEARCH = 512L * 1024 * 1024
        private const val BRAND_QUICKTIME = 0x71742020 // "qt  "
    }
}
