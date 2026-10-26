package com.edureminder.quicktube.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.MediaSection
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

data class MusicUi(
    val sections: List<MediaSection> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(
    private val media: MediaRepository,
    library: com.edureminder.quicktube.core.data.LibraryRepository,
) : ViewModel() {
    /** Latest finished downloads for the "Recent downloads" row. */
    val recent = library.downloads
        .map { l -> l.filter { it.status == com.edureminder.quicktube.core.model.DownloadStatus.COMPLETED && !it.inVault }.take(10) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    private val _feed = MutableStateFlow(FeedUi())
    val feed: StateFlow<FeedUi> = _feed
    private val _music = MutableStateFlow(MusicUi())
    val music: StateFlow<MusicUi> = _music

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

    fun ensureMusic() {
        if (_music.value.sections.isEmpty() && !_music.value.loading) loadMusic()
    }

    fun loadMusic() {
        _music.value = MusicUi(loading = true)
        viewModelScope.launch {
            runCatching { media.musicHome() }
                .onSuccess { _music.value = MusicUi(sections = it, error = if (it.isEmpty()) "Nothing to show right now" else null) }
                .onFailure { e -> _music.value = MusicUi(error = e.message ?: "Error") }
        }
    }
}
