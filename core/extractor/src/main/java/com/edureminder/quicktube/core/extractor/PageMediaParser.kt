package com.edureminder.quicktube.core.extractor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The video a page is about, as declared by the page itself (not by what happens to be playing). */
data class PageMedia(
    val title: String?,
    val thumbnail: String?,
    val durationSec: Double?,
    val candidates: List<StreamCandidate>,
)

/**
 * Reads the structured video data pages ship with their player: quality lists in player configs,
 * JSON-LD VideoObject, Open Graph video tags, HTML5 quality <source>s and well-known player setups.
 * Only declared sources are used, so autoplaying thumbnail previews on listing pages are never picked up.
 */
object PageMediaParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(html: String, pageUrl: String): PageMedia? {
        val out = linkedMapOf<String, StreamCandidate>()
        fun add(raw: String?, label: String? = null, remoteList: Boolean = false, hls: Boolean = false) {
            val u = raw?.let(WebMediaFinder::unescape)?.trim()?.let { absolutize(it, pageUrl) }?.takeIf { it.startsWith("http") } ?: return
            if (StreamResolver.isSegment(u)) return
            val h = heightOf(label)
            val existing = out[u]
            if (existing == null || existing.label == null && label != null) {
                out[u] = StreamCandidate(u, label?.let(::prettyLabel), h, remoteList, hls || StreamResolver.isPlaylistUrl(u))
            }
        }

