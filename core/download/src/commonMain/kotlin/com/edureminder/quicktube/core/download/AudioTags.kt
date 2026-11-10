package com.edureminder.quicktube.core.download

import com.edureminder.quicktube.core.common.Branding
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes the title, artist and cover picture into a downloaded file, so music players and the system's media library
 * show them, plus QuickTube's description, site address and tags (see [Branding]): iTunes-style atoms for MP4/M4A,
 * an ID3v2.3 tag for MP3. Other containers (WebM/Opus) are left as they are.
 */
internal object AudioTags {
    private val MP4_TYPES = setOf("m4a", "mp4", "m4b", "m4v", "mov")
    private const val MAX_COPY = 2L * 1024 * 1024 * 1024

    class Tags(val title: String, val artist: String?, val cover: ByteArray?)

    /** "Artist - Song" (how most music uploads are named) → artist and song; otherwise just the title. */
    fun fromTitle(title: String, uploader: String? = null, cover: ByteArray?): Tags {
        val parts = title.split(" - ", " – ", " — ", limit = 2).map { it.trim() }
        return if (parts.size == 2 && parts.all { it.isNotEmpty() } && parts[0].length <= 60) Tags(parts[1], parts[0], cover)
        else Tags(title, uploader?.takeIf { it.isNotBlank() }, cover)
    }

    /**
     * True when the file was tagged. An MP4 whose movie header is at the end is changed in place (a 4K video isn't
     * copied); otherwise a new copy is written next to [file] and swapped in.
     */
    fun write(file: File, tags: Tags): Boolean {
        val ext = file.extension.lowercase()
        if (ext in MP4_TYPES && runCatching { writeMp4InPlace(file, tags) }.getOrDefault(false)) return true
        // Copying needs room for a second copy; very big files aren't worth it.
        if (file.length() > MAX_COPY || file.length() * 2 > (file.parentFile?.usableSpace ?: 0L) + file.length()) return false
        val tmp = File(file.path + ".tag")
        val ok = runCatching {
            when (file.extension.lowercase()) {
                "mp3" -> writeId3(file, tmp, tags)
                in MP4_TYPES -> writeMp4(file, tmp, tags)
                else -> false
            }
        }.getOrDefault(false)
        if (!ok) { tmp.delete(); return false }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        return true
    }

    // ---- MP3: ID3v2.3 in front of the audio frames (an old tag is replaced) ----

    private fun writeId3(src: File, dest: File, tags: Tags): Boolean {
        val skip = src.inputStream().use { input ->
            val h = ByteArray(10)
            if (input.read(h) == 10 && String(h, 0, 3, Charsets.ISO_8859_1) == "ID3") 10L + syncSafe(h, 6) else 0L
        }
        dest.outputStream().buffered().use { out ->
            out.write(id3(tags))
            src.inputStream().use { input -> input.skip(skip); input.copyTo(out, 1 shl 16) }
        }
        return true
    }

