package com.edureminder.quicktube.core.extractor

import com.edureminder.quicktube.core.model.FormatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FacebookReaderTest {
    private fun rep(id: String, mime: String, codec: String, w: Int, h: Int, bw: Long) =
        """{"representation_id":"$id","mime_type":"$mime","codecs":"$codec","base_url":"https:\/\/video.fbcdn.net\/$id.mp4?x=1","bandwidth":$bw,"height":$h,"width":$w}"""

    private val page = """<html><head><meta property="og:title" content="350K views · 1.3K reactions | My clip | Facebook">
        <meta property="og:image" content="https://scontent.fbcdn.net/t.jpg"></head><body><script>
        {"playable_duration_in_ms":10000,"browser_native_sd_url":"https:\/\/video.fbcdn.net\/sd.mp4",
         "all_video_dash_prefetch_representations":[{"representations":[
          ${rep("a1", "audio/mp4", "mp4a.40.5", 0, 0, 64000)},
          ${rep("v480", "video/mp4", "avc1.4d401e", 480, 854, 300000)},
          ${rep("v720av1", "video/mp4", "av01.0.05M.08", 720, 1280, 900000)},
          ${rep("v720", "video/mp4", "avc1.64001f", 720, 1280, 1200000)},
          ${rep("v1080", "video/mp4", "av01.0.08M.08", 1080, 1920, 2000000)}
         ]}]}</script></body></html>"""

    @Test
    fun readsTheWholeLadder() {
        val p = FacebookReader.parse(page, "https://www.facebook.com/x", av1Playable = true)!!
        assertEquals("My clip", p.title)
        val videos = p.options.filter { it.kind == FormatKind.VIDEO }
        // The two bitrates at 720 px are two choices (the lower one looks like 640p).
        assertEquals(listOf("Standard (480p)", "Standard (640p)", "High quality (720p)", "Full HD (1080p)"), videos.map { it.title })
        assertTrue(videos[2].url.contains("v720.mp4"))
        assertTrue(videos.all { it.audioUrl!!.contains("a1.mp4") })
        assertEquals(1_500_000L, videos[2].sizeBytes) // 1.2 Mbit/s × 10 s
        assertEquals(1, p.options.count { it.kind == FormatKind.AUDIO })
    }

    @Test
    fun skipsAv1WhereItDoesntPlay() {
        val p = FacebookReader.parse(page, "https://www.facebook.com/x", av1Playable = false)!!
        assertEquals(listOf(480, 720), p.options.filter { it.kind == FormatKind.VIDEO }.map { it.height })
    }

    /** Facebook's real pattern: five bitrates at 720 px plus 1080/1440 (bitrates from a live page). */
    @Test
    fun namesSameSizeBitratesLikeOtherDownloaders() {
        val five = listOf(392392L, 513599L, 735468L, 1015002L, 1513832L)
            .joinToString(",") { rep("v$it", "video/mp4", "av01.0.05M.08", 720, 1424, it) }
        val html = """{"playable_duration_in_ms":10000,"representations":[${rep("a", "audio/mp4", "mp4a.40.5", 0, 0, 60180)},$five,
            ${rep("v1080", "video/mp4", "av01.0.08M.08", 1080, 2136, 2148724)},${rep("v1440", "video/mp4", "av01.0.08M.08", 1440, 2848, 2413872)}]}"""
        val p = FacebookReader.parse(html, "https://www.facebook.com/x", av1Playable = true)!!
        assertEquals(listOf(480, 540, 640, 720, 840, 1080, 1440), p.options.filter { it.kind == FormatKind.VIDEO }.map { it.height })
    }

    @Test
    fun decodesNumericEntitiesInTitles() {
        assertEquals("ফারহান 🎮 & co", FacebookReader.htmlDecode("&#x9ab;&#x9be;&#x9b0;&#x9b9;&#x9be;&#x9a8; &#127918; &amp; co"))
    }

    @Test
    fun recognisesFacebookLinks() {
        assertTrue(FacebookReader.isFacebook("https://www.facebook.com/share/1CTXvB4igB/"))
        assertTrue(FacebookReader.isFacebook("https://fb.watch/abc/"))
        assertTrue(FacebookReader.isFacebook("https://m.facebook.com/watch/?v=1"))
        assertTrue(!FacebookReader.isFacebook("https://www.instagram.com/reel/x/"))
    }

    /** Local check against a real saved page: FB_PAGE=/path/page.html (not committed: it holds people's names). */
    @Test
    fun realPage() {
        val f = System.getenv("FB_PAGE")?.let(::File)
        assumeTrue(f != null && f.exists())
        val p = FacebookReader.parse(f!!.readText(), "https://www.facebook.com/x", av1Playable = true)
        assertNotNull(p)
        println("REAL: " + p!!.options.joinToString { "${it.title} ${it.totalSize}" })
    }
}
