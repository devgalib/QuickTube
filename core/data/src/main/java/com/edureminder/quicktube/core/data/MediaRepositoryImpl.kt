package com.edureminder.quicktube.core.data

import android.util.LruCache
import com.edureminder.quicktube.core.extractor.MediaExtractor
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.Page
import com.edureminder.quicktube.core.model.SearchFilter
import com.edureminder.quicktube.core.model.VideoDetails

internal class MediaRepositoryImpl(
    private val extractor: MediaExtractor,
    /** Site engine that updates itself without an app release (yt-dlp), used when [extractor] fails on a video. */
    private val fallback: () -> com.edureminder.quicktube.core.extractor.UniversalExtractor? = { null },
) : MediaRepository {
    // Stream URLs expire after a few hours; keep details briefly so back/forward navigation is instant.
    private val detailsCache = LruCache<String, Pair<Long, VideoDetails>>(30)

    override suspend fun suggestions(query: String) = extractor.suggestions(query)
    // YouTube sometimes lists the same video twice in one page; the lists key items by URL, so duplicates go here.
    override suspend fun search(query: String, filter: SearchFilter, page: Any?) = extractor.search(query, filter, page).distinct()
    override suspend fun trending(page: Any?) = extractor.trending(page).distinct()
    override suspend fun musicHome() = extractor.musicHome().map { it.copy(items = it.items.distinctBy { i -> i.url }) }
    override suspend fun comments(url: String, page: Any?) = extractor.comments(url, page)
    override suspend fun channel(url: String, page: Any?) = extractor.channel(url, page).let { it.copy(videos = it.videos.distinct()) }
    override suspend fun channelTab(url: String, tab: com.edureminder.quicktube.core.model.ChannelTab, page: Any?) =
        extractor.channelTab(url, tab, page).distinct()
    override suspend fun playlist(url: String, page: Any?) = extractor.playlist(url, page).let { it.copy(videos = it.videos.distinct()) }
    override fun canExtract(url: String) = extractor.canHandle(url)
    override fun linkKind(url: String) = extractor.linkKind(url)

    override suspend fun details(url: String, forceRefresh: Boolean): VideoDetails {
        val now = System.currentTimeMillis()
        if (!forceRefresh) detailsCache.get(url)?.let { (at, d) -> if (now - at < CACHE_MS) return d }
        val details = try {
            extractor.details(url).let { it.copy(related = it.related.distinctBy { i -> i.url }) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // When YouTube changes faster than NewPipe ships, yt-dlp (updated on its own) often still reads the video.
            fromUniversal(url) ?: throw e
        }
        return details.also { detailsCache.put(url, now to it) }
    }

    private suspend fun fromUniversal(url: String): VideoDetails? {
        val page = runCatching { fallback()?.extract(url) }.getOrNull()?.takeIf { it.options.isNotEmpty() } ?: return null
        // Something playable without merging: a file with its own sound, best quality first; audio for the music player.
        val muxed = page.options.filter { it.kind == com.edureminder.quicktube.core.model.FormatKind.VIDEO && it.audioUrl == null }.maxByOrNull { it.height }
        val audio = page.options.filter { it.kind == com.edureminder.quicktube.core.model.FormatKind.AUDIO && !it.extractAudio }.maxByOrNull { it.bitrate }
        return VideoDetails(
            item = MediaItem(url = url, title = page.title, thumbnail = page.thumbnail, durationSec = page.durationSec?.toLong() ?: -1),
            description = "", likeCount = -1, subscriberCount = -1, uploadDate = null,
            playbackUrl = muxed?.url, playbackMime = muxed?.mimeType, hlsUrl = null, dashUrl = null,
            audioPlaybackUrl = audio?.url, options = page.options, related = emptyList(), commentsUrl = null,
        )
    }

    private fun Page<MediaItem>.distinct() = copy(items = items.distinctBy { it.url })

    private companion object {
        const val CACHE_MS = 30 * 60 * 1000L
    }
}
