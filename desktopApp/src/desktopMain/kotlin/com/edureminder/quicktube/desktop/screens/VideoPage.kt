package com.edureminder.quicktube.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.common.LoadState
import com.edureminder.quicktube.core.common.loadCatching
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.SectionHeader
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.StreamProtocol
import com.edureminder.quicktube.core.model.VideoDetails
import com.edureminder.quicktube.core.ui.Avatar
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.LoadingBox
import com.edureminder.quicktube.core.ui.MediaRow
import com.edureminder.quicktube.core.ui.QtBackHandler
import com.edureminder.quicktube.core.ui.rememberToaster
import com.edureminder.quicktube.core.ui.show
import com.edureminder.quicktube.desktop.LocalWindowControls
import com.edureminder.quicktube.desktop.player.DesktopPlayer
import com.edureminder.quicktube.desktop.player.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

class VideoPageViewModel(private val url: String, private val media: MediaRepository) : ViewModel() {
    val state = MutableStateFlow<LoadState<VideoDetails>>(LoadState.Loading)

    init { load() }

    fun load(force: Boolean = false) = viewModelScope.launch {
        state.value = LoadState.Loading
        state.value = loadCatching { media.details(url, forceRefresh = force) }
    }
}

/** A stream the player can open: a video up to [maxHeight] with its own or a separate sound. */
internal fun playableOptions(d: VideoDetails): List<DownloadOption> =
    d.options.filter { it.kind == FormatKind.VIDEO && !it.extractAudio && it.height > 0 && (it.protocol == StreamProtocol.HTTP || it.protocol == StreamProtocol.HLS) }
        .groupBy { it.height }.map { (_, same) -> same.firstOrNull { it.ext == "mp4" } ?: same.first() }
        .sortedByDescending { it.height }

