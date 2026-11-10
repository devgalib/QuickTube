package com.edureminder.quicktube.core.download

import com.edureminder.quicktube.core.common.Branding
import com.edureminder.quicktube.core.common.DesktopDirs
import com.edureminder.quicktube.core.common.DownloadErrors
import com.edureminder.quicktube.core.common.MediaProbe
import com.edureminder.quicktube.core.common.PartFiles
import com.edureminder.quicktube.core.common.QtLog
import com.edureminder.quicktube.core.common.Tools
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.database.DownloadDao
import com.edureminder.quicktube.core.database.DownloadEntity
import com.edureminder.quicktube.core.database.asModel
import com.edureminder.quicktube.core.extractor.UniversalExtractor
import com.edureminder.quicktube.core.model.ContentType
import com.edureminder.quicktube.core.model.DownloadLater
import com.edureminder.quicktube.core.model.DownloadRequest
import com.edureminder.quicktube.core.model.DownloadStatus
import com.edureminder.quicktube.core.model.DownloadTask
import com.edureminder.quicktube.core.model.StreamProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The desktop download engine. Same queue, database rows and controls as Android's [DownloadManager], built for a
 * computer: files come down in 4 MB ranges over parallel connections (resumable after a pause or a restart, and not
 * throttled by YouTube's CDN), and ffmpeg does what Media3 does on the phone — merging video and audio, HLS, cuts and
 * audio conversion (MP3, FLAC, WAV, AAC, Opus).
 */
internal class DesktopDownloadEngine(
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
    private val media: MediaRepository,
    client: OkHttpClient,
    private val universal: UniversalExtractor?,
) : DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).readTimeout(30, TimeUnit.SECONDS).build()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val nameLock = Mutex()
    private val scheduleLock = Mutex()
    private var started = false

    override val speeds = MutableStateFlow<Map<Long, Long>>(emptyMap())

    override fun start() {
        if (started) return
        started = true
        scope.launch {
            // Anything "running" when the app was closed is resumed.
            dao.byStatus(listOf(DownloadStatus.RUNNING, DownloadStatus.MERGING, DownloadStatus.WAITING_NETWORK)).forEach {
                dao.updateStatus(it.id, DownloadStatus.QUEUED)
            }
            reschedule()
        }
        // "Download later" times and freed slots: the app stays open on a computer, so a timer is enough.
        scope.launch {
            while (isActive) {
                delay(15_000)
                reschedule()
            }
        }
    }

    override suspend fun enqueue(request: DownloadRequest): Long = nameLock.withLock {
        val s = settings.current()
        val root = File(s.downloadDir.ifBlank { settings.defaultDownloadDir }).apply { mkdirs() }.takeIf { it.canWrite() }
            ?: DesktopDirs.downloads.apply { mkdirs() }
        val dir = request.folder?.let { Urls.safeFileName(it) }?.takeIf { it.isNotBlank() }?.let { File(root, it).apply { mkdirs() } } ?: root
        val base = Urls.safeFileName(request.title)
        val suffix = request.quality?.let { "($it)" }.orEmpty()
        var file = File(dir, Branding.fileName("$base$suffix") + ".${request.ext}")
        var n = 1
        while (file.exists() || dao.countWithPath(file.absolutePath) > 0) {
            file = File(dir, Branding.fileName("$base$suffix ($n)") + ".${request.ext}"); n++
        }
        val headers = request.headers.takeIf { it.isNotEmpty() }?.let { h -> JsonObject(h.mapValues { JsonPrimitive(it.value) }).toString() }
        val id = dao.insert(
            DownloadEntity(
                title = request.title, pageUrl = request.pageUrl, sourceHost = Urls.host(request.pageUrl ?: request.url),
                thumbnail = request.thumbnail, url = request.url, audioUrl = request.audioUrl, filePath = file.absolutePath,
                mimeType = request.mimeType, type = ContentType.fromMime(request.mimeType), quality = request.quality,
                totalBytes = request.sizeBytes, downloadedBytes = 0,
                audioTotalBytes = if (request.audioUrl != null) request.audioSizeBytes else -1, audioDownloadedBytes = 0,
                status = DownloadStatus.QUEUED, error = null, durationSec = request.durationSec,
                createdAt = System.currentTimeMillis(), completedAt = 0, inVault = false, deletedAt = 0, lastPlayedPositionMs = 0,
                headers = headers, protocol = request.protocol, extractAudio = request.extractAudio,
                clipStartMs = request.clipStartMs, clipEndMs = request.clipEndMs,
                slideImages = request.images.takeIf { it.isNotEmpty() }?.let { l -> kotlinx.serialization.json.JsonArray(l.map { JsonPrimitive(it) }).toString() },
                startAfter = request.startAfter, wifiOnly = request.wifiOnly,
            ),
        )
        reschedule()
        id
    }

    override fun submit(request: DownloadRequest) {
        scope.launch { enqueue(request) }
    }

    override suspend fun findExisting(request: DownloadRequest): DownloadTask? {
        val onePage = Urls.namesOneMedia(request.pageUrl)
        val page = request.pageUrl?.takeIf { onePage }
        val title = request.title.takeUnless { Urls.isGenericTitle(it) || request.pageUrl != null && !onePage }
        val e = dao.findSame(page, title, Urls.host(request.pageUrl ?: request.url), request.quality, Urls.mediaFileKey(request.url)) ?: return null
        return when (e.status) {
            DownloadStatus.COMPLETED -> e.takeIf { File(it.filePath).exists() }
            DownloadStatus.FAILED -> null
            else -> e
        }?.asModel()
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
        }
    }

    override fun resumeAll() {
        scope.launch {
            dao.byStatus(listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)).forEach { dao.updateStatus(it.id, DownloadStatus.QUEUED) }
            reschedule()
        }
    }

    override suspend fun remove(ids: List<Long>) {
        ids.forEach { id ->
            jobs.remove(id)?.cancel()
            val e = dao.get(id) ?: return@forEach
            if (e.status != DownloadStatus.COMPLETED) {
                PartFiles.delete(e.filePath, e.id, DesktopDirs.parts)
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
                val max = settings.current().maxTasksWifi.coerceIn(1, 8)
                val now = System.currentTimeMillis()
                for (e in dao.byStatus(listOf(DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK))) {
                    if (jobs.size >= max) break
                    if (jobs.containsKey(e.id) || DownloadLater.waiting(e.startAfter, e.wifiOnly, onWifi = true, now = now)) continue
                    dao.updateStatus(e.id, DownloadStatus.RUNNING)
                    val job = scope.launch { runTask(e.id) }
                    jobs[e.id] = job
                    job.invokeOnCompletion {
                        jobs.remove(e.id, job)
                        speeds.update { it - e.id }
                        reschedule()
                    }
                }
            }
        }
    }

    // ---------------- One task ----------------

    private fun partBase(id: Long) = File(DesktopDirs.parts, "task-$id").path

    private suspend fun runTask(id: Long) {
        val e = dao.get(id) ?: return
        try {
            val target = File(e.filePath).apply { parentFile?.mkdirs() }
            val work = when {
                e.slideImages != null -> throw IOException("Photo posts with music can't be saved on desktop yet")
                e.clipEndMs > e.clipStartMs -> clip(e)
                e.protocol == StreamProtocol.HLS -> hls(e)
                else -> http(e)
            }
            dao.updateStatus(id, DownloadStatus.MERGING)
            val finished = finish(dao.get(id) ?: return, work, target)
            val saved = publish(finished, target)
            if (!e.extractAudio && e.mimeType.startsWith("video/") && saved.extension in setOf("mp4", "webm", "mkv") && e.clipEndMs <= e.clipStartMs && settings.current().saveSubtitles) {
                e.pageUrl?.takeIf { Urls.youtubeVideoId(it) != null }?.let { saveCaptions(it, saved) }
            }
            val done = (dao.get(id) ?: return).copy(
                filePath = saved.absolutePath,
                durationSec = MediaProbe.durationSec(saved.absolutePath).takeIf { it > 0 } ?: e.durationSec,
                status = DownloadStatus.COMPLETED, completedAt = System.currentTimeMillis(),
                totalBytes = saved.length(), downloadedBytes = saved.length(), audioTotalBytes = -1, audioDownloadedBytes = 0, error = null,
            )
            dao.update(done)
            PartFiles.delete(e.filePath, id, DesktopDirs.parts)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            QtLog.w(TAG, "Download $id failed", t)
            dao.updateStatus(id, DownloadStatus.FAILED, friendlyError(t))
        }
    }

    /** Plain files: video (and its separate audio) at once, each in ranges; returns the downloaded video part. */
    private suspend fun http(e: DownloadEntity): File {
        val base = partBase(e.id)
        val videoPart = File("$base.part")
        val audioPart = File("$base.audio.part")
        val video = AtomicLong(0)
        val audio = AtomicLong(0)
        val totals = longArrayOf(e.totalBytes, e.audioTotalBytes)
        val refreshLock = Mutex()
        coroutineScope {
            val ticker = launch {
                var last = 0L
                while (isActive) {
                    delay(500)
                    val now = video.get() + audio.get()
                    speeds.update { it + (e.id to ((now - last) * 2).coerceAtLeast(0)) }
                    last = now
                    dao.updateTransfer(e.id, video.get(), audio.get(), totals[0], totals[1])
                }
            }
            val v = async { fetchWithRefresh(e.id, false, videoPart, video, refreshLock) { totals[0] = it } }
            val a = if (e.audioUrl != null) async { fetchWithRefresh(e.id, true, audioPart, audio, refreshLock) { totals[1] = it } } else null
            v.await(); a?.await()
            ticker.cancel()
            dao.updateTransfer(e.id, video.get(), audio.get(), totals[0], totals[1])
        }
        return videoPart
    }

    private suspend fun fetchWithRefresh(id: Long, isAudio: Boolean, part: File, counter: AtomicLong, refreshLock: Mutex, onTotal: (Long) -> Unit) {
        var refreshed = false
        while (true) {
            val e = dao.get(id) ?: return
            val url = (if (isAudio) e.audioUrl else e.url) ?: return
            try {
                fetch(url, headersOf(e), part, counter, onTotal)
                return
            } catch (x: HttpStatusException) {
                if (refreshed || (x.code != 403 && x.code != 410)) throw x
                refreshed = true
                // Links expire after a few hours: ask the site again for this quality and carry on.
                refreshLock.withLock {
                    val now = dao.get(id) ?: return
                    if ((if (isAudio) now.audioUrl else now.url) == url) {
                        val fresh = refreshUrls(now) ?: throw x
                        dao.update(fresh)
                    }
                }
                part.delete(); ChunkState.stateFile(part).delete()
            }
        }
    }

    private suspend fun refreshUrls(e: DownloadEntity): DownloadEntity? {
        val page = e.pageUrl ?: return null
        val options = if (media.canExtract(page)) runCatching { media.details(page, forceRefresh = true).options }.getOrNull()
        else universal?.takeIf { it.isReady }?.let { u -> runCatching { u.extract(page, headersOf(e)) }.getOrNull()?.options }
        options ?: return null
        val height = e.quality?.substringBefore('p')?.trim()?.toIntOrNull()
        val sameKind = when {
            e.extractAudio -> options.filter { it.extractAudio }
            e.mimeType.startsWith("audio/") -> options.filter { it.mimeType.startsWith("audio/") && !it.extractAudio }
            else -> options.filter { it.mimeType.startsWith("video/") && !it.extractAudio }
        }
        val option = (if (height != null) sameKind.minByOrNull { kotlin.math.abs(it.height - height) } else null)
            ?: sameKind.firstOrNull { o -> e.quality?.let { o.title.contains(it) } == true } ?: sameKind.lastOrNull() ?: return null
        return e.copy(url = option.url, audioUrl = option.audioUrl, protocol = option.protocol)
    }

    /** Downloads [url] into [part]: in ranges when the server allows it (resumable, parallel), else front to back. */
    private suspend fun fetch(url: String, headers: Map<String, String>, part: File, counter: AtomicLong, onTotal: (Long) -> Unit) {
        val (total, ranges) = probe(url, headers)
        if (total > 0) onTotal(total)
        if (total > 0 && ranges) {
            val state = ChunkState.load(part, total)
            withContext(Dispatchers.IO) { RandomAccessFile(part, "rw").use { if (it.length() != total) it.setLength(total) } }
            counter.set(state.doneBytes())
            val queue = ConcurrentLinkedQueue(state.pending())
            coroutineScope {
                repeat(SegmentPlan.connectionsFor(total)) {
                    launch(Dispatchers.IO) {
                        while (true) {
                            val i = queue.poll() ?: break
                            fetchRange(url, headers, part, SegmentPlan.range(i, total), counter)
                            state.markDone(i)
                        }
                    }
                }
            }
            state.delete()
        } else {
            fetchSequential(url, headers, part, counter)
        }
    }

    private suspend fun probe(url: String, headers: Map<String, String>): Pair<Long, Boolean> = withContext(Dispatchers.IO) {
        http.newCall(request(url, headers).header("Range", "bytes=0-0").build()).execute().use { r ->
            when {
                r.code == 206 -> (r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L) to true
                r.isSuccessful -> (r.body.contentLength()) to false
                else -> throw HttpStatusException(r.code)
            }
        }
    }

    private suspend fun fetchRange(url: String, headers: Map<String, String>, part: File, range: LongRange, counter: AtomicLong) {
        var pos = range.first
        var attempt = 0
        while (pos <= range.last) {
            currentCoroutineContext().ensureActive()
            try {
                http.newCall(request(url, headers).header("Range", "bytes=$pos-${range.last}").build()).execute().use { r ->
                    if (r.code != 206) throw HttpStatusException(r.code)
                    RandomAccessFile(part, "rw").use { raf ->
                        val ch = raf.channel
                        val buf = ByteArray(64 * 1024)
                        val input = r.body.byteStream()
                        while (pos <= range.last) {
                            val n = input.read(buf)
                            if (n < 0) break
                            ch.write(ByteBuffer.wrap(buf, 0, n), pos)
                            pos += n
                            counter.addAndGet(n.toLong())
                            currentCoroutineContext().ensureActive()
                        }
                    }
                }
            } catch (x: HttpStatusException) {
                throw x
            } catch (x: IOException) {
                if (++attempt > 5) throw x
                delay(1000L * attempt)
            }
        }
    }

    private suspend fun fetchSequential(url: String, headers: Map<String, String>, part: File, counter: AtomicLong) = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val done = if (part.exists()) part.length() else 0L
            counter.set(done)
            try {
                val req = request(url, headers).apply { if (done > 0) header("Range", "bytes=$done-") }.build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw HttpStatusException(r.code)
                    val append = r.code == 206
                    if (!append) counter.set(0)
                    java.io.FileOutputStream(part, append).use { out ->
                        val input = r.body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            counter.addAndGet(n.toLong())
                            ensureActive()
                        }
                    }
                }
                return@withContext
            } catch (x: HttpStatusException) {
                throw x
            } catch (x: IOException) {
                if (++attempt > 5) throw x
                delay(1000L * attempt)
            }
        }
    }

    private fun request(url: String, headers: Map<String, String>) = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
        if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", USER_AGENT)
    }

    /** HLS (.m3u8): ffmpeg fetches the segments (and decrypts AES-128) straight into an MP4. */
    private suspend fun hls(e: DownloadEntity): File {
        val out = File(partBase(e.id) + ".hls.mp4")
        val args = buildList {
            addAll(inputArgs(e.url, headersOf(e)))
            e.audioUrl?.let { addAll(inputArgs(it, headersOf(e))); addAll(listOf("-map", "0:v:0?", "-map", "1:a:0?")) }
            addAll(listOf("-c", "copy", "-bsf:a", "aac_adtstoasc", out.path))
        }
        ffmpeg(args, e.durationSec.toDouble(), e.id)
        return out
    }

    /** A cut: ffmpeg reads only the chosen part of the remote file(s). */
    private suspend fun clip(e: DownloadEntity): File {
        val ext = File(e.filePath).extension.ifBlank { "mp4" }
        val out = File(partBase(e.id) + ".clip.${if (e.extractAudio) "mka" else ext}")
        val from = "%.3f".format(java.util.Locale.US, e.clipStartMs / 1000.0)
        val to = "%.3f".format(java.util.Locale.US, e.clipEndMs / 1000.0)
        val args = buildList {
            addAll(listOf("-ss", from, "-to", to)); addAll(inputArgs(e.url, headersOf(e)))
            e.audioUrl?.let { addAll(listOf("-ss", from, "-to", to)); addAll(inputArgs(it, headersOf(e))); addAll(listOf("-map", "0:v:0?", "-map", "1:a:0?")) }
            addAll(listOf("-c", "copy", "-avoid_negative_ts", "make_zero", out.path))
        }
        ffmpeg(args, (e.clipEndMs - e.clipStartMs) / 1000.0, e.id)
        return out
    }

    private fun inputArgs(url: String, headers: Map<String, String>): List<String> = buildList {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT
        add("-user_agent"); add(ua)
        val other = headers.filterKeys { !it.equals("User-Agent", true) }
        if (other.isNotEmpty()) { add("-headers"); add(other.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }) }
        add("-i"); add(url)
    }

    /** Turns the downloaded part(s) into the file the user chose: merged, converted or as it is. */
    private suspend fun finish(e: DownloadEntity, work: File, target: File): File {
        val ext = target.extension.lowercase()
        val audioPart = File(partBase(e.id) + ".audio.part").takeIf { e.audioUrl != null && e.protocol == StreamProtocol.HTTP && e.clipEndMs <= e.clipStartMs && it.exists() }
        val out = File(partBase(e.id) + ".out.$ext")
        out.delete()
        val meta = listOf("-metadata", "title=${e.title}", "-metadata", "comment=${Branding.DESCRIPTION}", "-metadata", "encoder=${Branding.ENCODER}")
        val audioCodec = AUDIO_CODECS[ext]
        when {
            // Music the user wanted in another format (or the sound of a video): one ffmpeg pass.
            audioCodec != null && (e.extractAudio || ext != "m4a" || e.type != ContentType.AUDIO) -> {
                val kbps = Regex("""(\d{2,3})\s*[kK]""").find(e.quality.orEmpty())?.groupValues?.get(1) ?: "192"
                val codec = audioCodec.map { if (it == "{kbps}") "${kbps}k" else it }
                val input = listOf("-i", work.path, "-vn", "-map", "0:a:0?")
                // AAC and Opus are the site's own stream repackaged (no quality lost), as on Android; re-encoded
                // only when the source is another codec.
                val copied = ext in setOf("aac", "opus", "ogg") &&
                    runCatching { ffmpeg(input + listOf("-c:a", "copy") + (if (ext == "aac") listOf("-f", "adts") else emptyList()) + meta + out.path) }.isSuccess
                if (!copied) ffmpeg(input + codec + meta + out.path)
            }
            audioPart != null -> {
                val base = listOf("-i", work.path, "-i", audioPart.path, "-map", "0:v:0", "-map", "1:a:0") + meta
                // Usually the streams fit the container as they are; otherwise the sound is re-encoded (MP4 → AAC, WebM → Opus).
                val ok = runCatching { ffmpeg(base + listOf("-c", "copy", out.path)) }.isSuccess
                if (!ok) ffmpeg(base + listOf("-c:v", "copy", "-c:a", if (ext == "webm") "libopus" else "aac", "-b:a", "192k", out.path))
            }
            // Single file: a quick remux adds the title and QuickTube's tag without touching the picture or sound.
            ext in setOf("mp4", "m4a", "mkv", "webm", "mov") -> {
                if (runCatching { ffmpeg(listOf("-i", work.path, "-map", "0", "-c", "copy") + meta + out.path) }.isFailure) return work
            }
            else -> return work
        }
        return out
    }

    /** Moves the finished file into the download folder under the task's name. */
    private fun publish(file: File, target: File): File {
        if (file.absolutePath == target.absolutePath) return target
        target.parentFile?.mkdirs()
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // Another drive: copy, then remove the part.
            Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            file.delete()
        }
        return target
    }

    private suspend fun saveCaptions(page: String, video: File) = withContext(Dispatchers.IO) {
        runCatching {
            for (track in SubtitleFiles.pickAll(media.details(page).subtitles, java.util.Locale.getDefault().language)) runCatching {
                val vtt = http.newCall(Request.Builder().url(track.url).build()).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
                    ?: return@runCatching
                val srt = SubtitleFiles.vttToSrt(vtt).takeIf { it.isNotBlank() } ?: return@runCatching
                File(video.parentFile, SubtitleFiles.nameFor(video.name, track.language)).writeText(srt)
            }
        }.onFailure { QtLog.w(TAG, "Captions not saved", it) }
    }

    /**
     * Runs ffmpeg; with [durationSec] and [taskId] its progress (time done, bytes written) is shown on the task.
     * Cancelling the coroutine (pause, delete) stops the process.
     */
    private suspend fun ffmpeg(args: List<String>, durationSec: Double? = null, taskId: Long? = null) = withContext(Dispatchers.IO) {
        val log = File.createTempFile("qt-ffmpeg", ".log")
        val cmd = listOf(Tools.ffmpeg, "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-progress", "pipe:1", "-nostats") + args
        val p = try {
            ProcessBuilder(cmd).redirectError(log).start()
        } catch (x: IOException) {
            log.delete()
            throw IOException("ffmpeg isn't installed. Install it (e.g. 'sudo dnf install ffmpeg' or 'sudo apt install ffmpeg') and tap retry", x)
        }
        val kill = currentCoroutineContext().job.invokeOnCompletion { if (p.isAlive) p.destroyForcibly() }
        try {
            var timeUs = 0L
            var size = 0L
            var lastPublish = 0L
            p.inputStream.bufferedReader().forEachLine { line ->
                when (line.substringBefore('=')) {
                    "out_time_us", "out_time_ms" -> timeUs = line.substringAfter('=').toLongOrNull() ?: timeUs
                    "total_size" -> size = line.substringAfter('=').toLongOrNull() ?: size
                    "progress" -> if (taskId != null && System.currentTimeMillis() - lastPublish > 450) {
                        lastPublish = System.currentTimeMillis()
                        val fraction = if (durationSec != null && durationSec > 0) (timeUs / 1e6 / durationSec).coerceIn(0.0, 1.0) else 0.0
                        // Progress bars work in bytes: the size so far and the size it will have at this rate.
                        val total = if (fraction > 0.01) (size / fraction).toLong() else -1L
                        scope.launch { dao.updateTransfer(taskId, size, 0, total, -1) }
                    }
                }
            }
            val code = p.waitFor()
            ensureActive()
            if (code != 0) {
                val err = log.readLines().takeLast(3).joinToString(" ").take(300)
                throw IOException(if ("403" in err) "HTTP 403" else "ffmpeg failed ($code): $err")
            }
        } finally {
            kill.dispose()
            if (p.isAlive) p.destroyForcibly()
            log.delete()
        }
    }

    private fun headersOf(e: DownloadEntity): Map<String, String> = e.headers?.let { h ->
        runCatching { Json.parseToJsonElement(h).jsonObject.mapValues { it.value.jsonPrimitive.content } }.getOrNull()
    }.orEmpty()

    private fun friendlyError(e: Throwable) = when {
        e is HttpStatusException && (e.code == 401 || e.code == 403) -> "Link expired or login required"
        e is HttpStatusException && e.code == 404 -> "File not found"
        e is java.net.UnknownHostException -> "No internet connection"
        e is java.net.SocketTimeoutException -> "Connection timed out"
        e.message?.startsWith("ffmpeg isn't installed") == true -> e.message!!
        e.message?.contains("No space left") == true -> "Not enough storage"
        else -> DownloadErrors.friendly(e.message)
    }

    private companion object {
        const val TAG = "DesktopDownloads"
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"

        /** ffmpeg arguments per audio format ({kbps} = the bitrate from the chosen quality, e.g. "MP3 (320K)"). */
        val AUDIO_CODECS = mapOf(
            "mp3" to listOf("-c:a", "libmp3lame", "-b:a", "{kbps}"),
            "m4a" to listOf("-c:a", "aac", "-b:a", "{kbps}"),
            "aac" to listOf("-c:a", "aac", "-b:a", "{kbps}", "-f", "adts"),
            "opus" to listOf("-c:a", "libopus", "-b:a", "{kbps}"),
            "ogg" to listOf("-c:a", "libopus", "-b:a", "{kbps}"),
            "flac" to listOf("-c:a", "flac"),
            "wav" to listOf("-c:a", "pcm_s16le"),
        )
    }
}
