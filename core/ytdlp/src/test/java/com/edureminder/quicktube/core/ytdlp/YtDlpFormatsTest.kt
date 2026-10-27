package com.edureminder.quicktube.core.ytdlp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtDlpFormatsTest {
    private fun fmt(id: String, vcodec: String, acodec: String, ext: String, height: Int?, tbr: Double, abr: Double = 0.0) =
        """{"format_id":"$id","url":"https://cdn.example/$id","protocol":"https","vcodec":"$vcodec","acodec":"$acodec",
           "ext":"$ext","height":${height ?: "null"},"tbr":$tbr,"abr":$abr}"""

    /** Shaped like a Facebook reel: AV1 and H.264 video-only renditions plus AAC audio. */
    private val facebook = Json.parseToJsonElement(
        """{"title":"Reel","formats":[
            ${fmt("a", "none", "mp4a.40.5", "m4a", null, 64.0, 64.0)},
            ${fmt("av1-360", "av01.0.05M.08", "none", "mp4", 640, 300.0)},
            ${fmt("h264-360", "avc1.64001F", "none", "mp4", 640, 250.0)},
            ${fmt("av1-720", "av01.0.08M.08", "none", "mp4", 1280, 900.0)}
        ]}""",
    ).jsonObject

    @Test
    fun avoidsAv1WhenThePhoneCantPlayIt() {
        val page = YtDlpFormats.toPage(facebook, av1Playable = false)!!
        val videos = page.options.filter { it.mimeType.startsWith("video/") }
        assertEquals(listOf("https://cdn.example/h264-360"), videos.map { it.url })
        assertEquals("https://cdn.example/a", videos.single().audioUrl)
        assertEquals("mp4", videos.single().ext)
    }

    @Test
    fun prefersH264AndFillsTheRestWithAv1() {
        val page = YtDlpFormats.toPage(facebook, av1Playable = true)!!
        val videos = page.options.filter { it.mimeType.startsWith("video/") }.associateBy { it.height }
        assertEquals("https://cdn.example/h264-360", videos.getValue(640).url)
        assertEquals("https://cdn.example/av1-720", videos.getValue(1280).url)
    }

    @Test
    fun vp9GoesWithAacAsMp4() {
        val info = Json.parseToJsonElement(
            """{"title":"x","formats":[
                ${fmt("o", "none", "opus", "webm", null, 128.0, 128.0)},
                ${fmt("aac", "none", "mp4a.40.2", "m4a", null, 128.0, 128.0)},
                ${fmt("vp9", "vp09.00.40.08", "none", "webm", 1080, 2000.0)}
            ]}""",
        ).jsonObject
        val v = YtDlpFormats.toPage(info, av1Playable = false)!!.options.single { it.mimeType.startsWith("video/") }
        assertEquals("mp4", v.ext)
        assertEquals("https://cdn.example/aac", v.audioUrl)
    }

    @Test
    fun dropsHeightsWithNoSavableFormat() {
        val info = Json.parseToJsonElement(
            """{"title":"x","formats":[
                ${fmt("a", "none", "mp4a.40.2", "m4a", null, 128.0, 128.0)},
                ${fmt("av1", "av01.0.08M.08", "none", "mp4", 1080, 2000.0)}
            ]}""",
        ).jsonObject
        val page = YtDlpFormats.toPage(info, av1Playable = false)!!
        assertNull(page.options.firstOrNull { it.mimeType.startsWith("video/") })
    }

    /** The real shape of a Facebook post today: AV1-only DASH video plus progressive sd/hd files with no codec info. */
    @Test
    fun facebookProgressiveFilesAreVideos() {
        val info = Json.parseToJsonElement(
            """{"title":"x","formats":[
                ${fmt("a", "none", "mp4a.40.5", "m4a", null, 77.6, 77.6)},
                {"format_id":"sd","url":"https://cdn.example/sd","protocol":"https","ext":"mp4","quality":-3},
                {"format_id":"hd","url":"https://cdn.example/hd","protocol":"https","ext":"mp4","quality":-2},
                ${fmt("av1-640", "av01.0.01M.08", "none", "mp4", 640, 207.0)},
                ${fmt("av1-1280", "av01.0.05M.08", "none", "mp4", 1280, 1630.0)}
            ]}""",
        ).jsonObject
        val options = YtDlpFormats.toPage(info, av1Playable = false)!!.options
        val videos = options.filter { it.mimeType.startsWith("video/") }
        assertEquals(listOf("https://cdn.example/sd", "https://cdn.example/hd"), videos.map { it.url })
        assertEquals(listOf("Standard (SD)", "High quality (HD)"), videos.map { it.title })
        assertNull(videos.firstOrNull { it.audioUrl != null })
        // Only real audio is listed as music.
        assertEquals(listOf("https://cdn.example/a"), options.filter { it.mimeType.startsWith("audio/") }.map { it.url })
    }

    @Test
    fun cookiesBecomeAHeaderAndUnsizedFilesAreRanked() {
        assertEquals("tt_chain_token=abc; ttwid=1%7Cx", YtDlpFormats.cookieHeader("tt_chain_token=abc; Domain=.tiktok.com; Path=/; Secure; Expires=1790000000; ttwid=1%7Cx; HttpOnly"))
        val info = Json.parseToJsonElement(
            """{"title":"x","formats":[
                {"format_id":"1","url":"https://cdn.example/1","protocol":"https","ext":"mp4","cookies":"k=v; Domain=.example; Path=/"},
                {"format_id":"2","url":"https://cdn.example/2","protocol":"https","ext":"mp4"},
                {"format_id":"3","url":"https://cdn.example/3","protocol":"https","ext":"mp4"}
            ]}""",
        ).jsonObject
        val page = YtDlpFormats.toPage(info, av1Playable = false)!!
        assertEquals("k=v", page.headers["Cookie"])
        assertEquals(listOf("Low quality", "Medium quality", "Best quality"), page.options.filter { it.mimeType.startsWith("video/") }.map { it.title })
    }
}
