package com.edureminder.quicktube.core.extractor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailymotionReaderTest {
    @Test
    fun findsTheVideoId() {
        assertEquals("xbuznjy", DailymotionReader.videoId("https://www.dailymotion.com/video/xbuznjy"))
        assertEquals("x5kesuj", DailymotionReader.videoId("http://www.dailymotion.com/video/x5kesuj_office-christmas-party_news"))
        assertEquals("x8fmi9x", DailymotionReader.videoId("https://dai.ly/x8fmi9x"))
        assertEquals("x8fmi9x", DailymotionReader.videoId("https://www.dailymotion.com/embed/video/x8fmi9x?autoplay=1"))
        assertEquals("x8fmi9x", DailymotionReader.videoId("https://m.dailymotion.com/video/x8fmi9x"))
        assertEquals("x8fmi9x", DailymotionReader.videoId("https://geo.dailymotion.com/player/xid0t.html?video=x8fmi9x"))
        assertNull(DailymotionReader.videoId("https://www.dailymotion.com/"))
        assertNull(DailymotionReader.videoId("https://www.dailymotion.com/us"))
        assertNull(DailymotionReader.videoId("https://example.com/video/x8fmi9x"))
    }

    @Test
    fun readsTitleThumbnailAndStream() {
        val m = DailymotionReader.parse(JSONObject("""{"title":"Lens","duration":40,
            "thumbnails":{"60":"https://s1.dmcdn.net/x60","1080":"https://s1.dmcdn.net/x1080","720":"https://s1.dmcdn.net/x720"},
            "qualities":{"auto":[{"type":"application/x-mpegURL","url":"https://cdndirector.dailymotion.com/m.m3u8?sec=1"}]}}"""))!!
        assertEquals("Lens", m.title)
        assertEquals("https://s1.dmcdn.net/x1080", m.thumbnail)
        assertEquals(40.0, m.durationSec!!, 0.0)
        assertEquals("https://cdndirector.dailymotion.com/m.m3u8?sec=1", m.hlsUrl)
    }

    @Test
    fun skipsErrorsAndProtectedVideos() {
        assertNull(DailymotionReader.parse(JSONObject("""{"error":{"code":404}}""")))
        assertNull(DailymotionReader.parse(JSONObject("""{"protected_delivery":true,"qualities":{"auto":[{"type":"application/x-mpegURL","url":"u"}]}}""")))
    }
}
