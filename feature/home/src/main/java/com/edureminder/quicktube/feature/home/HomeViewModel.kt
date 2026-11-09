package com.edureminder.quicktube.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FeedUi(
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val nextPage: Any? = null,
)

class HomeViewModel(
    private val media: MediaRepository,
    library: com.edureminder.quicktube.core.data.LibraryRepository,
    private val gamesRepo: com.edureminder.quicktube.core.data.GamesRepository,
    settings: com.edureminder.quicktube.core.data.SettingsRepository,
) : ViewModel() {
    /** Settings → Trending on home (off by default). */
    val showTrending = settings.settings.map { it.showTrending }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /** Our games row (Remote Config, refreshed once per interval, rotated per visit). */
    val games = gamesRepo.games

    init { gamesRepo.refreshIfDue() }

    /**
     * Links the plain download sheet can't handle by itself: playlists and channels (their page lists the videos) and
     * Instagram posts (read in the browser, photos included). These open in the app instead.
     */
    fun opensInApp(url: String): Boolean {
        val u = com.edureminder.quicktube.core.common.Urls.normalize(url)
        val kind = media.linkKind(u)
        return kind == com.edureminder.quicktube.core.model.ItemKind.PLAYLIST || kind == com.edureminder.quicktube.core.model.ItemKind.CHANNEL ||
            com.edureminder.quicktube.core.common.Urls.platformOf(u) == com.edureminder.quicktube.core.common.Platform.INSTAGRAM
    }

    /** Coming back to the app after a long time away refreshes them too (only when due). */
    fun refreshGames() = gamesRepo.refreshIfDue()

    /** Latest finished downloads for the "Recent downloads" row. */
    val recent = library.downloads
        .map { l -> l.filter { it.status == com.edureminder.quicktube.core.model.DownloadStatus.COMPLETED && !it.inVault }.take(10) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    private val _feed = MutableStateFlow(FeedUi())
    val feed: StateFlow<FeedUi> = _feed

    fun ensureFeed() {
        if (_feed.value.items.isEmpty() && !_feed.value.loading) loadFeed(false)
    }

    fun loadFeed(refresh: Boolean) {
        _feed.update { it.copy(loading = !refresh, refreshing = refresh, error = null) }
        viewModelScope.launch {
            runCatching { media.trending() }
                .onSuccess { p -> _feed.value = FeedUi(items = p.items.distinctBy { it.url }, nextPage = p.nextPage) }
                .onFailure { e -> _feed.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Error") } }
        }
    }

    fun loadMoreFeed() {
        val s = _feed.value
        if (s.loadingMore || s.nextPage == null) return
        _feed.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { media.trending(s.nextPage) }
                .onSuccess { p -> _feed.update { it.copy(items = (it.items + p.items).distinctBy { i -> i.url }, nextPage = p.nextPage, loadingMore = false) } }
                .onFailure { _feed.update { it.copy(loadingMore = false, nextPage = null) } }
        }
    }
}
