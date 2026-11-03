package com.edureminder.quicktube.core.extractor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramReaderTest {
    /** The embed page carries the post as a JSON string inside a script, like Instagram's. */
    private fun page(media: JSONObject): String {
        val ctx = JSONObject().put("gql_data", JSONObject().put("shortcode_media", media)).toString()
        return """<script>{"require":[["X",{"contextJSON":${JSONObject.quote(ctx)}}]]}</script>"""
    }

    private fun res(vararg widths: Int) = org.json.JSONArray().apply {
        widths.forEach { w -> put(JSONObject().put("src", "https://cdn/$w.jpg").put("config_width", w)) }
    }

    @Test
    fun recognisesPostReelAndIgtvLinks() {
        assertEquals("DfAWxdIFmVB", InstagramReader.shortcode("https://www.instagram.com/natgeo/reel/DfAWxdIFmVB/?igsh=x"))
        assertEquals("DeuOR-CjKE9", InstagramReader.shortcode("https://instagram.com/p/DeuOR-CjKE9/"))
        assertEquals("Abc_12345", InstagramReader.shortcode("https://www.instagram.com/tv/Abc_12345"))
        assertNull(InstagramReader.shortcode("https://www.instagram.com/natgeo/"))
    }

    @Test
    fun readsAVideo() {
        val media = JSONObject().put("__typename", "GraphVideo").put("is_video", true).put("video_url", "https://cdn/v.mp4")
            .put("display_url", "https://cdn/poster.jpg").put("owner", JSONObject().put("username", "natgeo"))
        val post = InstagramReader.parseEmbed(page(media))!!
        assertEquals(1, post.items.size)
        assertTrue(post.items[0].isVideo)
        assertEquals("https://cdn/v.mp4", post.items[0].url)
        assertEquals("Video by natgeo", post.title)
    }

    @Test
    fun readsEveryCarouselItemAtTheBiggestSize() {
        val kids = org.json.JSONArray()
            .put(JSONObject().put("node", JSONObject().put("is_video", false).put("display_resources", res(640, 1080, 750))))
            .put(JSONObject().put("node", JSONObject().put("is_video", true).put("video_url", "https://cdn/k.mp4").put("display_url", "https://cdn/k.jpg")))
        val media = JSONObject().put("__typename", "GraphSidecar").put("edge_sidecar_to_children", JSONObject().put("edges", kids))
        val post = InstagramReader.parseEmbed(page(media))!!
        assertEquals(listOf("https://cdn/1080.jpg", "https://cdn/k.mp4"), post.items.map { it.url })
        assertEquals(listOf(false, true), post.items.map { it.isVideo })
    }

    @Test
    fun readsASinglePhotoFromItsImage() {
        val html = """<script>{"contextJSON":null}</script><img class="EmbeddedMediaImage" alt="Instagram post shared by &#064;natgeo" """ +
            """src="https://cdn/s.jpg?a=1&amp;b=2" srcset="https://cdn/640.jpg?x=1&amp;y=2 640w,https://cdn/1080.jpg?x=1&amp;y=2 1080w,https://cdn/750.jpg 750w">"""
        val post = InstagramReader.parseEmbed(html)!!
        assertEquals("https://cdn/1080.jpg?x=1&y=2", post.items.single().url)
        assertEquals("Photo by natgeo", post.title)
    }

    @Test
    fun videoSizeComesFromItsEncodingNotTheUpload() {
        val url = "https://cdn/v.mp4?efg=eyJ2ZW5jb2RlX3RhZyI6Inhwdl9wcm9ncmVzc2l2ZS5JTlNUQUdSQU0uQ0xJUFMuQzMuNzIwLmRhc2hfYmFzZWxpbmVfMV92MSIsImR1cmF0aW9uX3MiOjY4fQ&_nc_ht=x"
        assertEquals(720, InstagramReader.encodedShortSide(url))
        val media = JSONObject().put("is_video", true).put("video_url", url)
            .put("dimensions", JSONObject().put("width", 1080).put("height", 1920))
        val item = InstagramReader.parseEmbed(page(media))!!.items.single()
        assertEquals(720 to 1280, item.width to item.height)
    }

    @Test
    fun nothingOnOtherPages() {
        assertNull(InstagramReader.parseEmbed("<html><body>Log in</body></html>"))
    }
}
