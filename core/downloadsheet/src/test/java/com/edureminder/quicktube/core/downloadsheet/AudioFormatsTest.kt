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
    fun nothingAddedWithoutAudioOrWhenMp3Exists() {
        assertEquals(listOf("v"), withMp3(listOf(video("v"))).map { it.id })
        assertEquals(listOf("m", "v"), withMp3(listOf(audio("m", "mp3", 128), video("v"))).map { it.id })
    }
}
