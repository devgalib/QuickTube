package com.edureminder.quicktube.core.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsParserTest {
    @Test
    fun master() {
        val m = HlsParser.parse(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aud"
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            https://cdn.example.com/720p.m3u8?token=a,b
            """.trimIndent(),
            "https://site.example/hls/master.m3u8",
        ) as HlsPlaylist.Master
        assertEquals(2, m.variants.size)
        assertEquals("https://site.example/hls/360p/index.m3u8", m.variants[0].url)
        assertEquals(360, m.variants[0].height)
        assertEquals("aud", m.variants[0].audioGroup)
        assertEquals("https://cdn.example.com/720p.m3u8?token=a,b", m.variants[1].url)
        assertEquals("https://site.example/hls/audio/en.m3u8", m.audios.single().url)
    }

    @Test
    fun mediaWithKeyMapAndRanges() {
        val p = HlsParser.parse(
            """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:5
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x0000000000000000000000000000000A
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:1000@0
            video.mp4
            #EXTINF:3.5,
            #EXT-X-BYTERANGE:500
            video.mp4
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://s.example/a/b.m3u8",
        ) as HlsPlaylist.Media
        assertTrue(p.endList)
        assertEquals(7.5, p.durationSec, 0.001)
        assertEquals("https://s.example/a/init.mp4", p.init!!.url)
        assertEquals(0L..999L, p.segments[0].byteRange)
        assertEquals(1000L..1499L, p.segments[1].byteRange)
        assertEquals(5L, p.segments[0].sequence)
        assertEquals("AES-128", p.segments[1].key!!.method)
        assertEquals(10, p.segments[0].key!!.iv!!.last().toInt())
    }
}