/** The video page: the player, its title and actions, the description, and "Up next" beside it (or below on narrow windows). */
@Composable
fun VideoPage(url: String, previewTitle: String?, previewThumbnail: String?, onBack: () -> Unit, onOpen: (MediaItem) -> Unit, onChannel: (String) -> Unit) {
    val vm = koinViewModel<VideoPageViewModel>(key = url) { parametersOf(url) }
    val state by vm.state.collectAsState()
    val player = koinInject<DesktopPlayer>()
    val sheet = koinInject<DownloadSheetController>()
    val window = LocalWindowControls.current
    var fullscreen by remember { mutableStateOf(false) }
    var quality by remember(url) { mutableStateOf<Int?>(null) }
    QtBackHandler(fullscreen) { fullscreen = false; window.setFullscreen(false) }
    DisposableEffect(Unit) { onDispose { if (fullscreen) window.setFullscreen(false) } }

    fun startPlayback(d: VideoDetails, height: Int?, keepPosition: Boolean) {
        val options = playableOptions(d)
        // About 1080p by default: sharp on a computer screen without waiting long for the first frame.
        val o = options.firstOrNull { it.height == height } ?: options.firstOrNull { it.height <= 1080 } ?: options.lastOrNull()
        val start = if (keepPosition) player.state.value.positionMs else 0L
        val streamUrl = o?.url ?: d.hlsUrl ?: d.playbackUrl
        if (streamUrl == null) return
        player.play(
            url = streamUrl, audioUrl = o?.audioUrl, title = d.item.title, subtitle = d.item.uploader,
            artwork = d.item.thumbnail ?: previewThumbnail, key = url, startMs = start,
        )
        quality = o?.height
    }

    val ready = state as? LoadState.Success
    LaunchedEffect(ready) {
        val d = ready?.data ?: return@LaunchedEffect
        if (player.state.value.key != url) startPlayback(d, null, keepPosition = false)
    }

    val playerBox: @Composable (Modifier) -> Unit = { m ->
        PlayerView(
            player, m, fullscreen = fullscreen,
            onToggleFullscreen = { fullscreen = !fullscreen; window.setFullscreen(fullscreen) },
            onRetry = { (state as? LoadState.Success)?.data?.let { startPlayback(it, quality, keepPosition = true) } ?: vm.load(true) },
        )
    }

    if (fullscreen) {
        playerBox(Modifier.fillMaxSize())
        return
    }

    Column(Modifier.fillMaxSize()) {
        QtTopBar(previewTitle ?: (state as? LoadState.Success)?.data?.item?.title ?: "Video", onBack)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoPane = maxWidth >= 980.dp
            val upNextWidth = (maxWidth * 0.32f).coerceIn(320.dp, 440.dp)
            val playerMaxHeight = maxHeight * 0.62f
            when (val s = state) {
                is LoadState.Error -> ErrorBox(s.message, onRetry = { vm.load(true) })
                else -> {
                    val d = (s as? LoadState.Success)?.data
                    if (twoPane) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                                item { playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))) }
                                item { Details(d, url, previewTitle, quality, onChannel) { h -> d?.let { startPlayback(it, h, keepPosition = true) } } }
                            }
                            Column(Modifier.width(upNextWidth).fillMaxHeight()) {
                                UpNext(d, onOpen)
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            item {
                                // Never taller than about two thirds of the window, so the title stays in view.
                                Box(Modifier.fillMaxWidth().heightIn(max = playerMaxHeight), contentAlignment = Alignment.Center) {
                                    playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                                }
                            }
                            item { Details(d, url, previewTitle, quality, onChannel) { h -> d?.let { startPlayback(it, h, keepPosition = true) } } }
                            item { SectionHeader("Up next") }
                            if (d == null) item { LoadingBox(Modifier.fillMaxWidth().padding(32.dp)) }
                            else items(d.related, key = { it.url }) { MediaRow(it, onClick = { onOpen(it) }, onDownload = { sheet.open(it) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpNext(d: VideoDetails?, onOpen: (MediaItem) -> Unit) {
    val sheet = koinInject<DownloadSheetController>()
    Text("Up next", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    if (d == null) { LoadingBox(Modifier.fillMaxWidth().padding(32.dp)); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(d.related, key = { it.url }) { item ->
            Box(Modifier.padding(horizontal = 0.dp)) { MediaRow(item, onClick = { onOpen(item) }, onDownload = { sheet.open(item) }) }
        }
    }
}

@Composable
private fun Details(d: VideoDetails?, url: String, previewTitle: String?, quality: Int?, onChannel: (String) -> Unit, onQuality: (Int) -> Unit) {
    val sheet = koinInject<DownloadSheetController>()
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val toast = rememberToaster()
    var expanded by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp)) {
        Text(d?.item?.title ?: previewTitle.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (d != null) Text(
            listOf(Format.views(d.item.viewCount, d.item.isLive), d.uploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString(" • "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (d != null) Row(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).pressable(scaleDown = 0.98f, role = null, onClick = { d.item.uploaderUrl?.let(onChannel) }).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(d.item.uploaderAvatar, 40)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(d.item.uploader, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.subscriberCount >= 0) Text("${Format.count(d.subscriberCount)} subscribers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else Spacer(Modifier.weight(1f))
        }
        // Actions wrap onto more lines in a narrow window.
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PillButton("Download", { sheet.open(url, d?.item) }, icon = QtIcons.Download)
            if (d != null && playableOptions(d).isNotEmpty()) Box {
                PillButton(quality?.let { "${it}p" } ?: "Quality", { qualityMenu = true }, icon = QtIcons.Hd, secondary = true)
                DropdownMenu(qualityMenu, { qualityMenu = false }) {
                    playableOptions(d).forEach { o ->
                        DropdownMenuItem(text = { Text("${o.height}p" + if (o.height == quality) "  ✓" else "") }, onClick = { qualityMenu = false; onQuality(o.height) })
                    }
                }
            }
            PillButton("Copy link", { clipboard.setText(AnnotatedString(url)); toast.show("Link copied") }, icon = QtIcons.ContentCopy, secondary = true)
            PillButton("Open in browser", { runCatching { uri.openUri(url) } }, icon = QtIcons.OpenInNew, secondary = true)
        }
        if (d != null && d.description.isNotBlank()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pressable(scaleDown = 0.995f, role = null, onClick = { expanded = !expanded }).padding(14.dp),
            ) {
                Text(
                    d.description.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""),
                    style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                )
                Text(if (expanded) "Show less" else "Show more", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.widthIn(min = 1.dp))
    }
}
