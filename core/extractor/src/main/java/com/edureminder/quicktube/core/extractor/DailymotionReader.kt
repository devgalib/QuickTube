package com.edureminder.quicktube.core.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Dailymotion videos read from the player's own metadata endpoint: title, thumbnail, length and an HLS master
 * playlist with every quality, in about a second (yt-dlp takes 8–10 s on a phone). yt-dlp stays the fallback.
 */
class DailymotionReader(private val client: OkHttpClient, private val resolver: StreamResolver) : UniversalExtractor {
    override val isReady = true

    override suspend fun update(): String? = null

    override suspend fun extract(url: String, headers: Map<String, String>): ExtractedPage? {
        val id = videoId(url) ?: return null
        val meta = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://www.dailymotion.com/player/metadata/video/$id")
                    .header("User-Agent", headers["User-Agent"] ?: FacebookReader.DESKTOP_UA)
                    .header("Referer", "https://www.dailymotion.com/")
                    .build()
                client.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body.string()) else null }
            }.getOrNull()
        } ?: return null
        val page = parse(meta) ?: return null
        val playHeaders = mapOf("Referer" to "https://www.dailymotion.com/", "User-Agent" to (headers["User-Agent"] ?: FacebookReader.DESKTOP_UA))
        val options = resolver.resolve(listOf(StreamCandidate(page.hlsUrl, forcedHls = true)), playHeaders, page.durationSec)
        if (options.isEmpty()) return null
        return ExtractedPage(page.title, page.thumbnail, page.durationSec, options, playHeaders)
    }

    internal data class Meta(val title: String, val thumbnail: String?, val durationSec: Double?, val hlsUrl: String)

    companion object {
        private val ID = Regex("""^[a-z0-9]{5,12}$""", RegexOption.IGNORE_CASE)

        /** dailymotion.com/video/ID[_slug], /embed/video/ID, dai.ly/ID, geo.dailymotion.com/player…?video=ID. */
        fun videoId(url: String): String? {
            val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
            val path = uri.rawPath.orEmpty()
            val id = when {
                host == "dai.ly" -> path.trim('/').substringBefore('/')
                host == "dailymotion.com" -> Regex("""/(?:embed/)?video/([^/?#_]+)""").find(path)?.groupValues?.get(1)
                host.endsWith(".dailymotion.com") -> uri.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("video=") }?.substringAfter('=')
                else -> null
            }
            return id?.takeIf { ID.matches(it) }
        }

        /** The metadata's title, largest thumbnail, length and "auto" HLS address; null for errors and DRM. */
        internal fun parse(o: JSONObject): Meta? {
            if (o.has("error") || o.optBoolean("protected_delivery")) return null
            val hls = o.optJSONObject("qualities")?.optJSONArray("auto")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .firstOrNull { it.optString("type").contains("mpegurl", ignoreCase = true) }?.optString("url")
            }?.takeIf { it.isNotBlank() } ?: return null
            val thumb = o.optJSONObject("thumbnails")?.let { t ->
                t.keys().asSequence().maxByOrNull { it.toIntOrNull() ?: 0 }?.let { t.optString(it) }
            }?.takeIf { it.isNotBlank() }
            return Meta(
                title = o.optString("title").ifBlank { "Dailymotion video" },
                thumbnail = thumb,
                durationSec = o.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
                hlsUrl = hls,
            )
        }
    }
}
