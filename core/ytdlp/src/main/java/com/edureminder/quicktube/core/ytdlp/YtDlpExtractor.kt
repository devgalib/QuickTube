package com.edureminder.quicktube.core.ytdlp

import android.content.Context
import android.util.Log
import com.edureminder.quicktube.core.extractor.ExtractedPage
import com.edureminder.quicktube.core.extractor.UniversalExtractor
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import com.edureminder.quicktube.core.model.StreamProtocol
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * yt-dlp running on-device (bundled Python). Used for every site the app has no native extractor for.
 * We only ask it for stream URLs (`--dump-single-json`); downloading, HLS and merging stay in our own engine.
 */
class YtDlpExtractor(private val context: Context) : UniversalExtractor {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ready = CompletableDeferred<Boolean>()
    private val initLock = Mutex()
    @Volatile private var started = false

    override val isReady: Boolean get() = ready.isCompleted && runCatching { ready.getCompleted() }.getOrDefault(false)

    /** Unpacks the Python runtime (first launch only, a few seconds). Safe to call repeatedly. */
    suspend fun init() = initLock.withLock {
        if (started) return@withLock
        started = true
        withContext(Dispatchers.IO) {
            val ok = runCatching { YoutubeDL.getInstance().init(context) }
                .onFailure { Log.e(TAG, "yt-dlp init failed", it) }.isSuccess
            ready.complete(ok)
        }
    }

    /**
     * Downloads the latest yt-dlp release straight from its release URL (no GitHub API, so no rate limits)
     * and swaps it in atomically. Skipped when the server says the file hasn't changed (ETag).
     */
    override suspend fun update(): String? = withContext(Dispatchers.IO) {
        if (!awaitReady()) return@withContext null
        runCatching {
            val prefs = context.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
            val target = java.io.File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
            val req = okhttp3.Request.Builder().url(RELEASE_URL)
                .apply { prefs.getString("etag", null)?.let { header("If-None-Match", it) } }
                .build()
            http.newCall(req).execute().use { r ->
                if (r.code == 304) return@use
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val tmp = java.io.File(target.parentFile, "yt-dlp.new")
                r.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                // A valid release is a Python zipapp: shebang line followed by a zip archive.
                val head = ByteArray(4096).let { buf -> val n = tmp.inputStream().use { it.read(buf) }; buf.copyOf(n.coerceAtLeast(0)) }
                val looksValid = tmp.length() > 1_000_000 && String(head, 0, 2) == "#!" && head.indices.any { i ->
                    i + 1 < head.size && head[i] == 'P'.code.toByte() && head[i + 1] == 'K'.code.toByte()
                }
                if (!looksValid) { tmp.delete(); error("downloaded file isn't a yt-dlp release") }
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
                r.header("ETag")?.let { prefs.edit().putString("etag", it).apply() }
            }
            YoutubeDL.getInstance().version(context)
        }.onFailure { Log.w(TAG, "yt-dlp update failed: ${it.message}") }.getOrNull()
    }

    fun version(): String? = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    private val http = okhttp3.OkHttpClient.Builder()
        .followRedirects(true).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()

    private suspend fun awaitReady(): Boolean {
        if (!started) init()
        return withTimeoutOrNull(60_000) { ready.await() } ?: false
    }

