package com.edureminder.quicktube.core.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.edureminder.quicktube.core.data.MediaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Songs played straight from YouTube: a queue item's address names the song's page, and its audio stream is looked
 * up only when the player gets to it (like Flow's music player), so a whole shelf can be queued at once without
 * fetching every stream first, and an expired stream is simply looked up again.
 */
object OnlineAudio {
    const val SCHEME = "qtaudio"

    fun uri(pageUrl: String): String = "$SCHEME://stream?url=" + Uri.encode(pageUrl)

    fun pageUrl(uri: Uri): String? = if (uri.scheme == SCHEME) uri.getQueryParameter("url") else null

    /** A queue item for the song at [pageUrl] (played as audio, remembered in the history by its page). */
    fun item(pageUrl: String, title: String, artist: String, artwork: String?) = PlayItem(
        id = "music:$pageUrl", uri = uri(pageUrl), title = title, artist = artist, artwork = artwork, isVideo = false, historyKey = pageUrl,
    )

    fun isMusic(item: PlayItem?) = item?.id?.startsWith("music:") == true
}

/** Turns [OnlineAudio] addresses into the song's current audio stream (on the player's loading thread). */
@UnstableApi
internal class OnlineAudioResolver(private val media: () -> MediaRepository) : ResolvingDataSource.Resolver {
    private val streams = ConcurrentHashMap<String, Pair<Long, String>>()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val page = OnlineAudio.pageUrl(dataSpec.uri) ?: return dataSpec
        val now = System.currentTimeMillis()
        // Stream links last about six hours; a cached one is reused only well within that.
        val cached = streams[page]?.takeIf { now - it.first < STREAM_TTL_MS }?.second
        val url = cached ?: runBlocking { lookUp(page, refresh = streams.containsKey(page)) ?: run { delay(1500); lookUp(page, refresh = true) } }
            ?.let { d -> d.audioPlaybackUrl ?: d.playbackUrl }
            ?.also { streams[page] = now to it }
            ?: throw IOException("Couldn't load this song")
        return dataSpec.withUri(Uri.parse(url))
    }

    /** One try (a phone's network often fails a single lookup, so the caller tries twice). */
    private suspend fun lookUp(page: String, refresh: Boolean) = runCatching { media().details(page, forceRefresh = refresh) }
        .onFailure { android.util.Log.w("OnlineAudio", "Song lookup failed: $it") }.getOrNull()

    private companion object {
        const val STREAM_TTL_MS = 3 * 60 * 60 * 1000L
    }
}
