package com.edureminder.quicktube.core.navigation

import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.model.MediaItem

/**
 * A video playlist being played (Play all / Shuffle on a local playlist): the video page plays its videos one after
 * another instead of YouTube's suggestions. Opening a video that isn't in it simply goes back to suggestions.
 */
object VideoQueue {
    @Volatile var name: String = ""
        private set
    @Volatile private var items: List<MediaItem> = emptyList()

    fun start(name: String, items: List<MediaItem>) { this.name = name; this.items = items }

    private fun same(a: String, b: String) = a == b || Urls.youtubeVideoId(a)?.let { it == Urls.youtubeVideoId(b) } == true

    /** "3 / 12" for [url] when it's in the playlist. */
    fun position(url: String): Pair<Int, Int>? = items.indexOfFirst { same(it.url, url) }.takeIf { it >= 0 }?.let { it + 1 to items.size }

    /** The video after [url] in the playlist; null at its end or when [url] isn't in it. */
    fun nextAfter(url: String): MediaItem? = items.indexOfFirst { same(it.url, url) }.takeIf { it >= 0 }?.let { items.getOrNull(it + 1) }
}
