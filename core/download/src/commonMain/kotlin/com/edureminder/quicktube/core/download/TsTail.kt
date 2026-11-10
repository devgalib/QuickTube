package com.edureminder.quicktube.core.download

import java.io.File

/**
 * Builds one MPEG-TS packet that starts a new PES on the video stream with only an access unit delimiter.
 * Appended after an HLS download, it makes the extractor finish the real last frame (which it would otherwise
 * keep waiting on), with that frame's own timestamps.
 */
internal object TsTail {
    private const val PACKET = 188

    fun closingPacket(file: File): ByteArray? {
        val head = file.inputStream().use { input -> ByteArray(minOf(file.length(), 2L * 1024 * 1024).toInt()).also { input.read(it) } }
        val (pid, streamType) = videoStream(head) ?: return null
        val aud = when (streamType) {
            0x1B -> byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())          // H.264 AUD
            0x24 -> byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50)             // H.265 AUD
            else -> return null
        }
        // PES header without timestamps: start code, video stream id, unbounded length, no optional fields.
        val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0, 0) + aud
        val stuffing = PACKET - 4 - 1 - pes.size
        val out = ByteArray(PACKET)
        out[0] = 0x47
        out[1] = (0x40 or (pid shr 8 and 0x1F)).toByte() // payload unit start
        out[2] = (pid and 0xFF).toByte()
        out[3] = 0x30 // adaptation field + payload
        out[4] = stuffing.toByte()
        if (stuffing > 0) {
            out[5] = 0 // no adaptation flags
            for (i in 6 until 5 + stuffing) out[i] = 0xFF.toByte()
        }
        pes.copyInto(out, 5 + stuffing)
        return out
    }

    /** (PID, stream type) of the first H.264/H.265 stream, read from the PAT and PMT. */
    internal fun videoStream(data: ByteArray): Pair<Int, Int>? {
        var pmtPid = -1
        var i = 0
        while (i + PACKET <= data.size) {
            if (data[i] != 0x47.toByte()) { i++; continue } // resync
            val pusi = data[i + 1].toInt() and 0x40 != 0
            val pid = (data[i + 1].toInt() and 0x1F shl 8) or (data[i + 2].toInt() and 0xFF)
            val afc = data[i + 3].toInt() shr 4 and 3
            var p = i + 4
            if (afc and 2 != 0) p += 1 + (data[p].toInt() and 0xFF)
            if (afc and 1 != 0 && pusi && p < i + PACKET) {
                p += 1 + (data[p].toInt() and 0xFF) // pointer field
                val end = i + PACKET
                if (pid == 0 && pmtPid < 0 && p + 8 < end && data[p].toInt() == 0) {
                    val sectionEnd = minOf(end, p + 3 + ((data[p + 1].toInt() and 0x0F shl 8) or (data[p + 2].toInt() and 0xFF)) - 4)
                    var q = p + 8
                    while (q + 4 <= sectionEnd) {
                        val program = (data[q].toInt() and 0xFF shl 8) or (data[q + 1].toInt() and 0xFF)
                        if (program != 0) { pmtPid = (data[q + 2].toInt() and 0x1F shl 8) or (data[q + 3].toInt() and 0xFF); break }
                        q += 4
                    }
                } else if (pid == pmtPid && p + 12 < end && data[p].toInt() == 2) {
                    val sectionEnd = minOf(end, p + 3 + ((data[p + 1].toInt() and 0x0F shl 8) or (data[p + 2].toInt() and 0xFF)) - 4)
                    var q = p + 12 + ((data[p + 10].toInt() and 0x0F shl 8) or (data[p + 11].toInt() and 0xFF))
                    while (q + 5 <= sectionEnd) {
                        val type = data[q].toInt() and 0xFF
                        val es = (data[q + 1].toInt() and 0x1F shl 8) or (data[q + 2].toInt() and 0xFF)
                        if (type == 0x1B || type == 0x24) return es to type
                        q += 5 + ((data[q + 3].toInt() and 0x0F shl 8) or (data[q + 4].toInt() and 0xFF))
                    }
                }
            }
            i += PACKET
        }
        return null
    }
}
