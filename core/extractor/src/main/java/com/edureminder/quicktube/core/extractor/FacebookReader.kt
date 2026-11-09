package com.edureminder.quicktube.core.extractor

import com.edureminder.quicktube.core.common.DeviceCodecs
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import com.edureminder.quicktube.core.model.StreamProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Facebook posts, reels and videos read the way a desktop browser sees them: the page carries the player's
 * full quality ladder ("representations": every resolution as video-only MP4 plus the audio tracks, each with
 * its own address) and the ready-made SD/HD files. One request, about a second, and no account needed.
 * Anonymous yt-dlp requests get "Cannot parse data" / "registered users only" from Facebook every so often;
 * this doesn't.
 */
class FacebookReader(private val client: OkHttpClient) : UniversalExtractor {
    override val isReady = true

    override suspend fun update(): String? = null

    override suspend fun extract(url: String, headers: Map<String, String>): ExtractedPage? = withContext(Dispatchers.IO) {
        if (!isFacebook(url)) return@withContext null
        val (html, finalUrl) = runCatching { fetch(url, headers["Cookie"]) }.getOrNull() ?: return@withContext null
        // A share link redirects to the post, reel or video it stands for: that address says which video is meant.
        parse(html, finalUrl)
    }

    /** The page and the address it ended on after redirects. */
    private fun fetch(url: String, cookie: String?): Pair<String, String>? {
        val req = Request.Builder().url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Site", "none")
            .header("Upgrade-Insecure-Requests", "1")
            .apply { cookie?.let { header("Cookie", it) } }
            .build()
        return client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body.string() to r.request.url.toString() else null }
    }

    companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        fun isFacebook(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
            return host == "fb.watch" || host == "facebook.com" || host.endsWith(".facebook.com")
        }

        /** The id of the one reel or video a Facebook address is about (/reel/…, /videos/…, ?v=…); null for feeds. */
        fun videoId(url: String): String? {
            if (!isFacebook(url)) return null
            return Regex("""/(?:reel|reels|videos)/(?:[^/?#]+/)?(\d{6,})|[?&](?:v|video_id)=(\d{6,})""").find(url)
                ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        }

        /**
         * Options from a Facebook page's HTML (pure, unit-tested). [av1Playable]: this phone can play AV1.
         * Signed-in pages carry the data of many videos (the next reels, the feed under a post), each quality list
         * followed by its "video_id": only the video the address names is used. A page with several videos and an
         * address that names none (a post) gives nothing rather than someone else's video.
         */
        fun parse(html: String, pageUrl: String, av1Playable: Boolean = DeviceCodecs.canDecodeAv1): ExtractedPage? {
            val blocks = qualityLists(html)
            val ids = blocks.mapNotNull { it.second }.distinct()
            val wanted = videoId(pageUrl) ?: ids.singleOrNull()
            if (wanted == null && ids.size > 1) return null
            // Pages without ids (signed out) hold just the one video.
            val chosen = if (wanted == null || ids.isEmpty()) blocks.firstOrNull() else blocks.firstOrNull { it.second == wanted }
            if (wanted != null && chosen == null && ids.isNotEmpty()) return null // this video's data isn't on the page
            val reps = chosen?.first?.let { runCatching { JSONArray(it) }.getOrNull() }
            // The page's first length belongs to its first video: fine when that's the one chosen.
            val duration = (durationMs(html, wanted) ?: durationMs(html, null).takeIf { chosen == null || chosen == blocks.first() })?.div(1000)
            val options = mutableListOf<DownloadOption>()
            val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://www.facebook.com/")

            data class Rep(val url: String, val video: Boolean, val codec: String, val res: Int, val bandwidth: Long)
            val all = (0 until (reps?.length() ?: 0)).mapNotNull { i ->
                val o = reps!!.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("base_url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val w = o.optInt("width"); val h = o.optInt("height")
                Rep(u, o.optString("mime_type").startsWith("video"), o.optString("codecs").lowercase(),
                    if (w in 1 until h) w else h, o.optLong("bandwidth"))
            }
            fun size(bandwidth: Long) = if (duration != null && bandwidth > 0) (bandwidth / 8 * duration).toLong() else -1L
            val audios = all.filter { !it.video }.sortedByDescending { it.bandwidth }
            audios.distinctBy { it.bandwidth / 8000 }.forEachIndexed { i, a ->
                options += DownloadOption(
                    id = "fa$i", kind = FormatKind.AUDIO, title = "M4A (${a.bandwidth / 1000}K)", info = "Audio",
                    ext = "m4a", mimeType = "audio/mp4", url = a.url, sizeBytes = size(a.bandwidth),
                    bitrate = (a.bandwidth / 1000).toInt(), protocol = StreamProtocol.HTTP,
                )
            }
            // One file per resolution: H.264 if there is one, then VP9, then AV1 (only where it plays).
            fun rank(codec: String) = when {
                codec.startsWith("avc") -> 0
                codec.startsWith("vp09") || codec.startsWith("vp9") -> 1
                codec.startsWith("av01") -> if (av1Playable) 2 else 9
                codec.startsWith("hvc") || codec.startsWith("hev") -> 3
                else -> 9
            }
            val audio = audios.firstOrNull()
            if (audio != null) {
                // Facebook encodes several bitrates at the same size (e.g. five at 720 px). Each is its own choice,
                // named by the resolution it looks like: size × √(bitrate ÷ reference bitrate), on the usual ladder.
                // That reproduces what other downloaders show (480p, 540p, 640p, 720p, 840p for those five).
                val usable = all.filter { it.video && it.res > 0 && rank(it.codec) < 9 }
                val byRung = sortedMapOf<Int, Rep>()
                usable.groupBy { it.res }.forEach { (res, list) ->
                    // One file per bitrate: the best codec of each.
                    val tiers = list.groupBy { it.bandwidth / 20_000 }.values
                        .map { same -> same.sortedWith(compareBy({ rank(it.codec) }, { -it.bandwidth })).first() }
                        .sortedBy { it.bandwidth }
                    val reference = tiers[if (tiers.size >= 3) tiers.size - 2 else tiers.size - 1].bandwidth.coerceAtLeast(1)
                    tiers.forEach { t ->
                        // Odd encoder sizes (324, 644…) are named on the usual ladder too, like the Facebook app does.
                        val rung = if (tiers.size == 1) nearestRung(res.toDouble()) else nearestRung(res * kotlin.math.sqrt(t.bandwidth.toDouble() / reference))
                        val prev = byRung[rung]
                        if (prev == null || t.bandwidth > prev.bandwidth) byRung[rung] = t
                    }
                }
                byRung.forEach { (rung, v) ->
                    options += DownloadOption(
                        id = "fv$rung", kind = FormatKind.VIDEO, title = title(rung), info = "MP4",
                        ext = "mp4", mimeType = "video/mp4", url = v.url, audioUrl = audio.url,
                        sizeBytes = size(v.bandwidth), audioSizeBytes = size(audio.bandwidth), height = rung,
                        protocol = StreamProtocol.HTTP, label = if (rung >= 1080) "HD" else null,
                    )
                }
            }
            // The ready-made files (video + sound in one), when the page has no quality ladder (and only one video).
            if (options.none { it.kind == FormatKind.VIDEO } && ids.size <= 1) {
                listOf("browser_native_sd_url" to "Standard (SD)", "browser_native_hd_url" to "High quality (HD)").forEach { (key, label) ->
                    jsonString(html, key)?.let { u ->
                        options += DownloadOption(
                            id = "f$key", kind = FormatKind.VIDEO, title = label, info = "MP4", ext = "mp4",
                            mimeType = "video/mp4", url = u, protocol = StreamProtocol.HTTP,
                        )
                    }
                }
            }
            if (options.none { it.kind == FormatKind.VIDEO }) return null
            val title = meta(html, "og:title")?.let(::htmlDecode)
                ?.replace(Regex("""^(?:[\d.,]+[KMB]?\s+(?:views?|reactions?|comments?|shares?|plays?)\s*[·•]?\s*)+\|\s*"""), "")
                ?.removeSuffix(" | Facebook")?.let(::cleanTitle)?.takeIf { it.isNotBlank() && it != "Facebook" }
                ?: "Facebook video"
            return ExtractedPage(title, meta(html, "og:image")?.let(::htmlDecode), duration, options, headers)
        }

        /**
         * A caption as a title: one line, without the dot-only lines and hashtags people pad captions with
         * ("Pretty 🔥\n.\n.\n#reels #fyp | Page" → "Pretty 🔥 | Page").
         */
        internal fun cleanTitle(raw: String): String = raw.lines()
            .filterNot { it.isBlank() || it.trim().all { c -> c in ".·•_-–—…" } }
            .joinToString(" ")
            .replace(Regex("""#[\p{L}\p{N}\p{M}_]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?:\s*\|)+\s*"""), " | ")
            .trim(' ', '|', '.', '·', '-', '_')
            .trim()

        private val RUNGS = intArrayOf(144, 240, 360, 480, 540, 640, 720, 840, 960, 1080, 1440, 2160)

        private fun nearestRung(value: Double): Int = RUNGS.minBy { kotlin.math.abs(it - value) }

        private fun title(res: Int): String {
            val q = "${res}p"
            return when {
                res <= 360 -> "Fast ($q)"
                res <= 640 -> "Standard ($q)"
                res <= 960 -> "High quality ($q)"
                res <= 1100 -> "Full HD ($q)"
                res < 2000 -> "2K ($q)"
                else -> "4K ($q)"
            }
        }

        /** Every "representations" quality list with the video_id that follows it (null when there is none). */
        private fun qualityLists(html: String): List<Pair<String, String?>> {
            val key = "\"representations\":"
            val out = mutableListOf<Pair<String, String?>>()
            var from = 0
            while (true) {
                val at = html.indexOf(key, from).takeIf { it >= 0 } ?: break
                val open = html.indexOf('[', at).takeIf { it >= 0 } ?: break
                val array = arrayAt(html, open) ?: break
                val end = open + array.length
                val id = Regex(""""video_id":"(\d+)"""").find(html.substring(end, minOf(html.length, end + 2000)))?.groupValues?.get(1)
                out += array to id
                from = end
            }
            return out
        }

        /** The length of [videoId] (near its id), or the page's only length when no id is known. */
        private fun durationMs(html: String, videoId: String?): Double? {
            val length = Regex(""""playable_duration_in_ms":(\d+)""")
            if (videoId == null) return length.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
            return Regex(""""$videoId"""").findAll(html).firstNotNullOfOrNull { m ->
                val from = maxOf(0, m.range.first - 1500)
                // The closest one: a neighbouring video's length can be in the same stretch.
                length.findAll(html.substring(from, minOf(html.length, m.range.last + 1500)))
                    .minByOrNull { kotlin.math.abs(from + it.range.first - m.range.first) }?.groupValues?.get(1)?.toDoubleOrNull()
            }
        }

        /** The JSON array starting at [start] (bracket-matched, strings respected). */
        private fun arrayAt(html: String, start: Int): String? {
            var depth = 0
            var inString = false
            var i = start
            while (i < html.length) {
                val c = html[i]
                if (inString) {
                    if (c == '\\') i++ else if (c == '"') inString = false
                } else when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> if (--depth == 0) return html.substring(start, i + 1)
                }
                i++
            }
            return null
        }

        private fun jsonString(html: String, key: String): String? =
            Regex(""""${Regex.escape(key)}"\s*:\s*"((?:[^"\\]|\\.)+)"""").find(html)?.groupValues?.get(1)
                ?.let { runCatching { JSONArray("[\"$it\"]").getString(0) }.getOrNull() }?.takeIf { it.startsWith("http") }

        private fun meta(html: String, prop: String): String? =
            Regex("""<meta[^>]+property="${Regex.escape(prop)}"[^>]+content="([^"]*)"""").find(html)?.groupValues?.get(1)
                ?: Regex("""<meta[^>]+content="([^"]*)"[^>]+property="${Regex.escape(prop)}"""").find(html)?.groupValues?.get(1)

        /** HTML entities, including numeric ones: Facebook writes Bengali and emoji in meta tags as &#x9ab; etc. */
        internal fun htmlDecode(s: String): String = Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|quot|lt|gt|apos|nbsp);""").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "quot" -> "\""; "lt" -> "<"; "gt" -> ">"; "apos" -> "'"; "nbsp" -> " "
                else -> runCatching {
                    val cp = if (e.startsWith("#x", true)) e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(cp))
                }.getOrDefault(m.value)
            }
        }
    }
}

/** Tries each extractor in turn; the first with downloadable formats wins (fast native readers before yt-dlp). */
class ChainExtractor(private val extractors: List<UniversalExtractor>) : UniversalExtractor {
    override val isReady: Boolean get() = extractors.any { it.isReady }

    override suspend fun extract(url: String, headers: Map<String, String>): ExtractedPage? {
        for (e in extractors) {
            val page = runCatching { e.extract(url, headers) }.getOrNull()
            if (page != null && page.options.isNotEmpty()) return page
        }
        return null
    }

    override suspend fun update(): String? = extractors.firstNotNullOfOrNull { it.update() }
}
