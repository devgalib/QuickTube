package com.edureminder.quicktube.feature.video

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.edureminder.quicktube.core.model.ChannelTab
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.common.Permissions
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.downloadsheet.BatchDownloader
import com.edureminder.quicktube.core.downloadsheet.BatchQuality
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.ItemKind
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

/** What a quality pick applies to. */
private sealed interface BatchScope {
    data object All : BatchScope
    data class Selected(val items: List<MediaItem>) : BatchScope
}

@Composable
fun ListPageScreen(url: String, isChannel: Boolean, onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val vm = koinViewModel<ListPageViewModel>(key = url)
    LaunchedEffect(url) { vm.load(url, isChannel) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sheet = koinInject<DownloadSheetController>()
    val batch = koinInject<BatchDownloader>()
    val batchState by batch.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var asking by remember { mutableStateOf<BatchScope?>(null) }
    // Only a list (a playlist or a channel tab) can be downloaded or selected from, not Home or About.
    val videos = if (ui.section == null || ui.section is ChannelSection.Tab) ui.items.filter { it.kind == ItemKind.VIDEO } else emptyList()

    fun start(scope: BatchScope, quality: BatchQuality) {
        when (scope) {
            BatchScope.All -> vm.loadAll { all -> batch.enqueue(all, quality) }
            is BatchScope.Selected -> batch.enqueue(scope.items, quality)
        }
        selecting = false
        selected = emptySet()
        Toast.makeText(context, "Adding to downloads…", Toast.LENGTH_SHORT).show()
    }

    val localPlaylists = koinInject<com.edureminder.quicktube.core.data.LocalPlaylistRepository>()
    val subs = koinInject<com.edureminder.quicktube.core.data.SubscriptionRepository>()
    val scopeSave = rememberCoroutineScope()
    /** Plays [list] from [index] on the video page, which then goes on through the rest of it. */
    fun playFrom(list: List<MediaItem>, index: Int) {
        com.edureminder.quicktube.core.navigation.VideoQueue.start(ui.header?.title ?: "Playlist", list)
        onOpen(list[index])
    }

    // Android 10 and older need storage permission before saving into Downloads.
    var pendingQuality by remember { mutableStateOf<Pair<BatchScope, BatchQuality>?>(null) }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val p = pendingQuality ?: return@rememberLauncherForActivityResult
        pendingQuality = null
        if (result.values.all { it }) start(p.first, p.second)
        else Toast.makeText(context, "Storage permission is needed to save downloads", Toast.LENGTH_LONG).show()
    }

    // Tell the user how the batch went once it finishes.
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(batchState.running) {
        if (wasRunning && !batchState.running) {
            val msg = buildString {
                append("${batchState.added} added to downloads")
                if (batchState.failed > 0) append(" · ${batchState.failed} unavailable")
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
        wasRunning = batchState.running
    }

    Scaffold(
        topBar = {
            if (selecting) {
                QtTopBar("${selected.size} selected", onBack = { selecting = false; selected = emptySet() }) {
                    val all = videos.isNotEmpty() && selected.size == videos.size
                    QtIconButton(if (all) QtIcons.CheckBox else QtIcons.SelectAll, if (all) "Clear selection" else "Select all", {
                        selected = if (all) emptySet() else videos.map { it.url }.toSet()
                    })
                }
            } else {
                QtTopBar(ui.header?.title ?: if (isChannel) "Channel" else "Playlist", onBack) {
                    if (videos.isNotEmpty()) QtIconButton(QtIcons.Checklist, "Select videos", { selecting = true })
                }
            }
        },
        bottomBar = {
            Column {
                AnimatedVisibility(batchState.running || ui.loadingAll, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    BatchProgress(if (ui.loadingAll) null else batchState, ui.items.size, aboveNavBar = !selecting)
                }
                AnimatedVisibility(selecting, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                        PillButton(
                            if (selected.isEmpty()) "Select videos to download" else "Download (${selected.size})",
                            { asking = BatchScope.Selected(videos.filter { it.url in selected }) },
                            // Above the system navigation bar (the bar draws behind it, edge to edge).
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                            icon = QtIcons.Download,
                            enabled = selected.isNotEmpty(),
                        )
                    }
                }
            }
        },
    ) { pad ->
        val mod = Modifier.fillMaxSize().padding(pad)
        when {
            ui.loading -> LoadingBox(mod)
            ui.error != null -> ErrorBox(ui.error!!, vm::retry, mod)
            else -> {
                val state = rememberLazyListState()
                val scope = rememberCoroutineScope()
                state.OnNearEnd { vm.loadMore() }
                LazyColumn(mod, state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
                    ui.header?.let { h ->
                        item {
                            Column {
                                if (ui.banner != null) Thumbnail(ui.banner, Modifier.fillMaxWidth().aspectRatio(6f), corner = 0)
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (isChannel) Avatar(h.thumbnail, 64)
                                    else Thumbnail(h.thumbnail, Modifier.fillMaxWidth(.4f).aspectRatio(16f / 9f))
                                    Column(Modifier.padding(start = 16.dp)) {
                                        Text(h.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        // Mixes don't say how many videos they hold: the count loaded so far ("50+") then.
                                        val count = if (isChannel || h.streamCount >= 0) null
                                        else "${videos.size}${if (ui.next != null) "+" else ""} videos"
                                        Text(listOfNotNull(h.subtitle().ifBlank { null }, count).joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        // Follow the channel here (no account): its uploads join Subscriptions and For you.
                                        if (isChannel) {
                                            val subscribed by remember(url) { subs.isSubscribed(url) }.collectAsStateWithLifecycle(false)
                                            PillButton(
                                                if (subscribed) "Subscribed" else "Subscribe",
                                                {
                                                    scopeSave.launch {
                                                        if (subscribed) subs.unsubscribe(url) else subs.subscribe(h.copy(url = url, kind = ItemKind.CHANNEL))
                                                    }
                                                },
                                                Modifier.padding(top = 8.dp), icon = if (subscribed) QtIcons.Check else QtIcons.Notifications, secondary = subscribed,
                                            )
                                        }
                                    }
                                }
                                ui.description?.takeIf { it.isNotBlank() && ui.sections.isEmpty() }?.let {
                                    Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                }
                                // A playlist plays in order (or shuffled) on the video page, and can be kept as a playlist of my own.
                                if (!isChannel && videos.isNotEmpty() && !selecting) Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    PillButton("Play all", { playFrom(videos, 0) }, Modifier.weight(1f), icon = QtIcons.Play)
                                    PillButton("Shuffle", { playFrom(videos.shuffled(), 0) }, Modifier.weight(1f), icon = QtIcons.Shuffle, secondary = true)
                                    QtIconButton(QtIcons.PlaylistAdd, "Save to my playlists", {
                                        scopeSave.launch {
                                            val r = runCatching { localPlaylists.import(url, isMusic = false) }
                                            Toast.makeText(context, if (r.isSuccess) "Saved to your playlists" else "Couldn't save this playlist", Toast.LENGTH_SHORT).show()
                                        }
                                    }, container = MaterialTheme.colorScheme.surfaceContainerHigh)
                                }
                                if (videos.isNotEmpty() && !selecting) Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    PillButton("Download all", { asking = BatchScope.All }, Modifier.weight(1.35f), icon = QtIcons.Download)
                                    PillButton("Select", { selecting = true }, Modifier.weight(1f), icon = QtIcons.Checklist, secondary = true)
                                }
                            }
                        }
                    }
                    if (ui.sections.isNotEmpty()) stickyHeader(key = "tabs") {
                        ChannelTabs(ui.sections, ui.section) { vm.select(it); if (state.firstVisibleItemIndex > 1) scope.launch { state.scrollToItem(1) } }
                    }
                    when (val section = ui.section) {
                        ChannelSection.Home -> channelHome(ui, onOpen = onOpen, onDownload = { sheet.open(it) }, onSeeAll = { vm.select(ChannelSection.Tab(it)) })
                        ChannelSection.About -> item(key = "about") { ChannelAbout(ui) }
                        is ChannelSection.Tab -> if (ui.tabLoading) item(key = "tab-loading") { FooterLoading() }
                        else if (section.tab == ChannelTab.SHORTS && !selecting) {
                            if (ui.items.isEmpty()) item { EmptyBox("No Shorts", modifier = Modifier.fillMaxWidth()) }
                            shortsGrid(ui.items, onOpen = onOpen, onDownload = { sheet.open(it) })
                        }
                        null -> Unit
                    }
                    val showsList = ui.section == null || ui.section is ChannelSection.Tab && !ui.tabLoading &&
                        ((ui.section as ChannelSection.Tab).tab != ChannelTab.SHORTS || selecting)
                    if (showsList && ui.items.isEmpty()) item { EmptyBox("No videos", modifier = Modifier.fillMaxWidth()) }
                    if (showsList) items(ui.items, key = { it.url }) { item ->
                        val selectable = selecting && item.kind == ItemKind.VIDEO
                        val isSelected = item.url in selected
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                MediaRow(
                                    item,
                                    onClick = {
                                        if (selectable) selected = if (isSelected) selected - item.url else selected + item.url
                                        else if (!selecting) {
                                            val i = videos.indexOf(item)
                                            if (!isChannel && i >= 0) playFrom(videos, i) else onOpen(item)
                                        }
                                    },
                                    onDownload = if (selecting) null else ({ sheet.open(item) }),
                                )
                            }
                            if (selectable) Icon(
                                if (isSelected) QtIcons.CheckBox else QtIcons.CheckBoxBlank,
                                if (isSelected) "Selected" else "Not selected",
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp).size(26.dp),
                            )
                        }
                    }
                    if (ui.loadingMore) item { FooterLoading() }
                }
            }
        }
    }

    asking?.let { scope ->
        val count = when (scope) {
            BatchScope.All -> ui.header?.streamCount?.takeIf { it > 0 }?.toInt() ?: videos.size
            is BatchScope.Selected -> scope.items.size
        }
        QualitySheet(count, onDismiss = { asking = null }) { quality ->
            asking = null
            if (!Permissions.granted(context, Permissions.storageWrite)) {
                pendingQuality = scope to quality
                storageLauncher.launch(Permissions.storageWrite)
            } else start(scope, quality)
        }
    }
}

@Composable
private fun BatchProgress(state: BatchDownloader.State?, loaded: Int, aboveNavBar: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().then(if (aboveNavBar) Modifier.navigationBarsPadding() else Modifier).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                if (state == null) "Getting the full list… $loaded videos"
                else "Adding to downloads… ${state.added + state.failed} of ${state.total}",
                style = MaterialTheme.typography.labelLarge,
            )
            if (state == null || state.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            else LinearProgressIndicator(
                { (state.added + state.failed).toFloat() / state.total },
                Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun QualitySheet(count: Int, onDismiss: () -> Unit, onPick: (BatchQuality) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "Download $count video${if (count == 1) "" else "s"} as",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                "Each video gets this quality, or the closest one it has.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
            )
            BatchQuality.entries.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, onClick = { onPick(q) }).padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (q == BatchQuality.AUDIO) QtIcons.MusicNote else QtIcons.Movie, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(q.label, style = MaterialTheme.typography.titleSmall)
                        Text(q.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(QtIcons.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
