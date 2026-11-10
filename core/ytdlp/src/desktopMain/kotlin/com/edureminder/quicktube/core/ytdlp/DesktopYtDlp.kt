package com.edureminder.quicktube.core.ytdlp

import com.edureminder.quicktube.core.common.DesktopDirs
import com.edureminder.quicktube.core.common.QtLog
import com.edureminder.quicktube.core.extractor.BilibiliReader
import com.edureminder.quicktube.core.extractor.ChainExtractor
import com.edureminder.quicktube.core.extractor.DailymotionReader
import com.edureminder.quicktube.core.extractor.ExtractedPage
import com.edureminder.quicktube.core.extractor.FacebookReader
import com.edureminder.quicktube.core.extractor.UniversalExtractor
import com.edureminder.quicktube.core.extractor.VimeoReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

actual val ytDlpModule: org.koin.core.module.Module = module {
    single { DesktopYtDlpExtractor(get()) }
    single<UniversalExtractor> {
        ChainExtractor(listOf(FacebookReader(get()), DailymotionReader(get(), get()), VimeoReader(get(), get()), BilibiliReader(get()), get<DesktopYtDlpExtractor>()))
    }
}

/**
 * yt-dlp on a computer: the official standalone build (no Python needed), kept up to date in the app's data folder
 * (`bin/yt-dlp`). An installed `yt-dlp` on PATH is used until the app's own copy has been downloaded.
 */
class DesktopYtDlpExtractor(client: OkHttpClient) : UniversalExtractor {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private val prefs = Preferences.userRoot().node("com/edureminder/quicktube/ytdlp")
    private val updateLock = Mutex()

    private val ownBinary = File(DesktopDirs.data, "bin/" + if (DesktopDirs.isWindows) "yt-dlp.exe" else "yt-dlp")

    private fun binary(): String? = ownBinary.takeIf { it.canExecute() }?.path
        ?: System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, if (DesktopDirs.isWindows) "yt-dlp.exe" else "yt-dlp") }.firstOrNull { it.canExecute() }?.path

    override val isReady: Boolean get() = true

    override suspend fun update(): String? = updateLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(RELEASE_URL).apply { prefs.get(KEY_ETAG, null)?.takeIf { ownBinary.exists() }?.let { header("If-None-Match", it) } }.build()
                http.newCall(req).execute().use { r ->
                    prefs.putLong(KEY_CHECKED, System.currentTimeMillis())
                    if (r.code == 304) return@runCatching version()
                    if (!r.isSuccessful) return@runCatching null
                    ownBinary.parentFile.mkdirs()
                    val tmp = File(ownBinary.parentFile, ownBinary.name + ".new")
                    tmp.outputStream().use { r.body.byteStream().copyTo(it) }
                    // A real release is several MB; anything else (an error page) is thrown away.
                    if (tmp.length() < 5_000_000) { tmp.delete(); return@runCatching null }
                    tmp.setExecutable(true)
                    if (!tmp.renameTo(ownBinary)) { ownBinary.delete(); tmp.renameTo(ownBinary) }
                    r.header("ETag")?.let { prefs.put(KEY_ETAG, it) }
                    version()
                }
            }.onFailure { QtLog.w(TAG, "yt-dlp update failed", it) }.getOrNull()
        }
    }

    fun version(): String? = binary()?.let { b -> run(listOf(b, "--version"), 20)?.trim() }

    override suspend fun extract(url: String, headers: Map<String, String>): ExtractedPage? = withContext(Dispatchers.IO) {
        // First use with no yt-dlp anywhere: fetch it now.
        if (binary() == null) update()
        extractOnce(url, headers) ?: run {
            // Sites change; a newer yt-dlp often reads what this one couldn't (checked at most once an hour).
            if (System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) < 60 * 60 * 1000L) return@run null
            update()
            extractOnce(url, headers)
        }
    }

    private fun extractOnce(url: String, headers: Map<String, String>): ExtractedPage? {
        val bin = binary() ?: return null
        val cmd = buildList {
            addAll(listOf(bin, "--dump-single-json", "--no-playlist", "--no-warnings", "--no-check-certificates", "--socket-timeout", "20"))
            headers["User-Agent"]?.let { addAll(listOf("--user-agent", it)) }
            headers["Referer"]?.let { addAll(listOf("--referer", it)) }
            headers["Cookie"]?.let { addAll(listOf("--add-header", "Cookie:$it")) }
            add(url)
        }
        val out = run(cmd, 90) ?: return null
        val root = runCatching { json.parseToJsonElement(out).jsonObject }.getOrNull() ?: return null
        val info = (root["entries"] as? JsonArray)?.firstOrNull { it is JsonObject }?.jsonObject ?: root
        return YtDlpFormats.toPage(info)
    }

    /** Runs [cmd] and returns what it printed, or null when it failed or took longer than [timeoutSec]. */
    private fun run(cmd: List<String>, timeoutSec: Long): String? = runCatching {
        val err = File.createTempFile("qt-ytdlp", ".log")
        try {
            val p = ProcessBuilder(cmd).redirectError(err).start()
            val out = StringBuilder()
            val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching null }
            reader.join(5_000)
            if (p.exitValue() != 0) {
                QtLog.w(TAG, "yt-dlp exited with ${p.exitValue()}: ${err.readText().takeLast(300)}")
                return@runCatching null
            }
            out.toString()
        } finally { err.delete() }
    }.getOrNull()

    private companion object {
        const val TAG = "YtDlp"
        const val KEY_ETAG = "etag"
        const val KEY_CHECKED = "checked_at"
        val RELEASE_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/" + when {
            DesktopDirs.isWindows -> "yt-dlp.exe"
            DesktopDirs.isMac -> "yt-dlp_macos"
            System.getProperty("os.arch").contains("aarch64") -> "yt-dlp_linux_aarch64"
            else -> "yt-dlp_linux"
        }
    }
}
