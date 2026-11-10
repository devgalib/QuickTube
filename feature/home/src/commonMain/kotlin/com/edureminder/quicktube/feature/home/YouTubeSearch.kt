package com.edureminder.quicktube.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.edureminder.quicktube.core.designsystem.rememberHideOnScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.data.SearchHistoryRepository
import com.edureminder.quicktube.core.designsystem.LocalBottomBarHeight
import com.edureminder.quicktube.core.designsystem.QtChip
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.SearchFilter
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.FeedSkeleton
import com.edureminder.quicktube.core.ui.FooterLoading
import com.edureminder.quicktube.core.ui.MediaRow
import com.edureminder.quicktube.core.ui.OnNearEnd
import com.edureminder.quicktube.core.ui.Thumbnail
import com.edureminder.quicktube.core.ui.VideoFeedCard
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** The YouTube tab's own search: results replace the trending feed in place, like the YouTube app (and Flow). */
data class YtSearchUi(
    /** The search being shown; null shows the trending feed. */
    val query: String? = null,
    val filter: SearchFilter = SearchFilter.ALL,
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val nextPage: Any? = null,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class YouTubeSearchViewModel(
    private val media: MediaRepository,
    private val history: SearchHistoryRepository,
) : ViewModel() {
    /** What is typed in the field (suggestions follow it). */
    val typed = MutableStateFlow("")
    val recent = history.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val suggestions: StateFlow<List<String>> = typed.debounce(200).distinctUntilChanged().mapLatest { q ->
        if (q.isBlank()) emptyList() else runCatching { media.suggestions(q) }.getOrDefault(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(YtSearchUi())
    val ui: StateFlow<YtSearchUi> = _ui
    private var job: Job? = null

    fun search(query: String, filter: SearchFilter = _ui.value.filter) {
        val q = query.trim()
        if (q.isEmpty()) return
        typed.value = q
        viewModelScope.launch { history.add(q) }
        job?.cancel()
        _ui.value = YtSearchUi(query = q, filter = filter, loading = true)
        job = viewModelScope.launch {
            runCatching { media.search(q, filter) }
                .onSuccess { p -> _ui.value = YtSearchUi(q, filter, p.items.distinctBy { it.url }, nextPage = p.nextPage) }
                .onFailure { e -> _ui.value = YtSearchUi(q, filter, error = e.message ?: "Search failed") }
        }
    }

    fun setFilter(filter: SearchFilter) {
        val q = _ui.value.query ?: return
        if (filter != _ui.value.filter) search(q, filter)
    }

    fun loadMore() {
        val s = _ui.value
        val q = s.query ?: return
        if (s.loading || s.loadingMore || s.nextPage == null) return
        _ui.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch {
            runCatching { media.search(q, s.filter, s.nextPage) }
                .onSuccess { p -> _ui.update { it.copy(items = (it.items + p.items).distinctBy { i -> i.url }, nextPage = p.nextPage, loadingMore = false) } }
                .onFailure { _ui.update { it.copy(loadingMore = false, nextPage = null) } }
        }
    }

    /** Back to the trending feed. */
    fun clear() {
        job?.cancel()
        typed.value = ""
        _ui.value = YtSearchUi()
    }

    fun forget(q: String) = viewModelScope.launch { history.remove(q) }
}

private val filters = listOf(
    SearchFilter.ALL to "All", SearchFilter.VIDEOS to "Videos", SearchFilter.SHORTS to "Shorts",
    SearchFilter.CHANNELS to "Channels", SearchFilter.PLAYLISTS to "Playlists",
)

/**
 * The YouTube tab: a search field and chips (the feed's subjects, or the search filters) over [feed] (trending), which
 * search results replace. The header slides away while scrolling down the list and comes back on a small scroll up.
 */
@Composable
internal fun YouTubeTab(
    actions: HomeActions, onDownload: (MediaItem) -> Unit,
    feedChips: @Composable () -> Unit,
    feed: @Composable (top: Dp) -> Unit,
) {
    val vm = koinViewModel<YouTubeSearchViewModel>()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val typed by vm.typed.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val submit = { q: String -> vm.search(q); focus.clearFocus() }
    // Back leaves the search: first the field, then the results.
    com.edureminder.quicktube.core.ui.QtBackHandler(focused || ui.query != null) {
        if (focused) focus.clearFocus() else vm.clear()
    }
    val mode = when { focused -> 0; ui.query != null -> 1; else -> 2 }
    val header = rememberHideOnScroll()
    // A new page (typing, results, back to the feed) starts with the header showing.
    LaunchedEffect(mode, ui.query, ui.filter) { header.show() }
    val density = LocalDensity.current
    val top = with(density) { header.height.toDp() }

    Box(Modifier.fillMaxSize().clipToBounds().then(if (mode == 0) Modifier else Modifier.nestedScroll(header.connection))) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "yt-search",
        ) { m ->
            when (m) {
                0 -> SuggestionList(
                    items = if (typed.isBlank()) recent.take(12) else suggestions,
                    isRecent = typed.isBlank(), top = top,
                    onPick = submit, onFill = { vm.typed.value = it }, onForget = { vm.forget(it) },
                )
                1 -> Results(ui, actions, onDownload, top = top, onMore = vm::loadMore, onRetry = { vm.search(ui.query!!) })
                else -> feed(top)
            }
        }
        Column(
            Modifier.fillMaxWidth().onSizeChanged { header.height = it.height }
                .graphicsLayer { translationY = header.offset }
                .background(MaterialTheme.colorScheme.background),
        ) {
            SearchField(
                text = typed, onText = { vm.typed.value = it }, onSubmit = { submit(typed) },
                onClear = { if (typed.isNotEmpty() && focused) vm.typed.value = "" else { vm.clear(); focus.clearFocus() } },
                showClear = typed.isNotEmpty() || ui.query != null,
                onFocus = { focused = it },
                onHistory = actions.openWatchHistory,
                onPlaylists = { actions.openPlaylists(false) },
                onSubscriptions = actions.openSubscriptions,
            )
            when (mode) {
                1 -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters) { (f, label) -> QtChip(label, ui.filter == f, { vm.setFilter(f) }) }
                }
                2 -> feedChips()
            }
        }
    }
}

