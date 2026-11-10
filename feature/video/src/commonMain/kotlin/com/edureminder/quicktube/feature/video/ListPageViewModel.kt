package com.edureminder.quicktube.feature.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.LoadState
import com.edureminder.quicktube.core.common.loadCatching
import com.edureminder.quicktube.core.data.SavedVideosRepository
import com.edureminder.quicktube.core.model.SavedVideo
import com.edureminder.quicktube.core.data.MediaRepository
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