    override suspend fun extract(url: String, headers: Map<String, String>): ExtractedPage? = withContext(Dispatchers.IO) {
        if (!awaitReady()) return@withContext null
        val req = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-check-certificates")
            addOption("--socket-timeout", "20")
            headers["User-Agent"]?.let { addOption("--user-agent", it) }
            headers["Referer"]?.let { addOption("--referer", it) }
            headers["Cookie"]?.let { addOption("--add-header", "Cookie:$it") }
        }
        val out = runCatching { YoutubeDL.getInstance().execute(req).out }
            .onFailure { Log.w(TAG, "yt-dlp failed for $url: ${it.message?.take(300)}") }
            .getOrNull() ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(out).jsonObject }.getOrNull() ?: return@withContext null
        // A playlist/multi-video page: take its first real entry.
        val info = (root["entries"] as? JsonArray)?.firstOrNull { it is JsonObject }?.jsonObject ?: root
        toPage(info)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "none" }
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    private fun toPage(info: JsonObject): ExtractedPage? {
        val title = info.str("title") ?: info.str("id") ?: "video"
        val pageHeaders = (info["http_headers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        val formats = (info["formats"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?: listOfNotNull(info.takeIf { it.str("url") != null })
        data class F(
            val url: String, val ext: String, val height: Int, val hasV: Boolean, val hasA: Boolean,
            val proto: StreamProtocol?, val size: Long, val abr: Double, val tbr: Double, val note: String?,
        )
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
                url = url, ext = ext, height = (f.num("height") ?: 0.0).toInt(),
                hasV = vcodec != null || (vcodec == null && acodec == null && f.num("height") != null),
                hasA = acodec != null || (vcodec == null && acodec == null),
                proto = proto, size = f.long("filesize") ?: f.long("filesize_approx") ?: -1,
                abr = f.num("abr") ?: 0.0, tbr = f.num("tbr") ?: 0.0, note = f.str("format_note"),
            )
        }
        if (fs.isEmpty()) return null
        val options = mutableListOf<DownloadOption>()
        val audioOnly = fs.filter { it.hasA && !it.hasV }
        val bestAudioFor = { proto: StreamProtocol ->
            audioOnly.filter { it.proto == proto && (it.ext == "m4a" || it.ext == "mp4" || proto == StreamProtocol.HLS) }.maxByOrNull { it.abr }
        }
        audioOnly.sortedByDescending { it.abr }.distinctBy { it.ext }.take(2).forEachIndexed { i, a ->
            options += DownloadOption(
                id = "ya$i", kind = FormatKind.AUDIO,
                title = (if (a.ext == "m4a") "M4A" else a.ext.uppercase()) + if (a.abr > 0) " (${a.abr.toInt()}K)" else "",
                info = "Audio", ext = if (a.proto == StreamProtocol.HLS) "m4a" else a.ext, mimeType = if (a.ext == "mp3") "audio/mpeg" else "audio/mp4",
                url = a.url, sizeBytes = a.size, bitrate = a.abr.toInt(), protocol = a.proto!!,
            )
        }
        // One choice per height: muxed file > video+audio merge > HLS.
        val videos = fs.filter { it.hasV }.groupBy { it.height }.mapNotNull { (h, list) ->
            val muxed = list.filter { it.hasA }.sortedWith(compareBy({ it.proto != StreamProtocol.HTTP }, { -it.tbr }))
            val pick = muxed.firstOrNull { it.ext == "mp4" } ?: muxed.firstOrNull()
            if (pick != null) return@mapNotNull Triple(h, pick, null as F?)
            val vOnly = list.filter { it.ext == "mp4" || it.proto == StreamProtocol.HLS }.maxByOrNull { it.tbr } ?: return@mapNotNull null
            val audio = bestAudioFor(vOnly.proto!!) ?: return@mapNotNull null
            Triple(h, vOnly, audio)
        }.sortedBy { it.first }
        videos.forEachIndexed { i, (h, v, a) ->
            val q = if (h > 0) "${h}p" else (v.note ?: "Video")
            options += DownloadOption(
                id = "yv$i", kind = FormatKind.VIDEO,
                title = when {
                    h in 1..360 -> "Fast ($q)"; h in 361..576 -> "Standard ($q)"; h in 577..720 -> "High quality ($q)"
                    h in 721..1080 -> "Full HD ($q)"; h > 1080 -> "${if (h >= 2160) "4K" else "2K"} ($q)"; else -> q
                },
                info = "MP4" + if (v.proto == StreamProtocol.HLS) " · HLS" else "",
                ext = "mp4", mimeType = "video/mp4", url = v.url, audioUrl = a?.url,
                sizeBytes = v.size, audioSizeBytes = a?.size ?: -1, height = h, protocol = v.proto!!,
                label = if (h >= 1080) "HD" else null,
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

    companion object {
        private const val TAG = "YtDlp"
        private const val RELEASE_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    }
}
