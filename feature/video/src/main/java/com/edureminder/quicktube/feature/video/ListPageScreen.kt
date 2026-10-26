package com.edureminder.quicktube.feature.video

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.ui.Avatar
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.FooterLoading
import com.edureminder.quicktube.core.ui.LoadingBox
import com.edureminder.quicktube.core.ui.MediaRow
import com.edureminder.quicktube.core.ui.OnNearEnd
import com.edureminder.quicktube.core.ui.Thumbnail
import com.edureminder.quicktube.core.ui.subtitle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ListPageScreen(url: String, isChannel: Boolean, onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val vm = koinViewModel<ListPageViewModel>(key = url)
    LaunchedEffect(url) { vm.load(url, isChannel) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sheet = koinInject<DownloadSheetController>()
    Scaffold(topBar = { QtTopBar(ui.header?.title ?: if (isChannel) "Channel" else "Playlist", onBack) }) { pad ->
        val mod = Modifier.fillMaxSize().padding(pad)
        when {
            ui.loading -> LoadingBox(mod)
            ui.error != null -> ErrorBox(ui.error!!, vm::retry, mod)
            else -> {
                val state = rememberLazyListState()
                state.OnNearEnd { vm.loadMore() }
                LazyColumn(mod, state = state) {
                    ui.header?.let { h ->
                        item {
                            Column {
                                if (ui.banner != null) Thumbnail(ui.banner, Modifier.fillMaxWidth().aspectRatio(6f), corner = 0)
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (isChannel) Avatar(h.thumbnail, 64)
                                    else Thumbnail(h.thumbnail, Modifier.fillMaxWidth(.4f).aspectRatio(16f / 9f))
                                    Column(Modifier.padding(start = 16.dp)) {
                                        Text(h.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(h.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                ui.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                    if (ui.items.isEmpty()) item { EmptyBox("No videos", modifier = Modifier.fillMaxWidth()) }
                    items(ui.items, key = { it.url }) { item -> MediaRow(item, onClick = { onOpen(item) }, onDownload = { sheet.open(item) }) }
                    if (ui.loadingMore) item { FooterLoading() }
                }
            }
        }
    }
}
