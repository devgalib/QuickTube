package com.edureminder.quicktube.core.download

import android.content.Context
import android.media.MediaScannerConnection
import com.edureminder.quicktube.core.common.Branding
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
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.edureminder.quicktube.core.extractor.UniversalExtractor
import kotlinx.coroutines.coroutineScope
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/**
 * Queue + worker pool. Each task downloads its video and audio streams at once, each split into ranged
 * chunks over several connections (resumable, avoids per-connection throttling), then merges them.
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
    /** Site extractor used to refresh expired or unsavable links of non-YouTube pages; null where unavailable. */
    private val universal: UniversalExtractor?,
) : DownloadManager {

    // HTTP/1.1 only: each ranged chunk gets its own TCP connection instead of sharing one HTTP/2 connection,
    // which is what makes parallel chunks faster on CDNs that cap per-connection throughput.
    private val http = client.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(16, 2, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()
    private val limiter = RateLimiter { settings.current().speedLimitKbps * 1024L }
    private val audio = AudioFinisher(context, client)

    /*
     * App-wide limits, however many downloads are queued or running:
     * - at most TRANSFERS network transfers (chunks / HLS segments) in flight, shared by all running tasks;
     * - their own small thread pool, so the database, merging and the UI never wait behind blocking sockets;
     * - one merge at a time (CPU- and memory-heavy); other tasks keep downloading meanwhile.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val netDispatcher = Dispatchers.IO.limitedParallelism(TRANSFERS + 4)
    private val transfers = kotlinx.coroutines.sync.Semaphore(TRANSFERS)
    private val mergeLock = Mutex()
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
        // Safety net: a task parked as "waiting for network" after a brief drop, or queued while all slots were
        // busy, would otherwise wait for the next network/settings event, which may never come.
        scope.launch {
            while (true) {
                delay(15_000)
                val waiting = dao.byStatus(listOf(DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK))
                if (waiting.any { !jobs.containsKey(it.id) }) reschedule()
            }
        }
        scope.launch {
            settings.settings.map { Triple(it.maxTasksWifi, it.maxTasksMobile, it.allowMobileData) }
                .distinctUntilChanged().drop(1).collect { reschedule() }
        }
    }

    /** Names are picked one at a time, so two downloads of the same title can't get the same file. */
    private val nameLock = Mutex()

    override suspend fun enqueue(request: DownloadRequest): Long = nameLock.withLock { enqueueLocked(request) }

    override fun submit(request: DownloadRequest) {
        scope.launch { enqueue(request) }
    }

    override suspend fun findExisting(request: DownloadRequest): com.edureminder.quicktube.core.model.DownloadTask? {
        // A feed, profile or home page isn't one video: every video downloaded from it shares that address and title
        // (a TikTok profile's "Name (@user) | TikTok"), so only the file itself can tell them apart.
        val onePage = Urls.namesOneMedia(request.pageUrl)
        val page = request.pageUrl?.takeIf { onePage }
        val title = request.title.takeUnless { Urls.isGenericTitle(it) || request.pageUrl != null && !onePage }
        val e = dao.findSame(page, title, Urls.host(request.pageUrl ?: request.url), request.quality, Urls.mediaFileKey(request.url)) ?: return null
        return when (e.status) {
            DownloadStatus.COMPLETED -> e.takeIf { File(it.filePath).exists() }
            DownloadStatus.FAILED -> null
            else -> e // queued, running, paused, merging, waiting for network
        }?.asModel()
    }

    private suspend fun enqueueLocked(request: DownloadRequest): Long {
        val s = settings.current()
        // Public Downloads is the default; fall back to app storage if this device/OS version won't let us write there.
        val root = File(s.downloadDir).apply { mkdirs() }.takeIf { it.canWrite() }
            ?: File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "QuickTube").apply { mkdirs() }
        // A bulk download keeps its files together in a folder of its own name.
        val dir = request.folder?.let { Urls.safeFileName(it) }?.takeIf { it.isNotBlank() }?.let { File(root, it).apply { mkdirs() } } ?: root
        val base = Urls.safeFileName(request.title)
        val suffix = request.quality?.let { "($it)" }.orEmpty()
        // Every file ends with the app's name and site ("Song(720p) - QuickTube edureminder.com.mp4").
        var file = File(dir, Branding.fileName("$base$suffix") + ".${request.ext}")
        var n = 1
        // Merged downloads only appear on disk when they finish, so names already given to tasks count too.
        while (file.exists() || File(file.path + ".part").exists() || dao.countWithPath(file.absolutePath) > 0) {
            file = File(dir, Branding.fileName("$base$suffix ($n)") + ".${request.ext}"); n++
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
                clipStartMs = request.clipStartMs,
                clipEndMs = request.clipEndMs,
                slideImages = request.images.takeIf { it.isNotEmpty() }?.let { l -> kotlinx.serialization.json.JsonArray(l.map { JsonPrimitive(it) }).toString() },
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
                deleteParts(e)
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
        // Tasks saved by older versions may have names over Android's 255-byte limit, or share a name with another
        // task: give them a short, unique one before (re)trying.
        File(entity.filePath).let { f ->
            val shared = entity.status != DownloadStatus.COMPLETED && dao.countWithPath(entity.filePath) > 1
            if (f.name.toByteArray(Charsets.UTF_8).size > 180 || shared) {
                deleteParts(entity)
                val base = Urls.safeFileName(f.nameWithoutExtension)
                var short = File(f.parentFile, "$base.${f.extension}")
                var n = 1
                // Two old tasks of one video shorten to the same name: keep them apart.
                while (short.exists() || dao.countWithPath(short.absolutePath) > 0) short = File(f.parentFile, "$base (${n++}).${f.extension}")
                entity = entity.copy(filePath = short.absolutePath, downloadedBytes = 0, audioDownloadedBytes = 0)
                dao.update(entity)
            }
        }
        try {
            val target = File(entity.filePath)
            target.parentFile?.mkdirs()
            val saved = if (entity.slideImages != null) slideshow(entity, target)
            else if (entity.clipEndMs > entity.clipStartMs && entity.protocol == StreamProtocol.HTTP) clip(id, target) else try {
                val base = partBase(entity)
                entity = download(id, File("$base.part"), File("$base.audio.part"))
                finish(entity, File(entity.filePath), base)
            } catch (ex: UnsupportedCodecException) {
                // The site served a codec this phone can't store (AV1 before Android 14, VP9 with AAC…):
                // re-extract, take a compatible format of the same quality, download again.
                refreshUrls(dao.get(id) ?: return, compatibleOnly = true) ?: throw ex
                deleteParts(entity)
                val base = partBase(dao.get(id) ?: return)
                entity = download(id, File("$base.part"), File("$base.audio.part"))
                finish(entity, File(entity.filePath), base)
            }
            // Songs get their cover, title and artist; the cover is kept so the Library shows it offline too.
            val cover = if (entity.extractAudio || entity.mimeType.startsWith("audio/") || saved.extension == "mp3") {
                withContext(Dispatchers.IO) { audio.tag(id, saved, entity.title, entity.thumbnail, headersOf(entity)) }
            } else null
            // Videos and photos carry QuickTube's description, site and tags too (songs got them with their cover above).
            if (cover == null) withContext(Dispatchers.IO) { brand(saved, entity.title) }
            // YouTube videos get their captions beside them ("Title(720p).en.srt"), which any player can show. Not for cuts.
            if (!entity.extractAudio && saved.extension in setOf("mp4", "webm", "mkv") && entity.clipEndMs <= entity.clipStartMs) {
                entity.pageUrl?.takeIf { Urls.youtubeVideoId(it) != null }?.let { page -> withContext(Dispatchers.IO) { saveCaptions(page, saved) } }
            }
            // The saved file's own length: the page's figure can belong to another video (and then hides a wrong file).
            val probedDuration = probeDurationSec(saved).takeIf { it > 0 } ?: entity.durationSec
            val done = (dao.get(id) ?: return).copy(
                durationSec = probedDuration,
                thumbnail = cover ?: entity.thumbnail,
                status = DownloadStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                totalBytes = saved.length(),
                downloadedBytes = saved.length(),
                audioTotalBytes = -1, audioDownloadedBytes = 0,
                error = null,
            )
            dao.update(done)
            deleteParts(entity)
            MediaScannerConnection.scanFile(context, arrayOf(saved.absolutePath), arrayOf(done.mimeType), null)
            notifier.completed(done.asModel())
            analytics.event(com.edureminder.quicktube.core.analytics.Events.DOWNLOAD_COMPLETED,
                mapOf("kind" to done.type.name.lowercase(), "protocol" to done.protocol.name.lowercase()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Task $id failed", e)
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

    /**
     * A cut of the video ([ClipDownload]): fetches only that part of the streams and writes the clip straight away.
     * Expired links are refreshed once; a format that can't be stored is swapped for a compatible one once.
     */
    private suspend fun clip(id: Long, target: File): File {
        var refreshed = false
        var swapped = false
        while (true) {
            val e = dao.get(id) ?: throw CancellationException("Removed")
            val audioOnly = e.mimeType.startsWith("audio/")
            val work = File(partsDir(), "task-$id.clip.${if (audioOnly) "m4a" else "mp4"}")
            val progress = TaskProgress(0, 0, e.totalBytes, e.audioTotalBytes)
            val headers = headersOf(e)
            val ticker = scope.launch { while (isActive) { delay(500); publish(id, progress) } }
            try {
                withContext(netDispatcher) {
                    ClipDownload(http, { url, range -> request(url, headers, range) }, e.clipStartMs * 1000, e.clipEndMs * 1000)
                        .run(e.url, e.audioUrl, audioOnly, work, progress.video, progress.audio) { n -> progress.meter.add(n) }
                }
                if (!currentCoroutineContext().isActive) { work.delete(); throw CancellationException("Stopped") }
                val out = publishFile(work, target)
                dao.get(id)?.let {
                    val mime = if (out.extension == "m4a") "audio/mp4" else "video/mp4"
                    dao.update(it.copy(filePath = out.absolutePath, mimeType = mime, type = ContentType.fromMime(mime)))
                }
                return out
            } catch (ex: HttpStatusException) {
                work.delete()
                if (ex.code != 403 && ex.code != 410 || refreshed) throw ex
                refreshed = true
                refreshUrls(e, compatibleOnly = false) ?: throw ex
            } catch (ex: UnsupportedCodecException) {
                work.delete()
                if (swapped) throw ex
                swapped = true
                refreshUrls(e, compatibleOnly = true) ?: throw ex
            } finally {
                ticker.cancel()
                publish(id, progress)
            }
        }
    }

    /** Fetches the video and (if separate) the audio stream at the same time, publishing progress twice a second. */
    private suspend fun download(id: Long, videoPart: File, audioPart: File): DownloadEntity = coroutineScope {
        val start = dao.get(id) ?: throw CancellationException("Removed")
        val progress = TaskProgress(start.downloadedBytes, start.audioDownloadedBytes.coerceAtLeast(0), start.totalBytes, start.audioTotalBytes)
        val refreshLock = Mutex()
        val ticker = launch { while (isActive) { delay(500); publish(id, progress) } }
        try {
            listOfNotNull(
                async { fetchWithRefresh(id, isAudio = false, videoPart, progress, refreshLock) },
                start.audioUrl?.let { async { fetchWithRefresh(id, isAudio = true, audioPart, progress, refreshLock) } },
            ).awaitAll()
            // A refresh can turn a single file into separate video + audio.
            if (start.audioUrl == null && dao.get(id)?.audioUrl != null) fetchWithRefresh(id, isAudio = true, audioPart, progress, refreshLock)
        } finally {
            ticker.cancel()
        }
        publish(id, progress)
        dao.get(id) ?: throw CancellationException("Removed")
    }

    private suspend fun publish(id: Long, p: TaskProgress) {
        dao.updateTransfer(id, p.video.get(), p.audio.get(), p.videoTotal.get(), p.audioTotal.get())
        _speeds.update { it + (id to p.meter.bytesPerSecond()) }
        notifier.refresh(true)
    }

    /**
     * Where a task's partial files live (without the ".part" suffix). Streams that get merged or remuxed are
     * downloaded to app-private storage: the muxer is ~3x slower on shared storage, which goes through FUSE.
     * Plain single files are written straight next to their final name. Parts from older versions stay put.
     */
    private fun partBase(e: DownloadEntity): String {
        val legacy = e.filePath
        if (File("$legacy.part").exists() || File("$legacy.audio.part").exists()) return legacy
        val private = File(partsDir(), "task-${e.id}").path
        if (File("$private.part").exists() || File("$private.audio.part").exists()) return private
        val needsProcessing = e.audioUrl != null || e.protocol == StreamProtocol.HLS || e.extractAudio
        if (!needsProcessing) return legacy
        // Room for the parts plus the merged copy.
        val need = (e.totalBytes.coerceAtLeast(0) + e.audioTotalBytes.coerceAtLeast(0)) * 2 + 64L * 1024 * 1024
        return if (partsDir().usableSpace > need) private else legacy
    }

    private fun partsDir() = File(context.noBackupFilesDir, "parts").apply { mkdirs() }

    /** A photo post with music: its pictures and song fetched, then made into a video (see [SlideshowMaker]). */
    private suspend fun slideshow(e: DownloadEntity, target: File): File = withContext(Dispatchers.IO) {
        val headers = headersOf(e)
        val urls = Json.parseToJsonElement(e.slideImages!!).jsonArray.map { it.jsonPrimitive.content }
        val work = File(partsDir(), "task-${e.id}").apply { mkdirs() }
        try {
            fun fetch(url: String, to: File): File {
                http.newCall(request(url, headers, null)).execute().use { r ->
                    if (!r.isSuccessful) throw HttpStatusException(r.code)
                    to.outputStream().use { r.body.byteStream().copyTo(it) }
                }
                return to
            }
            val pictures = urls.mapIndexed { i, u -> fetch(u, File(work, "pic$i")) }
            val music = fetch(e.url, File(work, "music"))
            dao.updateStatus(e.id, DownloadStatus.MERGING)
            notifier.refresh(true)
            val out = mergeLock.withLock { SlideshowMaker.make(context, pictures, music, File(work, "out.mp4")) }
            publishFile(out, target).also {
                dao.get(e.id)?.let { cur -> dao.update(cur.copy(filePath = it.absolutePath, mimeType = "video/mp4", type = ContentType.VIDEO)) }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /** The best caption track of a YouTube video as SubRip next to [video]; nothing when it has none. Never fails the download. */
    private suspend fun saveCaptions(page: String, video: File) {
        runCatching {
            val track = SubtitleFiles.pick(media.details(page).subtitles, java.util.Locale.getDefault().language) ?: return
            val vtt = http.newCall(okhttp3.Request.Builder().url(track.url).build()).execute().use { r -> if (r.isSuccessful) r.body.string() else null } ?: return
            val srt = SubtitleFiles.vttToSrt(vtt).takeIf { it.isNotBlank() } ?: return
            File(video.parentFile, SubtitleFiles.nameFor(video.name, track.language)).writeText(srt)
        }.onFailure { android.util.Log.w(TAG, "Captions not saved: $it") }
    }

    /** Merges / remuxes / renames the finished parts into the final file, which is returned (its extension may change). */
    private suspend fun finish(e: DownloadEntity, target: File, base: String): File {
        val videoPart = File("$base.part")
        val audioPart = File("$base.audio.part")
        val isPrivate = base != e.filePath
        val isHls = e.protocol == StreamProtocol.HLS
        // Intermediate results stay next to the parts; only the finished file is moved to the download folder.
        val work = if (isPrivate) File("$base.out.${target.extension}") else target
        val videoOut = if (e.extractAudio) File("$base.video.mp4") else work
        val wantMp3 = target.extension.equals("mp3", ignoreCase = true)
        // FLAC, WAV, AAC or Opus chosen: made from the site's audio once it's here.
        val wantOther = target.extension.lowercase() in AudioConverter.TYPES
        if (e.audioUrl != null || isHls || e.extractAudio || wantMp3 || wantOther) {
            dao.updateStatus(e.id, DownloadStatus.MERGING)
            notifier.refresh(true)
        }
        val started = System.currentTimeMillis()
        var out = mergeLock.withLock { when {
            e.audioUrl != null -> MediaMerger.merge(videoPart, audioPart, videoOut).also { videoPart.delete(); audioPart.delete() }
            isHls -> try {
                // HLS arrives as MPEG-TS / fragmented MP4 pieces; package it as a regular MP4.
                MediaMerger.remux(videoPart, videoOut).also { videoPart.delete() }
            } catch (ex: Exception) {
                if (ex is CancellationException || e.extractAudio) throw ex
                moveFile(videoPart, if (isPrivate) File("$base.ts") else uniqueSibling(target, "ts"))
            }
            else -> moveFile(videoPart, videoOut)
        } }
        if (e.extractAudio) {
            // "Audio only" from a website video: keep just the sound track.
            val src = out
            try { out = mergeLock.withLock { MediaMerger.extractAudio(src, work) } } finally { if (src != out) src.delete() }
        }
        // MP3 chosen: the site's audio (AAC, Opus…) is converted on the phone.
        if (wantMp3) {
            // "MP3 320K" → 320 kbps (192 when the label has no number).
            val kbps = e.quality?.let { Regex("""(\d{2,3})K""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 192
            out = mergeLock.withLock { audio.toMp3(out, work, kbps) }
            if (!isPrivate && out != target) out = moveFile(out, target)
        }
        if (wantOther) {
            val made = File(work.parentFile, work.nameWithoutExtension + ".converting." + target.extension)
            val src = out
            mergeLock.withLock { AudioConverter.convert(src, made) }
            src.delete()
            out = moveFile(made, work)
        }
        // Removed or paused while processing: don't drop an untracked file into the download folder.
        if (!currentCoroutineContext().isActive) { out.delete(); throw CancellationException("Stopped") }
        if (isPrivate) out = publishFile(out, target)
        android.util.Log.i(TAG, "Post-processing took ${System.currentTimeMillis() - started} ms")
        val mime = when (out.extension.lowercase()) {
            "webm" -> if (e.extractAudio || e.mimeType.startsWith("audio/")) "audio/webm" else "video/webm"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "opus" -> "audio/ogg"
            "ts" -> "video/mp2t"
            "mp4" -> if (e.mimeType.startsWith("audio/")) e.mimeType else "video/mp4"
            else -> e.mimeType
        }
        dao.get(e.id)?.let {
            dao.update(it.copy(filePath = out.absolutePath, mimeType = mime, type = if (e.extractAudio) ContentType.AUDIO else ContentType.fromMime(mime)))
        }
        return out
    }

    /** Moves a finished file from private storage to the download folder under [target]'s name (its own extension). */
    /** QuickTube's description, address and tags in a finished video (MP4) or photo (JPEG); other files are left as they are. */
    private fun brand(file: File, title: String) {
        val started = System.currentTimeMillis()
        val ok = runCatching {
            when (file.extension.lowercase()) {
                "mp4", "m4v", "mov", "m4a", "mp3" -> AudioTags.write(file, AudioTags.Tags(title, null, null))
                "jpg", "jpeg" -> {
                    android.media.ExifInterface(file.path).apply {
                        setAttribute(android.media.ExifInterface.TAG_IMAGE_DESCRIPTION, Branding.DESCRIPTION)
                        setAttribute(android.media.ExifInterface.TAG_SOFTWARE, Branding.ENCODER)
                        setAttribute(android.media.ExifInterface.TAG_COPYRIGHT, Branding.URL)
                        setAttribute(android.media.ExifInterface.TAG_USER_COMMENT, Branding.KEYWORDS.joinToString(", "))
                    }.saveAttributes()
                    true
                }
                else -> false
            }
        }.onFailure { android.util.Log.w("DownloadEngine", "Branding failed: $it") }.getOrDefault(false)
        android.util.Log.i("DownloadEngine", "Branded ${file.name}: $ok in ${System.currentTimeMillis() - started} ms")
    }

    private fun publishFile(src: File, target: File): File {
        val dest = if (src.extension == target.extension) target else uniqueSibling(target, src.extension)
        return moveFile(src, dest)
    }

    /** Rename, or copy + delete across file systems. */
    private fun moveFile(src: File, dest: File): File {
        if (dest.exists()) dest.delete()
        if (src.renameTo(dest)) return dest
        try {
            src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        } catch (e: IOException) {
            dest.delete()
            throw IOException("Could not save file: ${e.message}")
        }
        src.delete()
        return dest
    }

    private fun uniqueSibling(target: File, ext: String): File {
        val base = target.nameWithoutExtension
        var f = File(target.parentFile, "$base.$ext")
        var n = 1
        while (f.exists()) f = File(target.parentFile, "$base (${n++}).$ext")
        return f
    }

    /** Removes every partial and intermediate file of a task, wherever it was downloading. */
    private fun deleteParts(e: DownloadEntity) {
        for (p in listOf("${e.filePath}.part", "${e.filePath}.audio.part")) {
            File(p).delete()
            File("$p.idx").delete()
            ChunkState.stateFile(File(p)).delete()
            InPlaceMp4Merge.marker(File(p)).delete()
        }
        partsDir().listFiles { f -> f.name.startsWith("task-${e.id}.") }?.forEach { it.delete() }
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
        e is UnsupportedCodecException -> e.message
        e is java.net.UnknownHostException -> "No internet connection"
        e is java.net.SocketTimeoutException -> "Connection timed out"
        e.message?.contains("ENOSPC") == true -> "Not enough storage"
        e is OutOfMemoryError -> "Not enough memory to process this video"
        else -> com.edureminder.quicktube.core.common.DownloadErrors.friendly(e.message)
    }

    private fun headersOf(e: DownloadEntity): Map<String, String> = e.headers?.let { h ->
        Json.parseToJsonElement(h).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }.orEmpty()

    /**
     * Downloads one stream. When its link expired (403/410) on any site, the page is extracted again and the
     * download continues with fresh links. The two streams of a task share [refreshLock] so they refresh once.
     */
    private suspend fun fetchWithRefresh(id: Long, isAudio: Boolean, part: File, progress: TaskProgress, refreshLock: Mutex) {
        var refreshed = false
        while (true) {
            val e = dao.get(id) ?: return
            val url = (if (isAudio) e.audioUrl else e.url) ?: return
            try {
                if (e.protocol == StreamProtocol.HLS) fetchHls(url, isAudio, part, headersOf(e), progress)
                else fetch(url, isAudio, part, headersOf(e), progress)
                return
            } catch (ex: HttpStatusException) {
                if (ex.code != 403 && ex.code != 410 || refreshed) throw ex
                refreshed = true
                refreshLock.withLock {
                    val cur = dao.get(id) ?: throw ex
                    // The other stream may have refreshed already.
                    if ((if (isAudio) cur.audioUrl else cur.url) == url) refreshUrls(cur, compatibleOnly = false) ?: throw ex
                }
            }
        }
    }

    /**
     * Re-extracts the page and stores fresh links for the closest matching format. With [compatibleOnly] the
     * current format is skipped (it couldn't be saved) and the partial files are discarded.
     */
    private suspend fun refreshUrls(e: DownloadEntity, compatibleOnly: Boolean): DownloadEntity? {
        val page = e.pageUrl ?: return null
        val headers = headersOf(e)
        var pageHeaders = emptyMap<String, String>()
        val options = if (media.canExtract(page)) {
            runCatching { media.details(page, forceRefresh = true).options }.getOrNull()
        } else {
            universal?.takeIf { it.isReady }?.let { u ->
                runCatching { u.extract(page, headers) }.getOrNull()?.also { pageHeaders = it.headers }?.options
            }
        } ?: return null
        val height = e.quality?.substringBefore('p')?.trim()?.toIntOrNull()
        val sameKind = when {
            e.extractAudio -> options.filter { it.extractAudio }.ifEmpty { options.filter { it.mimeType.startsWith("video/") } }
            e.mimeType.startsWith("audio/") -> options.filter { it.mimeType.startsWith("audio/") && !it.extractAudio }
            else -> options.filter { it.mimeType.startsWith("video/") && !it.extractAudio }
        }
        // Fresh links always differ, so the format that couldn't be saved is recognised by what it is.
        val failed = { o: com.edureminder.quicktube.core.model.DownloadOption ->
            o.ext == e.filePath.substringAfterLast('.') && o.protocol == e.protocol && (o.audioUrl != null) == (e.audioUrl != null) &&
                (height == null || o.height == height)
        }
        val candidates = if (compatibleOnly) sameKind.filterNot(failed).ifEmpty { sameKind.filter { it.url != e.url && !failed(it) } } else sameKind
        // Options are listed worst to best, so a tie (e.g. sizes unknown) goes to the better one.
        val option = (if (height != null) candidates.withIndex().minByOrNull { (i, o) -> kotlin.math.abs(o.height - height) * 1000L - i }?.value else null)
            ?: candidates.firstOrNull { o -> e.quality?.let { q -> o.title.contains(q) } == true }
            ?: candidates.firstOrNull()
            ?: return null
        val mergedHeaders = (headers + pageHeaders).takeIf { it.isNotEmpty() }
            ?.let { h -> JsonObject(h.mapValues { JsonPrimitive(it.value) }).toString() }
        val cur = dao.get(e.id) ?: e
        val sameFormat = !compatibleOnly && option.protocol == cur.protocol && (option.audioUrl != null) == (cur.audioUrl != null)
        val quality = if (height != null && option.height > 0) "${option.height}p" else cur.quality
        // Another quality is saved under its own name: "Title(1080p).mp4", never the "(2160p)" first asked for.
        val path = cur.quality?.takeIf { !sameFormat && quality != it && cur.filePath.contains("($it)") }
            ?.let { cur.filePath.replace("($it)", "($quality)") }
            ?.takeIf { !File(it).exists() && dao.countWithPath(it) == 0 } ?: cur.filePath
        val updated = cur.copy(
            url = option.url, audioUrl = option.audioUrl, protocol = option.protocol, headers = mergedHeaders ?: cur.headers,
            quality = quality, filePath = path,
            totalBytes = if (sameFormat) cur.totalBytes else option.sizeBytes,
            audioTotalBytes = if (option.audioUrl == null) -1 else if (sameFormat) cur.audioTotalBytes else option.audioSizeBytes,
            downloadedBytes = if (sameFormat) cur.downloadedBytes else 0,
            audioDownloadedBytes = if (sameFormat) cur.audioDownloadedBytes else 0,
        )
        if (!sameFormat) deleteParts(cur)
        dao.update(updated)
        return updated
    }

    private data class Probe(val total: Long, val ranges: Boolean)

    /** Exact size and whether the server takes byte ranges, from a one-byte request. */
    private fun probe(url: String, headers: Map<String, String>): Probe = try {
        http.newCall(request(url, headers, "bytes=0-0")).execute().use { r ->
            when {
                r.code == 416 -> Probe(-1, false)
                !r.isSuccessful -> throw HttpStatusException(r.code)
                else -> {
                    val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    if (r.code == 206 && total != null && total > 0) Probe(total, true) else Probe(r.body.contentLength(), false)
                }
            }
        }
    } catch (e: java.net.ProtocolException) {
        // A server that mishandles the one-byte range: download it front to back instead.
        Probe(-1, false)
    }

    private fun request(url: String, headers: Map<String, String>, range: String?): Request = Request.Builder().url(url)
        .header("User-Agent", headers["User-Agent"] ?: UA)
        .apply {
            range?.let { header("Range", it) }
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true) && !k.equals("Range", true)) header(k, v) }
        }
        .build()

    /**
     * Downloads a plain HTTP stream into [part]. With a known size and range support it is split into
     * [SegmentPlan.CHUNK] pieces fetched over several connections and written in place; completed pieces
     * are remembered so a pause or a crash resumes without losing them.
     */
    private suspend fun fetch(url: String, isAudio: Boolean, part: File, headers: Map<String, String>, p: TaskProgress) {
        val probe = probe(url, headers)
        val counter = p.counter(isAudio)
        if (probe.total <= 0 || !probe.ranges) {
            ChunkState.stateFile(part).delete()
            fetchSequential(url, part, headers, probe, counter, p.totalOf(isAudio), p.meter)
            return
        }
        val total = probe.total
        p.totalOf(isAudio).set(total)
        val state = ChunkState.load(part, total)
        counter.set(state.doneBytes())
        val queue = ConcurrentLinkedQueue(state.pending())
        if (queue.isEmpty() && part.length() == total) return
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
            val channel = raf.channel
            coroutineScope {
                repeat(minOf(SegmentPlan.connectionsFor(total), queue.size)) {
                    launch(netDispatcher) {
                        while (true) {
                            val i = queue.poll() ?: break
                            transfers.withPermit { fetchChunk(url, headers, SegmentPlan.range(i, total), channel, counter, p.meter) }
                            state.markDone(i)
                        }
                    }
                }
            }
        }
    }

    /** One byte range, resumed inside the range on a dropped connection and retried with backoff. */
    private suspend fun fetchChunk(url: String, headers: Map<String, String>, range: LongRange, channel: FileChannel, counter: AtomicLong, meter: SpeedMeter) {
        var pos = range.first
        var attempt = 0
        val buf = ByteArray(128 * 1024)
        while (pos <= range.last) {
            currentCoroutineContext().ensureActive()
            try {
                http.newCall(request(url, headers, "bytes=$pos-${range.last}")).execute().use { resp ->
                    if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                    if (resp.code != 206) throw IOException("The server ignored the byte range")
                    val input = resp.body.byteStream()
                    while (pos <= range.last) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), range.last - pos + 1).toInt())
                        if (n < 0) break
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        var at = pos
                        while (bb.hasRemaining()) at += channel.write(bb, at)
                        pos += n
                        counter.addAndGet(n.toLong())
                        meter.add(n)
                        limiter.consume(n)
                        attempt = 0
                    }
                }
                if (pos <= range.last) throw IOException("Connection closed early")
            } catch (ex: IOException) {
                val fatal = ex is HttpStatusException && ex.code in 400..499 && ex.code != 408 && ex.code != 429
                if (fatal || ++attempt > 5) throw ex
                delay(600L * attempt)
            }
        }
    }

    /** Front-to-back download for servers without range support or without a known size. */
    private suspend fun fetchSequential(url: String, part: File, headers: Map<String, String>, probe: Probe, counter: AtomicLong, totalRef: AtomicLong, meter: SpeedMeter) {
        var done = if (probe.ranges && part.exists()) part.length() else 0L
        var total = probe.total
        counter.set(done)
        if (total > 0) totalRef.set(total)
        if (total > 0 && done >= total) return
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(done)
            raf.seek(done)
            http.newCall(request(url, headers, if (done > 0) "bytes=$done-" else null)).execute().use { resp ->
                if (resp.code == 416) return
                if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                if (resp.code == 200 && done > 0) {
                    // Server ignored Range: restart from scratch.
                    raf.setLength(0); raf.seek(0); done = 0; counter.set(0)
                }
                resp.body.contentLength().takeIf { it > 0 }?.let { total = done + it; totalRef.set(total) }
                val input = resp.body.byteStream()
                val buf = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    done += n
                    counter.addAndGet(n.toLong())
                    meter.add(n)
                    limiter.consume(n)
                }
            }
        }
        if (total > 0 && done < total) throw IOException("Connection closed early")
        totalRef.set(maxOf(total, done))
    }

    /**
     * Downloads every segment of an HLS media playlist into [part], in order, [HLS_PARALLEL] at a time.
     * Progress ("segments done" + byte offset) is kept in [part].idx so pauses resume mid-stream.
     */
    private suspend fun fetchHls(url: String, isAudio: Boolean, part: File, headers: Map<String, String>, p: TaskProgress) {
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
        val counter = p.counter(isAudio)
        val totalRef = p.totalOf(isAudio)
        counter.set(offset)
        val keys = ConcurrentHashMap<String, ByteArray>()
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
                val results = coroutineScope {
                    batch.map { seg -> async(netDispatcher) { transfers.withPermit { fetchSegment(seg, headers, keys) }.also { meter(p, it.size) } } }.awaitAll()
                }
                for (bytes in results) {
                    raf.write(bytes)
                    offset += bytes.size
                    done++
                }
                idx.writeText("$done $offset")
                counter.set(offset)
                totalRef.set(offset * segments.size / done)
            }
        }
        idx.delete()
        counter.set(offset)
        totalRef.set(offset)
    }

    private suspend fun meter(p: TaskProgress, n: Int) {
        p.meter.add(n)
        limiter.consume(n)
    }

    private suspend fun fetchSegment(seg: HlsPlaylist.Segment, headers: Map<String, String>, keys: MutableMap<String, ByteArray>): ByteArray =
        withContext(netDispatcher) {
            var attempt = 0
            while (true) {
                try {
                    val raw = http.newCall(request(seg.url, headers, seg.byteRange?.let { "bytes=${it.first}-${it.last}" })).execute().use { r ->
                        if (!r.isSuccessful) throw HttpStatusException(r.code)
                        r.body.bytes()
                    }
                    val key = seg.key ?: return@withContext raw
                    val keyUrl = key.url
                    if (key.method != "AES-128" || keyUrl == null) throw IOException("This video is DRM-protected and can't be downloaded")
                    val keyBytes = keys.getOrPut(keyUrl) {
                        http.newCall(request(keyUrl, headers, null)).execute().use { r ->
                            if (!r.isSuccessful) throw HttpStatusException(r.code)
                            r.body.bytes()
                        }
                    }
                    val iv = key.iv ?: ByteArray(16).also { b -> var n = seg.sequence; for (i in 15 downTo 8) { b[i] = (n and 0xff).toByte(); n = n shr 8 } }
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), javax.crypto.spec.IvParameterSpec(iv))
                    return@withContext cipher.doFinal(raw)
                } catch (ex: IOException) {
                    val fatal = ex is HttpStatusException && ex.code in 400..499 && ex.code != 408 && ex.code != 429
                    if (fatal || ++attempt >= 4) throw ex
                    delay(800L * attempt)
                }
            }
            @Suppress("UNREACHABLE_CODE") ByteArray(0)
        }

    /** Keeps total throughput of all downloads under the configured limit (0 = unlimited). Shared by every connection. */
    private class RateLimiter(private val limit: suspend () -> Long) {
        private val lock = Any()
        private var windowStart = System.currentTimeMillis()
        private var bytes = 0L
        @Volatile private var cached = 0L
        @Volatile private var cachedAt = 0L

        suspend fun consume(n: Int) {
            val now = System.currentTimeMillis()
            if (now - cachedAt > 1000) { cachedAt = now; cached = limit() }
            val l = cached
            if (l <= 0) return
            val wait = synchronized(lock) {
                bytes += n
                val elapsed = System.currentTimeMillis() - windowStart
                val expected = bytes * 1000 / l
                if (elapsed > 1000 && expected <= elapsed) { windowStart = System.currentTimeMillis(); bytes = 0 }
                expected - elapsed
            }
            if (wait > 0) delay(wait)
        }
    }

    companion object {
        private const val TAG = "DownloadEngine"
        /** Segments per HLS stream fetched together (held in memory until written in order). */
        private const val HLS_PARALLEL = 6
        /** Network transfers in flight across all downloads. */
        private const val TRANSFERS = 12
        private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
