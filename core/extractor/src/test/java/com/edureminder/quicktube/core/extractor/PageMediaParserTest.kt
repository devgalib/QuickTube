package com.edureminder.quicktube.core.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMediaParserTest {
    @Test
    fun qualityListPlayerConfig() {
        val html = """<html><head><meta property="og:title" content="Clip"><meta property="video:duration" content="615"></head><script>
            var flashvars_1 = {"video_duration":615,"mediaDefinitions":[
              {"defaultQuality":false,"format":"hls","videoUrl":"https:\/\/cdn.example\/v\/720P_4000K.mp4\/master.m3u8?t=1","quality":"720"},
              {"defaultQuality":true,"format":"hls","videoUrl":"https:\/\/cdn.example\/v\/480P_2000K.mp4\/master.m3u8?t=1","quality":"480"},
              {"defaultQuality":false,"format":"mp4","videoUrl":"https:\/\/www.example\/video\/get_media?s=abc&v=1","quality":[],"remote":true}
            ],"isVR":0};</script></html>"""
        val p = PageMediaParser.parse(html, "https://www.example/view?v=1")!!
        assertEquals("Clip", p.title)
        assertEquals(615.0, p.durationSec!!, 0.1)
        assertEquals(3, p.candidates.size)
        assertEquals("720p", p.candidates[0].label)
        assertEquals(720, p.candidates[0].height)
        assertTrue(p.candidates[0].forcedHls)
        assertTrue(p.candidates[2].remoteList)
    }

    @Test
    fun html5PlayerSetup() {
        val html = """html5player.setVideoUrlLow('https://cdn.x/low.mp4?e=1');
            html5player.setVideoUrlHigh('https://cdn.x/high.mp4?e=1');
            html5player.setVideoHLS('https://cdn.x/hls.m3u8');"""
        val p = PageMediaParser.parse(html, "https://site.x/video1/")!!
        assertEquals(listOf("SD", "HD", null), p.candidates.map { it.label })
        assertEquals(720, p.candidates[1].height)
    }

    @Test
    fun jsonLdAndJwSources() {
        val html = """<script type="application/ld+json">{"@context":"https://schema.org","@type":"VideoObject","name":"My video",
            "duration":"PT1H2M3S","thumbnailUrl":["https://i.x/t.jpg"],"contentUrl":"https://cdn.x/full.mp4"}</script>
            <script>player.setup({sources:[{file:"https://cdn.x/1080.mp4",label:"1080p"},{file:"https://cdn.x/360.mp4",label:"360p"}]})</script>"""
        val p = PageMediaParser.parse(html, "https://x/v/1")!!
        assertEquals(3723.0, p.durationSec!!, 0.1)
        assertEquals("https://i.x/t.jpg", p.thumbnail)
        assertTrue(p.candidates.any { it.url == "https://cdn.x/full.mp4" })
        assertEquals(1080, p.candidates.first { it.url.endsWith("1080.mp4") }.height)
    }

    @Test
    fun listingPagePreviewsAreIgnored() {
        val html = """<div class="thumb"><video muted loop autoplay src="https://cdn.x/preview1.mp4"></video></div>
            <div class="thumb"><video muted loop><source src="https://cdn.x/preview2.webm" type="video/webm"></video></div>
            <a data-mediabook="https://cdn.x/preview3.mp4">"""
        assertNull(PageMediaParser.parse(html, "https://x/"))
    }

    @Test
    fun sourceListsKeepOnlyThisPagesVideo() {
        val files = { id: Int ->
            """{"id":$id,"video_files":[{"quality":"hd","link":"https://videos.x/video-files/$id/$id-hd_1920_1080_30fps.mp4","width":1920,"height":1080},""" +
                """{"quality":"sd","link":"https://videos.x/video-files/$id/$id-sd_640_360_25fps.mp4","width":640,"height":360}]}"""
        }
        val page = PageMediaParser.parse(files(1093662) + files(555555), "https://x.com/video/waves-on-the-shore-1093662/")!!
        assertEquals(2, page.candidates.size)
        assertTrue(page.candidates.all { it.url.contains("1093662") })
        assertEquals(setOf(1080, 360), page.candidates.map { it.height }.toSet())
        // A listing page (no id in the URL) with many videos: nothing.
        assertNull(PageMediaParser.parse(files(1) + files(2) + files(3), "https://x.com/search/videos/ocean/"))
    }
}
