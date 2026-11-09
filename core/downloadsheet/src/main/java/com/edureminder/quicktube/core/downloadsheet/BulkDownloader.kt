package com.edureminder.quicktube.core.downloadsheet

import com.edureminder.quicktube.core.analytics.Analytics
import com.edureminder.quicktube.core.analytics.Events
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.database.BulkDao
import com.edureminder.quicktube.core.database.BulkItemEntity
import com.edureminder.quicktube.core.database.BulkJobEntity
import com.edureminder.quicktube.core.download.DownloadManager
import com.edureminder.quicktube.core.model.FormatKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What became of one link of a bulk download (stored as its name). */
enum class BulkStatus { PENDING, CHECKING, QUEUED, SKIPPED, FAILED, NOT_FOUND }

data class BulkJob(val id: Long, val name: String, val folder: String, val createdAt: Long, val quality: BatchQuality, val items: List<BulkItem>)

/** One link; [downloadId] is the download it became (QUEUED) or the earlier one it matched (SKIPPED). */
data class BulkItem(val id: Long, val link: String, val title: String?, val status: BulkStatus, val message: String?, val downloadId: Long)

/**
 * Bulk download: a list of links from any sites (pasted or from a .txt file) saved together in a folder named after
 * the bulk. Each link is looked up in the background a few at a time, like a playlist download; links already
 * downloaded are skipped, and deleted or private videos are marked as such instead of failing the whole list.
 * Links still waiting when the app was closed are picked up again on the next start.
 */
