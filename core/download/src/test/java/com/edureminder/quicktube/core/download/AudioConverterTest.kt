package com.edureminder.quicktube.core.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioConverterTest {
    @Test
    fun adtsHeaderForAacLcStereo44k() {
        // AAC-LC (2), 44.1 kHz (index 4), 2 channels, 100-byte frame: the header every AAC player expects.
        val h = AudioConverter.adtsHeader(100, 2, 4, 2)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x0D, 0x7F, 0xFC.toByte()), h)
    }

    @Test
    fun wavHeaderDescribes16BitPcm() {
        val h = ByteBuffer.wrap(AudioConverter.wavHeader(48000, 2, 1000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(h.array(), 0, 4))
        assertEquals(1036, h.getInt(4))
        assertEquals(48000, h.getInt(24))
        assertEquals(192000, h.getInt(28))
        assertEquals(16, h.getShort(34).toInt())
        assertEquals(1000, h.getInt(40))
    }
}

class FlacLengthTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun streamInfoGetsTheSampleCount() {
        // A real header from the phone's encoder (44.1 kHz stereo 16-bit, length 0 = unknown).
        val head = byteArrayOf(0x66, 0x4c, 0x61, 0x43, 0, 0, 0, 0x22, 0x10, 0, 0x10, 0, 0, 0, 0, 0, 0, 0, 0x0a, 0xc4.toByte(), 0x42, 0xf0.toByte(), 0, 0, 0, 0) + ByteArray(20)
        val f = tmp.newFile("a.flac").apply { writeBytes(head) }
        AudioConverter.setFlacLength(f, 837_000)
        val b = f.readBytes()
        val v = ByteBuffer.wrap(b, 18, 8).long
        assertEquals(837_000L, v and 0xFFFFFFFFFL)
        // Sample rate, channels and bit depth untouched.
        assertEquals(0x0ac442f0L, v ushr 32)
    }
}
