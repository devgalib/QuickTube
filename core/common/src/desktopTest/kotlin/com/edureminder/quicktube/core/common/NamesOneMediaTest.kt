package com.edureminder.quicktube.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesOneMediaTest {
    @Test
    fun onePostPagesVersusFeeds() {
        assertTrue(Urls.namesOneMedia("https://www.tiktok.com/@khaby.lame/video/7301234567890123456"))
        assertTrue(Urls.namesOneMedia("https://www.tiktok.com/@user/photo/7301234567890123456"))
        assertTrue(Urls.namesOneMedia("https://vm.tiktok.com/ZMabc123/"))
        assertFalse(Urls.namesOneMedia("https://www.tiktok.com/@khaby.lame"))
        assertFalse(Urls.namesOneMedia("https://www.tiktok.com/foryou"))
        assertTrue(Urls.namesOneMedia("https://www.instagram.com/reel/C1abcDEF/"))
        assertTrue(Urls.namesOneMedia("https://www.instagram.com/p/C1abcDEF/?img_index=2"))
        assertFalse(Urls.namesOneMedia("https://www.instagram.com/someone/"))
        assertFalse(Urls.namesOneMedia("https://www.instagram.com/someone/reels/"))
        assertTrue(Urls.namesOneMedia("https://www.youtube.com/watch?v=JGwWNGJdvx8"))
        assertFalse(Urls.namesOneMedia("https://m.youtube.com/"))
        assertTrue(Urls.namesOneMedia("https://www.facebook.com/watch/?v=1234567890"))
        assertFalse(Urls.namesOneMedia("https://m.facebook.com/"))
        assertTrue(Urls.namesOneMedia("https://x.com/user/status/123"))
        assertFalse(Urls.namesOneMedia("https://x.com/home"))
        assertTrue(Urls.namesOneMedia("https://example.com/videos/42"))
        assertFalse(Urls.namesOneMedia("https://example.com/"))
        assertFalse(Urls.namesOneMedia(null))
    }

    @Test
    fun tiktokFilesAreKeyedByTheirId() {
        val a = "https://v16-webapp-prime.tiktok.com/video/tos/maliva/tos-maliva-ve-0068c799-us/oUhfAQAbCdEfGhIjKlMn/?a=1988&mime_type=video_mp4"
        assertEquals("https://v16-webapp-prime.tiktok.com/video/tos/maliva/tos-maliva-ve-0068c799-us/oUhfAQAbCdEfGhIjKlMn", Urls.mediaFileKey(a))
        assertNull(Urls.mediaFileKey("https://example.com/watch?id=1"))
    }
}

class ExtractUrlsTest {
    @org.junit.Test
    fun everyLinkOnceInOrder() {
        val text = """
            My list:
            https://www.youtube.com/watch?v=abc123DEF45,
            youtu.be/xyz98765432
            https://youtu.be/abc123DEF45
            https://youtube.com/watch?v=abc123DEF45
            see https://www.tiktok.com/@a/video/123 and https://www.instagram.com/reel/C1/.
            not a link
            https://m.facebook.com/watch?v=55
            https://facebook.com/watch?v=55
        """.trimIndent()
        org.junit.Assert.assertEquals(
            listOf(
                "https://www.youtube.com/watch?v=abc123DEF45", "https://youtu.be/xyz98765432", "https://www.tiktok.com/@a/video/123",
                "https://www.instagram.com/reel/C1/", "https://m.facebook.com/watch?v=55",
            ),
            Urls.extractUrls(text),
        )
    }
}
