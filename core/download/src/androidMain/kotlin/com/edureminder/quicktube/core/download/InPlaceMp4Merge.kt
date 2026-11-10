package com.edureminder.quicktube.core.download

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Merges a fragmented-MP4 video and audio (YouTube's DASH streams) into one regular MP4 with almost no extra
 * storage, so an 8 GB video doesn't need another 8 GB free. The video part becomes the output: its samples stay
 * where they are and its fragment headers become `free` boxes. The audio fragments are moved to its end one at a
 * time, last first, while the audio part is truncated behind them, and a moov describing both is appended.
 *
 * Moving the audio consumes the parts, so a merge that is killed half way can't be redone; [marker] notes that and
 * the next attempt starts the download again.
 */
internal object InPlaceMp4Merge {

    /** Box header: [start] of the box, [body] after the header, [end] after the box. */
    private class BoxRef(val type: String, val start: Long, val body: Long, val end: Long)

    /** What the init segment (ftyp + moov) of a one-track fragmented MP4 says. */
    private class Init(
        val isVideo: Boolean,
        val timescale: Int,
        val stsd: ByteArray,
        val width: Int,
        val height: Int,
        val mediaStartVu: Long,
        val defaultDuration: Int,
        val defaultSize: Int,
        val defaultFlags: Int,
    )

    /** One audio fragment's sample bytes, [start] to [start] + [length], and where its moof begins. */
    private class Piece(val moofStart: Long, val start: Long, val length: Long)

    fun marker(video: File) = File(video.path + ".merging")

    /**
     * Merges [audio] into [video] in place and returns true, or returns false (having changed nothing) when the
     * files aren't fragmented MP4s this handles.
     */
    fun merge(video: File, audio: File): Boolean {
        if (marker(video).exists()) {
            // A merge was killed half way; the parts are partly consumed. Drop them so a retry downloads again.
            listOf(video, audio).forEach { it.delete(); ChunkState.stateFile(it).delete() }
            marker(video).delete()
            throw IOException("Processing was interrupted. Tap retry to download the video again")
        }
        val vInit = RandomAccessFile(video, "r").use { readInit(it) } ?: return false
        val aInit = RandomAccessFile(audio, "r").use { readInit(it) } ?: return false
        if (!vInit.isVideo || aInit.isVideo) return false

        Mp4Moov.clearTemp()
        val vTrack = Mp4Track(1, true, vInit.timescale, vInit.stsd, vInit.width, vInit.height)
        val aTrack = Mp4Track(2, false, aInit.timescale, aInit.stsd)
        try {
            val videoEnd = video.length()
            // Read-only pass: the video's sample tables (its samples don't move), then where each audio fragment
            // will land: right after the video, last fragment first.
            RandomAccessFile(video, "r").use { raf -> if (!readFragments(raf, vInit, vTrack) { _, _ -> 0L }) return false }
            val pieces = ArrayList<Piece>()
            RandomAccessFile(audio, "r").use { raf ->
                if (!readFragments(raf, aInit, null) { moof, range -> pieces += Piece(moof, range.first, range.last - range.first); 0L }) return false
            }
            if (pieces.isEmpty() || vTrack.sampleCount == 0) return false
            val audioBytes = pieces.sumOf { it.length }
            val destination = HashMap<Long, Long>(pieces.size * 2)
            var at = videoEnd + 16
            for (p in pieces.asReversed()) { destination[p.start] = at; at += p.length }
            RandomAccessFile(audio, "r").use { raf ->
                readFragments(raf, aInit, aTrack) { _, range -> destination.getValue(range.first) - range.first }
            }
            vTrack.mediaStartVu = vInit.mediaStartVu
            aTrack.mediaStartVu = aInit.mediaStartVu
            vTrack.end(); aTrack.end()

            // From here on the parts are consumed.
            marker(video).writeText("1")
            RandomAccessFile(video, "rw").use { v ->
                RandomAccessFile(audio, "rw").use { a ->
                    v.seek(videoEnd)
                    v.write(ByteBuffer.allocate(16).putInt(1).put("mdat".toByteArray()).putLong(16 + audioBytes).array())
                    val buffer = ByteBuffer.allocateDirect(1 shl 20)
                    for (p in pieces.asReversed()) {
                        copy(a, p.start, p.length, v, destination.getValue(p.start), buffer)
                        a.channel.truncate(minOf(p.moofStart, p.start))
                    }
                }
            }
            BufferedOutputStream(FileOutputStream(video, true), 1 shl 20).use { Mp4Moov.write(it, listOf(vTrack, aTrack)) }
            RandomAccessFile(video, "rw").use { neutralizeHeaders(it, videoEnd) }
            audio.delete()
            marker(video).delete()
            return true
        } finally {
            vTrack.close()
            aTrack.close()
        }
    }

    private fun copy(from: RandomAccessFile, start: Long, length: Long, to: RandomAccessFile, at: Long, buffer: ByteBuffer) {
        var done = 0L
        while (done < length) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), length - done).toInt())
            val n = from.channel.read(buffer, start + done)
            if (n <= 0) throw IOException("Audio part ended early")
            buffer.flip()
            while (buffer.hasRemaining()) to.channel.write(buffer, at + done + buffer.position())
            done += n
        }
    }

    /** Makes the old fragmented-MP4 boxes before [end] invisible: the samples stay in their mdat boxes. */
    private fun neutralizeHeaders(raf: RandomAccessFile, end: Long) {
        for (box in boxes(raf, 0, end)) {
            when (box.type) {
                "mdat" -> Unit
                "ftyp" -> {
                    // Same size, plain MP4 brands (the DASH brands make some players expect fragments).
                    val brands = listOf("isom", "iso2", "mp41")
                    val body = ByteBuffer.allocate((box.end - box.body).toInt())
                    body.put("isom".toByteArray()).putInt(0x200)
                    var i = 0
                    while (body.hasRemaining()) body.put(brands[i++ % brands.size].toByteArray())
                    raf.seek(box.body); raf.write(body.array())
                }
                else -> {
                    raf.seek(box.start + 4)
                    raf.write("free".toByteArray())
                    // FFmpeg-based players treat a free box holding an mvhd as the moov, so blank the old one.
                    if (box.type == "moov") { raf.seek(box.body); raf.write(ByteArray((box.end - box.body).toInt())) }
                }
            }
        }
    }

    private fun boxes(raf: RandomAccessFile, from: Long, to: Long): Sequence<BoxRef> = sequence {
        var p = from
        val header = ByteArray(16)
        while (p + 8 <= to) {
            raf.seek(p)
            raf.readFully(header, 0, 8)
            val b = ByteBuffer.wrap(header)
            var size = b.getInt(0).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var body = p + 8
            if (size == 1L) { raf.readFully(header, 8, 8); size = b.getLong(8); body = p + 16 }
            if (size == 0L) size = to - p
            if (size < body - p || p + size > to) throw IOException("Broken box $type")
            yield(BoxRef(type, p, body, p + size))
            p += size
        }
    }

    private fun children(data: ByteBuffer, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = from
        while (p + 8 <= to) {
            val size = data.getInt(p)
            if (size < 8 || p + size > to) break
            val type = String(ByteArray(4) { data.get(p + 4 + it) }, Charsets.ISO_8859_1)
            out += Triple(type, p + 8, p + size)
            p += size
        }
        return out
    }

    private fun ByteBuffer.child(from: Int, to: Int, type: String) = children(this, from, to).firstOrNull { it.first == type }

    private fun readInit(raf: RandomAccessFile): Init? {
        val top = runCatching { boxes(raf, 0, raf.length()).take(4).toList() }.getOrNull() ?: return null
        if (top.firstOrNull()?.type != "ftyp") return null
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val bytes = ByteArray((moov.end - moov.body).toInt())
        raf.seek(moov.body); raf.readFully(bytes)
        val d = ByteBuffer.wrap(bytes)
        val traks = children(d, 0, bytes.size).filter { it.first == "trak" }
        if (traks.size != 1) return null
        val (_, ts, te) = traks[0]
        val mvex = d.child(0, bytes.size, "mvex")
        val trex = mvex?.let { d.child(it.second, it.third, "trex") }
        val mdia = d.child(ts, te, "mdia") ?: return null
        val mdhd = d.child(mdia.second, mdia.third, "mdhd") ?: return null
        val timescale = d.getInt(mdhd.second + if (d.get(mdhd.second).toInt() == 1) 20 else 12)
        val handler = d.child(mdia.second, mdia.third, "hdlr")?.let { String(ByteArray(4) { i -> d.get(it.second + 8 + i) }) } ?: return null
        val minf = d.child(mdia.second, mdia.third, "minf") ?: return null
        val stbl = d.child(minf.second, minf.third, "stbl") ?: return null
        val stsd = d.child(stbl.second, stbl.third, "stsd") ?: return null
        val tkhd = d.child(ts, te, "tkhd") ?: return null
        // The first edit that shows media gives where playback starts (YouTube: past the B-frame delay).
        val mediaStart = d.child(ts, te, "edts")?.let { e -> d.child(e.second, e.third, "elst") }?.let { e ->
            val v1 = d.get(e.second).toInt() == 1
            val count = d.getInt(e.second + 4)
            (0 until count).asSequence().map { i ->
                val p = e.second + 8 + i * (if (v1) 20 else 12)
                if (v1) d.getLong(p + 8) else d.getInt(p + 4).toLong()
            }.firstOrNull { it >= 0 }
        } ?: 0L
        val stsdBytes = bytes.copyOfRange(stsd.second - 8, stsd.third)
        // Encrypted streams (encv/enca) can't be stored as plain MP4.
        if (String(stsdBytes, 20, 4, Charsets.ISO_8859_1).startsWith("enc")) return null
        return Init(
            isVideo = handler == "vide",
            timescale = timescale.takeIf { it > 0 } ?: return null,
            stsd = stsdBytes,
            width = d.getInt(tkhd.third - 8) ushr 16,
            height = d.getInt(tkhd.third - 4) ushr 16,
            mediaStartVu = mediaStart,
            defaultDuration = trex?.let { d.getInt(it.second + 12) } ?: 0,
            defaultSize = trex?.let { d.getInt(it.second + 16) } ?: 0,
            defaultFlags = trex?.let { d.getInt(it.second + 20) } ?: 0,
        ).takeIf { handler == "vide" || handler == "soun" }
    }

    /**
     * Walks the moof boxes. Each fragment's samples go into [track] (when given) with their offsets moved by
     * what [shift] returns for (moof start, sample byte range). False when the layout isn't one this handles.
     */
    private fun readFragments(raf: RandomAccessFile, init: Init, track: Mp4Track?, shift: (Long, LongRange) -> Long): Boolean {
        var first = true
        for (box in boxes(raf, 0, raf.length())) {
            if (box.type != "moof") continue
            val bytes = ByteArray((box.end - box.body).toInt())
            raf.seek(box.body); raf.readFully(bytes)
            val d = ByteBuffer.wrap(bytes)
            val trafs = children(d, 0, bytes.size).filter { it.first == "traf" }
            if (trafs.size != 1) return false
            val (_, ts, te) = trafs[0]
            val tfhd = d.child(ts, te, "tfhd") ?: return false
            val tf = d.getInt(tfhd.second) and 0xFFFFFF
            var p = tfhd.second + 8
            val base = if (tf and 0x1 != 0) d.getLong(p).also { p += 8 } else box.start
            if (tf and 0x2 != 0) p += 4
            val defDuration = if (tf and 0x8 != 0) d.getInt(p).also { p += 4 } else init.defaultDuration
            val defSize = if (tf and 0x10 != 0) d.getInt(p).also { p += 4 } else init.defaultSize
            val defFlags = if (tf and 0x20 != 0) d.getInt(p) else init.defaultFlags
            if (first) {
                d.child(ts, te, "tfdt")?.let { t ->
                    val time = if (d.get(t.second).toInt() == 1) d.getLong(t.second + 4) else d.getInt(t.second + 4).toLong() and 0xFFFFFFFFL
                    track?.startUs = time * 1_000_000 / init.timescale
                }
                first = false
            }
            var dataPos = base
            val runs = children(d, ts, te).filter { it.first == "trun" }
            // First pass over the runs: the byte range of this fragment's samples.
            var lo = Long.MAX_VALUE
            var hi = Long.MIN_VALUE
            val parsed = runs.map { (_, rs, _) ->
                val rf = d.getInt(rs) and 0xFFFFFF
                val count = d.getInt(rs + 4)
                var q = rs + 8
                val start = if (rf and 0x1 != 0) base + d.getInt(q).also { q += 4 } else dataPos
                val firstFlags = if (rf and 0x4 != 0) d.getInt(q).also { q += 4 } else null
                var size = 0L
                var r = q
                repeat(count) {
                    if (rf and 0x100 != 0) r += 4
                    size += if (rf and 0x200 != 0) d.getInt(r).toLong().also { r += 4 } else defSize.toLong()
                    if (rf and 0x400 != 0) r += 4
                    if (rf and 0x800 != 0) r += 4
                }
                dataPos = start + size
                if (count > 0) { lo = minOf(lo, start); hi = maxOf(hi, start + size) }
                Triple(rs, start, firstFlags)
            }
            if (lo == Long.MAX_VALUE) continue
            if (hi > raf.length()) return false
            val offset = shift(box.start, lo..hi)
            if (track == null) continue
            for ((rs, start, firstFlags) in parsed) {
                val rf = d.getInt(rs) and 0xFFFFFF
                val count = d.getInt(rs + 4)
                var r = rs + 8 + (if (rf and 0x1 != 0) 4 else 0) + (if (rf and 0x4 != 0) 4 else 0)
                for (i in 0 until count) {
                    val duration = if (rf and 0x100 != 0) d.getInt(r).also { r += 4 } else defDuration
                    val size = if (rf and 0x200 != 0) d.getInt(r).also { r += 4 } else defSize
                    val flags = if (rf and 0x400 != 0) d.getInt(r).also { r += 4 } else if (i == 0 && firstFlags != null) firstFlags else defFlags
                    val cto = if (rf and 0x800 != 0) d.getInt(r).also { r += 4 } else 0
                    track.sample(size, flags and 0x10000 == 0)
                    track.duration(duration)
                    track.compositionOffset(cto)
                }
                if (count > 0) track.chunk(start + offset, count)
            }
        }
        return true
    }
}
