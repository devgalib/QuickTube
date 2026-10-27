package com.edureminder.quicktube.core.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class FoundMedia(
    val url: String,
    val mimeType: String,
    val quality: String? = null,
    val height: Int = 0,
    val sizeBytes: Long = -1,
)

data class FoundPage(
    val title: String,
    val thumbnail: String?,
    val pageUrl: String,
    val media: List<FoundMedia>,
    val headers: Map<String, String> = emptyMap(),
    val durationSec: Double? = null,
    /** Structured sources (quality lists etc.) when the page declares them. */
    val declared: List<StreamCandidate> = emptyList(),
) {
    val candidates: List<StreamCandidate>
        get() = declared.ifEmpty { media.map { StreamCandidate(it.url, it.quality, it.height) } }
}

/**
 * Best-effort media discovery for regular web pages (Facebook, TikTok, X, Instagram, blogs…):
 * reads Open Graph tags, inline JSON players and direct media links.
 */
class WebMediaFinder(private val client: OkHttpClient) {
    private val POST = Regex("""reddit\.com/r/([A-Za-z0-9_]+)/comments/([a-z0-9]+)""")


    suspend fun find(pageUrl: String): FoundPage? = withContext(Dispatchers.IO) {
        if (DIRECT_MEDIA.containsMatchIn(pageUrl.substringBefore('?'))) {
            return@withContext FoundPage(pageUrl.substringAfterLast('/').substringBefore('?'), null, pageUrl, listOf(FoundMedia(pageUrl, mimeFor(pageUrl))))
        }
        twitter(pageUrl)?.let { return@withContext it }
        reddit(pageUrl)?.let { return@withContext it }
        // Some sites (Facebook) refuse the mobile browser outright; try the desktop one then.
        val mobile = runCatching { get(pageUrl, MOBILE_UA) }.getOrNull()
            ?: return@withContext runCatching { get(pageUrl, DESKTOP_UA) }.getOrNull()?.parsed(DESKTOP_UA)?.takeIf { it.candidates.isNotEmpty() }
        val found = mobile.parsed(MOBILE_UA)
        if (found.candidates.isNotEmpty()) return@withContext found
        // Some sites only expose media to desktop browsers.
        val desktop = runCatching { get(pageUrl, DESKTOP_UA) }.getOrNull() ?: return@withContext found.takeIf { it.media.isNotEmpty() }
        desktop.parsed(DESKTOP_UA).takeIf { it.candidates.isNotEmpty() }
    }

    private class Fetched(val body: String, val finalUrl: String, val cookies: String?)

    /**
     * The media URLs a page hands out are often only served to the same "browser": e.g. TikTok's video
     * server answers 403 without the cookies the page set. Downloads get them, with the same User-Agent.
     */
    private fun Fetched.parsed(ua: String): FoundPage {
        val page = parse(body, finalUrl)
        return page.copy(headers = page.headers + ("User-Agent" to ua) + (cookies?.let { mapOf("Cookie" to it) } ?: emptyMap()))
    }

