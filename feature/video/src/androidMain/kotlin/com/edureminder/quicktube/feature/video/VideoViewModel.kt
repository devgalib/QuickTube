package com.edureminder.quicktube.feature.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.LoadState
import com.edureminder.quicktube.core.common.loadCatching
import com.edureminder.quicktube.core.data.SavedVideosRepository
import com.edureminder.quicktube.core.model.SavedVideo
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.media.PlayItem
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.ChannelDetails
import com.edureminder.quicktube.core.model.ChannelTab
import com.edureminder.quicktube.core.model.Comment
import com.edureminder.quicktube.core.model.FormatKind
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.PlaylistDetails
import com.edureminder.quicktube.core.model.VideoDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async

data class CommentsUi(val items: List<Comment> = emptyList(), val loading: Boolean = false, val next: Any? = null, val error: String? = null)

private const val AUTO = "Auto"

/** The caption language last chosen on any video (this app run); null = captions off. */
private var captionLanguage: String? = null

class VideoViewModel(
    private val media: MediaRepository,
    val player: PlayerController,
    private val savedVideos: SavedVideosRepository,
    private val sponsorBlock: com.edureminder.quicktube.core.extractor.SponsorBlockClient,
    private val settings: com.edureminder.quicktube.core.data.SettingsRepository,
) : ViewModel() {
    /** SponsorBlock parts of this video still to skip, and the note shown when one was skipped. */
    private var skips: List<com.edureminder.quicktube.core.model.SkipSegment> = emptyList()
    private val _skipped = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val skipped: kotlinx.coroutines.flow.SharedFlow<String> = _skipped
    private var skipWatch: kotlinx.coroutines.Job? = null

    /** Jumps over sponsor / intro / … parts while this video plays (each once, so seeking back into one is allowed). */
    private fun watchSkips(videoUrl: String) {
        skipWatch?.cancel()
        skips = emptyList()
        val id = com.edureminder.quicktube.core.common.Urls.youtubeVideoId(videoUrl) ?: return
        skipWatch = viewModelScope.launch {
            if (!settings.current().sponsorBlock) return@launch
            skips = sponsorBlock.segments(id)
            if (skips.isEmpty()) return@launch
            while (true) {
                kotlinx.coroutines.delay(400)
                val s = player.state.value
                if (com.edureminder.quicktube.core.common.Urls.youtubeVideoId(s.current?.historyKey ?: s.current?.id) != id) continue
                val pos = player.currentPositionMs
                val hit = skips.firstOrNull { pos >= it.startMs && pos < it.endMs - 500 } ?: continue
                skips = skips - hit
                player.seekTo(hit.endMs)
                _skipped.tryEmit(com.edureminder.quicktube.core.extractor.SponsorBlock.label(hit.category))
            }
        }
    }
    private val _details = MutableStateFlow<LoadState<VideoDetails>>(LoadState.Loading)
    val details: StateFlow<LoadState<VideoDetails>> = _details
    private val _comments = MutableStateFlow(CommentsUi())
    val comments: StateFlow<CommentsUi> = _comments
    private var url: String? = null
    var backgroundMode = false
        private set
    private val pageUrl = MutableStateFlow<String?>(null)
    /** Whether this video is in Watch later. One flow for the screen's lifetime, following the loaded URL. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val savedFlow: StateFlow<Boolean> = pageUrl.flatMapLatest { u -> if (u == null) flowOf(false) else savedVideos.isSaved(u) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun load(url: String, force: Boolean = false) {
        if (this.url == url && !force && _details.value !is LoadState.Error) return
        this.url = url
        _details.value = LoadState.Loading
        _comments.value = CommentsUi()
        viewModelScope.launch {
            val r = loadCatching { media.details(url, force) }
            _details.value = r
            if (r is LoadState.Success) {
                // The video's own URL, not the link it was opened from (youtu.be/…?si=…), so it matches what was saved.
                pageUrl.value = r.data.item.url
                // Captions stay on from video to video in the language last chosen (the video's own track first).
                _caption.value = captionLanguage?.let { lang ->
                    r.data.subtitles.filter { it.language.substringBefore('-') == lang.substringBefore('-') }.minByOrNull { if (it.autoGenerated) 1 else 0 }
                }
                play(r.data, audioOnly = false)
                watchSkips(r.data.item.url)
                loadComments()
            }
        }
    }

    fun retry() = url?.let { load(it, force = true) }

    /** Captions showing on this video; null = off. */
    private val _caption = MutableStateFlow<com.edureminder.quicktube.core.model.SubtitleTrack?>(null)
    val caption: StateFlow<com.edureminder.quicktube.core.model.SubtitleTrack?> = _caption

    /** Turns captions on in [track]'s language (remembered for the next videos, like YouTube) or off. */
    fun setCaption(track: com.edureminder.quicktube.core.model.SubtitleTrack?) {
        val d = (details.value as? LoadState.Success)?.data ?: return
        _caption.value = track
        captionLanguage = track?.language
        // The stream restarts at the same moment with the captions as part of it (like a quality change).
        val speed = player.state.value.speed
        play(d, audioOnly = false, at = player.currentPositionMs)
        player.setSpeed(speed)
    }

    /** "Auto" or a height like "720p"; the player's Quality menu. */
    private val _quality = MutableStateFlow(AUTO)
    val quality: StateFlow<String> = _quality

    /** Heights the player can switch to (video streams with sound, or with a separate audio stream), best first. */
    fun qualities(): List<String> {
        val d = (details.value as? LoadState.Success)?.data ?: return emptyList()
        return d.options.filter { it.kind == FormatKind.VIDEO && it.height > 0 && it.protocol == com.edureminder.quicktube.core.model.StreamProtocol.HTTP }
            .map { it.height }.distinct().sortedDescending().map { "${it}p" }
    }

    /** Switches the stream and carries on from the same moment. */
    fun setQuality(label: String) {
        val d = (details.value as? LoadState.Success)?.data ?: return
        _quality.value = label
        val speed = player.state.value.speed
        play(d, audioOnly = false, at = player.currentPositionMs)
        player.setSpeed(speed)
    }

    private fun play(d: VideoDetails, audioOnly: Boolean, at: Long = 0) {
        val item = d.item
        val chosen = _quality.value.removeSuffix("p").toIntOrNull()?.let { h ->
            d.options.filter { it.kind == FormatKind.VIDEO && it.height == h && it.protocol == com.edureminder.quicktube.core.model.StreamProtocol.HTTP }
                .maxByOrNull { if (it.audioUrl == null) 1 else 0 }
        }
        // Auto: 720p (video-only + separate audio) for a sharper picture; fall back to the muxed stream.
        val hd = chosen ?: d.options.filter { it.kind == FormatKind.VIDEO && it.audioUrl != null && it.height in 361..720 }.maxByOrNull { it.height }
        val (uri, audio) = when {
            audioOnly && d.audioPlaybackUrl != null -> d.audioPlaybackUrl to null
            item.isLive && d.hlsUrl != null -> d.hlsUrl to null
            hd != null -> hd.url to hd.audioUrl
            d.playbackUrl != null -> d.playbackUrl to null
            d.hlsUrl != null -> d.hlsUrl to null
            else -> return
        }
        player.play(
            listOf(PlayItem(id = "web:" + item.url, uri = uri!!, title = item.title, artist = item.uploader, artwork = item.thumbnail, isVideo = !audioOnly,
                audioUri = audio, startPositionMs = at, subtitleUri = _caption.value?.url?.takeIf { !audioOnly })),
            audioOnly = audioOnly,
        )
    }

    /** Switches to audio-only so playback continues after leaving the screen. */
    fun playInBackground() {
        val d = (details.value as? LoadState.Success)?.data ?: return
        backgroundMode = true
        val st = player.state.value
        if (st.current?.id == "web:" + d.item.url) player.setAudioOnly(true) else play(d, audioOnly = true)
    }

    /** The quality the Auto choice is playing, e.g. "Auto (720p)". */
    fun autoLabel(): String {
        val d = (details.value as? LoadState.Success)?.data ?: return AUTO
        val h = d.options.filter { it.kind == FormatKind.VIDEO && it.audioUrl != null && it.height in 361..720 }.maxOfOrNull { it.height }
        return if (h != null) "$AUTO (${h}p)" else AUTO
    }

    /** One page of a comment's replies ([page] from [Comment.replies] or the last page's next). */
    suspend fun loadReplies(page: Any): com.edureminder.quicktube.core.model.Page<com.edureminder.quicktube.core.model.Comment> =
        media.comments(url ?: error("No video"), page)

    fun loadComments() {
        val u = url ?: return
        val s = _comments.value
        if (s.loading || (s.items.isNotEmpty() && s.next == null)) return
        _comments.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching { media.comments(u, s.next) }
                .onSuccess { p -> _comments.update { it.copy(items = it.items + p.items, next = p.nextPage, loading = false) } }
                .onFailure { e -> _comments.update { it.copy(loading = false, error = e.message, next = null) } }
        }
    }

    /** Watch later: saves the video (or removes it) for the Saved screen. */
    fun toggleSaved() {
        val i = (details.value as? LoadState.Success)?.data?.item ?: return
        viewModelScope.launch {
            if (savedFlow.value) savedVideos.remove(listOf(i.url))
            else savedVideos.save(SavedVideo(i.url, i.title, i.thumbnail, i.uploader, i.durationSec))
        }
    }

    override fun onCleared() {
        val d = (details.value as? LoadState.Success)?.data
        // Not while it plays in the floating window: closing this page shouldn't close that.
        if (!backgroundMode && !com.edureminder.quicktube.core.playerui.PipState.floating.value && d != null &&
            player.state.value.current?.id == "web:" + d.item.url) player.stop()
        super.onCleared()
    }
}
