package com.edureminder.quicktube.core.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TsTailTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun packet(pid: Int, section: IntArray): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47; p[1] = (0x40 or (pid shr 8)).toByte(); p[2] = pid.toByte(); p[3] = 0x10
        p[4] = 0 // pointer field
        section.forEachIndexed { i, b -> p[5 + i] = b.toByte() }
        return p
    }

    // PAT: program 1 → PMT on PID 0x1000. PMT: AAC on 0x101, H.264 on 0x100 (CRCs aren't checked).
    private val pat = packet(0, intArrayOf(0x00, 0xB0, 0x0D, 0, 1, 0xC1, 0, 0, 0, 1, 0xF0, 0x00, 0, 0, 0, 0))
    private val pmt = packet(0x1000, intArrayOf(0x02, 0xB0, 0x17, 0, 1, 0xC1, 0, 0, 0xE1, 0x00, 0xF0, 0x00,
        0x0F, 0xE1, 0x01, 0xF0, 0x00, 0x1B, 0xE1, 0x00, 0xF0, 0x00, 0, 0, 0, 0))

    @Test
    fun findsTheVideoStream() {
        assertEquals(0x100 to 0x1B, TsTail.videoStream(pat + pmt))
        assertNull(TsTail.videoStream(pat))
    }

    @Test
    fun closingPacketStartsAnEmptyAccessUnitOnTheVideoPid() {
        val file = tmp.newFile("v.ts").apply { writeBytes(pat + pmt) }
        val p = TsTail.closingPacket(file)!!
        assertEquals(188, p.size)
        assertEquals(0x47.toByte(), p[0])
        assertEquals(0x41, p[1].toInt() and 0xFF) // payload unit start + PID high bits
        assertEquals(0x00, p[2].toInt() and 0xFF)
        // Ends with PES start code + video stream id + H.264 AUD.
        assertArrayEquals(byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 1, 0x09, 0xF0.toByte()), p.copyOfRange(173, 188))
        assertEquals(168, p[4].toInt() and 0xFF) // adaptation field (flags + stuffing) fills the rest
    }
}