        // 1. Player configs with a quality list: "mediaDefinitions":[{"format":"mp4","videoUrl":"…","quality":"720"}, …]
        Regex(""""mediaDefinitions"\s*:\s*\[""").findAll(html).forEach { m ->
            val arr = balanced(html, m.range.last) ?: return@forEach
            val items = runCatching { json.parseToJsonElement(arr) as JsonArray }.getOrNull() ?: return@forEach
            items.filterIsInstance<JsonObject>().forEach { o ->
                val url = o.str("videoUrl") ?: return@forEach
                val format = o.str("format")?.lowercase()
                val quality = o["quality"]
                val isList = quality is JsonArray || url.contains("get_media") || o.str("remote") == "true"
                add(url, if (quality is JsonPrimitive) quality.contentOrNull else null, remoteList = isList, hls = format == "hls")
            }
        }
        // 2. html5player.setVideoUrlLow('…') / setVideoUrlHigh / setVideoHLS
        Regex("""setVideoUrl(Low|High)\(\s*['"]([^'"]+)['"]""").findAll(html).forEach {
            add(it.groupValues[2], if (it.groupValues[1] == "High") "HD" else "SD")
        }
        Regex("""setVideoHLS\(\s*['"]([^'"]+)['"]""").findAll(html).forEach { add(it.groupValues[1], hls = true) }
        // 3. Player/source lists: [{file|src|link|url: "…mp4", label|quality|height: …}] (JW Player, video.js, CMS JSON)
        val listed = mutableListOf<Pair<String, String?>>()
        Regex("""\{[^{}]{0,600}?["']?(?:file|src|link|url|videoUrl|contentUrl)["']?\s*:\s*["']([^"']+?\.(?:mp4|m3u8|webm|mov)(?:\?[^"']*)?)["'][^{}]{0,600}?\}""").findAll(html).forEach { m ->
            val obj = m.value
            val height = Regex("""["']?height["']?\s*:\s*["']?(\d{3,4})""").find(obj)?.groupValues?.get(1)
            val label = height ?: Regex("""["']?(?:label|res|size|quality)["']?\s*:\s*["']?(\d{3,4}p?|HD|SD|4K|hd|sd|uhd)""").find(obj)?.groupValues?.get(1)
            // Without a quality this is just as likely a preview clip — skip it.
            if (label != null || obj.contains("m3u8")) listed += WebMediaFinder.unescape(m.groupValues[1]) to label
        }
        pickThisPagesGroup(listed, pageUrl).forEach { (u, l) ->
            add(u, l?.let { if (it.equals("uhd", true)) "4K" else it.uppercase().removeSuffix("P").let { x -> if (x.all(Char::isDigit)) "${x}p" else x } })
        }
        // 4. HTML5 <source> with an explicit quality attribute
        Regex("""<source[^>]+>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            val label = Regex("""(?:size|res|label|data-quality|data-res|title)=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            val src = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            if (label != null && heightOf(label) > 0) add(src, label)
        }
        // 5. JSON-LD VideoObject
        var ldTitle: String? = null
        var ldThumb: String? = null
        var ldDuration: Double? = null
        Regex("""<script[^>]+application/ld\+json[^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).forEach { m ->
            val root = runCatching { json.parseToJsonElement(m.groupValues[1].trim()) }.getOrNull() ?: return@forEach
            videoObjects(root).forEach { v ->
                ldTitle = ldTitle ?: v.str("name")
                ldThumb = ldThumb ?: v.str("thumbnailUrl") ?: (v["thumbnailUrl"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ldDuration = ldDuration ?: v.str("duration")?.let(::isoDuration)
                add(v.str("contentUrl"))
            }
        }
        // 6. Open Graph / Twitter player stream
        listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream").forEach { p ->
            meta(html, p)?.takeIf { Regex("""\.(mp4|m3u8|webm|mov)(\?|$)""").containsMatchIn(it) }?.let { add(it) }
        }
        // 7. Social sites' inline players
        add(jsonString(html, "browser_native_hd_url"), "HD")
        add(jsonString(html, "playable_url_quality_hd"), "HD")
        add(jsonString(html, "browser_native_sd_url"), "SD")
        add(jsonString(html, "playable_url"), "SD")
        add(jsonString(html, "playAddr"), "HD")
        add(jsonString(html, "video_url"))
        Regex(""""(?:hls|hlsUrl|hls_url|manifestUrl)"\s*:\s*"([^"]+\.m3u8[^"]*)"""").findAll(html).forEach { add(it.groupValues[1], hls = true) }

        if (out.isEmpty()) return null
        val duration = ldDuration
            ?: meta(html, "video:duration")?.toDoubleOrNull()
            ?: meta(html, "og:video:duration")?.toDoubleOrNull()
            ?: Regex(""""video_duration"\s*:\s*"?(\d{1,6})(?:\.\d+)?"?""").find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        val title = meta(html, "og:title") ?: ldTitle
            ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()
        return PageMedia(
            title = title?.let(WebMediaFinder::decodeEntities),
            thumbnail = (meta(html, "og:image") ?: ldThumb)?.let(WebMediaFinder::unescape),
            durationSec = duration?.takeIf { it > 0 },
            candidates = out.values.toList(),
        )
    }

    /**
     * Listing pages embed source lists for many videos. Keep only the files of the video this page is about:
     * the group (same folder) whose id appears in the page URL, or the only group present.
     */
    private fun pickThisPagesGroup(items: List<Pair<String, String?>>, pageUrl: String): List<Pair<String, String?>> {
        if (items.isEmpty()) return items
        val groups = items.groupBy { it.first.substringBefore('?').substringBeforeLast('/') }
        if (groups.size == 1) return items
        val pageIds = Regex("""[A-Za-z0-9]{5,}""").findAll(pageUrl.substringAfter("://").substringAfter('/'))
            .map { it.value }.filter { id -> id.any(Char::isDigit) }.toSet()
        val match = groups.entries.firstOrNull { (dir, _) -> pageIds.any { id -> dir.contains(id) } }
        return match?.value ?: emptyList()
    }

    private fun videoObjects(e: JsonElement): List<JsonObject> = when (e) {
        is JsonArray -> e.flatMap(::videoObjects)
        is JsonObject -> {
            val type = e["@type"]
            val isVideo = (type as? JsonPrimitive)?.contentOrNull == "VideoObject" ||
                (type as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "VideoObject" } == true
            (if (isVideo) listOf(e) else emptyList()) + (e["@graph"]?.let(::videoObjects) ?: emptyList()) +
                (e["video"]?.let(::videoObjects) ?: emptyList())
        }
        else -> emptyList()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Returns the JSON array/object starting at [start] (index of '[' or '{'), honouring strings. */
    fun balanced(s: String, start: Int): String? {
        val open = s[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < s.length && i - start < 500_000) {
            val c = s[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                open -> depth++
                close -> if (--depth == 0) return s.substring(start, i + 1)
            }
            i++
        }
        return null
    }

    fun heightOf(label: String?): Int {
        if (label == null) return 0
        val l = label.lowercase()
        return when {
            l.contains("4k") || l.contains("2160") -> 2160
            l == "hd" -> 720
            l == "sd" -> 360
            else -> Regex("""(\d{3,4})""").find(l)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 100..4320 } ?: 0
        }
    }

    private fun prettyLabel(l: String): String = if (l.matches(Regex("""\d{3,4}"""))) "${l}p" else l

    /** ISO-8601 "PT1H2M3S" → seconds. */
    fun isoDuration(v: String): Double? {
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""").matchEntire(v.trim()) ?: return v.toDoubleOrNull()
        val (d, h, mi, s) = m.destructured
        return (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 + (mi.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)
    }

    private fun meta(html: String, prop: String): String? {
        val p = Regex.escape(prop)
        return (Regex("""<meta[^>]+(?:property|name)=["']$p["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$p["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1))
            ?.let(WebMediaFinder::decodeEntities)
    }

    private fun jsonString(html: String, key: String): String? =
        Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)

    private fun absolutize(u: String, base: String) =
        if (u.startsWith("//")) "https:$u" else runCatching { java.net.URI(base).resolve(u).toString() }.getOrDefault(u)
}