    internal fun id3(tags: Tags): ByteArray {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, body: ByteArray) = DataOutputStream(frames).run {
            write(id.toByteArray(Charsets.ISO_8859_1)); writeInt(body.size); writeShort(0); write(body)
        }
        // Encoding 1 = UTF-16 with BOM, which every player reads (ID3v2.3 has no UTF-8).
        fun text(s: String) = byteArrayOf(1) + s.toByteArray(Charsets.UTF_16)
        // A text with a (here empty) description first: COMM, TXXX and WXXX.
        fun described(value: ByteArray, desc: String = "") = byteArrayOf(1) + desc.toByteArray(Charsets.UTF_16) + byteArrayOf(0, 0) + value
        frame("TIT2", text(tags.title))
        tags.artist?.let { frame("TPE1", text(it)) }
        frame("COMM", byteArrayOf(1) + "eng".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 0) +
            Branding.DESCRIPTION.toByteArray(Charsets.UTF_16))
        frame("TENC", text(Branding.ENCODER))
        frame("TPUB", text(Branding.DOMAIN))
        frame("TXXX", described(Branding.KEYWORDS.joinToString(", ").toByteArray(Charsets.UTF_16), "keywords"))
        // The URL itself is always ISO-8859-1.
        frame("WXXX", byteArrayOf(0, 0) + Branding.URL.toByteArray(Charsets.ISO_8859_1))
        tags.cover?.let { jpeg ->
            // Encoding 0, MIME, picture type 3 (front cover), empty description.
            frame("APIC", byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + jpeg)
        }
        val body = frames.toByteArray()
        val size = body.size
        return byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte(),
        ) + body
    }

    private fun syncSafe(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    // ---- MP4/M4A: moov/udta/meta/ilst ----

    private class Box(val type: String, val offset: Long, val size: Long, val header: Int)

    private fun topLevel(raf: RandomAccessFile): List<Box> {
        val boxes = mutableListOf<Box>()
        var at = 0L
        val len = raf.length()
        while (at + 8 <= len) {
            raf.seek(at)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = raf.readLong(); header = 16 }
            if (size == 0L) size = len - at
            if (size < header || at + size > len) break
            boxes += Box(type, at, size, header)
            at += size
        }
        return boxes
    }

    /** The movie header is the file's last box: write the new one over it (nothing before it moves). */
    private fun writeMp4InPlace(file: File, tags: Tags): Boolean = RandomAccessFile(file, "rw").use { raf ->
        val boxes = topLevel(raf)
        val moov = boxes.lastOrNull()?.takeIf { it.type == "moov" && it.offset + it.size == raf.length() } ?: return false
        if (moov.size > 64L * 1024 * 1024 || boxes.any { it.type == "moof" }) return false
        val moovBytes = ByteArray(moov.size.toInt()).also { raf.seek(moov.offset); raf.readFully(it) }
        val newMoov = rebuildMoov(moovBytes, moov.header, ilst(tags))
        raf.seek(moov.offset)
        raf.write(newMoov)
        raf.setLength(moov.offset + newMoov.size)
        true
    }

    private fun writeMp4(src: File, dest: File, tags: Tags): Boolean = RandomAccessFile(src, "r").use { raf ->
        val boxes = topLevel(raf)
        val moov = boxes.firstOrNull { it.type == "moov" } ?: return false
        if (moov.size > 64L * 1024 * 1024) return false
        val moovBytes = ByteArray(moov.size.toInt()).also { raf.seek(moov.offset); raf.readFully(it) }
        val mdatAfter = boxes.any { (it.type == "mdat" || it.type == "moof") && it.offset > moov.offset }
        // Fragments that give absolute data offsets would need every moof patched; leave those files alone.
        if (boxes.any { it.type == "moof" } && fragmentsUseAbsoluteOffsets(raf, boxes.first { it.type == "moof" })) return false
        // A segment index before the movie header points across it, by a distance the bigger header would break.
        if (boxes.any { it.type == "sidx" && it.offset < moov.offset }) return false
        val newMoov = rebuildMoov(moovBytes, moov.header, ilst(tags))
        val delta = (newMoov.size - moovBytes.size).toLong()
        // Media data after the movie header moves by [delta]: its chunk offsets must move too.
        if (mdatAfter && delta != 0L) shiftChunkOffsets(newMoov, delta)
        dest.outputStream().buffered(1 shl 16).use { out ->
            fun copy(from: Long, to: Long) {
                val buf = ByteArray(1 shl 16)
                var at = from
                raf.seek(from)
                while (at < to) { val n = raf.read(buf, 0, minOf(buf.size.toLong(), to - at).toInt()); if (n < 0) break; out.write(buf, 0, n); at += n }
            }
            copy(0, moov.offset)
            out.write(newMoov)
            copy(moov.offset + moov.size, raf.length())
        }
        true
    }

    /** moov with any old udta replaced by ours (title, artist, cover). */
    private fun rebuildMoov(moov: ByteArray, header: Int, ilst: ByteArray): ByteArray {
        val children = ByteArrayOutputStream()
        var at = header
        while (at + 8 <= moov.size) {
            val size = int(moov, at).toInt()
            if (size < 8 || at + size > moov.size) break
            if (String(moov, at + 4, 4, Charsets.ISO_8859_1) != "udta") children.write(moov, at, size)
            at += size
        }
        // meta is a full box (version/flags) with a handler naming it as iTunes metadata ("mdir").
        val hdlr = box("hdlr", ByteArray(8) + "mdirappl".toByteArray(Charsets.ISO_8859_1) + ByteArray(9))
        val meta = box("meta", ByteArray(4) + hdlr + ilst)
        children.write(box("udta", meta))
        return box("moov", children.toByteArray())
    }

    internal fun ilst(tags: Tags): ByteArray {
        fun item(type: String, dataType: Int, value: ByteArray) =
            box(type, box("data", byteArrayOf(0, 0, 0, dataType.toByte(), 0, 0, 0, 0) + value))
        val items = ByteArrayOutputStream()
        items.write(item("©nam", 1, tags.title.toByteArray(Charsets.UTF_8)))
        tags.artist?.let { items.write(item("©ART", 1, it.toByteArray(Charsets.UTF_8))) }
        items.write(item("©cmt", 1, Branding.DESCRIPTION.toByteArray(Charsets.UTF_8)))
        items.write(item("desc", 1, Branding.DESCRIPTION.toByteArray(Charsets.UTF_8)))
        items.write(item("©too", 1, Branding.ENCODER.toByteArray(Charsets.UTF_8)))
        items.write(item("©pub", 1, Branding.DOMAIN.toByteArray(Charsets.UTF_8)))
        items.write(item("purl", 1, Branding.URL.toByteArray(Charsets.UTF_8)))
        items.write(item("keyw", 1, Branding.KEYWORDS.joinToString(", ").toByteArray(Charsets.UTF_8)))
        tags.cover?.let { items.write(item("covr", 13, it)) }
        return box("ilst", items.toByteArray())
    }

    private fun box(type: String, body: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()

    private fun int(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun putInt(b: ByteArray, at: Int, v: Long) { for (i in 0..3) b[at + i] = (v shr (24 - 8 * i)).toByte() }

    /** Adds [delta] to every stco/co64 entry under moov/trak/mdia/minf/stbl. */
    private fun shiftChunkOffsets(moov: ByteArray, delta: Long) {
        fun walk(from: Int, to: Int) {
            var at = from
            while (at + 8 <= to) {
                val size = int(moov, at).toInt()
                if (size < 8 || at + size > to) return
                when (String(moov, at + 4, 4, Charsets.ISO_8859_1)) {
                    "trak", "mdia", "minf", "stbl" -> walk(at + 8, at + size)
                    "stco" -> {
                        val n = int(moov, at + 12).toInt()
                        for (i in 0 until n) { val p = at + 16 + 4 * i; putInt(moov, p, int(moov, p) + delta) }
                    }
                    "co64" -> {
                        val n = int(moov, at + 12).toInt()
                        for (i in 0 until n) {
                            val p = at + 16 + 8 * i
                            val v = (int(moov, p) shl 32) or int(moov, p + 4)
                            putInt(moov, p, (v + delta) ushr 32); putInt(moov, p + 4, (v + delta) and 0xFFFFFFFFL)
                        }
                    }
                }
                at += size
            }
        }
        walk(8, moov.size)
    }

    /** The first fragment's tfhd says base-data-offset-present (an absolute file position). */
    private fun fragmentsUseAbsoluteOffsets(raf: RandomAccessFile, moof: Box): Boolean {
        val bytes = ByteArray(minOf(moof.size, 4096L).toInt()).also { raf.seek(moof.offset); raf.readFully(it) }
        val i = (0 until bytes.size - 12).firstOrNull { String(bytes, it, 4, Charsets.ISO_8859_1) == "tfhd" } ?: return false
        return bytes[i + 7].toInt() and 0x01 != 0
    }
}
