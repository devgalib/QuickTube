package com.edureminder.quicktube.core.data

import android.util.LruCache
import com.edureminder.quicktube.core.extractor.MediaExtractor
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.Page
import com.edureminder.quicktube.core.model.SearchFilter
import com.edureminder.quicktube.core.model.VideoDetails

internal class MediaRepositoryImpl(private val extractor: MediaExtractor) : MediaRepository {
    // Stream URLs expire after a few hours; keep details briefly so back/forward navigation is instant.
    private val detailsCache = LruCache<String, Pair<Long, VideoDetails>>(30)

    override suspend fun suggestions(query: String) = extractor.suggestions(query)
    // YouTube sometimes lists the same video twice in one page; the lists key items by URL, so duplicates go here.
    override suspend fun search(query: String, filter: SearchFilter, page: Any?) = extractor.search(query, filter, page).distinct()
    override suspend fun trending(page: Any?) = extractor.trending(page).distinct()
    override suspend fun musicHome() = extractor.musicHome().map { it.copy(items = it.items.distinctBy { i -> i.url }) }
    override suspend fun comments(url: String, page: Any?) = extractor.comments(url, page)
    override suspend fun channel(url: String, page: Any?) = extractor.channel(url, page).let { it.copy(videos = it.videos.distinct()) }
    override suspend fun playlist(url: String, page: Any?) = extractor.playlist(url, page).let { it.copy(videos = it.videos.distinct()) }
    override fun canExtract(url: String) = extractor.canHandle(url)
    override fun linkKind(url: String) = extractor.linkKind(url)

    override suspend fun details(url: String, forceRefresh: Boolean): VideoDetails {
        val now = System.currentTimeMillis()
        if (!forceRefresh) detailsCache.get(url)?.let { (at, d) -> if (now - at < CACHE_MS) return d }
        return extractor.details(url).let { it.copy(related = it.related.distinctBy { i -> i.url }) }.also { detailsCache.put(url, now to it) }
    }

    private fun Page<MediaItem>.distinct() = copy(items = items.distinctBy { it.url })

    private companion object {
        const val CACHE_MS = 30 * 60 * 1000L
    }
}
