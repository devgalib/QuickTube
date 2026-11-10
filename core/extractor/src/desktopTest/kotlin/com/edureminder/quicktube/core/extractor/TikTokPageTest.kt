package com.edureminder.quicktube.core.extractor

import org.junit.Assert.assertEquals
import org.junit.Test

class TikTokPageTest {
    private fun page(desc: String) = """<html><head><title>TikTok - Make Your Day</title></head><body><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__">
        {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"6718335390845095173","desc":"$desc",
        "author":{"uniqueId":"scout2015"},"video":{"duration":10,"cover":"https://p16.tiktokcdn.com/c.jpg",
        "playAddr":"https://v16-webapp-prime.tiktok.com/video/tos/maliva/abcdefghijklmnopqrst/?a=1"}}}},
        "webapp.related":{"itemList":[{"desc":"another video","video":{"playAddr":"https://v16/other"}}]}}}</script></body></html>"""

    @Test
    fun theVideoPagesOwnCaptionCoverAndStream() {
        val p = PageMediaParser.parse(page("Scramble up ur name #foryoupage"), "https://www.tiktok.com/@scout2015/video/6718335390845095173")!!
        assertEquals("Scramble up ur name #foryoupage", p.title)
        assertEquals("https://p16.tiktokcdn.com/c.jpg", p.thumbnail)
        assertEquals(10.0, p.durationSec!!, 0.0)
        assertEquals("https://v16-webapp-prime.tiktok.com/video/tos/maliva/abcdefghijklmnopqrst/?a=1", p.candidates.first().url)
    }

    @Test
    fun noCaptionNamesTheAuthor() {
        assertEquals("TikTok video by @scout2015", PageMediaParser.parse(page(""), "https://www.tiktok.com/@scout2015/video/1")!!.title)
    }
}

class TikTokGoneTest {
    @org.junit.Test
    fun removedVideoIsRecognised() {
        org.junit.Assert.assertTrue(PageMediaParser.tikTokGone("""{"webapp.video-detail":{"statusCode":10204,"statusMsg":"item doesn't exist"}}"""))
        org.junit.Assert.assertFalse(PageMediaParser.tikTokGone("""{"webapp.video-detail":{"statusCode":0,"statusMsg":"","itemInfo":{}}}"""))
        org.junit.Assert.assertFalse(PageMediaParser.tikTokGone("<html>no data</html>"))
        // Throttled or checked: not removed.
        org.junit.Assert.assertFalse(PageMediaParser.tikTokGone("""{"videoDetail":{"statusCode":10000,"statusMessage":""}}"""))
        // The mobile page.
        org.junit.Assert.assertTrue(PageMediaParser.tikTokGone("""<script id="api-data" type="application/json">{"videoDetail":{"statusCode":10204,"statusMessage":""}}</script>"""))
    }
}
