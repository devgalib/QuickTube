package com.edureminder.quicktube.core.extractor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VimeoReaderTest {
    @Test
    fun linksToVideoIds() {
        assertEquals("76979871" to null, VimeoReader.videoId("https://vimeo.com/76979871"))
        assertEquals("76979871" to null, VimeoReader.videoId("https://vimeo.com/channels/staffpicks/76979871"))
        assertEquals("123456789" to "abcdef1234", VimeoReader.videoId("https://vimeo.com/123456789/abcdef1234"))
        assertEquals("123456789" to "ff00ff00", VimeoReader.videoId("https://player.vimeo.com/video/123456789?h=ff00ff00&badge=0"))
        assertNull(VimeoReader.videoId("https://vimeo.com/staffpicks"))
        assertNull(VimeoReader.videoId("https://youtube.com/watch?v=76979871"))
    }

    @Test
    fun configGivesHlsTitleAndLength() {
        val json = JSONObject("""{"request":{"files":{"dash":{},"progressive":[],"hls":{"default_cdn":"fastly_skyfire",
            "cdns":{"akfire_interconnect_quic":{"url":"https://a/master.m3u8"},"fastly_skyfire":{"url":"https://f/master.m3u8"}}}}},
            "video":{"title":"The New Vimeo Player","duration":62,"thumbs":{"640":"https://i/640.jpg","base":"https://i/base"}}}""")
        val m = VimeoReader.parse(json)!!
        assertEquals("https://f/master.m3u8", m.hlsUrl)
        assertEquals("The New Vimeo Player", m.title)
        assertEquals(62.0, m.durationSec!!, 0.0)
        assertNull(VimeoReader.parse(JSONObject("""{"request":{"files":{}}}""")))
    }
}
