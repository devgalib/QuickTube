package com.edureminder.quicktube.core.extractor

import com.edureminder.quicktube.core.model.FormatKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliReaderTest {
    @Test
    fun recognisesVideoLinks() {
        assertEquals("BV13x41117TL", BilibiliReader.videoId("https://www.bilibili.com/video/BV13x41117TL"))
        assertEquals("BV13x41117TL", BilibiliReader.videoId("https://m.bilibili.com/video/BV13x41117TL?p=2&share_source=copy"))
        assertEquals("av170001", BilibiliReader.videoId("https://www.bilibili.com/video/av170001/"))
        assertNull(BilibiliReader.videoId("https://www.bilibili.com/"))
        assertTrue(BilibiliReader.isBilibili("https://b23.tv/abc123"))
        assertFalse(BilibiliReader.isBilibili("https://www.bilibili.tv/en/video/123"))
        assertEquals(2, BilibiliReader.partOf("https://www.bilibili.com/video/BV13x41117TL?p=2"))
        assertEquals(1, BilibiliReader.partOf("https://www.bilibili.com/video/BV13x41117TL"))
    }

    private fun stream(id: Int, w: Int, h: Int, codecs: String, bw: Long) =
        """{"id":$id,"width":$w,"height":$h,"codecs":"$codecs","bandwidth":$bw,"baseUrl":"https://cdn/$id-$codecs.m4s"}"""

    private val play = JSONObject("""{"dash":{"video":[
        ${stream(32, 852, 480, "avc1.64001F", 789485)}, ${stream(32, 852, 480, "av01.0.00M.10", 190733)},
        ${stream(16, 640, 360, "avc1.64001E", 352759)}, ${stream(80, 1920, 1080, "hev1.1.6.L150", 1500000)}],
        "audio":[${stream(30216, 0, 0, "mp4a.40.2", 67155)}, ${stream(30280, 0, 0, "mp4a.40.2", 190926)}]}}""")

    @Test
    fun oneChoicePerResolutionWithTheBestAudio() {
        val o = BilibiliReader.options(play, 100.0, av1Playable = true)
        val videos = o.filter { it.kind == FormatKind.VIDEO }
        assertEquals(listOf(360, 480, 1080), videos.map { it.height })
        // H.264 wins over AV1 at 480p; 1080p only exists as HEVC.
        assertEquals("https://cdn/32-avc1.64001F.m4s", videos[1].url)
        assertEquals("https://cdn/80-hev1.1.6.L150.m4s", videos[2].url)
        assertTrue(videos.all { it.audioUrl == "https://cdn/30280-mp4a.40.2.m4s" })
        assertEquals(789485L * 100 / 8, videos[1].sizeBytes)
        val audio = o.single { it.kind == FormatKind.AUDIO }
        assertEquals("m4a", audio.ext)
        assertEquals(190, audio.bitrate)
    }
}
