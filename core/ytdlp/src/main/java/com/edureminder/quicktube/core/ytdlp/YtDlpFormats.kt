package com.edureminder.quicktube.core.ytdlp

import com.edureminder.quicktube.core.common.DeviceCodecs
import com.edureminder.quicktube.core.extractor.ExtractedPage
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import com.edureminder.quicktube.core.model.StreamProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Turns yt-dlp's JSON for one video into download options, one per height, choosing formats this phone can save. */
internal object YtDlpFormats {
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "none" }
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    fun toPage(info: JsonObject, av1Playable: Boolean = DeviceCodecs.canDecodeAv1): ExtractedPage? {
        // Facebook titles start with "350K views · 1.3K reactions | "; the caption is what people want to see.
        val title = (info.str("title") ?: info.str("id") ?: "video")
            .replace(Regex("""^(?:[\d.,]+[KMB]?\s+(?:views?|reactions?|comments?|shares?|plays?)\s*[·•]?\s*)+\|\s*"""), "")
            .ifBlank { info.str("id") ?: "video" }
        val duration = info.num("duration")
        val formats = (info["formats"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?: listOfNotNull(info.takeIf { it.str("url") != null })
        // Some sites' media servers only answer with the cookies yt-dlp got while extracting (e.g. TikTok).
        val cookies = (listOf(info) + formats).firstNotNullOfOrNull { it.str("cookies") }?.let(::cookieHeader)
        val pageHeaders = (info["http_headers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty() +
            (cookies?.let { mapOf("Cookie" to it) } ?: emptyMap())
        data class F(
            val url: String, val ext: String, val height: Int, val width: Int, val hasV: Boolean, val hasA: Boolean,
            val proto: StreamProtocol?, val size: Long, val abr: Double, val tbr: Double, val note: String?,
            val vcodec: String?, val acodec: String?, val id: String?, val quality: Double,
        ) {
            val video get() = Codecs.video(vcodec, av1Playable)
            val audio get() = Codecs.audio(acodec, ext)
        }
        val fs = formats.mapNotNull { f ->
            val url = f.str("url") ?: return@mapNotNull null
            val p = f.str("protocol").orEmpty()
            val proto = when {
                p.startsWith("http") && !p.contains("dash") -> StreamProtocol.HTTP
                p.startsWith("m3u8") -> StreamProtocol.HLS
                else -> null // DASH fragments, RTMP, mhtml storyboards: not supported by our engine
            } ?: return@mapNotNull null
            val vcodec = f.str("vcodec")
            val acodec = f.str("acodec")
            val ext = f.str("ext") ?: "mp4"
            if (ext == "mhtml") return@mapNotNull null
            F(
                url = url, ext = ext, height = (f.num("height") ?: 0.0).toInt(), width = (f.num("width") ?: 0.0).toInt(),
                // No codec info at all (e.g. Facebook's progressive "sd"/"hd" files): a video container means video + audio.
                hasV = vcodec != null || (vcodec == null && acodec == null && (f.num("height") != null || ext in VIDEO_EXTS)),
                hasA = acodec != null || (vcodec == null && acodec == null),
                // Exact size, yt-dlp's estimate, or bitrate × duration; the sheet asks the server for any still unknown.
                proto = proto, size = f.long("filesize") ?: f.long("filesize_approx")
                    ?: (f.num("tbr") ?: f.num("vbr") ?: f.num("abr"))?.let { kbps -> duration?.let { (kbps * 1000 / 8 * it).toLong() } } ?: -1,
                abr = f.num("abr") ?: 0.0, tbr = f.num("tbr") ?: 0.0, note = f.str("format_note"),
                vcodec = vcodec, acodec = acodec, id = f.str("format_id"), quality = f.num("quality") ?: 0.0,
            )
        }
        if (fs.isEmpty()) return null
        val options = mutableListOf<DownloadOption>()
        val audioOnly = fs.filter { it.hasA && !it.hasV }
        // Audio that can sit next to a video in the given container (HLS audio is remuxed, so any codec the muxer takes).
        val bestAudioFor = { proto: StreamProtocol, webm: Boolean ->
            audioOnly.filter { a ->
                a.proto == proto && when (a.audio) {
                    Codecs.Audio.AAC -> !webm
                    Codecs.Audio.OPUS -> webm
                    Codecs.Audio.UNKNOWN -> !webm && (a.ext == "m4a" || a.ext == "mp4" || proto == StreamProtocol.HLS)
                    Codecs.Audio.OTHER -> false
                }
            }.maxByOrNull { it.abr }
        }
        // Every distinct audio format, best first (the sheet shows the top two; "More formats" shows them all).
        audioOnly.sortedByDescending { it.abr }.distinctBy { it.ext to (it.abr / 8).toInt() }.take(8).forEachIndexed { i, a ->
            options += DownloadOption(
                id = "ya$i", kind = FormatKind.AUDIO,
                title = (if (a.ext == "m4a") "M4A" else a.ext.uppercase()) + if (a.abr > 0) " (${a.abr.toInt()}K)" else "",
                info = "Audio", ext = if (a.proto == StreamProtocol.HLS) "m4a" else a.ext,
                mimeType = when (a.ext) { "mp3" -> "audio/mpeg"; "webm" -> "audio/webm"; "ogg", "opus" -> "audio/ogg"; else -> "audio/mp4" },
                url = a.url, sizeBytes = a.size, bitrate = a.abr.toInt(), protocol = a.proto!!,
            )
        }
        // One choice per height: muxed file > video+audio merge > HLS.
        // Codecs are ranked by what plays and merges everywhere: H.264 first, AV1 only where the muxer takes it.
        // Files of unknown height can't be compared, so each one that has its own sound is offered separately.
        val videos = fs.filter { it.hasV }.groupBy { if (it.height > 0 || !it.hasA) "h${it.height}" else "x${it.id ?: it.url}" }.mapNotNull { (_, list) ->
            val h = list.first().height
            val muxed = list.filter { it.hasA && it.video.rank < Codecs.UNUSABLE }
                .sortedWith(compareBy({ it.proto != StreamProtocol.HTTP }, { it.ext != "mp4" }, { it.video.rank }, { -it.tbr }))
            muxed.firstOrNull()?.let { return@mapNotNull Triple(h, it, null as F?) }
            val vOnly = list.filter { !it.hasA && it.video.rank < Codecs.UNUSABLE && (it.ext in setOf("mp4", "webm") || it.proto == StreamProtocol.HLS) }
                .sortedWith(compareBy({ it.video.rank }, { -it.tbr }))
            // AAC → MP4 (plays everywhere) first; VP9 can also go with Opus into WebM.
            vOnly.firstNotNullOfOrNull { v ->
                (bestAudioFor(v.proto!!, false) ?: if (v.video == Codecs.Video.VP9) bestAudioFor(v.proto, true) else null)
                    ?.let { a -> Triple(h, v, a) }
            }
        }.sortedWith(compareBy({ it.first }, { it.second.quality }))
        // Several files of unknown size (yt-dlp lists them worst to best): name them by rank, not all "Video".
        val unsized = videos.filter { it.first == 0 && (it.second.note ?: it.second.id)?.lowercase() !in setOf("sd", "hd") }
        videos.forEachIndexed { i, (h, v, a) ->
            val webm = if (a != null) a.audio == Codecs.Audio.OPUS else v.ext == "webm"
            // Vertical videos are named by their short side, like the apps do: 720x1280 is 720p.
            val res = if (v.width in 1 until h) v.width else h
            val q = if (h > 0) "${res}p" else when ((v.note ?: v.id)?.lowercase()) {
                "sd" -> "Standard (SD)"
                "hd" -> "High quality (HD)"
                else -> v.note ?: when (val rank = unsized.indexOfFirst { it.second === v }) {
                    -1, 0 -> if (unsized.size > 1) "Low quality" else "Video"
                    unsized.lastIndex -> "Best quality"
                    else -> if (unsized.size == 3) "Medium quality" else "Medium quality $rank"
                }
            }
            options += DownloadOption(
                id = "yv$i", kind = FormatKind.VIDEO,
                title = when {
                    h <= 0 -> q
                    res <= 360 -> "Fast ($q)"; res <= 576 -> "Standard ($q)"; res <= 720 -> "High quality ($q)"
                    res <= 1100 -> "Full HD ($q)"; else -> "${if (res >= 2000) "4K" else "2K"} ($q)"
                },
                info = (if (webm) "WEBM" else "MP4") + if (v.proto == StreamProtocol.HLS) " · HLS" else "",
                ext = if (webm) "webm" else "mp4", mimeType = if (webm) "video/webm" else "video/mp4", url = v.url, audioUrl = a?.url,
                sizeBytes = v.size, audioSizeBytes = a?.size ?: -1, height = res, protocol = v.proto!!,
                label = if (res >= 1080) "HD" else null,
            )
        }
        if (audioOnly.isEmpty()) videos.firstOrNull { it.first >= 240 || it.first == 0 }?.let { (h, v, a) ->
            options.add(0, DownloadOption(
                id = "yx", kind = FormatKind.AUDIO, title = "M4A (audio only)", info = "Music from this video", ext = "m4a", mimeType = "audio/mp4",
                url = v.url, audioUrl = a?.url, sizeBytes = v.size, height = h, protocol = v.proto!!, extractAudio = true,
            ))
        }
        if (options.isEmpty()) return null
        return ExtractedPage(
            title = title,
            thumbnail = info.str("thumbnail"),
            durationSec = info.num("duration"),
            options = options,
            headers = pageHeaders,
        )
    }

    private val VIDEO_EXTS = setOf("mp4", "webm", "mov", "flv", "3gp", "mkv", "m4v")

    /** yt-dlp's cookie string ("a=1; Domain=.x.com; Path=/; Secure; b=2; …") → a Cookie header ("a=1; b=2"). */
    internal fun cookieHeader(raw: String): String? = raw.split(';').map { it.trim() }
        .filter { '=' in it && it.substringBefore('=').lowercase() !in COOKIE_ATTRIBUTES }
        .joinToString("; ").takeIf { it.isNotEmpty() }

    private val COOKIE_ATTRIBUTES = setOf("domain", "path", "expires", "max-age", "samesite", "secure", "httponly", "comment", "version")
}
