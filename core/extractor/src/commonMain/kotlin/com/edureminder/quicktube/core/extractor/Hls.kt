package com.edureminder.quicktube.core.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI

/** Parsed HLS (.m3u8) playlist. */
sealed interface HlsPlaylist {
    data class Master(val variants: List<Variant>, val audios: List<Rendition>) : HlsPlaylist
    data class Media(
        val segments: List<Segment>,
        val endList: Boolean,
        val init: Segment?,
        val mediaSequence: Long,
    ) : HlsPlaylist {
        val durationSec get() = segments.sumOf { it.durationSec }
    }

    data class Variant(val url: String, val bandwidth: Long, val width: Int, val height: Int, val codecs: String?, val audioGroup: String?)
    data class Rendition(val url: String, val groupId: String, val name: String?, val isDefault: Boolean)
    data class Key(val method: String, val url: String?, val iv: ByteArray?)
    data class Segment(
        val url: String,
        val durationSec: Double,
        val byteRange: LongRange? = null,
        val key: Key? = null,
        val sequence: Long = 0,
    )
}

object HlsParser {
    fun isPlaylist(body: String) = body.trimStart().startsWith("#EXTM3U")

    fun parse(body: String, baseUrl: String): HlsPlaylist {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) parseMaster(lines, baseUrl) else parseMedia(lines, baseUrl)
    }

    private fun parseMaster(lines: List<String>, base: String): HlsPlaylist.Master {
        val variants = mutableListOf<HlsPlaylist.Variant>()
        val audios = mutableListOf<HlsPlaylist.Rendition>()
        var pending: Map<String, String>? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attrs(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attrs(line.substringAfter(':'))
                    if (a["TYPE"] == "AUDIO" && a["URI"] != null) audios += HlsPlaylist.Rendition(
                        resolve(base, a.getValue("URI")), a["GROUP-ID"].orEmpty(), a["NAME"], a["DEFAULT"] == "YES",
                    )
                }
                !line.startsWith("#") && pending != null -> {
                    val a = pending
                    val res = a["RESOLUTION"]?.split('x')
                    variants += HlsPlaylist.Variant(
                        url = resolve(base, line),
                        bandwidth = (a["AVERAGE-BANDWIDTH"] ?: a["BANDWIDTH"])?.toLongOrNull() ?: 0,
                        width = res?.getOrNull(0)?.toIntOrNull() ?: 0,
                        height = res?.getOrNull(1)?.toIntOrNull() ?: 0,
                        codecs = a["CODECS"],
                        audioGroup = a["AUDIO"],
                    )
                    pending = null
                }
            }
        }
        return HlsPlaylist.Master(variants, audios)
    }

    private fun parseMedia(lines: List<String>, base: String): HlsPlaylist.Media {
        val segments = mutableListOf<HlsPlaylist.Segment>()
        var duration = 0.0
        var key: HlsPlaylist.Key? = null
        var init: HlsPlaylist.Segment? = null
        var range: LongRange? = null
        var lastRangeEnd = 0L
        var sequence = 0L
        var mediaSequence = 0L
        var endList = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> { mediaSequence = line.substringAfter(':').toLongOrNull() ?: 0; sequence = mediaSequence }
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(line.substringAfter(':'))
                    val method = a["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null else HlsPlaylist.Key(method, a["URI"]?.let { resolve(base, it) }, a["IV"]?.let(::hexToBytes))
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(line.substringAfter(':'))
                    val r = a["BYTERANGE"]?.let { parseRange(it, 0) }
                    init = a["URI"]?.let { HlsPlaylist.Segment(resolve(base, it), 0.0, r) }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> range = parseRange(line.substringAfter(':'), lastRangeEnd)
                line.startsWith("#EXT-X-ENDLIST") -> endList = true
                !line.startsWith("#") -> {
                    segments += HlsPlaylist.Segment(resolve(base, line), duration, range, key, sequence++)
                    range?.let { lastRangeEnd = it.last + 1 }
                    range = null
                    duration = 0.0
                }
            }
        }
        return HlsPlaylist.Media(segments, endList, init, mediaSequence)
    }

    /** "length[@offset]" → inclusive byte range. */
    private fun parseRange(v: String, defaultStart: Long): LongRange? {
        val len = v.substringBefore('@').toLongOrNull() ?: return null
        val start = if ('@' in v) v.substringAfter('@').toLongOrNull() ?: defaultStart else defaultStart
        return start until start + len
    }

    fun attrs(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(s).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2].trim('"')
        }
        return out
    }

    fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref.replace(" ", "%20")).toString() }.getOrDefault(ref)

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

/** Fetches playlists with the same headers (cookies, referer, UA) the page used. */
class HlsClient(private val client: OkHttpClient) {
    suspend fun load(url: String, headers: Map<String, String>): HlsPlaylist = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body.string()
            if (!HlsParser.isPlaylist(body)) throw IOException("Not an HLS playlist")
            HlsParser.parse(body, r.request.url.toString())
        }
    }

    /** Loads a media playlist, following a master playlist to its best variant if needed. */
    suspend fun loadMedia(url: String, headers: Map<String, String>): HlsPlaylist.Media = when (val p = load(url, headers)) {
        is HlsPlaylist.Media -> p
        is HlsPlaylist.Master -> loadMedia(p.variants.maxBy { it.bandwidth }.url, headers)
    }
}
