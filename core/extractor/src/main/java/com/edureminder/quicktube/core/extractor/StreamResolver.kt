package com.edureminder.quicktube.core.extractor

import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import com.edureminder.quicktube.core.model.StreamProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs

/**
 * A media URL seen on a page, newest first; [label]/[height] when the page told us the quality.
 * [remoteList] marks a URL that returns a JSON list of qualities instead of media.
 */
data class StreamCandidate(
    val url: String,
    val label: String? = null,
    val height: Int = 0,
    val remoteList: Boolean = false,
    val forcedHls: Boolean = false,
)

/**
 * Turns media URLs found on a page into download choices for *the* video the user is on:
 * declared quality lists are kept whole; anonymous sniffed streams are matched by duration (HLS) or size (files),
 * so hover previews and ads are dropped. Adds an audio-only choice like the native YouTube sheet.
 */
class StreamResolver(private val client: OkHttpClient, private val hls: HlsClient) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    /** What happened while resolving (shown under "Technical details" when nothing is found). */
    class Result(val options: List<DownloadOption>, val report: List<String>)

    suspend fun resolve(
        input: List<StreamCandidate>,
        headers: Map<String, String>,
        targetDurationSec: Double? = null,
        pageFetch: (suspend (String) -> String?)? = null,
    ): List<DownloadOption> = resolveWithReport(input, headers, targetDurationSec, pageFetch).options

    suspend fun resolveWithReport(
        input: List<StreamCandidate>,
        rawHeaders: Map<String, String>,
        targetDurationSec: Double? = null,
        pageFetch: (suspend (String) -> String?)? = null,
    ): Result = coroutineScope {
        val report = java.util.Collections.synchronizedList(mutableListOf<String>())
        // Players send Origin with their XHRs; many CDNs reject requests without it.
        val headers = rawHeaders.toMutableMap().apply {
            val ref = this["Referer"]
            if (ref != null && "Origin" !in this) runCatching { java.net.URI(ref) }.getOrNull()?.let { u ->
                if (u.scheme != null && u.host != null) put("Origin", "${u.scheme}://${u.host}")
            }
        }
        report += "candidates: ${input.size} (declared ${input.count { it.label != null || it.remoteList }})"
        val expanded = input.distinctBy { it.url }.flatMap { c ->
            if (c.remoteList) expandList(c, headers, pageFetch).also { report += "list ${host(c.url)} → ${it.size} items" } else listOf(c)
        }.distinctBy { it.url }
        val playlists = expanded.filter { it.forcedHls || isPlaylistUrl(it.url) }.take(10)
        val files = expanded.filter { !it.forcedHls && !isPlaylistUrl(it.url) && !isSegment(it.url) }.take(10)

        // ---- HLS ----
        val loaded = playlists.map { c ->
            async {
                runCatching { hlsOptions(c, headers) }
                    .recoverCatching { e ->
                        // Same request from inside the page (its cookies, its TLS) when the CDN refuses ours.
                        val text = pageFetch?.invoke(c.url)?.takeIf(HlsParser::isPlaylist) ?: throw e
                        hlsFromText(c, text, headers)
                    }
                    .onSuccess { report += "hls ${host(c.url)} → ${it.options.size} qualities, ${it.durationSec.toInt()}s${if (it.live) " (live)" else ""}" }
                    .onFailure { report += "hls ${host(c.url)} → ${it.message ?: it.javaClass.simpleName}" }
                    .getOrNull()
            }
        }.awaitAll().filterNotNull()
        val variantUrls = loaded.flatMap { it.variantUrls }.toSet()
        var hlsUsable = loaded.filter { it.sourceUrl !in variantUrls && !it.live }
        if (targetDurationSec != null && targetDurationSec > 0) {
            val close = hlsUsable.filter { abs(it.durationSec - targetDurationSec) <= maxOf(3.0, targetDurationSec * 0.05) }
            if (close.isNotEmpty()) hlsUsable = close
        } else if (hlsUsable.none { it.labeled } && hlsUsable.size > 1) {
            // Anonymous streams with no duration hint: the longest one is the feature, not an ad.
            hlsUsable = listOf(hlsUsable.maxBy { it.durationSec })
        }
        val hlsOptions = hlsUsable.flatMap { it.options }

        // ---- plain files ----
        val probed = files.map { c ->
            async {
                probe(c, headers).also { r ->
                    report += "file ${host(c.url)} → " + (r?.let { "${it.second.ifBlank { "?" }} ${it.third}B" } ?: "unreachable")
                }
                    // A declared quality stays even if the size check is refused; the download itself may still work.
                    ?: if (c.label != null || c.height > 0) Triple(c, WebMediaFinder.mimeFor(c.url), -1L) else null
            }
        }.awaitAll().filterNotNull()
            .filter { (_, mime, _) -> mime.isEmpty() || mime.startsWith("video") || mime.startsWith("audio") || mime.contains("octet-stream") || mime.contains("mp4") }
        val labeledFiles = probed.filter { it.first.label != null || it.first.height > 0 }
        val anonymous = probed - labeledFiles.toSet()
        val biggest = anonymous.maxOfOrNull { it.third } ?: 0
        val anonymousKept = anonymous
            // Hover previews are a few hundred KB; drop them when a real file exists.
            .filter { it.third < 0 || biggest < 3_000_000 || it.third >= biggest / 4 }
            .sortedByDescending { it.third }
            .take(if (labeledFiles.isEmpty()) 3 else 0)
        val fileOptions = (labeledFiles + anonymousKept).mapIndexed { i, (c, mime, size) ->
            val m = if (mime.isBlank() || mime.contains("octet-stream")) WebMediaFinder.mimeFor(c.url) else mime.substringBefore(';').trim()
            val ext = WebMediaFinder.extFor(m)
            DownloadOption(
                id = "f$i", kind = if (m.startsWith("audio")) FormatKind.AUDIO else FormatKind.VIDEO,
                title = titleFor(c.height, c.label, if (anonymousKept.size > 1) "Video ${i + 1}" else "Video"),
                info = ext.uppercase(), ext = ext, mimeType = m, url = c.url, sizeBytes = size, height = c.height,
            )
        }

        // Anonymous sniffed files next to an HLS stream are usually previews/ads — skip them then.
        val videoOptions = (if (hlsOptions.isNotEmpty() && labeledFiles.isEmpty()) hlsOptions else fileOptions + hlsOptions)
            .filter { it.kind == FormatKind.VIDEO }
            // One choice per quality; a plain file beats HLS (faster, no conversion).
            .groupBy { if (it.height > 0) "h${it.height}" else it.url }
            .map { (_, same) -> same.firstOrNull { it.protocol == StreamProtocol.HTTP } ?: same.first() }
            .sortedWith(compareBy({ it.height == 0 }, { it.height }))
        val audioFiles = fileOptions.filter { it.kind == FormatKind.AUDIO }
        val audioOnly = videoOptions.filter { it.height == 0 || it.height >= 240 }.minByOrNull { if (it.height > 0) it.height else 9999 }
            ?.let {
                it.copy(
                    id = "a-" + it.id, kind = FormatKind.AUDIO, title = "M4A (audio only)", info = "Music from this video",
                    // sizeBytes stays the source video's size: that's what gets downloaded before the audio is kept.
                    ext = "m4a", mimeType = "audio/mp4", extractAudio = true, label = null,
                )
            }
        Result(audioFiles + listOfNotNull(audioOnly) + videoOptions, report.toList())
    }

    private fun host(u: String) = runCatching { java.net.URI(u).host }.getOrNull() ?: u.take(40)

    private suspend fun hlsFromText(c: StreamCandidate, text: String, headers: Map<String, String>): HlsResult {
        val parsed = HlsParser.parse(text, c.url)
        return when (parsed) {
            is HlsPlaylist.Media -> HlsResult(c.url, emptySet(), parsed.durationSec, !parsed.endList,
                listOf(DownloadOption("h-" + c.url.hashCode(), FormatKind.VIDEO, titleFor(c.height, c.label, "Video (HLS)"), "MP4 · HLS", "mp4",
                    "video/mp4", c.url, height = c.height, protocol = StreamProtocol.HLS)), c.label != null)
            is HlsPlaylist.Master -> HlsResult(c.url, parsed.variants.map { it.url }.toSet(), 0.0, false,
                parsed.variants.mapIndexed { i, v ->
                    DownloadOption("h-" + v.url.hashCode(), FormatKind.VIDEO, titleFor(v.height, null, "Quality ${i + 1}"), "MP4 · HLS", "mp4",
                        "video/mp4", v.url, height = v.height, protocol = StreamProtocol.HLS)
                }, true)
        }
    }

    private fun titleFor(height: Int, label: String?, fallback: String): String = when {
        height in 1..360 -> "Fast (${height}p)"
        height in 361..576 -> "Standard (${height}p)"
        height in 577..720 -> "High quality (${height}p)"
        height in 721..1080 -> "Full HD (${height}p)"
        height > 1080 -> "${if (height >= 2160) "4K" else "2K"} (${height}p)"
        label != null -> "Video ($label)"
        else -> fallback
    }

    /** A URL that answers with a JSON list of qualities (common "get media" endpoints). */
    private suspend fun expandList(
        c: StreamCandidate, headers: Map<String, String>, pageFetch: (suspend (String) -> String?)?,
    ): List<StreamCandidate> {
        val direct = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(c.url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
            }.getOrNull()
        }
        parseList(direct).takeIf { it.isNotEmpty() }?.let { return it }
        // Quality lists often need the page's session: ask the page itself to fetch it.
        return parseList(pageFetch?.invoke(c.url))
    }

    private fun parseList(body: String?): List<StreamCandidate> {
        if (body.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(body)
            val items = when (root) {
                is kotlinx.serialization.json.JsonArray -> root
                is kotlinx.serialization.json.JsonObject -> root.values.filterIsInstance<kotlinx.serialization.json.JsonArray>().firstOrNull() ?: emptyList()
                else -> emptyList()
            }
            items.filterIsInstance<kotlinx.serialization.json.JsonObject>().mapNotNull { o ->
                fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
                val url = (str("videoUrl") ?: str("url") ?: str("src") ?: str("file"))?.let(WebMediaFinder::unescape) ?: return@mapNotNull null
                val label = str("quality") ?: str("label") ?: str("height")
                val pretty = label?.let { if (it.matches(Regex("""\d{3,4}"""))) "${it}p" else it }
                StreamCandidate(url, pretty, PageMediaParser.heightOf(label), forcedHls = str("format")?.lowercase() == "hls")
            }
        }.getOrDefault(emptyList())
    }

    private class HlsResult(
        val sourceUrl: String, val variantUrls: Set<String>, val durationSec: Double, val live: Boolean,
        val options: List<DownloadOption>, val labeled: Boolean,
    )

    private suspend fun hlsOptions(c: StreamCandidate, headers: Map<String, String>): HlsResult = when (val p = hls.load(c.url, headers)) {
        is HlsPlaylist.Media -> HlsResult(
            c.url, emptySet(), p.durationSec, !p.endList,
            listOf(
                DownloadOption(
                    id = "h-" + c.url.hashCode(), kind = FormatKind.VIDEO, title = titleFor(c.height, c.label, "Video (HLS)"),
                    info = "MP4 · HLS", ext = "mp4", mimeType = "video/mp4", url = c.url, height = c.height, protocol = StreamProtocol.HLS,
                ),
            ),
            labeled = c.label != null,
        )
        is HlsPlaylist.Master -> {
            val media = hls.loadMedia(p.variants.maxBy { it.bandwidth }.url, headers)
            val dur = media.durationSec
            // A master listed under a quality label (e.g. "720p") with a single variant keeps that label.
            // Skip AV1 variants this phone can't play, when there's any other choice.
            val usable = p.variants.filter { v ->
                val c = v.codecs?.lowercase() ?: return@filter true
                !c.contains("av01") || com.edureminder.quicktube.core.common.DeviceCodecs.canDecodeAv1
            }.ifEmpty { p.variants }
            val options = usable.mapIndexed { i, v ->
                val audio = v.audioGroup?.let { g -> p.audios.filter { it.groupId == g }.let { a -> a.firstOrNull { it.isDefault } ?: a.firstOrNull() } }
                val h = if (v.height > 0) v.height else if (usable.size == 1) c.height else 0
                DownloadOption(
                    id = "h-" + v.url.hashCode(), kind = FormatKind.VIDEO,
                    title = titleFor(h, if (usable.size == 1) c.label else null, "Quality ${i + 1}"),
                    info = "MP4 · HLS", ext = "mp4", mimeType = "video/mp4", url = v.url, audioUrl = audio?.url,
                    sizeBytes = if (v.bandwidth > 0 && dur > 0) (v.bandwidth * dur / 8).toLong() else -1,
                    height = h, protocol = StreamProtocol.HLS,
                )
            }
            HlsResult(c.url, p.variants.map { it.url }.toSet(), dur, !media.endList, options, labeled = c.label != null || p.variants.size > 1)
        }
    }

    /** (candidate, content-type, length) via a 1-byte range request. */
    private suspend fun probe(c: StreamCandidate, headers: Map<String, String>): Triple<StreamCandidate, String, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(c.url).header("Range", "bytes=0-0").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val len = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.body.contentLength()
                Triple(c, r.header("Content-Type").orEmpty(), len)
            }
        }.getOrNull()
    }

    companion object {
        fun isPlaylistUrl(u: String) = u.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") } ||
            u.contains("mpegurl", ignoreCase = true)

        /** Pieces of a stream (HLS/DASH segments) — never a whole video by themselves. */
        fun isSegment(u: String): Boolean {
            val path = u.substringBefore('?').lowercase()
            return path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".aac") && u.contains("seg") ||
                Regex("""[?&](bytestart|byteend|range)=""").containsMatchIn(u) || Regex("""/seg-?\d+|segment\d+|frag\(?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(path)
        }
    }
}
