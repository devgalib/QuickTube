package com.edureminder.quicktube.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.data.SearchHistoryRepository
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.SearchFilter
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val media: MediaRepository,
    private val history: SearchHistoryRepository,
) : ViewModel() {
    val query = MutableStateFlow("")
    val recent = history.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val suggestions: StateFlow<List<String>> = query.debounce(200).distinctUntilChanged().mapLatest { q ->
        if (q.isBlank()) emptyList() else runCatching { media.suggestions(q) }.getOrDefault(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hot = listOf("Top hits", "Trending songs", "Lofi", "Workout music", "Cartoon", "Natok", "Cricket highlights", "Taylor Swift")

    fun remember(q: String) = viewModelScope.launch { history.add(q) }
    fun forget(q: String) = viewModelScope.launch { history.remove(q) }
    fun clearHistory() = viewModelScope.launch { history.clear() }
}

data class ResultsUi(
    val filter: SearchFilter = SearchFilter.ALL,
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val nextPage: Any? = null,
)

class SearchResultsViewModel(private val media: MediaRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ResultsUi())
    val ui: StateFlow<ResultsUi> = _ui
    private var query = ""

    fun start(q: String) {
        if (q == query) return
        query = q
        load(_ui.value.filter)
    }

    fun load(filter: SearchFilter) {
        _ui.value = ResultsUi(filter = filter, loading = true)
        viewModelScope.launch {
            runCatching { media.search(query, filter) }
                .onSuccess { p -> if (_ui.value.filter == filter) _ui.value = ResultsUi(filter, p.items.distinctBy { it.url }, false, nextPage = p.nextPage) }
                .onFailure { e -> if (_ui.value.filter == filter) _ui.value = ResultsUi(filter, loading = false, error = e.message ?: "Search failed") }
        }
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loading || s.loadingMore || s.nextPage == null) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { media.search(query, s.filter, s.nextPage) }
                .onSuccess { p -> _ui.update { it.copy(items = (it.items + p.items).distinctBy { i -> i.url }, nextPage = p.nextPage, loadingMore = false) } }
                .onFailure { _ui.update { it.copy(loadingMore = false, nextPage = null) } }
        }
    }
}
