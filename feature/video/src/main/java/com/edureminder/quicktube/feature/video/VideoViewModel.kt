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

data class ListPageUi(
    val header: MediaItem? = null,
    val banner: String? = null,
    val description: String? = null,
    /** The list being shown: the playlist, or the channel tab that is open. */
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val next: Any? = null,
    /** Fetching every remaining page for "Download all". */
    val loadingAll: Boolean = false,
    /** A channel's sections (Home, its tabs, About); empty for playlists. */
    val sections: List<ChannelSection> = emptyList(),
    val section: ChannelSection? = null,
    /** Home: the first few items of each tab, as shelves. */
    val shelves: List<Pair<ChannelTab, List<MediaItem>>> = emptyList(),
    /** The open tab's first page is loading. */
    val tabLoading: Boolean = false,
)

/** What a channel page shows, like the YouTube app (and Flow): Home, the channel's own tabs, About. */
sealed interface ChannelSection {
    val label: String
    data object Home : ChannelSection { override val label = "Home" }
    data class Tab(val tab: ChannelTab) : ChannelSection { override val label get() = tab.label }
    data object About : ChannelSection { override val label = "About" }
}

/** Shared by channel and playlist pages. */
class ListPageViewModel(private val media: MediaRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ListPageUi())
    val ui: StateFlow<ListPageUi> = _ui
    private var url = ""
    private var isChannel = true
    /** Each channel tab's loaded items and next page, kept while switching tabs. */
    private val tabs = mutableMapOf<ChannelTab, Pair<List<MediaItem>, Any?>>()

    fun load(url: String, channel: Boolean) {
        if (this.url == url && _ui.value.error == null) return
        this.url = url; isChannel = channel
        tabs.clear()
        _ui.value = ListPageUi()
        viewModelScope.launch {
            runCatching {
                if (channel) media.channel(url).let { c: ChannelDetails ->
                    c.tabs.firstOrNull()?.let { tabs[it] = c.videos.items to c.videos.nextPage }
                    val sections = if (c.tabs.isEmpty()) emptyList()
                    else listOf(ChannelSection.Home) + c.tabs.map { ChannelSection.Tab(it) } + ChannelSection.About
                    ListPageUi(
                        c.item, c.banner, c.description, c.videos.items, false, next = c.videos.nextPage,
                        sections = sections, section = sections.firstOrNull(),
                    )
                }
                else media.playlist(url).let { p: PlaylistDetails -> ListPageUi(p.item, null, null, p.videos.items, false, next = p.videos.nextPage) }
            }.onSuccess {
                _ui.value = it
                if (it.section == ChannelSection.Home) loadShelves()
            }
                .onFailure { e -> _ui.value = ListPageUi(loading = false, error = e.message ?: "Error") }
        }
    }

    /** Home: every tab's first page at once, shown as shelves in the channel's tab order. */
    private fun loadShelves() = viewModelScope.launch {
        val order = _ui.value.sections.filterIsInstance<ChannelSection.Tab>().map { it.tab }
        order.map { tab ->
            async { tab to (tabs[tab]?.first ?: runCatching { media.channelTab(url, tab) }.getOrNull()?.also { tabs[tab] = it.items to it.nextPage }?.items.orEmpty()) }
        }.awaitAll().filter { it.second.isNotEmpty() }.let { shelves -> _ui.update { it.copy(shelves = shelves.map { (t, l) -> t to l.take(12) }) } }
    }

    fun select(section: ChannelSection) {
        if (_ui.value.section == section) return
        val tab = (section as? ChannelSection.Tab)?.tab
        val cached = tab?.let { tabs[it] }
        _ui.update { it.copy(section = section, items = cached?.first.orEmpty(), next = cached?.second, tabLoading = tab != null && cached == null) }
        if (tab != null && cached == null) viewModelScope.launch {
            runCatching { media.channelTab(url, tab) }
                .onSuccess { p ->
                    tabs[tab] = p.items to p.nextPage
                    _ui.update { if (it.section == section) it.copy(items = p.items, next = p.nextPage, tabLoading = false) else it }
                }
                .onFailure { _ui.update { if (it.section == section) it.copy(tabLoading = false) else it } }
        }
    }

    /** The open channel tab (null on playlists, Home and About). */
    private val openTab get() = (_ui.value.section as? ChannelSection.Tab)?.tab

    private suspend fun nextPage(next: Any): com.edureminder.quicktube.core.model.Page<MediaItem> {
        val tab = openTab
        return when {
            !isChannel -> media.playlist(url, next).videos
            tab != null -> media.channelTab(url, tab, next)
            else -> media.channel(url, next).videos
        }
    }

    /** Keeps the tab cache in step with what the open tab shows. */
    private fun remember(items: List<MediaItem>, next: Any?) { openTab?.let { tabs[it] = items to next } }

    fun retry() { val u = url; url = ""; load(u, isChannel) }

    fun loadMore() {
        val s = _ui.value
        if (s.loading || s.loadingMore || s.tabLoading || s.next == null) return
        if (isChannel && s.sections.isNotEmpty() && openTab == null) return
        val section = s.section
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { nextPage(s.next) }
                .onSuccess { p ->
                    _ui.update {
                        if (it.section != section) return@update it.copy(loadingMore = false)
                        val items = (it.items + p.items).distinctBy { i -> i.url }
                        remember(items, p.nextPage)
                        it.copy(items = items, next = p.nextPage, loadingMore = false)
                    }
                }
                .onFailure { _ui.update { it.copy(loadingMore = false, next = null) } }
        }
    }

    /** Loads every remaining page of the open list (up to [MAX_ALL] videos), then hands all videos to [onReady]. */
    fun loadAll(onReady: (List<MediaItem>) -> Unit) {
        if (_ui.value.loadingAll) return
        _ui.update { it.copy(loadingAll = true) }
        viewModelScope.launch {
            var next = _ui.value.next
            while (next != null && _ui.value.items.size < MAX_ALL) {
                val page = runCatching { nextPage(next) }.getOrNull() ?: break
                _ui.update { it.copy(items = (it.items + page.items).distinctBy { i -> i.url }, next = page.nextPage).also { s -> remember(s.items, s.next) } }
                next = page.nextPage
            }
            _ui.update { it.copy(loadingAll = false) }
            onReady(_ui.value.items.filter { it.kind == ItemKind.VIDEO })
        }
    }

    private companion object {
        const val MAX_ALL = 1000
    }
}
