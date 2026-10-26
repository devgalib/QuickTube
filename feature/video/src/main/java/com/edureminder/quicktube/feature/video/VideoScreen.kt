package com.edureminder.quicktube.feature.video

import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.common.LoadState
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.Comment
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.VideoDetails
import com.edureminder.quicktube.core.playerui.FullscreenEffect
import com.edureminder.quicktube.core.playerui.VideoPlayerBox
import com.edureminder.quicktube.core.ui.Avatar
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.FooterLoading
import com.edureminder.quicktube.core.ui.LoadingBox
import com.edureminder.quicktube.core.ui.MediaRow
import com.edureminder.quicktube.core.ui.OnNearEnd
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VideoScreen(
    url: String,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onChannel: (String) -> Unit,
) {
    val vm = koinViewModel<VideoViewModel>(key = url)
    LaunchedEffect(url) { vm.load(url) }
    val details by vm.details.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    val sheet = koinInject<DownloadSheetController>()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    val context = LocalContext.current

    FullscreenEffect(fullscreen)
    BackHandler(fullscreen) { fullscreen = false }

    if (fullscreen) {
        VideoPlayerBox(vm.player, playback, Modifier.fillMaxSize(), fullscreen = true, onToggleFullscreen = { fullscreen = false },
            showPrevNext = false, onRetry = vm::retry)
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            if (details is LoadState.Success) {
                VideoPlayerBox(vm.player, playback, Modifier.fillMaxSize(), onToggleFullscreen = { fullscreen = true },
                    onCollapse = onBack, showPrevNext = false, onRetry = vm::retry)
            } else if (details is LoadState.Loading) LoadingBox()
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
                if (details !is LoadState.Success) Icon(QtIcons.ArrowBack, "Back", tint = Color.White)
            }
        }
        when (val d = details) {
            is LoadState.Loading -> LoadingBox(Modifier.fillMaxWidth().weight(1f))
            is LoadState.Error -> ErrorBox(d.message, vm::retry, Modifier.fillMaxWidth().weight(1f))
            is LoadState.Success -> {
                val v = d.data
                val comments by vm.comments.collectAsStateWithLifecycle()
                LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                    item {
                        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
                            Avatar(v.item.uploaderAvatar, 36, Modifier.clickable { v.item.uploaderUrl?.let(onChannel) })
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp).clickable { showAbout = true }) {
                                Text(v.item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(v.item.uploader, Format.views(v.item.viewCount), v.uploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    item {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PillButton(
                                "Download", { sheet.open(v.item.url, v.item) }, Modifier.weight(1f), icon = QtIcons.Download,
                                enabled = !v.item.isLive,
                            )
                            QtIconButton(QtIcons.Headphones, "Play in background", {
                                vm.playInBackground()
                                Toast.makeText(context, "Playing audio in background", Toast.LENGTH_SHORT).show()
                            }, container = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(start = 12.dp))
                            QtIconButton(QtIcons.MoreVert, "More", { showAbout = true }, container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { showComments = true }.padding(14.dp),
                        ) {
                            Text("Comments", style = MaterialTheme.typography.titleSmall)
                            val first = comments.items.firstOrNull()
                            if (first != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(first.authorAvatar, 24)
                                Text(first.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 10.dp))
                            } else Text(
                                if (comments.loading) "Loading…" else comments.error?.let { "Comments unavailable" } ?: "No comments",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    item { Text("Up next", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp)) }
                    items(v.related, key = { it.url }) { r ->
                        MediaRow(r, onClick = { onOpen(r) }, onDownload = { sheet.open(r) })
                    }
                }
                if (showAbout) AboutSheet(v, vm, onDismiss = { showAbout = false }, onChannel = onChannel)
                if (showComments) CommentsSheet(vm, onDismiss = { showComments = false })
            }
        }
    }
}

@Composable
private fun AboutSheet(v: VideoDetails, vm: VideoViewModel, onDismiss: () -> Unit, onChannel: (String) -> Unit) {
    val context = LocalContext.current
    val saved by vm.savedFlow.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("About", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(v.item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().clickable { v.item.uploaderUrl?.let(onChannel); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(v.item.uploaderAvatar, 40)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(v.item.uploader, fontWeight = FontWeight.SemiBold)
                    if (v.subscriberCount >= 0) Text("${Format.count(v.subscriberCount)} subscribers", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(vertical = 8.dp)) {
                TextButton(onClick = {}) {
                    Icon(QtIcons.ThumbUp, null); Text(" " + if (v.likeCount >= 0) Format.count(v.likeCount) else "Like")
                }
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, v.item.url), "Share"))
                }) { Icon(QtIcons.Share, null); Text(" Share") }
                TextButton(onClick = vm::toggleSaved) {
                    Icon(if (saved) QtIcons.Bookmark else QtIcons.BookmarkBorder, null)
                    Text(if (saved) " Saved" else " Watch later")
                }
            }
            HorizontalDivider()
            Text(
                listOf(Format.views(v.item.viewCount), v.uploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString("  "),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp),
            )
            Text(v.description.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun CommentsSheet(vm: VideoViewModel, onDismiss: () -> Unit) {
    val ui by vm.comments.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Comments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val state = rememberLazyListState()
        state.OnNearEnd { vm.loadComments() }
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 640.dp)) {
            items(ui.items) { CommentRow(it) }
            if (ui.loading) item { FooterLoading() }
            if (!ui.loading && ui.items.isEmpty()) item { Text("No comments", Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun CommentRow(c: Comment) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Avatar(c.authorAvatar, 32)
        Column(Modifier.padding(start = 12.dp)) {
            Text(listOfNotNull(c.author, c.date).joinToString(" • "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(QtIcons.ThumbUp, null, Modifier.padding(end = 4.dp).heightIn(max = 14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (c.likeCount > 0) Format.count(c.likeCount.toLong()) else "", style = MaterialTheme.typography.labelSmall)
                if (c.replyCount > 0) Text("   ${c.replyCount} replies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
