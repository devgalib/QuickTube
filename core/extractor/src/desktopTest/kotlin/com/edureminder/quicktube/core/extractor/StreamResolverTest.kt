package com.edureminder.quicktube.core.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class StreamResolverTest {
    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(8 + body.size); writeBytes(type); write(body) }
        return out.toByteArray()
    }

    /** mvhd version 0 (32-bit times) or 1 (64-bit). */
    private fun mvhd(version: Int, timescale: Int, duration: Long): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(version shl 24)
            if (version == 1) { writeLong(0); writeLong(0); writeInt(timescale); writeLong(duration) }
            else { writeInt(0); writeInt(0); writeInt(timescale); writeInt(duration.toInt()) }
            write(ByteArray(80))
        }
        return box("mvhd", out.toByteArray())
    }

    @Test
    fun durationFromFastStartMp4() {
        val file = box("ftyp", "isom0000".toByteArray()) + box("moov", mvhd(0, 1000, 96_500)) + box("mdat", ByteArray(32))
        assertEquals(96.5, StreamResolver.mp4DurationSec(file)!!, 0.001)
    }

    @Test
    fun durationFromVersion1Header() {
        val file = box("ftyp", "isom0000".toByteArray()) + box("moov", mvhd(1, 90_000, 90_000L * 63))
        assertEquals(63.0, StreamResolver.mp4DurationSec(file)!!, 0.001)
    }

    @Test
    fun noDurationWhenHeaderIsAtTheEndOrNotMp4() {
        // mdat first, bigger than what was read: moov can't be reached.
        val head = byteArrayOf(0, 0x10, 0, 0) + "mdat".toByteArray() + ByteArray(100)
        assertNull(StreamResolver.mp4DurationSec(head))
        assertNull(StreamResolver.mp4DurationSec("<html>not a video</html>".toByteArray()))
        assertNull(StreamResolver.mp4DurationSec(ByteArray(0)))
    }
}
