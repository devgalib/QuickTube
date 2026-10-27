package com.edureminder.quicktube.core.downloadsheet

import com.edureminder.quicktube.core.analytics.Analytics
import com.edureminder.quicktube.core.analytics.Events
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.download.DownloadManager
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.DownloadRequest
import com.edureminder.quicktube.core.model.FormatKind
import com.edureminder.quicktube.core.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The one quality the user picks for a whole batch; each video gets its closest available format. */
enum class BatchQuality(val label: String, val hint: String, val height: Int?) {
    AUDIO("Music (M4A)", "Audio only, smallest", null),
    P360("Fast (360p)", "Small files", 360),
    P720("High quality (720p)", "Recommended", 720),
    P1080("Full HD (1080p)", "Large files", 1080),
    BEST("Best available", "Up to 4K", Int.MAX_VALUE),
}

/**
 * Adds many videos (a playlist, a channel, a selection) to the download queue. Formats are resolved a few at
 * a time in the background, so it keeps going after the user leaves the page.
 */
class BatchDownloader(
    private val media: MediaRepository,
    private val downloads: DownloadManager,
    private val analytics: Analytics,
) {
    data class State(val total: Int = 0, val added: Int = 0, val failed: Int = 0) {
        val running get() = added + failed < total
    }

    private data class Job(val item: MediaItem, val quality: BatchQuality)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Job>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        // A few resolvers in parallel: fast enough for big playlists without hammering the site.
        repeat(PARALLEL) {
            scope.launch {
                for (job in queue) {
                    val ok = try {
                        add(job)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        false
                    }
                    _state.update { if (ok) it.copy(added = it.added + 1) else it.copy(failed = it.failed + 1) }
                }
            }
        }
    }

    fun enqueue(items: List<MediaItem>, quality: BatchQuality) {
        if (items.isEmpty()) return
        // A finished batch starts a fresh count; a running one grows.
        _state.update { if (it.running) it.copy(total = it.total + items.size) else State(total = items.size) }
        analytics.event(Events.DOWNLOAD_STARTED, mapOf("kind" to "batch", "count" to items.size, "quality" to quality.name.lowercase()))
        items.forEach { queue.trySend(Job(it, quality)) }
    }

    private suspend fun add(job: Job): Boolean {
        val d = media.details(job.item.url)
        if (d.item.isLive) return false
        val option = pick(d.options, job.quality) ?: return false
        downloads.enqueue(option.toRequest(d.item.title.ifBlank { job.item.title }, d.item.url, d.item.thumbnail ?: job.item.thumbnail, d.item.durationSec))
        return true
    }

    companion object {
        private const val PARALLEL = 3

        /** Closest format to [quality]: the best at or below the asked height, else the smallest above it. */
        fun pick(options: List<DownloadOption>, quality: BatchQuality): DownloadOption? {
            val target = quality.height
            if (target == null) {
                return options.filter { it.kind == FormatKind.AUDIO && !it.extractAudio }
                    .sortedWith(compareBy({ it.ext != "m4a" }, { -it.bitrate })).firstOrNull()
                    ?: options.firstOrNull { it.extractAudio }
            }
            val videos = options.filter { it.kind == FormatKind.VIDEO && !it.extractAudio }
            return videos.filter { it.height in 1..target }.maxByOrNull { it.height }
                ?: videos.filter { it.height > target }.minByOrNull { it.height }
                ?: videos.firstOrNull()
        }
    }
}

/** The queue request for a chosen format; shared by the format sheet and batch downloads. */
fun DownloadOption.toRequest(
    title: String,
    pageUrl: String?,
    thumbnail: String?,
    durationSec: Long,
    headers: Map<String, String> = emptyMap(),
) = DownloadRequest(
    title = title.ifBlank { "video" },
    url = url,
    audioUrl = audioUrl,
    ext = ext,
    mimeType = mimeType,
    pageUrl = pageUrl,
    thumbnail = thumbnail,
    quality = when {
        kind == FormatKind.VIDEO && height > 0 -> "${height}p"
        // Unknown height: the short label in the title, e.g. "High quality (HD)" → "HD".
        kind == FormatKind.VIDEO -> this.title.substringAfterLast('(', "").substringBefore(')').takeIf { it.isNotBlank() && it.length <= 8 } ?: "Video"
        else -> ext.uppercase() + if (bitrate > 0) " ${bitrate}K" else ""
    },
    sizeBytes = sizeBytes,
    audioSizeBytes = audioSizeBytes,
    durationSec = durationSec,
    headers = headers,
    protocol = protocol,
    extractAudio = extractAudio,
)
