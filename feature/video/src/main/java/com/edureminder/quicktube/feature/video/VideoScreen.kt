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
import androidx.compose.ui.text.fromHtml
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
import com.edureminder.quicktube.core.playerui.PlayerOption
import com.edureminder.quicktube.core.playerui.QualityMenu
import com.edureminder.quicktube.core.playerui.UpNext
import kotlinx.coroutines.launch
import com.edureminder.quicktube.core.playerui.AutoPipEffect
import com.edureminder.quicktube.core.playerui.LocalPipMode
import com.edureminder.quicktube.core.playerui.VideoPlayerBox
import com.edureminder.quicktube.core.ui.Avatar
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.FooterLoading
import com.edureminder.quicktube.core.ui.LoadingBox
import com.edureminder.quicktube.core.designsystem.shimmer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.edureminder.quicktube.core.ui.MediaRow
import com.edureminder.quicktube.core.ui.OnNearEnd
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VideoScreen(
    url: String,
    /** The tapped card's title and thumbnail, shown while the page loads. */
    previewTitle: String? = null,
    previewThumbnail: String? = null,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onChannel: (String) -> Unit,
    /** The player's picture-in-picture button: the video goes on in the floating window. */
    onPip: () -> Unit = {},
    /** Plays another video in place of this one (Next, autoplay). */
    onPlayNext: (MediaItem) -> Unit = onOpen,
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

    FullscreenEffect(fullscreen && !LocalPipMode.current)
    BackHandler(fullscreen) { fullscreen = false }
    val saved by vm.savedFlow.collectAsStateWithLifecycle()
    // SponsorBlock: a short note each time a sponsor / intro / … part was skipped.
    LaunchedEffect(vm) { vm.skipped.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    if (details is LoadState.Success) AutoPipEffect(vm.player, playback)
    // Back closes the video; only the picture-in-picture button sends it to the floating window.
    fun leave(toPip: Boolean = false) {
        if (toPip) { onPip(); return }
        onBack()
    }
    BackHandler(!fullscreen) { leave() }
    if (LocalPipMode.current) {
        // The whole app is the picture-in-picture window: only the video.
        VideoPlayerBox(vm.player, playback, Modifier.fillMaxSize(), fullscreen = true, showPrevNext = false, overlayControls = false)
        return
    }
    // Rows this page adds to the player's options sheet.
    val settingsRepo = koinInject<com.edureminder.quicktube.core.data.SettingsRepository>()
    val appSettings by settingsRepo.settings.collectAsStateWithLifecycle(com.edureminder.quicktube.core.model.AppSettings())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val currentQuality by vm.quality.collectAsStateWithLifecycle()
    val caption by vm.caption.collectAsStateWithLifecycle()
    val captionMenu = (details as? LoadState.Success)?.data?.subtitles?.takeIf { it.isNotEmpty() }?.let {
        com.edureminder.quicktube.core.playerui.CaptionMenu(it, caption, vm::setCaption)
    }
    // Playing a local playlist: its next video, always autoplayed; otherwise YouTube's first suggestion.
    val queued = remember(url) { com.edureminder.quicktube.core.navigation.VideoQueue.nextAfter(url) }
    val inQueue = remember(url) { com.edureminder.quicktube.core.navigation.VideoQueue.position(url) }
    val related = queued ?: (details as? LoadState.Success)?.data?.related.orEmpty().firstOrNull { it.kind == com.edureminder.quicktube.core.model.ItemKind.VIDEO && !it.isLive }
    val autoplayOn = if (inQueue != null) queued != null else appSettings.autoplayNext
    var savePlaylist by remember { mutableStateOf(false) }
    // YouTube's player extras: Quality, the autoplay switch, Next = the first suggestion (with "Up next" at the end).
    val qualityMenu = vm.qualities().takeIf { it.isNotEmpty() }?.let { q ->
        QualityMenu(if (currentQuality == "Auto") vm.autoLabel() else currentQuality, q) { vm.setQuality(it) }
    }
    val nextVideo: (() -> Unit)? = related?.let { r -> { onPlayNext(r) } }
    val upNext = related?.let { r -> UpNext(r.title, r.thumbnail) { onPlayNext(r) } }
    val onAutoplay: (Boolean) -> Unit = { v -> scope.launch { settingsRepo.update { it.copy(autoplayNext = v) } } }
    val playerOptions = listOf(
        PlayerOption(if (saved) QtIcons.Bookmark else QtIcons.BookmarkBorder, if (saved) "Saved to Watch later" else "Watch later", saved) {
            vm.toggleSaved()
            Toast.makeText(context, if (saved) "Removed from Watch later" else "Saved to Watch later", Toast.LENGTH_SHORT).show()
        },
        PlayerOption(QtIcons.PlaylistAdd, "Save to playlist") { savePlaylist = true },
        PlayerOption(QtIcons.Headphones, "Play in background") {
            vm.playInBackground()
            Toast.makeText(context, "Playing audio in background", Toast.LENGTH_SHORT).show()
        },
    )

    if (fullscreen) {
        VideoPlayerBox(vm.player, playback, Modifier.fillMaxSize(), fullscreen = true, onToggleFullscreen = { fullscreen = false },
            showPrevNext = false, onRetry = vm::retry, options = playerOptions, onPip = { leave(toPip = true) },
            quality = qualityMenu, autoplay = autoplayOn, onAutoplayChange = onAutoplay, onNext = nextVideo, upNext = upNext, captions = captionMenu)
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            if (details is LoadState.Success) {
                VideoPlayerBox(vm.player, playback, Modifier.fillMaxSize(), onToggleFullscreen = { fullscreen = true },
                    onCollapse = { leave() }, showPrevNext = false, onRetry = vm::retry, options = playerOptions, onPip = { leave(toPip = true) },
                    quality = qualityMenu, autoplay = autoplayOn, onAutoplayChange = onAutoplay, onNext = nextVideo, upNext = upNext, captions = captionMenu)
            } else if (details is LoadState.Loading) PlayerLoading(previewThumbnail)
            IconButton(onClick = { leave() }, modifier = Modifier.align(Alignment.TopStart)) {
                if (details !is LoadState.Success) Icon(QtIcons.ArrowBack, "Back", tint = Color.White)
            }
        }
        when (val d = details) {
            is LoadState.Loading -> VideoPageSkeleton(previewTitle, Modifier.fillMaxWidth().weight(1f))
            is LoadState.Error -> ErrorBox(d.message, vm::retry, Modifier.fillMaxWidth().weight(1f))
            is LoadState.Success -> {
                val v = d.data
                val comments by vm.comments.collectAsStateWithLifecycle()
                LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                    if (inQueue != null) item {
                        Row(
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(QtIcons.PlaylistPlay, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                            Text("${com.edureminder.quicktube.core.navigation.VideoQueue.name} · ${inQueue.first} / ${inQueue.second}" +
                                if (queued == null) " · last video" else "",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    item {
                        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
                            Avatar(v.item.uploaderAvatar, 36, Modifier.clickable { v.item.uploaderUrl?.let(onChannel) })
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp).clickable { showAbout = true }) {
                                Text(v.item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(v.item.uploader, Format.views(v.item.viewCount, v.item.isLive), v.uploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString(" • "),
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
                            QtIconButton(if (saved) QtIcons.Bookmark else QtIcons.BookmarkBorder, "Watch later", {
                                vm.toggleSaved()
                                Toast.makeText(context, if (saved) "Removed from Watch later" else "Saved to Watch later", Toast.LENGTH_SHORT).show()
                            }, container = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(start = 12.dp),
                                tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            // Background play stays in the player's options; this place is for local playlists.
                            QtIconButton(QtIcons.PlaylistAdd, "Save to playlist", { savePlaylist = true },
                                container = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(start = 8.dp))
                            QtIconButton(QtIcons.MoreVert, "More", { showAbout = true }, container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    // Chapters: tap one to jump there.
                    if (v.chapters.isNotEmpty()) item {
                        Text("Chapters", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) {
                            items(v.chapters.size) { i ->
                                val c = v.chapters[i]
                                Column(
                                    Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .clickable { vm.player.seekTo(c.startMs) }.padding(10.dp),
                                ) {
                                    Text(Format.duration(c.startMs / 1000), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(c.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
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
                                CommentAvatar(first, 24)
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
                if (savePlaylist) com.edureminder.quicktube.core.playerui.SaveToPlaylistSheet(v.item, isMusic = false) { savePlaylist = false }
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
                // Likes are shown, not given: liking needs a YouTube account.
                if (v.likeCount >= 0) Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(QtIcons.ThumbUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" " + Format.count(v.likeCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                listOf(Format.views(v.item.viewCount, v.item.isLive), v.uploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString("  "),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp),
            )
            Description(v.description, v.item.url, onSeek = { vm.player.seekTo(it) }, onOpen = { u ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).setPackage(context.packageName)) }
                    .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))) } }
            })
        }
    }
}

@Composable
private fun CommentsSheet(vm: VideoViewModel, onDismiss: () -> Unit) {
    val ui by vm.comments.collectAsStateWithLifecycle()
    // One open state only: Back always closes it (with a half-open state, opening replies made Back only collapse it).
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Comments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val state = rememberLazyListState()
        state.OnNearEnd { vm.loadComments() }
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 640.dp)) {
            items(ui.items) { CommentRow(it, vm) }
            if (ui.loading) item { FooterLoading() }
            if (!ui.loading && ui.items.isEmpty()) item { Text("No comments", Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun CommentRow(c: Comment, vm: VideoViewModel? = null, indent: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    var replies by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var next by remember { mutableStateOf<Any?>(c.replies) }
    var loading by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun more() {
        val page = next ?: return
        if (loading || vm == null) return
        loading = true
        scope.launch {
            runCatching { vm.loadReplies(page) }.onSuccess { p -> replies = replies + p.items; next = p.nextPage }.onFailure { next = null }
            loading = false
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 60.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
        CommentAvatar(c, if (indent) 24 else 32)
        Column(Modifier.padding(start = 12.dp)) {
            Text(listOfNotNull(c.author, c.date).joinToString(" • "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(QtIcons.ThumbUp, null, Modifier.padding(end = 4.dp).heightIn(max = 14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (c.likeCount > 0) Format.count(c.likeCount.toLong()) else "", style = MaterialTheme.typography.labelSmall)
            }
            // YouTube's "View 12 replies": opens the thread under the comment.
            if (c.replyCount > 0 && c.replies != null && vm != null) Text(
                if (open) "Hide replies" else "View ${c.replyCount} ${if (c.replyCount == 1) "reply" else "replies"}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable {
                    open = !open
                    if (open && replies.isEmpty()) more()
                }.padding(vertical = 4.dp, horizontal = 2.dp),
            )
        }
    }
    if (open) {
        replies.forEach { r -> CommentRow(r, indent = true) }
        if (loading) FooterLoading()
        else if (next != null && replies.isNotEmpty()) Text("More replies", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 60.dp, bottom = 8.dp).clickable { more() }.padding(4.dp))
    }
}

/** The author's picture, or (when YouTube sends none) their first letter on a colour of their own, as YouTube does. */
@Composable
private fun CommentAvatar(c: Comment, size: Int) {
    if (c.authorAvatar != null) { Avatar(c.authorAvatar, size); return }
    val name = c.author.removePrefix("@")
    val colors = listOf(0xFFE57373, 0xFF64B5F6, 0xFF81C784, 0xFFFFB74D, 0xFFBA68C8, 0xFF4DB6AC, 0xFFF06292, 0xFF7986CB)
    Box(Modifier.size(size.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(colors[Math.floorMod(name.hashCode(), colors.size)])),
        contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "?", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** The player area while the page loads: the video's thumbnail (when known) under the loading ring. */
@Composable
private fun PlayerLoading(thumbnail: String?) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (thumbnail != null) coil3.compose.AsyncImage(
            thumbnail, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(), alpha = 0.55f,
        )
        com.edureminder.quicktube.core.playerui.PlayerSpinner()
    }
}

/**
 * The page's shape while it loads: title (the real one when the tapped card had it), channel, the action buttons,
 * comments and the "Up next" list, all as soft shimmering blocks.
 */
@Composable
private fun VideoPageSkeleton(title: String?, modifier: Modifier) {
    val bone = MaterialTheme.colorScheme.surfaceContainerHigh
    fun Modifier.bone(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)) = clip(shape).background(bone).shimmer()
    Column(modifier.verticalScroll(rememberScrollState(), enabled = false)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).bone(androidx.compose.foundation.shape.CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else {
                    Box(Modifier.fillMaxWidth().height(18.dp).bone())
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.7f).height(18.dp).bone())
                }
                Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.55f).height(12.dp).bone())
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(48.dp).bone(RoundedCornerShape(50)))
            repeat(3) { Box(Modifier.padding(start = 10.dp).size(44.dp).bone(androidx.compose.foundation.shape.CircleShape)) }
        }
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(76.dp).bone(RoundedCornerShape(14.dp)))
        Box(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp).width(80.dp).height(16.dp).bone())
        repeat(5) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(160.dp).aspectRatio(16f / 9f).bone(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Box(Modifier.fillMaxWidth().height(14.dp).bone())
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.8f).height(14.dp).bone())
                    Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.5f).height(11.dp).bone())
                }
            }
        }
    }
}

/**
 * The description as YouTube shows it: HTML decoded (no "&quot;" or "&amp;"), line breaks kept, links tappable. A link
 * to a moment of this same video (a chapter time like 2:15) seeks the player instead of opening a page.
 */
@Composable
private fun Description(html: String, videoUrl: String, onSeek: (Long) -> Unit, onOpen: (String) -> Unit) {
    val id = com.edureminder.quicktube.core.common.Urls.youtubeVideoId(videoUrl)
    val link = MaterialTheme.colorScheme.primary
    val text = remember(html) {
        androidx.compose.ui.text.AnnotatedString.fromHtml(
            html.replace("\n", "<br>"),
            linkStyles = androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = link)),
            linkInteractionListener = androidx.compose.ui.text.LinkInteractionListener { l ->
                val u = (l as? androidx.compose.ui.text.LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
                val t = Regex("""[?&#]t=(\d+)""").find(u)?.groupValues?.get(1)?.toLongOrNull()
                if (t != null && id != null && com.edureminder.quicktube.core.common.Urls.youtubeVideoId(u) == id) onSeek(t * 1000) else onOpen(u)
            },
        )
    }
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
}
