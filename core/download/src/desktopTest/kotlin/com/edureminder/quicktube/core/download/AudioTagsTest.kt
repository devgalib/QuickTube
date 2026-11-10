package com.edureminder.quicktube.core.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class AudioTagsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun box(type: String, body: ByteArray) =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()

    private fun Int.bytes() = ByteBuffer.allocate(4).putInt(this).array()

    /** ftyp, moov (one track whose only chunk starts at [chunk]), mdat with [payload]. */
    private fun mp4(payload: ByteArray, chunkAt: (Int) -> Int): ByteArray {
        val ftyp = box("ftyp", "M4A ".toByteArray() + 0.bytes())
        fun moov(offset: Int) = box("moov", box("trak", box("mdia", box("minf", box("stbl", box("stco", 0.bytes() + 1.bytes() + offset.bytes()))))))
        val moovSize = moov(0).size
        val mdatStart = ftyp.size + moovSize
        return ftyp + moov(chunkAt(mdatStart + 8)) + box("mdat", payload)
    }

    private fun find(b: ByteArray, s: String): Int {
        val p = s.toByteArray(Charsets.ISO_8859_1)
        return (0..b.size - p.size).firstOrNull { i -> p.indices.all { b[i + it] == p[it] } } ?: -1
    }

    @Test
    fun m4aGetsTagsAndChunkOffsetsFollowTheBiggerHeader() {
        val payload = "AUDIO-SAMPLES".toByteArray()
        val f = tmp.newFile("song.m4a").apply { writeBytes(mp4(payload) { it }) }
        val cover = byteArrayOf(-1, -40, -1, 1, 2, 3)
        assertTrue(AudioTags.write(f, AudioTags.Tags("Song", "Artist", cover)))
        val b = f.readBytes()
        assertTrue(find(b, "ilst") > 0)
        assertTrue(find(b, "©nam") > 0)
        assertTrue(find(b, "covr") > 0)
        // stco still points at the payload.
        val stco = find(b, "stco")
        val offset = ByteBuffer.wrap(b, stco + 12, 4).int
        assertArrayEquals(payload, b.copyOfRange(offset, offset + payload.size))
    }

    @Test
    fun videoWithHeaderAtTheEndIsBrandedInPlace() {
        val payload = "VIDEO-SAMPLES".toByteArray()
        val ftyp = box("ftyp", "isom".toByteArray() + 0.bytes())
        val mdat = box("mdat", payload)
        val moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", box("stco", 0.bytes() + 1.bytes() + (ftyp.size + 8).bytes()))))))
        val f = tmp.newFile("clip.mp4").apply { writeBytes(ftyp + mdat + moov) }
        assertTrue(AudioTags.write(f, AudioTags.Tags("Clip", null, null)))
        val b = f.readBytes()
        // Media data didn't move; the header now carries the branding.
        assertArrayEquals(ftyp + mdat, b.copyOfRange(0, ftyp.size + mdat.size))
        for (tag in listOf("©cmt", "©too", "keyw", "purl", "edureminder.com")) assertTrue(tag, find(b, tag) > 0)
        assertEquals(-1, find(b, ".tag"))
    }

    @Test
    fun mp3GetsTheBrandingFrames() {
        val tag = AudioTags.id3(AudioTags.Tags("Song", null, null))
        for (frame in listOf("COMM", "TENC", "TPUB", "TXXX", "WXXX")) assertTrue(frame, find(tag, frame) > 0)
        assertTrue(find(tag, "https://edureminder.com") > 0)
    }

    @Test
    fun retaggingReplacesTheOldTags() {
        val payload = "AUDIO".toByteArray()
        val f = tmp.newFile("song.m4a").apply { writeBytes(mp4(payload) { it }) }
        AudioTags.write(f, AudioTags.Tags("First", null, null))
        AudioTags.write(f, AudioTags.Tags("Second", null, null))
        val b = f.readBytes()
        assertEquals(-1, find(b, "First"))
        assertTrue(find(b, "Second") > 0)
        val stco = find(b, "stco")
        val offset = ByteBuffer.wrap(b, stco + 12, 4).int
        assertArrayEquals(payload, b.copyOfRange(offset, offset + payload.size))
    }

    @Test
    fun mp3GetsAnId3TagInFrontAndAnOldOneIsReplaced() {
        val frames = byteArrayOf(-1, -5, -112, 0, 1, 2, 3)
        val old = AudioTags.id3(AudioTags.Tags("Old", null, null))
        val f = tmp.newFile("song.mp3").apply { writeBytes(old + frames) }
        assertTrue(AudioTags.write(f, AudioTags.Tags("New", "Someone", byteArrayOf(9, 9))))
        val b = f.readBytes()
        assertEquals("ID3", String(b, 0, 3))
        assertTrue(find(b, "TIT2") > 0 && find(b, "TPE1") > 0 && find(b, "APIC") > 0)
        assertArrayEquals(frames, b.copyOfRange(b.size - frames.size, b.size))
        assertEquals(1, Regex("ID3").findAll(String(b, Charsets.ISO_8859_1)).count())
    }

    @Test
    fun otherContainersAreLeftAlone() {
        val f = tmp.newFile("song.webm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(!AudioTags.write(f, AudioTags.Tags("x", null, null)))
        assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
    }

    @Test
    fun artistAndSongComeFromTheTitle() {
        AudioTags.fromTitle("Arijit Singh - Tum Hi Ho", cover = null).let { assertEquals("Tum Hi Ho", it.title); assertEquals("Arijit Singh", it.artist) }
        AudioTags.fromTitle("Lofi beats to study to", "Lofi Girl", null).let { assertEquals("Lofi beats to study to", it.title); assertEquals("Lofi Girl", it.artist) }
        assertNull(AudioTags.fromTitle("Just a title", cover = null).artist)
    }
}
