package com.edureminder.quicktube.desktop.player

import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Songs played as music (the Music tab, music playlists): only their sound is streamed, one after another, like the
 * Android music player. Each song's audio link is looked up when it's its turn (links expire after a few hours).
 */
class DesktopMusic(private val player: DesktopPlayer, private val media: MediaRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index

    init {
        player.onEnded = { if (isPlaying()) next() }
    }

    /** True while the player is playing one of this queue's songs. */
    fun isPlaying() = player.state.value.key?.startsWith(KEY) == true

    fun play(songs: List<MediaItem>, start: Int) {
        if (songs.isEmpty()) return
        _queue.value = songs
        playAt(start.coerceIn(0, songs.lastIndex))
    }

    fun next() { if (_index.value < _queue.value.lastIndex) playAt(_index.value + 1) }
    fun previous() { if (_index.value > 0) playAt(_index.value - 1) }

    private fun playAt(i: Int) {
        val song = _queue.value.getOrNull(i) ?: return
        _index.value = i
        job?.cancel()
        job = scope.launch {
            val d = runCatching { media.details(song.url) }.getOrNull()
            val audio = d?.audioPlaybackUrl ?: d?.options?.filter { it.kind == com.edureminder.quicktube.core.model.FormatKind.AUDIO && !it.extractAudio }?.maxByOrNull { it.bitrate }?.url
            withContext(Dispatchers.Main) {
                if (audio == null) { next(); return@withContext }
                player.play(
                    url = audio, title = song.title, key = KEY + song.url, isVideo = false, subtitle = song.uploader,
                    artwork = d?.item?.thumbnail ?: song.thumbnail, historyKey = "music:" + song.url,
                )
            }
        }
    }

    private companion object {
        const val KEY = "music:"
    }
}
