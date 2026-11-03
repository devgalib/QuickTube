package com.edureminder.quicktube.core.download

import androidx.media3.muxer.QuickTubeMp4Boxes
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * One track of an MP4 being written. Its sample tables are spilled to temp files in the app's cache instead of
 * the heap: a 47-hour video has ~13 million samples, far past the app's 192 MB heap if kept as objects.
 * [stsd] is the finished sample description box (codec and its config).
 */
internal class Mp4Track(
    val id: Int,
    val isVideo: Boolean,
    val timescale: Int,
    private val stsd: ByteArray,
    private val width: Int = 0,
    private val height: Int = 0,
    private val rotation: Int = 0,
) : Closeable {
    var sampleCount = 0
        private set
    /** Sum of the sample durations. */
    var durationVu = 0L
        private set
    /** Media time where playback starts (an edit list skipping e.g. the B-frame delay). */
    var mediaStartVu = 0L
    /** When the track starts, in µs on a timeline shared by the tracks; a later start gets an empty edit. */
    var startUs = 0L
    /** A cut: the edit list shows at most this much of the track. */
    var maxDurationUs = Long.MAX_VALUE

    private var chunkCount = 0
    private var lastChunkSamples = -1
    private val stsz = Spill()
    private val stss = Spill()
    private val stsc = Spill()
    private val co64 = Spill()
    private val stts = Runs()
    private val ctts = Runs()

    fun sample(size: Int, keyFrame: Boolean) {
        sampleCount++
        stsz.ints(size)
        if (keyFrame) stss.ints(sampleCount)
    }

    /** Durations and composition offsets, in decode order, one each per sample. */
    fun duration(vu: Int) { stts.add(vu); durationVu += vu }
    fun compositionOffset(vu: Int) = ctts.add(vu)

    /** [samples] consecutive samples stored back to back from [offset]. */
    fun chunk(offset: Long, samples: Int) {
        chunkCount++
        co64.long(offset)
        // stsc lists only the chunks where the samples-per-chunk count changes.
        if (samples != lastChunkSamples) stsc.ints(chunkCount, samples, 1)
        lastChunkSamples = samples
    }

    fun end() {
        stts.end(); ctts.end()
        listOf(stsz, stss, stsc, co64).forEach { it.end() }
    }

    fun durationUs() = minOf(vuToUs(durationVu - mediaStartVu), maxDurationUs)
    fun vuToUs(vu: Long) = vu * 1_000_000 / timescale

    override fun close() = listOf(stsz, stss, stsc, co64, stts.spill, ctts.spill).forEach { it.close() }

    internal fun trak(globalStartUs: Long, movieDurationUs: Long): Box {
        val stbl = Container("stbl", listOfNotNull(
            Leaf(stsd),
            stts.spill.box("stts", 0),
            ctts.takeIf { it.nonZero }?.let { it.spill.box("ctts", if (it.negative) 1 else 0) },
            stss.takeIf { it.entries < sampleCount }?.box("stss", 0),
            stsc.box("stsc", 0),
            // Sample size 0 = "sizes vary", then the count.
            stsz.box("stsz", 0, header = ByteBuffer.allocate(8).putInt(0).putInt(stsz.entries).array()),
            co64.box("co64", 0),
        ))
        val minf = Container("minf", listOf(Leaf(QuickTubeMp4Boxes.mediaHeader(isVideo)), Leaf(QuickTubeMp4Boxes.dinf()), stbl))
        val mdia = Container("mdia", listOf(Leaf(mdhd()), Leaf(QuickTubeMp4Boxes.hdlr(isVideo)), minf))
        return Container("trak", listOf(Leaf(tkhd(movieDurationUs)), Leaf(edts(startUs - globalStartUs)), mdia))
    }

    private fun tkhd(durationUs: Long) = fullBox("tkhd", 1, flags = 3) {
        putLong(0); putLong(0); putInt(id); putInt(0); putLong(Mp4Moov.movieUnits(durationUs))
        putLong(0); putShort(0); putShort(0); putShort(if (isVideo) 0 else 0x0100); putShort(0)
        matrix(rotation)
        putInt(width shl 16)
        putInt(height shl 16)
    }

    private fun mdhd() = fullBox("mdhd", 1) {
        putLong(0); putLong(0); putInt(timescale); putLong(durationVu)
        putShort(0x55C4) // "und"
        putShort(0)
    }

    /** An empty edit when this track starts later than another, then the track from [mediaStartVu]. */
    private fun edts(startOffsetUs: Long): ByteArray {
        val edits = buildList {
            if (Mp4Moov.movieUnits(startOffsetUs) > 0) add(Mp4Moov.movieUnits(startOffsetUs) to -1L)
            add(Mp4Moov.movieUnits(durationUs()) to mediaStartVu)
        }
        val elst = fullBox("elst", 1) {
            putInt(edits.size)
            edits.forEach { (duration, mediaTime) -> putLong(duration); putLong(mediaTime); putShort(1); putShort(0) }
        }
        return ByteBuffer.allocate(8 + elst.size).putInt(8 + elst.size).put("edts".toByteArray()).put(elst).array()
    }

    /** Run-length table (stts / ctts): (count, value) entries. */
    private class Runs {
        val spill = Spill()
        private var count = 0
        private var value = 0
        var nonZero = false
        var negative = false

        fun add(v: Int) {
            if (count > 0 && v == value) { count++; return }
            flush()
            value = v
            count = 1
            if (v != 0) nonZero = true
            if (v < 0) negative = true
        }

        private fun flush() {
            if (count > 0) spill.ints(count, value)
        }

        fun end() { flush(); count = 0; spill.end() }
    }

    /** Table entries written to a temp file, copied into moov at the end. */
    private class Spill : Closeable {
        private val file = File.createTempFile("table", ".tmp", Mp4Moov.tempDir.apply { mkdirs() })
        private val stream = DataOutputStream(BufferedOutputStream(FileOutputStream(file), 64 * 1024))
        var bytes = 0L
            private set
        var entries = 0
            private set

        /** One table entry. */
        fun ints(vararg v: Int) { v.forEach { stream.writeInt(it) }; bytes += 4L * v.size; entries++ }
        fun long(v: Long) { stream.writeLong(v); bytes += 8; entries++ }
        fun end() = stream.close()

        /** A full box holding this table, after [header] (by default the entry count). */
        fun box(type: String, version: Int, header: ByteArray = ByteBuffer.allocate(4).putInt(entries).array()): Box =
            TableBox(type, version, header, this)

        fun copyTo(target: OutputStream) = file.inputStream().use { it.copyTo(target, 64 * 1024) }

        override fun close() {
            runCatching { stream.close() }
            file.delete()
        }
    }

    private class TableBox(val type: String, val version: Int, val header: ByteArray, val spill: Spill) : Box {
        override val size: Long get() = 12 + header.size + spill.bytes
        override fun writeTo(out: DataOutputStream) {
            out.writeInt(size.toInt()); out.writeBytes(type); out.writeInt(version shl 24)
            out.write(header)
            spill.copyTo(out)
        }
    }
}