class BulkDownloader(
    private val links: LinkResolver,
    private val downloads: DownloadManager,
    private val dao: BulkDao,
    private val analytics: Analytics,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Long>(Channel.UNLIMITED)
    /** Links already given their one automatic second try. */
    private val retried = java.util.Collections.synchronizedSet(HashSet<Long>())

    /** Every bulk, newest first, with its links. */
    val jobs: Flow<List<BulkJob>> = combine(dao.observeJobs(), dao.observeAllItems()) { jobs, items ->
        val byJob = items.groupBy { it.jobId }
        jobs.map { it.asModel(byJob[it.id].orEmpty()) }
    }

    fun job(id: Long): Flow<BulkJob?> = combine(dao.observeJob(id), dao.observeItems(id)) { j, items -> j?.asModel(items) }

    private fun BulkJobEntity.asModel(items: List<BulkItemEntity>) = BulkJob(
        id, name, folder, createdAt, runCatching { BatchQuality.valueOf(quality) }.getOrDefault(BatchQuality.P720),
        items.map { BulkItem(it.id, it.link, it.title, runCatching { BulkStatus.valueOf(it.status) }.getOrDefault(BulkStatus.FAILED), it.message, it.downloadId) },
    )

    init {
        repeat(PARALLEL) {
            scope.launch {
                for (id in queue) {
                    try {
                        process(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        dao.item(id)?.let { dao.updateItem(it.copy(status = BulkStatus.FAILED.name, message = e.message ?: "Couldn't add this link")) }
                    }
                }
            }
        }
        scope.launch {
            dao.unfinished().forEach { dao.updateItem(it.copy(status = BulkStatus.PENDING.name)); queue.send(it.id) }
        }
    }

    /** Starts a bulk of [urls] saved in a folder called [name]; returns its id. */
    suspend fun start(name: String, urls: List<String>, quality: BatchQuality): Long {
        val title = name.trim().ifBlank { "Bulk " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date()) }
        val jobId = dao.insertJob(BulkJobEntity(name = title, folder = Urls.safeFileName(title, 80), createdAt = System.currentTimeMillis(), quality = quality.name))
        dao.insertItems(urls.mapIndexed { i, u -> BulkItemEntity(jobId = jobId, position = i, link = u, title = null, status = BulkStatus.PENDING.name, message = null, downloadId = 0) })
        analytics.event(Events.DOWNLOAD_STARTED, mapOf("kind" to "bulk", "count" to urls.size, "quality" to quality.name.lowercase()))
        dao.items(jobId).forEach { queue.send(it.id) }
        return jobId
    }

    /** Looks up again the links that failed or weren't found. */
    fun retry(jobId: Long) = scope.launch {
        dao.items(jobId).filter { it.status == BulkStatus.FAILED.name || it.status == BulkStatus.NOT_FOUND.name }.forEach {
            retried.remove(it.id)
            dao.updateItem(it.copy(status = BulkStatus.PENDING.name, message = null))
            queue.send(it.id)
        }
    }

    /** Forgets the bulk (its downloaded files stay). */
    fun delete(jobId: Long) = scope.launch { dao.deleteItems(jobId); dao.deleteJob(jobId) }

    private suspend fun process(id: Long) {
        val item = dao.item(id)?.takeIf { it.status == BulkStatus.PENDING.name } ?: return
        val job = dao.job(item.jobId) ?: return
        dao.updateItem(item.copy(status = BulkStatus.CHECKING.name))
        val found = links.resolve(item.link)
        val title = found.title.takeIf { it.isNotBlank() }
        if (found.error != null || found.options.isEmpty()) {
            val (status, message) = problem(found.error)
            // Sites refuse bursts now and then (and connections drop): one more try a little later, at the back of the queue.
            if (status == BulkStatus.FAILED && retried.add(id)) {
                dao.updateItem(item.copy(status = BulkStatus.PENDING.name, title = title))
                scope.launch { kotlinx.coroutines.delay(RETRY_DELAY_MS); queue.send(id) }
                return
            }
            dao.updateItem(item.copy(status = status.name, message = message, title = title))
            return
        }
        val quality = runCatching { BatchQuality.valueOf(job.quality) }.getOrDefault(BatchQuality.P720)
        // A post with several photos/videos: all of them. Otherwise the format nearest the bulk's quality.
        val chosen = if (found.items) found.options
        else listOf(BatchDownloader.pick(found.options, quality) ?: found.options.firstOrNull { it.kind == FormatKind.VIDEO } ?: found.options.first())
        var added = 0L
        var existing = 0L
        chosen.forEachIndexed { i, o ->
            val name = if (chosen.size > 1) "${found.title} (${i + 1})" else found.title
            val request = o.toRequest(name, found.pageUrl ?: item.link, o.thumbnail ?: found.thumbnail, found.durationSec, found.headers).copy(folder = job.folder)
            val same = downloads.findExisting(request)
            if (same != null) existing = same.id else added = downloads.enqueue(request)
        }
        dao.updateItem(
            if (added != 0L) item.copy(status = BulkStatus.QUEUED.name, title = title, message = null, downloadId = added)
            else item.copy(status = BulkStatus.SKIPPED.name, title = title, message = "Already downloaded", downloadId = existing),
        )
    }

    companion object {
        private const val PARALLEL = 3
        private const val RETRY_DELAY_MS = 8_000L
        private val gone = Regex(
            "(?i)unavailable|removed|deleted|private|not found|doesn'?t exist|does not exist|no longer|\\b404\\b|terminated|" +
                "not available|copyright|suspended|age.?restricted|sign in to confirm|" +
                // NewPipe's answer for a YouTube id that doesn't exist.
                "json response is too short|invalid video id|no such video",
        )

        /** A failed look-up as a status and a message people understand. */
        fun problem(error: String?): Pair<BulkStatus, String> {
            val e = error.orEmpty()
            return when {
                e.contains("live", ignoreCase = true) && e.contains("stream", ignoreCase = true) -> BulkStatus.FAILED to "Live stream: can be downloaded after it ends"
                e.contains("age", ignoreCase = true) && e.contains("restrict", ignoreCase = true) -> BulkStatus.NOT_FOUND to "Age-restricted: can't be downloaded without signing in"
                e.contains("removed or is private", ignoreCase = true) -> BulkStatus.NOT_FOUND to "Removed or private: the video can't be downloaded"
                e.contains("private", ignoreCase = true) -> BulkStatus.NOT_FOUND to "Private video: only its owner can see it"
                gone.containsMatchIn(e) -> BulkStatus.NOT_FOUND to "Not found: the video was deleted or isn't available"
                e.isBlank() -> BulkStatus.FAILED to "No video in this link"
                // The page gave nothing this time (often a passing refusal): retried, then shown as not downloaded.
                e.contains("no video found", true) || e.contains("couldn't get this video", true) -> BulkStatus.FAILED to "No video found in this link"
                e.contains("timeout", true) || e.contains("unable to resolve host", true) || e.contains("network", true) ->
                    BulkStatus.FAILED to "No connection: try again"
                else -> BulkStatus.FAILED to e.lineSequence().first().take(140)
            }
        }
    }
}
