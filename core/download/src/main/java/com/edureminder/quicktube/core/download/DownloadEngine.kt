package com.edureminder.quicktube.core.download

import android.content.Context
import android.media.MediaScannerConnection
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.database.DownloadDao
import com.edureminder.quicktube.core.database.DownloadEntity
import com.edureminder.quicktube.core.database.asModel
import com.edureminder.quicktube.core.extractor.HlsClient
import com.edureminder.quicktube.core.extractor.HlsPlaylist
import com.edureminder.quicktube.core.model.StreamProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.edureminder.quicktube.core.model.ContentType
import com.edureminder.quicktube.core.model.DownloadRequest
import com.edureminder.quicktube.core.model.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/**
 * Queue + worker pool. Each task downloads in ranged chunks (resumable, avoids server throttling),
 * then optionally merges a separate audio stream into the video container.
 */
internal class DownloadEngine(
    private val context: Context,
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
    private val media: MediaRepository,
    private val network: NetworkMonitor,
    client: OkHttpClient,
    private val notifier: DownloadNotifier,
    private val hls: HlsClient,
    private val analytics: com.edureminder.quicktube.core.analytics.Analytics,
) : DownloadManager {

    private val http = client.newBuilder().retryOnConnectionFailure(true).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val scheduleLock = Mutex()
    private val _speeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    override val speeds: StateFlow<Map<Long, Long>> = _speeds
    @Volatile private var started = false

    override fun start() {
        if (started) return
        started = true
        scope.launch {
            // Anything "running" when the process died is resumed.
            dao.byStatus(listOf(DownloadStatus.RUNNING, DownloadStatus.MERGING)).forEach {
                dao.updateStatus(it.id, DownloadStatus.QUEUED)
            }
            reschedule()
        }
        scope.launch { network.type.drop(1).collect { reschedule() } }
        scope.launch {
            settings.settings.map { Triple(it.maxTasksWifi, it.maxTasksMobile, it.allowMobileData) }
                .distinctUntilChanged().drop(1).collect { reschedule() }
        }
    }

    override suspend fun enqueue(request: DownloadRequest): Long {
        val s = settings.current()
        // Public Downloads is the default; fall back to app storage if this device/OS version won't let us write there.
        val dir = File(s.downloadDir).apply { mkdirs() }.takeIf { it.canWrite() }
            ?: File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "QuickTube").apply { mkdirs() }
        val base = Urls.safeFileName(request.title)
        val suffix = request.quality?.let { "($it)" }.orEmpty()
        var file = File(dir, "$base$suffix.${request.ext}")
        var n = 1
        while (file.exists() || File(file.path + ".part").exists()) {
            file = File(dir, "$base$suffix ($n).${request.ext}"); n++
        }
        val headers = if (request.headers.isEmpty()) null
        else JsonObject(request.headers.mapValues { JsonPrimitive(it.value) }).toString()
        val id = dao.insert(
            DownloadEntity(
                title = request.title,
                pageUrl = request.pageUrl,
                sourceHost = Urls.host(request.pageUrl ?: request.url),
                thumbnail = request.thumbnail,
                url = request.url,
                audioUrl = request.audioUrl,
                filePath = file.absolutePath,
                mimeType = request.mimeType,
                type = ContentType.fromMime(request.mimeType),
                quality = request.quality,
                totalBytes = request.sizeBytes,
                downloadedBytes = 0,
                audioTotalBytes = if (request.audioUrl != null) request.audioSizeBytes else -1,
                audioDownloadedBytes = 0,
                status = DownloadStatus.QUEUED,
                error = null,
                durationSec = request.durationSec,
                createdAt = System.currentTimeMillis(),
                completedAt = 0,
                inVault = false,
                deletedAt = 0,
                lastPlayedPositionMs = 0,
                headers = headers,
                protocol = request.protocol,
                extractAudio = request.extractAudio,
            ),
        )
        reschedule()
        return id
    }

    override fun pause(id: Long) {
        jobs.remove(id)?.cancel()
        scope.launch {
            dao.updateStatus(id, DownloadStatus.PAUSED)
            reschedule()
        }
    }

    override fun resume(id: Long) {
        scope.launch {
            dao.updateStatus(id, DownloadStatus.QUEUED)
            reschedule()
        }
    }

    override fun pauseAll() {
        scope.launch {
            dao.byStatus(listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING_NETWORK)).forEach {
                jobs.remove(it.id)?.cancel()
                dao.updateStatus(it.id, DownloadStatus.PAUSED)
            }
            reschedule()
        }
    }

    override fun resumeAll() {
        scope.launch {
            dao.byStatus(listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)).forEach {
                dao.updateStatus(it.id, DownloadStatus.QUEUED)
            }
            reschedule()
        }
    }

    override suspend fun remove(ids: List<Long>) {
        ids.forEach { id ->
            jobs.remove(id)?.cancel()
            val e = dao.get(id) ?: return@forEach
            if (e.status != DownloadStatus.COMPLETED) {
                File(e.filePath + ".part").delete()
                File(e.filePath + ".audio.part").delete()
                File(e.filePath + ".part.idx").delete()
                File(e.filePath + ".audio.part.idx").delete()
                dao.delete(listOf(id))
            } else {
                dao.update(e.copy(deletedAt = System.currentTimeMillis()))
            }
        }
        reschedule()
    }

    override fun reschedule() {
        scope.launch {
            scheduleLock.withLock {
                val s = settings.current()
                val net = network.currentType()
                val active = dao.byStatus(listOf(DownloadStatus.RUNNING, DownloadStatus.MERGING))
                val queued = dao.byStatus(listOf(DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK))
                val blocked = net == NetworkType.NONE || (net == NetworkType.CELLULAR && !s.allowMobileData)
                if (blocked) {
                    queued.filter { it.status == DownloadStatus.QUEUED }
                        .forEach { dao.updateStatus(it.id, DownloadStatus.WAITING_NETWORK) }
                } else {
                    val max = if (net == NetworkType.WIFI) s.maxTasksWifi else s.maxTasksMobile
                    var slots = max - active.count { jobs.containsKey(it.id) }
                    for (task in queued) {
                        if (slots <= 0) {
                            if (task.status == DownloadStatus.WAITING_NETWORK) dao.updateStatus(task.id, DownloadStatus.QUEUED)
                            continue
                        }
                        if (jobs.containsKey(task.id)) continue
                        slots--
                        dao.updateStatus(task.id, DownloadStatus.RUNNING)
                        jobs[task.id] = scope.launch { runTask(task.id) }
                    }
                }
                notifier.refresh(hasWork = jobs.isNotEmpty() || queued.isNotEmpty() && !blocked)
            }
        }
    }

    private suspend fun runTask(id: Long) {
        var entity = dao.get(id) ?: return
        try {
            val target = File(entity.filePath)
            target.parentFile?.mkdirs()
            val headers = entity.headers?.let { h ->
                Json.parseToJsonElement(h).jsonObject.mapValues { it.value.jsonPrimitive.content }
            }.orEmpty()
            val videoPart = File(entity.filePath + ".part")
            val audioPart = File(entity.filePath + ".audio.part")

            val videoOut = if (entity.extractAudio) File(target.path + ".video.mp4") else target
            val isHls = entity.protocol == StreamProtocol.HLS
            entity = if (isHls) fetchHls(entity, isAudio = false, videoPart, headers) else fetchWithRefresh(entity, isAudio = false, videoPart, headers)
            if (entity.audioUrl != null) {
                entity = if (isHls) fetchHls(entity, isAudio = true, audioPart, headers) else fetchWithRefresh(entity, isAudio = true, audioPart, headers)
                dao.updateStatus(id, DownloadStatus.MERGING)
                notifier.refresh(true)
                MediaMerger.merge(videoPart, audioPart, videoOut)
                videoPart.delete(); audioPart.delete()
            } else if (isHls) {
                // HLS arrives as MPEG-TS / fragmented MP4 pieces; package it as a regular MP4.
                dao.updateStatus(id, DownloadStatus.MERGING)
                notifier.refresh(true)
                try {
                    MediaMerger.remux(videoPart, videoOut)
                    videoPart.delete()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (entity.extractAudio) throw e
                    val ts = File(target.path.substringBeforeLast('.') + ".ts")
                    if (!videoPart.renameTo(ts)) throw IOException("Could not save file")
                    dao.get(id)?.let { dao.update(it.copy(filePath = ts.absolutePath, mimeType = "video/mp2t")) }
                }
            } else {
                if (videoOut.exists()) videoOut.delete()
                if (!videoPart.renameTo(videoOut)) throw IOException("Could not save file")
            }
            if (entity.extractAudio) {
                // "Audio only" from a website video: keep just the sound track as .m4a.
                dao.updateStatus(id, DownloadStatus.MERGING)
                notifier.refresh(true)
                val src = File(dao.get(id)?.filePath?.takeIf { it != target.path } ?: videoOut.path)
                try {
                    MediaMerger.extractAudio(src, target)
                } finally {
                    src.delete()
                }
                dao.get(id)?.let { dao.update(it.copy(filePath = target.absolutePath, mimeType = "audio/mp4", type = ContentType.AUDIO)) }
            }
            val saved = File(dao.get(id)?.filePath ?: target.path)
            val probedDuration = if (entity.durationSec > 0) entity.durationSec else probeDurationSec(saved)
            val done = (dao.get(id) ?: return).copy(
                durationSec = probedDuration,
                status = DownloadStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                totalBytes = saved.length(),
                downloadedBytes = saved.length(),
                audioTotalBytes = -1, audioDownloadedBytes = 0,
                error = null,
            )
            dao.update(done)
            File(saved.path + ".idx").delete()
            MediaScannerConnection.scanFile(context, arrayOf(saved.absolutePath), arrayOf(done.mimeType), null)
            notifier.completed(done.asModel())
            analytics.event(com.edureminder.quicktube.core.analytics.Events.DOWNLOAD_COMPLETED,
                mapOf("kind" to done.type.name.lowercase(), "protocol" to done.protocol.name.lowercase()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val net = network.currentType()
            val status = if (net == NetworkType.NONE) DownloadStatus.WAITING_NETWORK else DownloadStatus.FAILED
            dao.updateStatus(id, status, friendlyError(e))
            if (status == DownloadStatus.FAILED) {
                dao.get(id)?.let { notifier.failed(it) }
                // Only the error type, never the message (it can contain URLs).
                analytics.event(com.edureminder.quicktube.core.analytics.Events.DOWNLOAD_FAILED,
                    mapOf("error" to (if (e is HttpStatusException) "http_${e.code}" else e.javaClass.simpleName)))
            }
        } finally {
            jobs.remove(id)
            _speeds.update { it - id }
            reschedule()
        }
    }

    private fun probeDurationSec(file: File): Long = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1000) / 1000
        } finally { r.release() }
    }.getOrDefault(-1)

    private fun friendlyError(e: Throwable) = when {
        e is HttpStatusException && (e.code == 401 || e.code == 403) -> "Link expired or login required"
        e is HttpStatusException && e.code == 404 -> "File not found"
        e is java.net.UnknownHostException -> "No internet connection"
        e is java.net.SocketTimeoutException -> "Connection timed out"
        e.message?.contains("ENOSPC") == true -> "Not enough storage"
        else -> e.message ?: "Download failed"
    }

    /** Downloads a stream; when a YouTube link expired (403) it re-extracts fresh URLs once. */
    private suspend fun fetchWithRefresh(e: DownloadEntity, isAudio: Boolean, part: File, headers: Map<String, String>): DownloadEntity {
        var entity = e
        try {
            fetch(entity, isAudio, part, headers)
        } catch (ex: HttpStatusException) {
            val page = entity.pageUrl
            if ((ex.code == 403 || ex.code == 410) && page != null && media.canExtract(page)) {
                entity = refreshUrls(entity) ?: throw ex
                fetch(entity, isAudio, part, headers)
            } else throw ex
        }
        return dao.get(entity.id) ?: entity
    }

    private suspend fun refreshUrls(e: DownloadEntity): DownloadEntity? {
        val details = runCatching { media.details(e.pageUrl!!, forceRefresh = true) }.getOrNull() ?: return null
        val option = details.options.firstOrNull { opt -> e.quality?.let { q -> opt.title.contains(q) } == true }
            ?: details.options.firstOrNull { it.mimeType == e.mimeType && (it.audioUrl != null) == (e.audioUrl != null) }
            ?: return null
        val updated = (dao.get(e.id) ?: e).copy(url = option.url, audioUrl = option.audioUrl)
        dao.update(updated)
        return updated
    }

    private suspend fun fetch(e: DownloadEntity, isAudio: Boolean, part: File, headers: Map<String, String>) {
        val url = if (isAudio) e.audioUrl!! else e.url
        var total = if (isAudio) e.audioTotalBytes else e.totalBytes
        var done = if (part.exists()) part.length() else 0L
        if (total > 0 && done >= total) return
        if (total <= 0) {
            total = probeLength(url, headers)
            val cur = dao.get(e.id) ?: return
            dao.update(if (isAudio) cur.copy(audioTotalBytes = total) else cur.copy(totalBytes = total))
        }
        val limiter = RateLimiter { settings.current().speedLimitKbps * 1024L / jobs.size.coerceAtLeast(1) }
        val speed = SpeedMeter()
        var lastSave = 0L
        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(done)
            while (total <= 0 || done < total) {
                currentCoroutineContext().ensureActive()
                val end = if (total > 0) minOf(done + CHUNK - 1, total - 1) else done + CHUNK - 1
                val req = Request.Builder().url(url)
                    .header("User-Agent", headers["User-Agent"] ?: UA)
                    .header("Range", "bytes=$done-$end")
                    .apply { headers.forEach { (k, v) -> if (k != "User-Agent") header(k, v) } }
                    .build()
                var read = 0L
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 416) { total = done; return@use }
                    if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                    if (resp.code == 200 && done > 0) {
                        // Server ignored Range: restart from scratch.
                        raf.setLength(0); raf.seek(0); done = 0
                    }
                    // The server's length wins over any size estimate we were given.
                    val serverTotal = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: resp.body.contentLength().takeIf { it > 0 && resp.code == 200 }
                    if (serverTotal != null && serverTotal > 0) total = serverTotal
                    val input = resp.body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n; read += n
                        limiter.consume(n)
                        speed.add(n)
                        val now = System.currentTimeMillis()
                        if (now - lastSave > 500) {
                            lastSave = now
                            if (isAudio) dao.updateProgress(e.id, (dao.get(e.id)?.downloadedBytes ?: 0), done)
                            else dao.updateProgress(e.id, done, 0)
                            _speeds.update { it + (e.id to speed.bytesPerSecond()) }
                            notifier.refresh(true)
                        }
                    }
                }
                if (read == 0L) break // no more data (unknown total length)
                if (total <= 0 && read < CHUNK) break
            }
        }
        val cur = dao.get(e.id) ?: return
        dao.update(
            if (isAudio) cur.copy(audioDownloadedBytes = done, audioTotalBytes = maxOf(total, done))
            else cur.copy(downloadedBytes = done, totalBytes = maxOf(total, done)),
        )
    }

    /**
     * Downloads every segment of an HLS media playlist into [part], in order, a few at a time.
     * Progress ("segments done" + byte offset) is kept in [part].idx so pauses resume mid-stream.
     */
    private suspend fun fetchHls(e: DownloadEntity, isAudio: Boolean, part: File, headers: Map<String, String>): DownloadEntity {
        val url = if (isAudio) e.audioUrl!! else e.url
        val playlist = hls.loadMedia(url, headers)
        if (!playlist.endList) throw IOException("Live streams can't be downloaded")
        val segments = playlist.segments
        if (segments.isEmpty()) throw IOException("Empty playlist")
        val idx = File(part.path + ".idx")
        var done = 0
        var offset = 0L
        idx.takeIf { it.exists() && part.exists() }?.readText()?.split(' ')?.let { v ->
            done = v.getOrNull(0)?.toIntOrNull() ?: 0
            offset = v.getOrNull(1)?.toLongOrNull() ?: 0
        }
        if (done == 0) { part.delete(); offset = 0 }
        val keys = ConcurrentHashMap<String, ByteArray>()
        val limiter = RateLimiter { settings.current().speedLimitKbps * 1024L / jobs.size.coerceAtLeast(1) }
        val speed = SpeedMeter()
        var lastSave = 0L
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(offset)
            raf.seek(offset)
            val init = playlist.init
            if (done == 0 && init != null) {
                val bytes = fetchSegment(init, headers, keys)
                raf.write(bytes); offset += bytes.size
            }
            while (done < segments.size) {
                currentCoroutineContext().ensureActive()
                val batch = segments.subList(done, minOf(done + HLS_PARALLEL, segments.size))
                val results = kotlinx.coroutines.coroutineScope {
                    batch.map { seg -> async { fetchSegment(seg, headers, keys) } }.awaitAll()
                }
                for (bytes in results) {
                    raf.write(bytes)
                    offset += bytes.size
                    done++
                    limiter.consume(bytes.size)
                    speed.add(bytes.size)
                }
                idx.writeText("$done $offset")
                val estimate = offset * segments.size / done
                val now = System.currentTimeMillis()
                if (now - lastSave > 500 || done == segments.size) {
                    lastSave = now
                    val cur = dao.get(e.id) ?: return e
                    dao.update(
                        if (isAudio) cur.copy(audioDownloadedBytes = offset, audioTotalBytes = estimate)
                        else cur.copy(downloadedBytes = offset, totalBytes = estimate),
                    )
                    _speeds.update { it + (e.id to speed.bytesPerSecond()) }
                    notifier.refresh(true)
                }
            }
        }
        idx.delete()
        val cur = dao.get(e.id) ?: return e
        val finalEntity = if (isAudio) cur.copy(audioDownloadedBytes = offset, audioTotalBytes = offset)
        else cur.copy(downloadedBytes = offset, totalBytes = offset)
        dao.update(finalEntity)
        return finalEntity
    }

    private suspend fun fetchSegment(seg: HlsPlaylist.Segment, headers: Map<String, String>, keys: MutableMap<String, ByteArray>): ByteArray =
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                try {
                    val req = Request.Builder().url(seg.url)
                        .header("User-Agent", headers["User-Agent"] ?: UA)
                        .apply {
                            seg.byteRange?.let { header("Range", "bytes=${it.first}-${it.last}") }
                            headers.forEach { (k, v) -> if (k != "User-Agent") header(k, v) }
                        }.build()
                    val raw = http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw HttpStatusException(r.code)
                        r.body.bytes()
                    }
                    val key = seg.key ?: return@withContext raw
                    val keyUrl = key.url
                    if (key.method != "AES-128" || keyUrl == null) throw IOException("This video is DRM-protected and can't be downloaded")
                    val keyBytes = keys.getOrPut(keyUrl) {
                        http.newCall(Request.Builder().url(keyUrl).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { r ->
                            if (!r.isSuccessful) throw HttpStatusException(r.code)
                            r.body.bytes()
                        }
                    }
                    val iv = key.iv ?: ByteArray(16).also { b -> var n = seg.sequence; for (i in 15 downTo 8) { b[i] = (n and 0xff).toByte(); n = n shr 8 } }
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), javax.crypto.spec.IvParameterSpec(iv))
                    return@withContext cipher.doFinal(raw)
                } catch (ex: IOException) {
                    if (++attempt >= 3 || ex is HttpStatusException && ex.code in 400..499) throw ex
                    delay(1000L * attempt)
                }
            }
            @Suppress("UNREACHABLE_CODE") ByteArray(0)
        }

    private fun probeLength(url: String, headers: Map<String, String>): Long = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", headers["User-Agent"] ?: UA)
            .header("Range", "bytes=0-0")
            .apply { headers.forEach { (k, v) -> if (k != "User-Agent") header(k, v) } }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code)
            r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.body.contentLength()
        }
    }.getOrElse { if (it is HttpStatusException) throw it else -1 }

    private class SpeedMeter {
        private val window = ArrayDeque<Pair<Long, Int>>()
        fun add(n: Int) {
            val now = System.currentTimeMillis()
            window.addLast(now to n)
            while (window.isNotEmpty() && now - window.first().first > 2000) window.removeFirst()
        }
        fun bytesPerSecond(): Long {
            if (window.isEmpty()) return 0
            val span = (System.currentTimeMillis() - window.first().first).coerceAtLeast(250)
            return window.sumOf { it.second.toLong() } * 1000 / span
        }
    }

    /** Sleeps as needed to keep throughput under the configured limit (0 = unlimited). */
    private class RateLimiter(private val limit: suspend () -> Long) {
        private var windowStart = System.currentTimeMillis()
        private var bytes = 0L
        private var cached = 0L
        private var cachedAt = 0L
        suspend fun consume(n: Int) {
            val now = System.currentTimeMillis()
            if (now - cachedAt > 1000) { cached = limit(); cachedAt = now }
            val l = cached
            if (l <= 0) return
            bytes += n
            val elapsed = System.currentTimeMillis() - windowStart
            val expected = bytes * 1000 / l
            if (expected > elapsed) delay(expected - elapsed)
            if (elapsed > 1000) { windowStart = System.currentTimeMillis(); bytes = 0 }
        }
    }

    companion object {
        private const val CHUNK = 4L * 1024 * 1024
        private const val HLS_PARALLEL = 4
        private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
