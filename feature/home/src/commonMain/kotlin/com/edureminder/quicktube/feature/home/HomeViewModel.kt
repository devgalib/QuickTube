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
    private val forYou: com.edureminder.quicktube.core.data.ForYouFeed,
    private val history: com.edureminder.quicktube.core.data.PlaybackHistoryRepository,
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

    /**
     * A link worth offering from the clipboard: a site QuickTube knows (YouTube, TikTok, Instagram, Facebook, X,
     * Dailymotion, Vimeo, Bilibili, SoundCloud…), one its extractors read, or a direct media file.
     */
    fun canDownload(url: String): Boolean {
        val u = com.edureminder.quicktube.core.common.Urls.normalize(url)
        return com.edureminder.quicktube.core.common.Urls.platformOf(u) != com.edureminder.quicktube.core.common.Platform.OTHER ||
            runCatching { media.canExtract(u) }.getOrDefault(false) || com.edureminder.quicktube.core.common.Urls.isMediaFile(u)
    }

    /** Coming back to the app after a long time away refreshes them too (only when due). */
    fun refreshGames() = gamesRepo.refreshIfDue()

    private val _feed = MutableStateFlow(FeedUi())
    val feed: StateFlow<FeedUi> = _feed

    /** The YouTube tab's chip: "For you" or a subject. */
    private val _category = MutableStateFlow(com.edureminder.quicktube.core.data.FeedCategory.FOR_YOU)
    val category: StateFlow<com.edureminder.quicktube.core.data.FeedCategory> = _category

    /** The newest watch-history entry when "For you" was made: watching something since makes it out of date. */
    private var madeFrom: Long? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    fun ensureFeed() {
        val s = _feed.value
        if (s.items.isEmpty() && !s.loading) { loadFeed(false); return }
        // Back on the tab after watching more: a fresh "For you" (quietly, the old one stays until it's ready).
        if (_category.value == com.edureminder.quicktube.core.data.FeedCategory.FOR_YOU && !s.loading && !s.refreshing) viewModelScope.launch {
            val newest = history.all().maxOfOrNull { it.openedAt }
            if (madeFrom != null && newest != null && newest != madeFrom) loadFeed(true)
        }
    }

    fun selectCategory(c: com.edureminder.quicktube.core.data.FeedCategory) {
        if (c == _category.value) return
        _category.value = c
        _feed.value = FeedUi()
        loadFeed(false)
    }

    fun loadFeed(refresh: Boolean) {
        val c = _category.value
        _feed.update { it.copy(loading = !refresh, refreshing = refresh, error = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val newest = runCatching { history.all().maxOfOrNull { it.openedAt } }.getOrNull()
            runCatching { forYou.load(c) }
                .onSuccess { p ->
                    if (c == com.edureminder.quicktube.core.data.FeedCategory.FOR_YOU) madeFrom = newest ?: 0L
                    _feed.value = FeedUi(items = p.items.distinctBy { it.url }, nextPage = p.nextPage)
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _feed.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Error") }
                }
        }
    }

    fun loadMoreFeed() {
        val s = _feed.value
        if (s.loadingMore || s.nextPage == null || s.loading) return
        val c = _category.value
        _feed.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { forYou.load(c, s.nextPage) }
                .onSuccess { p ->
                    if (c != _category.value) return@onSuccess
                    _feed.update { it.copy(items = (it.items + p.items).distinctBy { i -> i.url }, nextPage = p.nextPage, loadingMore = false) }
                }
                .onFailure { _feed.update { it.copy(loadingMore = false, nextPage = null) } }
        }
    }
}
