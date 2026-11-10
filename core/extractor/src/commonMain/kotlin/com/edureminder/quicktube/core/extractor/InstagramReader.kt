package com.edureminder.quicktube.core.extractor

import org.json.JSONArray
import org.json.JSONObject

/** One photo or video of an Instagram post (a carousel has several). */
data class InstagramItem(
    val isVideo: Boolean,
    val url: String,
    val thumbnail: String?,
    /** Size of the file at [url]: the photo's, or the video stream's (scaled from the original to its encoding). */
    val width: Int,
    val height: Int,
)

data class InstagramPost(
    val title: String,
    val owner: String?,
    val thumbnail: String?,
    val items: List<InstagramItem>,
    val durationSec: Double? = null,
    /** The song added to a photo post (Instagram plays it over the photos); null when it has none. */
    val music: String? = null,
)

/**
 * Instagram posts, reels and IGTV from the post's public embed page (`/p/<code>/embed/captioned/`), the page Instagram
 * serves for embedding posts on other sites. It's read through the open browser page (the user's own session), and
 * holds every item: the video file of a video or reel, each photo and video of a carousel, and the photo of a single
 * photo post (only as an <img> there).
 */
object InstagramReader {
    private val POST = Regex("""instagram\.com/(?:[A-Za-z0-9_.]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,})""")
    private val CONTEXT = Regex(""""contextJSON":"((?:[^"\\]|\\.)*)"""")
    private val IMG = Regex("""<img[^>]*class="EmbeddedMediaImage"[^>]*>""")
    private val SRCSET = Regex("""srcset="([^"]+)"""")
    private val SRC = Regex("""\ssrc="([^"]+)"""")
    private val ALT = Regex("""alt="([^"]*)"""")

    /** The post's shortcode, or null when [url] isn't a post, reel or IGTV link. */
    fun shortcode(url: String): String? = POST.find(url)?.groupValues?.get(1)

    fun embedUrl(shortcode: String) = "https://www.instagram.com/p/$shortcode/embed/captioned/"

    /** The app's media API (needs the signed-in session, and [API_HEADERS]): every item, and a photo post's song. */
    fun apiUrl(shortcode: String) = "https://www.instagram.com/api/v1/media/${mediaId(shortcode)}/info/"

    /** Instagram's web app id; the API answers only requests that name it. */
    val API_HEADERS = mapOf("X-IG-App-ID" to "936619743392459", "X-Requested-With" to "XMLHttpRequest")

    /** A shortcode is the media id in base 64 (A–Z a–z 0–9 - _); longer private codes add a suffix after 11 characters. */
    internal fun mediaId(shortcode: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return shortcode.take(11).fold(java.math.BigInteger.ZERO) { acc, c ->
            acc.shiftLeft(6).add(java.math.BigInteger.valueOf(alphabet.indexOf(c).coerceAtLeast(0).toLong()))
        }.toString()
    }

    /** The media API's answer; null when it isn't one (signed out: a login page or an error). */
    fun parseApi(json: String): InstagramPost? = runCatching {
        val m = JSONObject(json).optJSONArray("items")?.optJSONObject(0) ?: return null
        val owner = m.optJSONObject("user")?.optString("username")?.takeIf { it.isNotBlank() }
        val caption = m.optJSONObject("caption")?.optString("text")?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(80)
        val children = m.optJSONArray("carousel_media")
        val nodes = if (children != null && children.length() > 0) (0 until children.length()).mapNotNull { children.optJSONObject(it) } else listOf(m)
        val items = nodes.mapNotNull { n ->
            val image = n.optJSONObject("image_versions2")?.optJSONArray("candidates")?.optJSONObject(0)
            val video = n.optJSONArray("video_versions")?.optJSONObject(0)
            when {
                video != null && video.optString("url").isNotBlank() ->
                    InstagramItem(true, video.optString("url"), image?.optString("url"), video.optInt("width"), video.optInt("height"))
                image != null && image.optString("url").isNotBlank() ->
                    InstagramItem(false, image.optString("url"), image.optString("url"), image.optInt("width"), image.optInt("height"))
                else -> null
            }
        }
        if (items.isEmpty()) return null
        val asset = m.optJSONObject("music_metadata")?.optJSONObject("music_info")?.optJSONObject("music_asset_info")
        val music = asset?.optString("progressive_download_url")?.ifBlank { null } ?: asset?.optString("fast_start_progressive_download_url")?.ifBlank { null }
        val kind = when { items.size > 1 -> "Post"; items[0].isVideo -> "Video"; else -> "Photo" }
        InstagramPost(
            caption ?: owner?.let { "$kind by $it" } ?: "Instagram ${kind.lowercase()}", owner, items[0].thumbnail, items,
            m.optDouble("video_duration").takeIf { !it.isNaN() && it > 0 },
            music = music.takeIf { items.any { !it.isVideo } },
        )
    }.getOrNull()

    /** Null when the page holds no media (removed, private, or not an embed page). */
    fun parseEmbed(html: String): InstagramPost? {
        CONTEXT.find(html)?.let { m ->
            val media = runCatching {
                JSONObject(unescape(m.groupValues[1])).optJSONObject("gql_data")?.optJSONObject("shortcode_media")
            }.getOrNull()
            if (media != null) fromMedia(media)?.let { return it }
        }
        // A single photo isn't in the JSON: take the biggest size of the post's image.
        val tag = IMG.find(html)?.value ?: return null
        val url = SRCSET.find(tag)?.groupValues?.get(1)?.let(::largestFromSrcset) ?: SRC.find(tag)?.groupValues?.get(1)?.let(::html)
            ?: return null
        val owner = ALT.find(tag)?.groupValues?.get(1)?.let(::html)?.substringAfter("@", "")?.trim()?.takeIf { it.isNotBlank() }
        return InstagramPost(owner?.let { "Photo by $it" } ?: "Instagram photo", owner, url, listOf(InstagramItem(false, url, url, 0, 0)))
    }

    private fun fromMedia(m: JSONObject): InstagramPost? {
        val owner = m.optJSONObject("owner")?.optString("username")?.takeIf { it.isNotBlank() }
        val caption = m.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")
            ?.optString("text")?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(80)
        val children = m.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        val nodes = if (children != null && children.length() > 0) (0 until children.length()).mapNotNull { children.optJSONObject(it)?.optJSONObject("node") }
        else listOf(m)
        val items = nodes.mapNotNull(::item)
        if (items.isEmpty()) return null
        val kind = when {
            items.size > 1 -> "Post"
            items[0].isVideo -> "Video"
            else -> "Photo"
        }
        val title = caption?.takeIf { it.isNotBlank() } ?: owner?.let { "$kind by $it" } ?: "Instagram ${kind.lowercase()}"
        return InstagramPost(
            title, owner, m.optString("display_url").ifBlank { items[0].thumbnail }, items,
            m.optDouble("video_duration").takeIf { !it.isNaN() && it > 0 },
        )
    }

    private fun item(n: JSONObject): InstagramItem? {
        val dims = n.optJSONObject("dimensions")
        val w = dims?.optInt("width") ?: 0
        val h = dims?.optInt("height") ?: 0
        val display = biggest(n.optJSONArray("display_resources")) ?: n.optString("display_url").ifBlank { null }
        if (n.optBoolean("is_video") && n.optString("video_url").isNotBlank()) {
            val url = n.optString("video_url")
            // The post's dimensions are the upload's; the file is often a smaller encoding (named in the URL).
            val short = encodedShortSide(url)
            val (vw, vh) = if (short != null && w > 0 && h > 0 && short < minOf(w, h)) {
                val f = short.toDouble() / minOf(w, h)
                (w * f).toInt().roundEven() to (h * f).toInt().roundEven()
            } else w to h
            return InstagramItem(true, url, display, vw, vh)
        }
        return display?.let { InstagramItem(false, it, it, w, h) }
    }

    private fun Int.roundEven() = this + (this and 1)

    private val EFG = Regex("""[?&]efg=([^&]+)""")
    private val ENCODED_SIZE = Regex("""\.(\d{3,4})\.""")

    /**
     * Instagram video URLs carry an `efg` parameter: base64 JSON whose `vencode_tag` names the encoding, e.g.
     * "xpv_progressive.INSTAGRAM.CLIPS.C3.720.dash_baseline_1_v1" → 720 (the short side). Null when it doesn't say.
     */
    internal fun encodedShortSide(url: String): Int? = runCatching {
        val efg = EFG.find(url)?.groupValues?.get(1) ?: return null
        val raw = java.net.URLDecoder.decode(efg, "UTF-8").replace('-', '+').replace('_', '/')
        val json = String(java.util.Base64.getDecoder().decode(raw + "=".repeat((4 - raw.length % 4) % 4)))
        ENCODED_SIZE.find(JSONObject(json).optString("vencode_tag"))?.groupValues?.get(1)?.toInt()
    }.getOrNull()

    private fun biggest(res: JSONArray?): String? {
        if (res == null) return null
        return (0 until res.length()).mapNotNull { res.optJSONObject(it) }.maxByOrNull { it.optInt("config_width") }?.optString("src")?.ifBlank { null }
    }

    /** "url1 640w, url2 1080w" → the widest url. */
    internal fun largestFromSrcset(srcset: String): String? = html(srcset).split(",").mapNotNull { part ->
        val bits = part.trim().split(Regex("\\s+"))
        val url = bits.firstOrNull()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
        url to (bits.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0)
    }.maxByOrNull { it.second }?.first

    private fun html(s: String) = s.replace("&amp;", "&").replace("&#064;", "@").replace("&quot;", "\"").replace("&#039;", "'")

    /** The JSON string literal's contents → the JSON text it holds. */
    private fun unescape(s: String): String = JSONArray("[\"$s\"]").getString(0)
}