    fun parse(html: String, pageUrl: String): FoundPage {
        PageMediaParser.parse(html, pageUrl)?.let { pm ->
            val real = pm.candidates.filterNot { isSiteAsset(it.url) }
            if (real.isNotEmpty()) return FoundPage(pm.title ?: "video", pm.thumbnail, pageUrl, emptyList(), mapOf("Referer" to pageUrl), pm.durationSec, real)
        }
        val title = meta(html, "og:title") ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()
            ?: "video"
        val thumb = meta(html, "og:image") ?: meta(html, "twitter:image")
        val media = linkedMapOf<String, FoundMedia>()
        fun add(raw: String?, quality: String? = null, height: Int = 0) {
            val u = raw?.let(::unescape)?.takeIf { it.startsWith("http") && !isSiteAsset(it) } ?: return
            if (u !in media) media[u] = FoundMedia(u, mimeFor(u), quality, height)
        }
        // Facebook
        add(jsonString(html, "browser_native_hd_url"), "HD", 720)
        add(jsonString(html, "playable_url_quality_hd"), "HD", 720)
        add(jsonString(html, "browser_native_sd_url"), "SD", 360)
        add(jsonString(html, "playable_url"), "SD", 360)
        Regex(""""(?:hd_src|hd_src_no_ratelimit)"\s*:\s*"([^"]+)"""").findAll(html).forEach { add(it.groupValues[1], "HD", 720) }
        Regex(""""(?:sd_src|sd_src_no_ratelimit)"\s*:\s*"([^"]+)"""").findAll(html).forEach { add(it.groupValues[1], "SD", 360) }
        // TikTok / Instagram / generic players
        add(jsonString(html, "playAddr"), "HD", 720)
        add(jsonString(html, "downloadAddr"), "Watermark", 0)
        add(jsonString(html, "video_url"), null)
        add(jsonString(html, "contentUrl"))
        // Open Graph & twitter cards
        listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream").forEach { add(meta(html, it)) }
        // <video>/<source> tags
        Regex("""<(?:video|source)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { add(absolutize(it.groupValues[1], pageUrl)) }
        // Common player configs: {"hls": "...m3u8"}, file: "...", setVideoHLS('...'), sources: [{src: ...}]
        Regex("""https?:\\?/\\?/[^"'\s<>]+?\.m3u8(?:\?[^"'\s<>]*)?""").findAll(html).take(10).forEach { add(it.value) }
        if (media.isEmpty()) {
            // Last resort: plain links to media files. Thumbnail previews get filtered out later by size.
            Regex("""https?:\\?/\\?/[^"'\s<>]+?\.(?:mp4|m4a|mp3|webm)(?:\?[^"'\s<>]*)?""").findAll(html).take(12).forEach { add(it.value) }
        }
        return FoundPage(decodeEntities(title), thumb?.let(::unescape), pageUrl, media.values.toList(), mapOf("Referer" to pageUrl))
    }

    /**
     * Reddit: www.reddit.com answers apps with a browser check and its API needs an account, but the embed
     * page names the post's v.redd.it video, whose HLS playlist (H.264 + AAC) is public.
     */
    private fun reddit(pageUrl: String): FoundPage? {
        val host = runCatching { java.net.URI(pageUrl).host.orEmpty().lowercase() }.getOrDefault("")
        if (!(host == "redd.it" || host == "v.redd.it" || host == "reddit.com" || host.endsWith(".reddit.com"))) return null
        if (host == "v.redd.it") return redditVideo(pageUrl.trimEnd('/').substringAfterLast('/'), "Reddit video", pageUrl)
        // Share links (/r/sub/s/abc, redd.it/abc) redirect to the post.
        val post = POST.find(pageUrl) ?: runCatching { resolve(pageUrl) }.getOrNull()?.let { POST.find(it) } ?: return null
        val (sub, id) = post.destructured
        val embed = runCatching { get("https://embed.reddit.com/r/$sub/comments/$id/", MOBILE_UA).body }.getOrNull() ?: return null
        val video = Regex("""v\.redd\.it/([a-z0-9]+)""").find(embed)?.groupValues?.get(1) ?: return null
        val title = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.){1,300})"""").find(embed)?.groupValues?.get(1)?.let(::unescape) ?: "Reddit video"
        return redditVideo(video, title, pageUrl)
    }

    private fun redditVideo(id: String, title: String, pageUrl: String) = FoundPage(
        title = title, thumbnail = null, pageUrl = pageUrl, media = emptyList(),
        headers = mapOf("User-Agent" to MOBILE_UA),
        declared = listOf(StreamCandidate("https://v.redd.it/$id/HLSPlaylist.m3u8")),
    )

    /** Final URL after redirects, without reading the body. */
    private fun resolve(url: String): String = client.newCall(
        Request.Builder().url(url).header("User-Agent", MOBILE_UA).build(),
    ).execute().use { it.request.url.toString() }

    /** X / Twitter: uses the public embed endpoint that powers tweet widgets. */
    private fun twitter(pageUrl: String): FoundPage? {
        val id = Regex("""(?:twitter\.com|x\.com)/[^/]+/status/(\d+)""").find(pageUrl)?.groupValues?.get(1) ?: return null
        val token = toBase36Token(id)
        val json = runCatching { get("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=$token", DESKTOP_UA).body }.getOrNull()
            ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val text = o.optString("text").ifBlank { "X video $id" }
        val details = o.optJSONArray("mediaDetails") ?: return null
        val media = mutableListOf<FoundMedia>()
        var thumb: String? = null
        for (i in 0 until details.length()) {
            val m = details.getJSONObject(i)
            thumb = thumb ?: m.optString("media_url_https").takeIf { it.isNotBlank() }
            val variants = m.optJSONObject("video_info")?.optJSONArray("variants") ?: continue
            for (j in 0 until variants.length()) {
                val v = variants.getJSONObject(j)
                if (v.optString("content_type") != "video/mp4") continue
                val url = v.optString("url")
                val h = Regex("""/(\d+)x(\d+)/""").find(url)?.groupValues?.get(2)?.toIntOrNull() ?: 0
                media += FoundMedia(url, "video/mp4", if (h > 0) "${h}p" else null, h)
            }
        }
        if (media.isEmpty()) return null
        return FoundPage(text.take(80), thumb, pageUrl, media.sortedByDescending { it.height })
    }

    private fun toBase36Token(id: String): String {
        // Mirrors ((id / 1e15) * PI).toString(36).replace(/(0+|\.)/g, '') from the widget script.
        val value = id.toDouble() / 1e15 * Math.PI
        val intPart = value.toLong()
        var frac = value - intPart
        val sb = StringBuilder(java.lang.Long.toString(intPart, 36))
        sb.append('.')
        repeat(12) {
            frac *= 36
            val d = frac.toInt()
            sb.append(Character.forDigit(d, 36))
            frac -= d
        }
        return sb.toString().replace(Regex("(0+|\\.)"), "")
    }

    private fun get(url: String, ua: String): Fetched {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            // Cookies set anywhere along the redirect chain (short links set them before redirecting).
            val jar = linkedMapOf<String, String>()
            generateSequence(r) { it.priorResponse }.toList().asReversed().forEach { resp ->
                resp.headers("Set-Cookie").forEach { c ->
                    val pair = c.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotEmpty() && '=' in pair) jar[name] = pair.substringAfter('=')
                }
            }
            val cookies = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }.takeIf { it.isNotEmpty() }
            return Fetched(r.body.string(), r.request.url.toString(), cookies)
        }
    }

    private fun meta(html: String, prop: String): String? {
        val p = Regex.escape(prop)
        return Regex("""<meta[^>]+(?:property|name)=["']$p["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$p["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
    }

    private fun jsonString(html: String, key: String): String? =
        Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)

    private fun absolutize(u: String, base: String) = runCatching { java.net.URI(base).resolve(u).toString() }.getOrDefault(u)

    companion object {
        /**
         * The site's own interface files (sounds, animations) rather than the post's media, e.g. Meta's
         * static.cdninstagram.com/rsrc.php/…mp4 that logged-out Threads pages carry.
         */
        fun isSiteAsset(url: String): Boolean = SITE_ASSET.containsMatchIn(url.replace("\\/", "/"))

        private val SITE_ASSET = Regex("""/rsrc\.php/|//static\.(?:cdninstagram\.com|xx\.fbcdn\.net|whatsapp\.net)/""")

        private val DIRECT_MEDIA = Regex("""\.(mp4|m4a|mp3|webm|mkv|mov|3gp|ogg|wav|jpg|jpeg|png|gif|webp)$""", RegexOption.IGNORE_CASE)
        const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        fun unescape(s: String): String = s
            .replace("\\/", "/")
            .replace(Regex("""\\u([0-9a-fA-F]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("&amp;", "&")

        fun decodeEntities(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }

        fun mimeFor(url: String): String {
            val path = url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".m4a") -> "audio/mp4"
                path.endsWith(".mp3") -> "audio/mpeg"
                path.endsWith(".ogg") -> "audio/ogg"
                path.endsWith(".wav") -> "audio/wav"
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".mkv") -> "video/x-matroska"
                path.endsWith(".mov") -> "video/quicktime"
                path.endsWith(".3gp") -> "video/3gpp"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".gif") -> "image/gif"
                path.endsWith(".webp") -> "image/webp"
                else -> "video/mp4"
            }
        }

        fun extFor(mime: String) = when (mime) {
            "audio/mp4" -> "m4a"; "audio/mpeg" -> "mp3"; "audio/ogg" -> "ogg"; "audio/wav" -> "wav"
            "video/webm" -> "webm"; "video/x-matroska" -> "mkv"; "video/quicktime" -> "mov"; "video/3gpp" -> "3gp"
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"
            else -> "mp4"
        }
    }
}