/** Assembles the moov box from [Mp4Track]s; the big tables stream from their temp files. */
internal object Mp4Moov {
    /** In the app's cache (java.io.tmpdir on Android). */
    val tempDir = File(System.getProperty("java.io.tmpdir"), "mp4-tables")

    private const val MOVIE_TIMESCALE = 1000

    /** Merges run one at a time, so tables still here are from a merge that was killed. */
    fun clearTemp() {
        tempDir.listFiles()?.forEach { it.delete() }
    }

    fun write(out: OutputStream, tracks: List<Mp4Track>) {
        val started = tracks.filter { it.sampleCount > 0 }
        val startUs = started.minOfOrNull { it.startUs } ?: 0L
        val durations = started.associateWith { it.startUs - startUs + it.durationUs() }
        val movieDurationUs = durations.values.maxOrNull() ?: 0L
        val mvhd = fullBox("mvhd", 1) {
            putLong(0); putLong(0); putInt(MOVIE_TIMESCALE); putLong(movieUnits(movieDurationUs))
            putInt(0x00010000); putShort(0x0100); putShort(0); putLong(0)
            matrix(0)
            repeat(6) { putInt(0) }
            putInt((tracks.maxOfOrNull { it.id } ?: 0) + 1)
        }
        val data = DataOutputStream(out)
        Container("moov", listOf(Leaf(mvhd)) + started.map { it.trak(startUs, durations.getValue(it)) }).writeTo(data)
        data.flush()
    }

    fun movieUnits(us: Long) = us * MOVIE_TIMESCALE / 1_000_000
}

internal interface Box {
    val size: Long
    fun writeTo(out: DataOutputStream)
}

private class Leaf(val bytes: ByteArray) : Box {
    override val size get() = bytes.size.toLong()
    override fun writeTo(out: DataOutputStream) = out.write(bytes)
}

private class Container(val type: String, val children: List<Box>) : Box {
    override val size: Long get() = 8 + children.sumOf { it.size }
    override fun writeTo(out: DataOutputStream) {
        out.writeInt(size.toInt()); out.writeBytes(type)
        children.forEach { it.writeTo(out) }
    }
}

private fun ByteBuffer.matrix(rotation: Int) {
    val (a, b, c, d) = when (rotation) {
        90 -> listOf(0, 0x10000, -0x10000, 0)
        180 -> listOf(-0x10000, 0, 0, -0x10000)
        270 -> listOf(0, -0x10000, 0x10000, 0)
        else -> listOf(0x10000, 0, 0, 0x10000)
    }
    putInt(a); putInt(b); putInt(0); putInt(c); putInt(d); putInt(0); putInt(0); putInt(0); putInt(0x40000000)
}

private fun fullBox(type: String, version: Int, flags: Int = 0, body: ByteBuffer.() -> Unit): ByteArray {
    val content = ByteBuffer.allocate(256).apply(body)
    val size = 12 + content.position()
    return ByteArrayOutputStream(size).apply {
        DataOutputStream(this).apply {
            writeInt(size); writeBytes(type); writeInt((version shl 24) or flags)
            write(content.array(), 0, content.position())
        }
    }.toByteArray()
}
