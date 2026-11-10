package com.edureminder.quicktube.core.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Vimeo videos from the embedded player's own config (`player.vimeo.com/video/ID/config`): title, picture, length and
 * an HLS playlist with every quality, without signing in. yt-dlp's Vimeo support now asks for an account, so it would
 * fail on public videos.
 */
class VimeoReader(private val client: OkHttpClient, private val resolver: StreamResolver) : UniversalExtractor {
    override val isReady = true

    override suspend fun update(): String? = null

    override suspend fun extract(url: String, headers: Map<String, String>): ExtractedPage? {
        val (id, hash) = videoId(url) ?: return null
        val ua = headers["User-Agent"] ?: FacebookReader.DESKTOP_UA
        val config = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://player.vimeo.com/video/$id/config" + (hash?.let { "?h=$it" } ?: ""))
                    .header("User-Agent", ua).header("Referer", "https://vimeo.com/").build()
                client.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body.string()) else null }
            }.getOrNull()
        } ?: return null
        val meta = parse(config) ?: return null
        val playHeaders = mapOf("Referer" to "https://player.vimeo.com/", "User-Agent" to ua)
        val candidates = meta.progressive.map { (u, h) -> StreamCandidate(u, "${h}p", h) } + StreamCandidate(meta.hlsUrl ?: "", forcedHls = true)
        val options = resolver.resolve(candidates.filter { it.url.isNotBlank() }, playHeaders, meta.durationSec)
        if (options.isEmpty()) return null
        return ExtractedPage(meta.title, meta.thumbnail, meta.durationSec, options, playHeaders)
    }

    internal data class Meta(val title: String, val thumbnail: String?, val durationSec: Double?, val hlsUrl: String?, val progressive: List<Pair<String, Int>>)

    companion object {
        /** vimeo.com/ID, vimeo.com/ID/HASH (unlisted), vimeo.com/channels/x/ID, vimeo.com/groups/x/videos/ID, player.vimeo.com/video/ID?h=HASH. */
        fun videoId(url: String): Pair<String, String?>? {
            val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
            if (host != "vimeo.com" && host != "player.vimeo.com") return null
            val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
            val i = parts.indexOfLast { it.all(Char::isDigit) && it.length >= 6 }.takeIf { it >= 0 } ?: return null
            val hash = parts.getOrNull(i + 1)?.takeIf { it.matches(Regex("[0-9a-f]{8,}")) }
                ?: uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("h=") }?.substringAfter('=')
            return parts[i] to hash
        }

        /** Null for errors and DRM-only videos. */
        internal fun parse(o: JSONObject): Meta? {
            val files = o.optJSONObject("request")?.optJSONObject("files") ?: return null
            val hls = files.optJSONObject("hls")?.let { h ->
                val cdns = h.optJSONObject("cdns") ?: return@let null
                val name = h.optString("default_cdn").takeIf { cdns.has(it) } ?: cdns.keys().asSequence().firstOrNull() ?: return@let null
                cdns.optJSONObject(name)?.optString("url")?.takeIf { it.isNotBlank() }
            }
            val progressive = files.optJSONArray("progressive")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { p ->
                    p.optString("url").takeIf { it.isNotBlank() }?.let { it to p.optInt("height") }
                }
            }.orEmpty()
            if (hls == null && progressive.isEmpty()) return null
            val video = o.optJSONObject("video")
            val thumb = video?.optJSONObject("thumbs")?.let { t -> t.optString("base").ifBlank { t.keys().asSequence().mapNotNull { k -> t.optString(k) }.lastOrNull() } }
            return Meta(
                title = video?.optString("title")?.ifBlank { null } ?: "Vimeo video",
                thumbnail = thumb?.takeIf { it.isNotBlank() },
                durationSec = video?.optDouble("duration")?.takeIf { !it.isNaN() && it > 0 },
                hlsUrl = hls,
                progressive = progressive,
            )
        }
    }
}
