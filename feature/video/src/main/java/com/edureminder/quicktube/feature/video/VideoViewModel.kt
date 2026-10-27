package com.edureminder.quicktube.feature.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.LoadState
import com.edureminder.quicktube.core.common.loadCatching
import com.edureminder.quicktube.core.data.BrowserRepository
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.media.PlayItem
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.ChannelDetails
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommentsUi(val items: List<Comment> = emptyList(), val loading: Boolean = false, val next: Any? = null, val error: String? = null)

class VideoViewModel(
    private val media: MediaRepository,
    val player: PlayerController,
    private val browser: BrowserRepository,
) : ViewModel() {
    private val _details = MutableStateFlow<LoadState<VideoDetails>>(LoadState.Loading)
    val details: StateFlow<LoadState<VideoDetails>> = _details
    private val _comments = MutableStateFlow(CommentsUi())
    val comments: StateFlow<CommentsUi> = _comments
    private var url: String? = null
    var backgroundMode = false
        private set
    var savedFlow: StateFlow<Boolean> = MutableStateFlow(false)
        private set

    fun load(url: String, force: Boolean = false) {
        if (this.url == url && !force && _details.value !is LoadState.Error) return
        this.url = url
        savedFlow = browser.isBookmarked(url).stateIn(viewModelScope, SharingStarted.Eagerly, false)
        _details.value = LoadState.Loading
        _comments.value = CommentsUi()
        viewModelScope.launch {
            val r = loadCatching { media.details(url, force) }
            _details.value = r
            if (r is LoadState.Success) {
                play(r.data, audioOnly = false)
                loadComments()
            }
        }
    }

    fun retry() = url?.let { load(it, force = true) }

    private fun play(d: VideoDetails, audioOnly: Boolean) {
        val item = d.item
        // Prefer 720p (video-only + separate audio) for a sharper picture; fall back to the muxed stream.
        val hd = d.options.filter { it.kind == FormatKind.VIDEO && it.audioUrl != null && it.height in 361..720 }.maxByOrNull { it.height }
        val (uri, audio) = when {
            audioOnly && d.audioPlaybackUrl != null -> d.audioPlaybackUrl to null
            item.isLive && d.hlsUrl != null -> d.hlsUrl to null
            hd != null -> hd.url to hd.audioUrl
            d.playbackUrl != null -> d.playbackUrl to null
            d.hlsUrl != null -> d.hlsUrl to null
            else -> return
        }
        player.play(
            listOf(PlayItem(id = "web:" + item.url, uri = uri!!, title = item.title, artist = item.uploader, artwork = item.thumbnail, isVideo = !audioOnly, audioUri = audio)),
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

    fun toggleSaved() {
        val d = (details.value as? LoadState.Success)?.data ?: return
        viewModelScope.launch { browser.toggleBookmark(d.item.title, d.item.url) }
    }

    override fun onCleared() {
        val d = (details.value as? LoadState.Success)?.data
        if (!backgroundMode && d != null && player.state.value.current?.id == "web:" + d.item.url) player.stop()
        super.onCleared()
    }
}

data class ListPageUi(
    val header: MediaItem? = null,
    val banner: String? = null,
    val description: String? = null,
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val next: Any? = null,
    /** Fetching every remaining page for "Download all". */
    val loadingAll: Boolean = false,
)

/** Shared by channel and playlist pages. */
class ListPageViewModel(private val media: MediaRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ListPageUi())
    val ui: StateFlow<ListPageUi> = _ui
    private var url = ""
    private var isChannel = true

    fun load(url: String, channel: Boolean) {
        if (this.url == url && _ui.value.error == null) return
        this.url = url; isChannel = channel
        _ui.value = ListPageUi()
        viewModelScope.launch {
            runCatching {
                if (channel) media.channel(url).let { c: ChannelDetails -> ListPageUi(c.item, c.banner, c.description, c.videos.items, false, next = c.videos.nextPage) }
                else media.playlist(url).let { p: PlaylistDetails -> ListPageUi(p.item, null, null, p.videos.items, false, next = p.videos.nextPage) }
            }.onSuccess { _ui.value = it }
                .onFailure { e -> _ui.value = ListPageUi(loading = false, error = e.message ?: "Error") }
        }
    }

    fun retry() { val u = url; url = ""; load(u, isChannel) }

    fun loadMore() {
        val s = _ui.value
        if (s.loading || s.loadingMore || s.next == null) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { if (isChannel) media.channel(url, s.next).videos else media.playlist(url, s.next).videos }
                .onSuccess { p -> _ui.update { it.copy(items = (it.items + p.items).distinctBy { i -> i.url }, next = p.nextPage, loadingMore = false) } }
                .onFailure { _ui.update { it.copy(loadingMore = false, next = null) } }
        }
    }

    /** Loads every remaining page (up to [MAX_ALL] videos), then hands all videos to [onReady]. */
    fun loadAll(onReady: (List<MediaItem>) -> Unit) {
        if (_ui.value.loadingAll) return
        _ui.update { it.copy(loadingAll = true) }
        viewModelScope.launch {
            var next = _ui.value.next
            while (next != null && _ui.value.items.size < MAX_ALL) {
                val page = runCatching { if (isChannel) media.channel(url, next).videos else media.playlist(url, next).videos }.getOrNull() ?: break
                _ui.update { it.copy(items = (it.items + page.items).distinctBy { i -> i.url }, next = page.nextPage) }
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
