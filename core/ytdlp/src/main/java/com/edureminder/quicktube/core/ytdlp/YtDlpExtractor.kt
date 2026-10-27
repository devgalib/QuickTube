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
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
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
    override suspend fun update(): String? {
        // One download at a time; callers arriving meanwhile share its result.
        val job = updateLock.withLock {
            inFlight?.takeIf { it.isActive } ?: scope.async { downloadLatest() }.also { inFlight = it }
        }
        return job.await()
    }

    private val updateLock = Mutex()
    private var inFlight: Deferred<String?>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences("ytdlp", Context.MODE_PRIVATE) }

    private suspend fun downloadLatest(): String? = withContext(Dispatchers.IO) {
        if (!awaitReady()) return@withContext null
        prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
        runCatching {
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

    /**
     * The engine bundled in the APK is months old and sites change often, so a fresh install first waits (briefly)
     * for the latest release, and a failure on a stale engine updates it and tries once more.
     */
    override suspend fun extract(url: String, headers: Map<String, String>): ExtractedPage? = withContext(Dispatchers.IO) {
        if (!awaitReady()) return@withContext null
        // Only while it has never been updated and hasn't just tried (GitHub may be unreachable).
        if (prefs.getString("etag", null) == null && System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) > RETRY_UPDATE_AFTER_MS) {
            withTimeoutOrNull(FIRST_UPDATE_WAIT_MS) { update() }
        }
        extractOnce(url, headers) ?: run {
            if (System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) < RETRY_UPDATE_AFTER_MS) return@run null
            Log.i(TAG, "Retrying with the latest yt-dlp")
            withTimeoutOrNull(FIRST_UPDATE_WAIT_MS) { update() }
            extractOnce(url, headers)
        }
    }

    private fun extractOnce(url: String, headers: Map<String, String>): ExtractedPage? {
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
            .onFailure { Log.w(TAG, "yt-dlp failed for $url: ${it.message?.takeLast(600)}") }
            .getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(out).jsonObject }.getOrNull() ?: return null
        // A playlist/multi-video page: take its first real entry.
        val info = (root["entries"] as? JsonArray)?.firstOrNull { it is JsonObject }?.jsonObject ?: root
        return YtDlpFormats.toPage(info)
    }

    companion object {
        private const val TAG = "YtDlp"
        private const val KEY_CHECKED = "checked_at"
        private const val FIRST_UPDATE_WAIT_MS = 45_000L
        private const val RETRY_UPDATE_AFTER_MS = 60 * 60 * 1000L
        private const val RELEASE_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    }
}
