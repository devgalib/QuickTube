package com.edureminder.quicktube.core.downloadsheet

import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFormatsTest {
    private fun audio(id: String, ext: String, kbps: Int, extract: Boolean = false) =
        DownloadOption(id, FormatKind.AUDIO, id, "", ext, "audio/$ext", "https://x/$id", bitrate = kbps, extractAudio = extract)
    private fun video(id: String) = DownloadOption(id, FormatKind.VIDEO, id, "", "mp4", "video/mp4", "https://x/$id", height = 720)

    @Test
    fun mp3ComesFromTheBestAacAndSitsSecond() {
        val out = withMp3(listOf(audio("opus", "webm", 160), audio("aac", "m4a", 128), audio("aac-low", "m4a", 48), video("v")))
        assertEquals(
            listOf("opus", "mp3-aac", "aac", "aac-low", "mp3320-aac", "mp3128-aac", "aac-aac", "opus-opus", "flac-aac", "wav-aac", "v"),
            out.map { it.id },
        )
        // Seven formats made on the phone besides the site's own.
        val made = listOf(out[1]) + out.subList(4, 10)
        assertEquals(listOf("mp3", "mp3", "mp3", "aac", "opus", "flac", "wav"), made.map { it.ext })
        assertEquals(listOf(192, 320, 128, 128, 160, 0, 0), made.map { it.bitrate })
        val mp3 = out[1]
        assertEquals("mp3", mp3.ext)
        assertEquals("audio/mpeg", mp3.mimeType)
        assertEquals("https://x/aac", mp3.url)
        assertEquals(MP3_KBPS, mp3.bitrate)
    }

    @Test
    fun websiteVideosGetMp3FromTheirAudioOnlyChoice() {
        val out = withMp3(listOf(audio("x", "m4a", 0, extract = true), video("v")))
        // No AAC / Opus stream of its own to repackage: MP3s, FLAC and WAV from the video's sound.
        assertEquals(listOf("x", "mp3-x", "mp3320-x", "mp3128-x", "flac-x", "wav-x", "v"), out.map { it.id })
        assertEquals(true, out[1].extractAudio)
    }

    @Test
    fun sizesFromTheVideosLength() {
        // A 62-second video: MP3 192K ≈ 1.49 MB, WAV ≈ 10.9 MB.
        val out = withMp3(listOf(audio("x", "m4a", 0, extract = true), video("v")), durationSec = 62)
        assertEquals(1_488_000L, out.first { it.id == "mp3-x" }.sizeBytes)
        assertEquals(10_935_250L, out.first { it.id == "wav-x" }.sizeBytes)
    }

    @Test
    fun nothingAddedWithoutAudioOrWhenMp3Exists() {
        assertEquals(listOf("v"), withMp3(listOf(video("v"))).map { it.id })
        assertEquals(listOf("m", "v"), withMp3(listOf(audio("m", "mp3", 128), video("v"))).map { it.id })
    }
}

class DefaultChoiceTest {
    private fun v(h: Int) = DownloadOption("v$h", FormatKind.VIDEO, "${h}p", "", "mp4", "video/mp4", "https://x/$h", height = h, sizeBytes = h * 1000L)
    private fun a(id: String, ext: String) = DownloadOption(id, FormatKind.AUDIO, id, "", ext, "audio/$ext", "https://x/$id")
    private val all = listOf(a("m4a", "m4a"), a("mp3", "mp3"), v(144), v(360), v(720), v(1080), v(2160))

    @org.junit.Test
    fun followsTheDefaultQualitySetting() {
        org.junit.Assert.assertEquals("v720", defaultChoice(all)!!.id)
        org.junit.Assert.assertEquals("v360", defaultChoice(all, "P360")!!.id)
        org.junit.Assert.assertEquals("v1080", defaultChoice(all, "P1080")!!.id)
        org.junit.Assert.assertEquals("v2160", defaultChoice(all, "BEST")!!.id)
        org.junit.Assert.assertEquals("m4a", defaultChoice(all, "AUDIO")!!.id)
        org.junit.Assert.assertEquals("mp3", defaultChoice(all, "MP3")!!.id)
        // Asked for 1080p on a 480p-only video: the nearest there is.
        org.junit.Assert.assertEquals("v360", defaultChoice(listOf(v(144), v(360)), "P1080")!!.id)
    }
}
