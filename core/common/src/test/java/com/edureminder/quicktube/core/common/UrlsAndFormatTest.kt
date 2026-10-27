package com.edureminder.quicktube.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsAndFormatTest {
    @Test
    fun youtubeIds() {
        assertEquals("dQw4w9WgXcQ", Urls.youtubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("1PtOb0pMYW8", Urls.youtubeVideoId("https://m.youtube.com/watch?v=1PtOb0pMYW8&list=RD1&start_radio=1&pp=x"))
        assertEquals("1PtOb0pMYW8", Urls.youtubeVideoId("https://m.youtube.com/watch?feature=share&v=1PtOb0pMYW8"))
        assertEquals("jNQXAC9IVRw", Urls.youtubeVideoId("https://youtu.be/jNQXAC9IVRw?si=abc"))
        assertEquals("abcdefghijk", Urls.youtubeVideoId("https://youtube.com/shorts/abcdefghijk?feature=share"))
        assertNull(Urls.youtubeVideoId("https://www.youtube.com/playlist?list=PL123"))
    }

    @Test
    fun extractsUrlFromSharedText() {
        assertEquals("https://fb.watch/abc/", Urls.extractUrl("Check this out! https://fb.watch/abc/"))
        assertEquals("https://vt.tiktok.com/ZS123/", Urls.extractUrl("https://vt.tiktok.com/ZS123/ Shared via TikTok"))
        assertNull(Urls.extractUrl("no links here"))
    }

    @Test
    fun urlDetection() {
        assertTrue(Urls.looksLikeUrl("youtube.com/watch?v=x"))
        assertTrue(Urls.looksLikeUrl("https://x.com/a/status/1"))
        assertFalse(Urls.looksLikeUrl("coldplay yellow"))
        assertEquals("https://example.com", Urls.normalize("example.com"))
    }

    @Test
    fun platforms() {
        assertEquals(Platform.TIKTOK, Urls.platformOf("https://www.tiktok.com/@a/video/1"))
        assertEquals(Platform.FACEBOOK, Urls.platformOf("https://fb.watch/xyz"))
        assertEquals(Platform.TWITTER, Urls.platformOf("https://x.com/a/status/1"))
        assertEquals(Platform.YOUTUBE, Urls.platformOf("https://youtu.be/abc"))
    }

    @Test
    fun safeFileNames() {
        assertEquals("a_b_c", Urls.safeFileName("a/b:c"))
        assertEquals("download", Urls.safeFileName("   "))
        // Long Bengali + emoji title (3–4 bytes per character): must fit Android's 255-byte name limit with suffixes.
        val long = "350K views · 1.3K reactions | আমি এই বিল্ডিংয়ে জীবনের ঝুঁকি নিয়ে নিজ থেকে সাত পর্যন্ত উঠছি কারণ একটাই সমুদ্রের লাইট🕹️".repeat(3)
        val name = Urls.safeFileName(long)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 150)
        assertTrue((name + "(1080p) (12).webm.part.chunks.tmp").toByteArray(Charsets.UTF_8).size < 255)
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8)) // no broken character
    }

    @Test
    fun formatting() {
        assertEquals("1.0 KB", Format.bytes(1024))
        assertEquals("572.5 MB", Format.bytes(600_309_760))
        assertEquals("03:33", Format.duration(213))
        assertEquals("1:00:21", Format.duration(3621))
        assertEquals("1.8B", Format.count(1_800_000_000))
        assertEquals("440K", Format.count(440_000))
    }
}
