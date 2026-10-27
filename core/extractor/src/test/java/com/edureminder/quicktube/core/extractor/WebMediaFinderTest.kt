package com.edureminder.quicktube.core.extractor

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMediaFinderTest {
    private val finder = WebMediaFinder(OkHttpClient())

    @Test
    fun readsOpenGraphVideo() {
        val html = """<html><head><title>x</title>
            <meta property="og:title" content="My &amp; clip">
            <meta property="og:image" content="https://cdn.example.com/t.jpg">
            <meta property="og:video:secure_url" content="https://cdn.example.com/v.mp4?sig=1">
            </head></html>"""
        val page = finder.parse(html, "https://example.com/p")
        assertEquals("My & clip", page.title)
        assertEquals("https://cdn.example.com/t.jpg", page.thumbnail)
        assertEquals(listOf("https://cdn.example.com/v.mp4?sig=1"), page.candidates.map { it.url })
    }

    /** Logged-out Threads pages carry Meta's interface sound as an .mp4; it must not pass for the post's video. */
    @Test
    fun ignoresSiteInterfaceAssets() {
        val html = """<html><head><title>Threads</title></head><body>
            <script>{"url":"https:\/\/static.cdninstagram.com\/rsrc.php\/y_\/r\/i8Ek3A1myhR.mp4"}</script>
            <video src="https://static.xx.fbcdn.net/rsrc.php/v3/abc.mp4"></video></body></html>"""
        assertTrue(finder.parse(html, "https://www.threads.com/share/x/").candidates.isEmpty())
        assertTrue(WebMediaFinder.isSiteAsset("https://static.cdninstagram.com/rsrc.php/y_/r/a.mp4"))
        assertTrue(!WebMediaFinder.isSiteAsset("https://scontent.cdninstagram.com/o1/v/t16/f2/m69/clip.mp4"))
    }

    @Test
    fun readsFacebookStyleInlineJson() {
        val html = """{"browser_native_hd_url":"https:\/\/video.fbcdn.net\/hd.mp4?a=1&b=2","browser_native_sd_url":"https:\/\/video.fbcdn.net\/sd.mp4"}"""
        val page = finder.parse(html, "https://www.facebook.com/watch/?v=1")
        assertEquals("https://video.fbcdn.net/hd.mp4?a=1&b=2", page.candidates.first().url)
        assertEquals(720, page.candidates.first().height)
        assertTrue(page.candidates.any { it.url == "https://video.fbcdn.net/sd.mp4" })
    }

    @Test
    fun mimeAndExtension() {
        assertEquals("audio/mp4", WebMediaFinder.mimeFor("https://a/b.m4a?x=1"))
        assertEquals("mp3", WebMediaFinder.extFor("audio/mpeg"))
        assertEquals("video/mp4", WebMediaFinder.mimeFor("https://a/b"))
    }
}