@Composable
private fun SearchField(
    text: String, onText: (String) -> Unit, onSubmit: () -> Unit, onClear: () -> Unit, showClear: Boolean, onFocus: (Boolean) -> Unit,
    onHistory: () -> Unit,
    onPlaylists: () -> Unit,
    onSubscriptions: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val requester = remember { FocusRequester() }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(cs.surfaceContainerHigh).pressable(scaleDown = 0.99f, role = null, onClick = { requester.requestFocus() })
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QtIcons.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (text.isEmpty()) Text("Search YouTube", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                text, onText, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { onFocus(it.isFocused) },
            )
        }
        if (showClear) Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).pressable(scaleDown = 0.85f, onClick = onClear),
            contentAlignment = Alignment.Center,
        ) { Icon(QtIcons.Close, "Clear", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        // Channels followed here and their new videos.
        if (!showClear) Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).pressable(scaleDown = 0.85f, onClick = onSubscriptions),
            contentAlignment = Alignment.Center,
        ) { Icon(QtIcons.Subscriptions, "Subscriptions", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        // Video playlists made here, then watch history (what was watched here, and where it was left).
        if (!showClear) Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).pressable(scaleDown = 0.85f, onClick = onPlaylists),
            contentAlignment = Alignment.Center,
        ) { Icon(QtIcons.PlaylistPlay, "Your playlists", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        if (!showClear) Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).pressable(scaleDown = 0.85f, onClick = onHistory),
            contentAlignment = Alignment.Center,
        ) { Icon(QtIcons.History, "Watch history", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun SuggestionList(items: List<String>, isRecent: Boolean, top: Dp, onPick: (String) -> Unit, onFill: (String) -> Unit, onForget: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top, bottom = 24.dp + LocalBottomBarHeight.current)) {
        items(items, key = { it }) { s ->
            Row(
                Modifier.fillMaxWidth().pressable(scaleDown = 0.99f, role = null, onClick = { onPick(s) }).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (isRecent) QtIcons.History else QtIcons.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(s, Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge)
                // Recent searches can be removed; suggestions can be copied into the field to refine them.
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).pressable(scaleDown = 0.85f, onClick = { if (isRecent) onForget(s) else onFill(s) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (isRecent) QtIcons.Close else QtIcons.NorthWest, if (isRecent) "Remove" else "Fill in", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Results(
    ui: YtSearchUi, actions: HomeActions, onDownload: (MediaItem) -> Unit,
    top: Dp, onMore: () -> Unit, onRetry: () -> Unit,
) {
    // The search field and filter chips lie over the top [top] of this.
    Column(Modifier.fillMaxSize()) {
        val bottom = 24.dp + LocalBottomBarHeight.current
        when {
            ui.loading -> Box(Modifier.padding(top = top)) { FeedSkeleton() }
            ui.error != null -> ErrorBox(ui.error, onRetry, Modifier.fillMaxSize().padding(top = top))
            ui.items.isEmpty() -> EmptyBox("No results", "Try different keywords", Modifier.fillMaxSize().padding(top = top))
            ui.filter == SearchFilter.SHORTS -> {
                val grid = rememberLazyGridState()
                LaunchedEffect(grid) {
                    snapshotFlow { grid.layoutInfo.let { it.visibleItemsInfo.lastOrNull()?.index ?: 0 >= it.totalItemsCount - 6 } }
                        .distinctUntilChanged().collect { if (it) onMore() }
                }
                LazyVerticalGrid(
                    GridCells.Fixed(2), state = grid,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 6.dp, bottom = bottom),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(ui.items, key = { it.url }) { ShortTile(it, onClick = { actions.openItem(it) }, onDownload = { onDownload(it) }) }
                    if (ui.loadingMore) item(span = { GridItemSpan(2) }) { FooterLoading() }
                }
            }
            else -> {
                val state = rememberLazyListState()
                state.OnNearEnd { onMore() }
                LazyColumn(state = state, contentPadding = PaddingValues(top = top + 6.dp, bottom = bottom)) {
                    items(ui.items, key = { it.url }) { item ->
                        Box(Modifier.animateItem()) {
                            if (item.kind == ItemKind.VIDEO) VideoFeedCard(
                                item, onClick = { actions.openItem(item) }, onDownload = { onDownload(item) },
                                onChannel = item.uploaderUrl?.let { u -> { actions.openChannel(u) } },
                            ) else MediaRow(item, onClick = { actions.openItem(item) })
                        }
                    }
                    if (ui.loadingMore) item { FooterLoading() }
                }
            }
        }
    }
}

/** A Short as the YouTube app shows them in search: tall picture, title over a dark fade, views below. */
@Composable
private fun ShortTile(item: MediaItem, onClick: () -> Unit, onDownload: () -> Unit) {
    Column(Modifier.pressable(scaleDown = 0.97f, role = null, onClick = onClick)) {
        Thumbnail(item.thumbnail, Modifier.fillMaxWidth().aspectRatio(9f / 16f), corner = 16) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            Text(
                item.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp).clip(RoundedCornerShape(17.dp)).background(Color.Black.copy(alpha = 0.45f))
                    .pressable(scaleDown = 0.85f, onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) { Icon(QtIcons.Download, "Download", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
        if (item.viewCount >= 0) Text(
            "${Format.count(item.viewCount)} views", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
    }
}
