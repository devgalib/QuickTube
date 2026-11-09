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

    @Test
    fun videoIdsOfReelAndVideoAddresses() {
        assertEquals("2634990543581315", FacebookReader.videoId("https://m.facebook.com/reel/2634990543581315/?referral_source=native_in_feed_unit"))
        assertEquals("1234567890", FacebookReader.videoId("https://www.facebook.com/somepage/videos/1234567890/"))
        assertEquals("1234567890", FacebookReader.videoId("https://www.facebook.com/watch/?v=1234567890"))
        assertEquals(null, FacebookReader.videoId("https://m.facebook.com/"))
        assertEquals(null, FacebookReader.videoId("https://example.com/reel/2634990543581315/"))
    }

    /** A signed-in page: the data of several videos (next reels, the feed), each list followed by its video_id. */
    private fun video(id: String, seconds: Int) =
        """{"id":"$id","playable_duration_in_ms":${seconds * 1000}},{"representations":[${rep("a$id", "audio/mp4", "mp4a.40.5", 0, 0, 64000)},
            ${rep("v$id", "video/mp4", "avc1.64001f", 720, 1280, 1200000)}],"video_id":"$id"}"""

    private val signedInPage = "<html><script>[${video("1111111111", 15)},${video("2222222222", 26)},${video("3333333333", 195)}]</script></html>"

    @Test
    fun takesTheVideoTheAddressNames() {
        val p = FacebookReader.parse(signedInPage, "https://m.facebook.com/reel/2222222222/?referral_source=feed", av1Playable = true)!!
        val v = p.options.first { it.kind == FormatKind.VIDEO }
        assertTrue(v.url.contains("v2222222222.mp4"))
        assertTrue(v.audioUrl!!.contains("a2222222222.mp4"))
        assertEquals(26.0, p.durationSec!!, 0.01)
    }

    @Test
    fun aPostWithOtherVideosUnderItGivesNothing() {
        // A photo post: the page's videos are the feed under it, none of them the post.
        assertEquals(null, FacebookReader.parse(signedInPage, "https://www.facebook.com/61590775260449/posts/122142044991359175/", av1Playable = true))
        // The named video isn't on the page.
        assertEquals(null, FacebookReader.parse(signedInPage, "https://www.facebook.com/reel/9999999999/", av1Playable = true))
    }

    @Test
    fun aPageWithOneVideoNeedsNoId() {
        val p = FacebookReader.parse("<html><script>[${video("4444444444", 40)}]</script></html>", "https://www.facebook.com/somepage/posts/123/", av1Playable = true)!!
        assertTrue(p.options.first { it.kind == FormatKind.VIDEO }.url.contains("v4444444444.mp4"))
    }

    @Test
    fun captionsBecomeTidyTitles() {
        assertEquals("Pretty 🔥❤️ | Aneet Padda Fanpage", FacebookReader.cleanTitle("Pretty 🔥❤️\n\n#aneetpadda #ahaanpnadey #saiyaara#foryou #life | Aneet Padda Fanpage"))
        assertEquals("Wait for the end😂 | Taz's Vibe", FacebookReader.cleanTitle("Wait for the end😂\n.\n.\n.\n.\n #fbreels #trending #fyp | Taz's Vibe"))
        assertEquals("আমি ছাড়া এটা আর কেউ না😆😆 | Suhasinee Mithu", FacebookReader.cleanTitle("আমি ছাড়া এটা আর কেউ না😆😆 | Suhasinee Mithu"))
    }
}
