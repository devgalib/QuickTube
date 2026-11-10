package com.edureminder.quicktube.core.extractor

import com.edureminder.quicktube.core.common.DeviceCodecs
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Bilibili (bilibili.com, b23.tv) videos from the site's own web API: title, cover and every quality the visitor
 * may watch (480p without an account; the browser's signed-in cookies unlock more), as video + audio streams that
 * are downloaded together and merged. About a second; yt-dlp stays the fallback (and handles bilibili.tv).
 */
class BilibiliReader(private val client: OkHttpClient) : UniversalExtractor {
    override val isReady = true

    override suspend fun update(): String? = null

    /** The anonymous visitor cookie the API asks for (without it every call answers 412). */
    @Volatile private var visitor: String? = null

    override suspend fun extract(url: String, headers: Map<String, String>): ExtractedPage? = withContext(Dispatchers.IO) {
        if (!isBilibili(url)) return@withContext null
        // b23.tv short links name the video only after their redirect.
        val target = if (hostOf(url) == "b23.tv") runCatching { resolveRedirect(url) }.getOrNull() ?: return@withContext null else url
        val id = videoId(target) ?: return@withContext null
        val cookie = headers["Cookie"]?.takeIf { "buvid3" in it } ?: visitorCookie() ?: return@withContext null
        val apiHeaders = mapOf("User-Agent" to (headers["User-Agent"] ?: FacebookReader.DESKTOP_UA), "Referer" to "https://www.bilibili.com/", "Cookie" to cookie)
        val idParam = if (id.startsWith("BV")) "bvid=$id" else "aid=${id.removePrefix("av")}"
        val view = get("https://api.bilibili.com/x/web-interface/view?$idParam", apiHeaders)?.optJSONObject("data") ?: return@withContext null
        val part = partOf(target)
        val pages = view.optJSONArray("pages")
        val page = pages?.optJSONObject((part - 1).coerceIn(0, (pages.length() - 1).coerceAtLeast(0)))
        val cid = page?.optLong("cid")?.takeIf { it > 0 } ?: view.optLong("cid")
        val play = get("https://api.bilibili.com/x/player/playurl?$idParam&cid=$cid&qn=127&fnval=4048&fourk=1", apiHeaders)
            ?.optJSONObject("data") ?: return@withContext null
        val duration = (page?.optLong("duration") ?: 0L).takeIf { it > 0 } ?: view.optLong("duration")
        val title = view.optString("title").let { t ->
            // A part of a multi-part video is named after both.
            if ((pages?.length() ?: 0) > 1) "$t - ${page?.optString("part").orEmpty()}".trimEnd(' ', '-') else t
        }
        val options = options(play, duration.toDouble(), DeviceCodecs.canDecodeAv1)
        if (options.isEmpty()) return@withContext null
        // The CDN checks the Referer; the cookie isn't needed for the files.
        ExtractedPage(title, view.optString("pic").replace("http://", "https://").ifBlank { null }, duration.toDouble(), options,
            mapOf("User-Agent" to apiHeaders.getValue("User-Agent"), "Referer" to "https://www.bilibili.com/"))
    }

    private fun get(url: String, headers: Map<String, String>): JSONObject? {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONObject(r.body.string()).takeIf { it.optInt("code", -1) == 0 }
        }
    }

    private fun visitorCookie(): String? = visitor ?: runCatching {
        get("https://api.bilibili.com/x/frontend/finger/spi", mapOf("User-Agent" to FacebookReader.DESKTOP_UA))
            ?.optJSONObject("data")?.optString("b_3")?.takeIf { it.isNotBlank() }?.let { "buvid3=$it" }
    }.getOrNull()?.also { visitor = it }

    private fun resolveRedirect(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", FacebookReader.DESKTOP_UA).build()
        return client.newCall(req).execute().use { it.request.url.toString() }
    }

    companion object {
        private fun hostOf(url: String) = runCatching { java.net.URI(url.trim()).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") }.getOrNull()

        /** bilibili.com and its b23.tv short links (bilibili.tv, the international site, is another API). */
        fun isBilibili(url: String): Boolean = hostOf(url).let { it == "bilibili.com" || it == "b23.tv" }

        /** "BV…" or "av…" from /video/BV…/, /video/av123; null for other pages. */
        fun videoId(url: String): String? {
            if (hostOf(url) != "bilibili.com") return null
            return Regex("""/video/(BV[0-9A-Za-z]{10}|av\d+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
                ?.let { if (it.startsWith("av", ignoreCase = true)) it.lowercase() else it }
        }

        /** The part of a multi-part video (?p=2), 1 when not given. */
        internal fun partOf(url: String): Int = Regex("""[?&]p=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        /**
         * One choice per resolution: H.264 where it exists (plays and merges everywhere), else HEVC, else AV1 when this
         * phone plays it; each with the best AAC track. Plus the best audio alone.
         */
        internal fun options(play: JSONObject, durationSec: Double, av1Playable: Boolean): List<DownloadOption> {
            val dash = play.optJSONObject("dash") ?: return emptyList()
            fun list(name: String) = dash.optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            fun url(o: JSONObject) = o.optString("baseUrl").ifBlank { o.optString("base_url") }
            fun size(o: JSONObject) = if (durationSec > 0) (o.optLong("bandwidth") * durationSec / 8).toLong() else -1L
            val audio = list("audio").filter { it.optString("codecs").startsWith("mp4a") }.maxByOrNull { it.optLong("bandwidth") }
            fun rank(codecs: String) = when {
                codecs.startsWith("avc") -> 0
                codecs.startsWith("hev") || codecs.startsWith("hvc") -> 1
                codecs.startsWith("av01") && av1Playable -> 2
                else -> 9
            }
            val videos = list("video").filter { rank(it.optString("codecs")) < 9 && url(it).isNotBlank() }
                .groupBy { minOf(it.optInt("width"), it.optInt("height")) }
                .mapNotNull { (_, same) -> same.minWithOrNull(compareBy({ rank(it.optString("codecs")) }, { -it.optLong("bandwidth") })) }
                .sortedBy { minOf(it.optInt("width"), it.optInt("height")) }
            val out = mutableListOf<DownloadOption>()
            if (audio != null) out += DownloadOption(
                id = "bili-a", kind = FormatKind.AUDIO, title = "M4A (${audio.optLong("bandwidth") / 1000}K)", info = "Audio",
                ext = "m4a", mimeType = "audio/mp4", url = url(audio), sizeBytes = size(audio), bitrate = (audio.optLong("bandwidth") / 1000).toInt(),
            )
            if (audio == null) return out
            videos.forEach { v ->
                // Vertical videos are named by their short side, like the apps do.
                val res = minOf(v.optInt("width"), v.optInt("height"))
                out += DownloadOption(
                    id = "bili-v$res", kind = FormatKind.VIDEO,
                    title = when {
                        res <= 360 -> "Fast (${res}p)"; res <= 576 -> "Standard (${res}p)"; res <= 720 -> "High quality (${res}p)"
                        res <= 1100 -> "Full HD (${res}p)"; else -> "${if (res >= 2000) "4K" else "2K"} (${res}p)"
                    },
                    info = "MP4", ext = "mp4", mimeType = "video/mp4", url = url(v), audioUrl = url(audio),
                    sizeBytes = size(v), audioSizeBytes = size(audio), height = res, label = if (res >= 1080) "HD" else null,
                )
            }
            return out
        }
    }
}
