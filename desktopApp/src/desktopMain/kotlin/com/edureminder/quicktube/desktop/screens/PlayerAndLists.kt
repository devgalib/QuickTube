package com.edureminder.quicktube.desktop.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.downloadsheet.BatchDownloader
import com.edureminder.quicktube.core.downloadsheet.BatchQuality
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.Page
import com.edureminder.quicktube.core.ui.Avatar
import com.edureminder.quicktube.core.ui.CornerLabel
import com.edureminder.quicktube.core.ui.DownloadIconButton
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.FooterLoading
import com.edureminder.quicktube.core.ui.LoadingBox
import com.edureminder.quicktube.core.ui.QtBackHandler
import com.edureminder.quicktube.core.ui.Thumbnail
import com.edureminder.quicktube.core.ui.WatchedBar
import com.edureminder.quicktube.core.ui.rememberToaster
import com.edureminder.quicktube.core.ui.show
import com.edureminder.quicktube.core.ui.subtitle
import com.edureminder.quicktube.desktop.LocalWindowControls
import com.edureminder.quicktube.desktop.player.DesktopPlayer
import com.edureminder.quicktube.desktop.player.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The player on its own (downloaded files, or what the mini player was playing), filling the window. */
@Composable
fun DesktopPlayerScreen(onBack: () -> Unit) {
    val player = koinInject<DesktopPlayer>()
    val library = koinInject<LibraryRepository>()
    val s by player.state.collectAsState()
    val window = LocalWindowControls.current
    var fullscreen by remember { mutableStateOf(window.isFullscreen()) }
    QtBackHandler(fullscreen) { fullscreen = false; window.setFullscreen(false) }
    // Downloaded files remember where they were left (the Library resumes there).
    DisposableEffect(Unit) {
        onDispose {
            val now = player.state.value
            if (window.isFullscreen()) window.setFullscreen(false)
            val key = now.key ?: return@onDispose
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                library.downloads.first().firstOrNull { it.filePath == key }?.let { library.savePosition(it.id, now.positionMs) }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) QtTopBar(s.title.ifBlank { "Player" }, onBack, subtitle = s.subtitle.takeIf { it.isNotBlank() })
        PlayerView(
            player, Modifier.fillMaxWidth().weight(1f), fullscreen = fullscreen,
            onToggleFullscreen = { fullscreen = !fullscreen; window.setFullscreen(fullscreen) },
        )
    }
}

class ListPageViewModel(private val url: String, private val isChannel: Boolean, private val media: MediaRepository) : ViewModel() {
    class Ui(
        val title: String = "", val avatar: String? = null, val banner: String? = null, val info: String = "",
        val items: List<MediaItem> = emptyList(), val loading: Boolean = true, val error: String? = null, val next: Any? = null,
    )

    val ui = MutableStateFlow(Ui())
    private var loadingMore = false

    init { load() }

    fun load() = viewModelScope.launch {
        ui.value = Ui()
        runCatching {
            if (isChannel) media.channel(url).let { c ->
                Ui(c.item.title, c.item.thumbnail, c.banner, if (c.item.subscriberCount >= 0) "${Format.count(c.item.subscriberCount)} subscribers" else "",
                    c.videos.items, false, null, c.videos.nextPage)
            } else media.playlist(url).let { p ->
                Ui(p.item.title, null, p.item.thumbnail, listOf(p.item.uploader, if (p.item.streamCount >= 0) "${p.item.streamCount} videos" else "").filter { it.isNotBlank() }.joinToString(" • "),
                    p.videos.items, false, null, p.videos.nextPage)
            }
        }.onSuccess { ui.value = it }.onFailure { ui.value = Ui(loading = false, error = it.message ?: "Couldn't load this page") }
    }

    fun more() {
        val now = ui.value
        val next = now.next ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            runCatching<Page<MediaItem>> { if (isChannel) media.channel(url, next).videos else media.playlist(url, next).videos }
                .onSuccess { p -> ui.value = Ui(now.title, now.avatar, now.banner, now.info, (now.items + p.items).distinctBy { it.url }, false, null, p.nextPage) }
            loadingMore = false
        }
    }
}

/** A channel's or playlist's videos as a grid of cards (1 column on narrow windows, up to 5 on wide ones). */
@Composable
fun DesktopListPage(url: String, isChannel: Boolean, onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val vm = koinViewModel<ListPageViewModel>(key = url) { parametersOf(url, isChannel) }
    val ui by vm.ui.collectAsState()
    val sheet = koinInject<DownloadSheetController>()
    val batch = koinInject<BatchDownloader>()
    val toast = rememberToaster()
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 6 } == true } }
    LaunchedEffect(nearEnd, ui.items.size) { if (nearEnd) vm.more() }
    Column(Modifier.fillMaxSize()) {
        QtTopBar(ui.title.ifBlank { if (isChannel) "Channel" else "Playlist" }, onBack, subtitle = ui.info.takeIf { it.isNotBlank() })
        when {
            ui.error != null -> ErrorBox(ui.error!!, onRetry = { vm.load() })
            ui.loading -> LoadingBox()
            else -> LazyVerticalGrid(
                GridCells.Adaptive(280.dp), Modifier.fillMaxSize(), state = grid,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isChannel) Avatar(ui.avatar, 56)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(ui.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (ui.info.isNotBlank()) Text(ui.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PillButton("Download all", {
                            batch.enqueue(ui.items.filter { !it.isLive }, BatchQuality.P720)
                            toast.show("Downloading ${ui.items.size} videos in 720p")
                        }, icon = QtIcons.Download)
                    }
                }
                items(ui.items, key = { it.url }) { item -> VideoCard(item, onClick = { onOpen(item) }, onDownload = { sheet.open(item) }) }
                if (ui.next != null) item(span = { GridItemSpan(maxLineSpan) }) { FooterLoading() }
            }
        }
    }
}

/** A video card for grids: picture on top, title and channel below, a download button. */
@Composable
fun VideoCard(item: MediaItem, onClick: () -> Unit, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).pressable(scaleDown = 0.985f, role = null, onClick = onClick)) {
        Thumbnail(item.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 18) {
            CornerLabel(if (item.isLive) "LIVE" else Format.duration(item.durationSec), live = item.isLive)
            WatchedBar(item.url)
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            if (!item.isLive) DownloadIconButton(onDownload)
        }
        Box(Modifier.height(2.dp))
    }
}
