package com.edureminder.quicktube.core.download

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.QuickTubeMp4Boxes
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.PriorityQueue

/**
 * Writes an MP4 whose memory use doesn't grow with the video's length. Media3's Mp4Muxer keeps an object per
 * sample until the end and keeps rebuilding the whole moov box, which runs out of heap on very long videos.
 * Here samples stream into mdat in ~1 s chunks, the sample tables are spilled to disk ([Mp4Track]), and moov
 * is written after mdat.
 *
 * Samples of each track must arrive in decode order with presentation timestamps (like an extractor gives them).
 */
internal class StreamingMp4Writer(
    output: File,
    formats: List<Format>,
    rotation: Int,
    /**
     * A cut ([ClipDownload]): sample times are relative to the cut's start, video may begin at a keyframe before it
     * (negative times), and each track's edit list starts playback at 0 and lasts at most this long. 0 = no cut.
     */
    private val clipDurationUs: Long = 0,
) : Closeable {

    private val fileStream = FileOutputStream(output)
    private val out = BufferedOutputStream(fileStream, 1 shl 20)
    private var position = 0L
    private val mdatStart: Long
    private val tracks = run {
        Mp4Moov.clearTemp()
        formats.mapIndexed { i, f -> TrackState(i + 1, f, rotation, clipDurationUs) }
    }

    init {
        try {
            write(QuickTubeMp4Boxes.ftyp())
            mdatStart = position
            // 64-bit mdat: size 1 means "the real size follows"; it's patched in finish().
            write(ByteBuffer.allocate(16).putInt(1).put("mdat".toByteArray()).putLong(0).array())
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    fun writeSample(track: Int, timeUs: Long, keyFrame: Boolean, data: ByteArray) {
        val t = tracks[track]
        val sample = QuickTubeMp4Boxes.toAvcc(t.format, data)
        t.add(t.vu(timeUs), keyFrame, sample)
        if (t.pendingBytes >= MAX_CHUNK_BYTES || t.pendingSpanVu() >= t.mp4.timescale) writeChunk(t)
    }

    /** Writes what's still buffered, patches the mdat size and appends moov. */
    fun finish() {
        tracks.forEach { writeChunk(it); it.endSamples() }
        out.flush()
        fileStream.channel.write(ByteBuffer.allocate(8).putLong(0, position - mdatStart), mdatStart + 8)
        Mp4Moov.write(out, tracks.map { it.mp4 })
        out.flush()
    }

    override fun close() {
        runCatching { out.close() }
        runCatching { fileStream.close() }
        tracks.forEach { it.mp4.close() }
    }

    private fun write(bytes: ByteArray) {
        out.write(bytes)
        position += bytes.size
    }

    private fun writeChunk(t: TrackState) {
        if (t.pending.isEmpty()) return
        t.mp4.chunk(position, t.pending.size)
        t.pending.forEach { write(it) }
        t.pending.clear()
        t.pendingBytes = 0
    }

    /** One track's buffered chunk and reorder window. */
    private class TrackState(id: Int, val format: Format, rotation: Int, private val clipDurationUs: Long) {
        private val isVideo = MimeTypes.isVideo(format.sampleMimeType)
        val mp4 = Mp4Track(
            id, isVideo,
            timescale = if (isVideo) VIDEO_TIMESCALE else format.sampleRate.takeIf { it > 0 } ?: 48_000,
            stsd = QuickTubeMp4Boxes.stsd(format, isVideo),
            width = if (isVideo) format.width.coerceAtLeast(0) else 0,
            height = if (isVideo) format.height.coerceAtLeast(0) else 0,
            rotation = if (isVideo) rotation else 0,
        )
        val pending = ArrayList<ByteArray>()
        var pendingBytes = 0
        private var pendingFirstVu = 0L
        private var pendingLastVu = 0L

        // Decoding times aren't given (B-frames make presentation order differ), so they're the presentation
        // times in sorted order, sorted over a window wider than any reorder depth.
        private val windowPts = ArrayDeque<Long>()
        private val sorted = PriorityQueue<Long>()
        private var firstDts = Long.MIN_VALUE
        private var lastDts = Long.MIN_VALUE
        private var lastDelta = 0

        fun vu(us: Long) = Math.floorDiv(us * mp4.timescale + 500_000, 1_000_000L)

        fun pendingSpanVu() = pendingLastVu - pendingFirstVu

        fun add(ptsVu: Long, keyFrame: Boolean, data: ByteArray) {
            if (pending.isEmpty()) pendingFirstVu = ptsVu
            pendingLastVu = ptsVu
            pending += data
            pendingBytes += data.size
            mp4.sample(data.size, keyFrame)
            windowPts.addLast(ptsVu)
            sorted.add(ptsVu)
            if (sorted.size > REORDER_WINDOW) emit()
        }

        fun endSamples() {
            while (sorted.isNotEmpty()) emit()
            // The last sample lasts as long as the one before it.
            if (mp4.sampleCount > 0) mp4.duration(lastDelta)
            if (firstDts != Long.MIN_VALUE) {
                if (clipDurationUs > 0) {
                    // Frames before the cut's start (back to the keyframe) are decoded but not shown.
                    if (firstDts < 0) mp4.mediaStartVu = -firstDts else mp4.startUs = mp4.vuToUs(firstDts)
                    mp4.maxDurationUs = (clipDurationUs - mp4.startUs).coerceAtLeast(0)
                } else mp4.startUs = mp4.vuToUs(firstDts)
            }
            mp4.end()
        }

        private fun emit() {
            val pts = windowPts.removeFirst()
            val dts = maxOf(sorted.poll()!!, lastDts)
            if (firstDts == Long.MIN_VALUE) firstDts = dts else {
                lastDelta = (dts - lastDts).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                mp4.duration(lastDelta)
            }
            lastDts = dts
            mp4.compositionOffset((pts - dts).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private companion object {
        const val VIDEO_TIMESCALE = 90_000
        const val MAX_CHUNK_BYTES = 4 shl 20
        const val REORDER_WINDOW = 64
    }
}
