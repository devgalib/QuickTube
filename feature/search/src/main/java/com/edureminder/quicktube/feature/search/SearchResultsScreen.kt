package com.edureminder.quicktube.feature.search

import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import com.edureminder.quicktube.core.designsystem.QtChip
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.ui.BrandTile
import com.edureminder.quicktube.core.ui.RowsSkeleton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.SearchFilter
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.FooterLoading
import com.edureminder.quicktube.core.ui.LoadingBox
import com.edureminder.quicktube.core.ui.MediaRow
import com.edureminder.quicktube.core.ui.OnNearEnd
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import java.net.URLEncoder

private val inAppFilters = listOf(
    SearchFilter.ALL to "All", SearchFilter.VIDEOS to "Videos", SearchFilter.PLAYLISTS to "Playlist",
    SearchFilter.CHANNELS to "Channels", SearchFilter.MUSIC to "Music",
)

private fun webSearches(q: String): List<Pair<String, String>> {
    val e = URLEncoder.encode(q, "UTF-8")
    return listOf(
        "Google" to "https://www.google.com/search?q=$e&tbm=vid",
        "TikTok" to "https://www.tiktok.com/search?q=$e",
        "Facebook" to "https://m.facebook.com/search/videos/?q=$e",
        "Instagram" to "https://www.instagram.com/explore/search/keyword/?q=$e",
    )
}

@Composable
fun SearchResultsScreen(
    query: String,
    onBack: () -> Unit,
    onEditQuery: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val vm = koinViewModel<SearchResultsViewModel>()
    val sheet = koinInject<DownloadSheetController>()
    LaunchedEffect(query) { vm.start(query) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        Column(Modifier.statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
                QtIconButton(QtIcons.ArrowBack, "Back", onBack)
                Row(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pressable(scaleDown = 0.98f, onClick = onEditQuery).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(QtIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(query, Modifier.padding(start = 10.dp), maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(inAppFilters) { (f, label) -> QtChip(label, ui.filter == f, { vm.load(f) }) }
                items(webSearches(query)) { (label, url) ->
                    Row(
                        Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .pressable(scaleDown = 0.94f, onClick = { onOpenUrl(url) }).padding(start = 5.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrandTile(label, size = 26.dp)
                        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 7.dp))
                        Icon(QtIcons.OpenInNew, null, Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }) { pad ->
        val mod = Modifier.fillMaxSize().padding(pad)
        when {
            ui.loading -> Box(mod) { RowsSkeleton() }
            ui.error != null -> ErrorBox(ui.error!!, { vm.load(ui.filter) }, mod)
            ui.items.isEmpty() -> EmptyBox("No results", "Try different keywords", mod)
            else -> {
                val state = rememberLazyListState()
                state.OnNearEnd { vm.loadMore() }
                LazyColumn(mod, state = state) {
                    items(ui.items, key = { it.url }) { item ->
                        Box(Modifier.animateItem()) { MediaRow(item, onClick = { onOpen(item) }, onDownload = { sheet.open(item) }) }
                    }
                    if (ui.loadingMore) item { FooterLoading() }
                }
            }
        }
    }
}
