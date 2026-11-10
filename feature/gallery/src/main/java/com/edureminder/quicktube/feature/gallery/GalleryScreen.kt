package com.edureminder.quicktube.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import com.edureminder.quicktube.core.navigation.GALLERY_AUDIOS
import com.edureminder.quicktube.core.navigation.GALLERY_VIDEOS
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.designsystem.LocalBottomBarHeight
import com.edureminder.quicktube.core.designsystem.LocalBottomBarHidden
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.model.GalleryAlbum
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.core.navigation.GALLERY_ALL
import com.edureminder.quicktube.core.ui.EmptyBox
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt

/** Toolbar height under the status bar, from the system gallery (54dp). */
internal val ToolbarHeight = 54.dp

/**
 * Gallery tab: Photos (all media by month) and Albums (folders), laid out like the phone's own gallery app:
 * a large title that scrolls away into a compact toolbar title, a 4-column grid, and a 3-column album grid.
 */
@Composable
fun GalleryScreen(
    onOpenAlbum: (GalleryAlbum) -> Unit,
    onOpenViewer: (GalleryMedia) -> Unit,
    onOpenVideo: (GalleryMedia) -> Unit,
    /** An audio folder from the Audios tab. */
    onOpenAudioFolder: (GalleryAlbum) -> Unit = {},
    /** Pick songs from any folder: a new queue, or more for the playing one ([add]). */
    onPickAudios: (add: Boolean) -> Unit = {},
    /** Make a collage of these photos. */
    onCollage: (List<GalleryMedia>) -> Unit = {},
) {
    val vm = koinViewModel<GalleryViewModel>(key = "gallery-all") { parametersOf(GALLERY_ALL) }
    val controller = org.koin.compose.koinInject<com.edureminder.quicktube.core.media.PlayerController>()
    val playerState by controller.state.collectAsStateWithLifecycle()
    var queueOpen by remember { mutableStateOf(false) }
    val audiosState = androidx.compose.foundation.lazy.rememberLazyListState()
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Folders pinned to the top of Albums and Audios (long-press a folder, or its ⋮ in Audios).
    val settings = org.koin.compose.koinInject<com.edureminder.quicktube.core.data.SettingsRepository>()
    val pinned by remember(settings) { settings.settings.map { it.pinnedFolders }.distinctUntilChanged() }.collectAsStateWithLifecycle(emptyList())
    val albums = remember(ui.albums, pinned) { GallerySections.pinFirst(ui.albums, pinned) }
    val audioFolders = remember(ui.audioFolders, pinned) { GallerySections.pinFirst(ui.audioFolders, pinned) }
    val pinScope = rememberCoroutineScope()
    val togglePin: (GalleryAlbum) -> Unit = { a ->
        pinScope.launch {
            settings.update { s -> s.copy(pinnedFolders = if (a.bucketId in s.pinnedFolders) s.pinnedFolders - a.bucketId else listOf(a.bucketId) + s.pinnedFolders) }
        }
    }
    val (access, requestAccess) = rememberGalleryAccess()
    LaunchedEffect(access) { if (access != GalleryAccess.NONE) vm.load(force = true) }

    // Photos, Albums and Audios are three pages: swipe between them or tap the switch.
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    val photosState = rememberLazyGridState()
    val albumsState = rememberLazyGridState()
    val hideBar = LocalBottomBarHidden.current
    val selecting = selection != null
    DisposableEffect(selecting) {
        hideBar.value = selecting
        onDispose { hideBar.value = false }
    }
    BackHandler(selecting) { selection = null }

    val page = pager.currentPage
    val collapsed by remember {
        derivedStateOf {
            when (pager.currentPage) {
                0 -> photosState.firstVisibleItemIndex > 0 || photosState.firstVisibleItemScrollOffset > 24
                1 -> albumsState.firstVisibleItemIndex > 0 || albumsState.firstVisibleItemScrollOffset > 24
                else -> audiosState.firstVisibleItemIndex > 0 || audiosState.firstVisibleItemScrollOffset > 24
            }
        }
    }
    val title = when (page) { 0 -> "Photos"; 1 -> "Albums"; else -> "Audios" }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GalleryToolbar(
                title = title,
                collapsed = collapsed,
                selectionCount = selection?.size,
                onCloseSelection = { selection = null },
                trailing = {
                    if (access != GalleryAccess.NONE) PhotosAlbumsSwitch(
                        position = { pager.currentPage + pager.currentPageOffsetFraction },
                        selected = pager.targetPage,
                        onSelect = { selection = null; scope.launch { pager.animateScrollToPage(it) } },
                    )
                },
            )
            if (access == GalleryAccess.NONE) {
                GalleryPermissionPrompt(requestAccess, Modifier.padding(bottom = LocalBottomBarHeight.current))
                return@Column
            }
            // The selection bar replaces the floating bar while selecting, and is taller.
            val bottom = LocalBottomBarHeight.current + if (selecting) SelectionBarSpace else 8.dp
            // The album grid has 16dp side margins of its own, so its title and banner drop theirs.
            fun largeTitle(text: String, inset: Boolean): LazyGridScope.() -> Unit = {
                item(key = "title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                    LargeTitle(if (selecting) "" else text, if (inset) 16.dp else 0.dp)
                }
                if (access == GalleryAccess.PARTIAL) item(key = "partial", span = { GridItemSpan(maxLineSpan) }, contentType = "banner") {
                    PartialAccessBanner(requestAccess, margin = if (inset) 16.dp else 0.dp)
                }
            }
            if (ui.loaded && ui.complete && ui.items.isEmpty() && ui.audios.isEmpty()) {
                EmptyBox("No photos or videos", "Pictures and videos on your phone appear here", Modifier.fillMaxWidth().padding(top = 96.dp), QtIcons.Gallery)
                return@Column
            }
            HorizontalPager(
                pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !selecting, key = { it },
            ) { p ->
                if (p == 0) {
                    MediaGrid(
                        ui, photosState, selection, { selection = it },
                        onOpen = { if (it.isVideo) onOpenVideo(it) else onOpenViewer(it) },
                        contentPadding = PaddingValues(bottom = bottom),
                        top = largeTitle("Photos", inset = true),
                    )
                } else if (p == 1) {
                    AlbumsGrid(albums, albumsState, onOpenAlbum, pinned, togglePin, PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom), largeTitle("Albums", inset = false))
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), audiosState, PaddingValues(bottom = bottom)) {
                        item(key = "title") { LargeTitle("Audios", 16.dp) }
                        if (ui.audios.isEmpty() && ui.complete) item(key = "none") {
                            EmptyBox("No audio files", "Songs, recordings and voice notes on your phone appear here", Modifier.fillMaxWidth().padding(top = 60.dp), QtIcons.MusicNote)
                        } else audiosPage(
                            ui.copy(audioFolders = audioFolders), playing = GalleryQueue.isPlayingGallery(controller),
                            onPlayAll = { shuffle ->
                                GalleryQueue.play(controller, if (shuffle) ui.audios.shuffled() else ui.audios)?.let(onOpenVideo)
                            },
                            onNewQueue = { onPickAudios(false) },
                            onShowQueue = { queueOpen = true },
                            onOpenFolder = onOpenAudioFolder,
                            pinned = pinned, onTogglePin = togglePin,
                        )
                    }
                }
            }
        }
        if (queueOpen) AudioQueueSheet(controller, onAdd = { queueOpen = false; onPickAudios(playerState.queue.isNotEmpty()) }, onDismiss = { queueOpen = false })
        selection?.let { sel ->
            SelectionBar(
                selected = remember(sel, ui.items) { ui.items.filter { it.id in sel } },
                allSelected = sel.size == ui.items.size,
                onSelectAll = { selection = if (sel.size == ui.items.size) emptySet() else ui.items.mapTo(HashSet()) { it.id } },
                onDone = { selection = null },
                modifier = Modifier.align(Alignment.BottomCenter),
                onOpenPlayer = onOpenVideo,
                onCollage = onCollage,
            )
        }
    }
}

/** Compact toolbar: the small title fades in once the large one has scrolled away; selection replaces it. */
@Composable
internal fun GalleryToolbar(
    title: String,
    collapsed: Boolean,
    selectionCount: Int?,
    onCloseSelection: () -> Unit,
    onBack: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val titleAlpha by animateFloatAsState(if (collapsed || onBack != null) 1f else 0f, tween(180), label = "title")
    Row(Modifier.fillMaxWidth().height(ToolbarHeight).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selectionCount != null) {
            QtIconButton(QtIcons.Close, "Cancel", onCloseSelection, Modifier.padding(start = 6.dp))
            Text(
                selectedLabel(selectionCount), Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.titleLarge, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        } else {
            if (onBack != null) QtIconButton(QtIcons.ArrowBack, "Back", onBack, Modifier.padding(start = 6.dp))
            Text(
                title, Modifier.weight(1f).padding(start = if (onBack != null) 10.dp else 20.dp).graphicsLayer { alpha = titleAlpha },
                style = MaterialTheme.typography.titleLarge, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            trailing()
        }
    }
}

@Composable
private fun LargeTitle(text: String, start: androidx.compose.ui.unit.Dp) {
    Text(
        text, Modifier.fillMaxWidth().height(42.dp).padding(start = start),
        style = MaterialTheme.typography.headlineLarge, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}

/** Photos | Albums | Audios segmented pill whose accent thumb tracks the pager. */
@Composable
private fun PhotosAlbumsSwitch(position: () -> Float, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ex = LocalQtExtras.current
    val labels = listOf("Photos", "Albums", "Audios")
    val segment = 64.dp
    val density = LocalDensity.current
    Box(Modifier.width(segment * labels.size + 8.dp).height(34.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp)) {
        Box(
            // Follows the pager's live position, so the thumb slides with the finger while swiping.
            Modifier.offset { IntOffset((position().coerceIn(0f, (labels.size - 1).toFloat()) * with(density) { segment.toPx() }).roundToInt(), 0) }
                .width(segment).fillMaxHeight().clip(CircleShape).background(ex.brand),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier.width(segment).fillMaxHeight().clip(CircleShape)
                        .selectable(on, role = Role.Tab, onClick = { onSelect(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, color = if (on) cs.onPrimary else cs.onSurfaceVariant, fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Album grid measured from the system gallery: 3 columns, 16dp margins, 8dp gutters, rounded covers, name and count. */
@Composable
private fun AlbumsGrid(
    albums: List<GalleryAlbum>,
    state: LazyGridState,
    onOpen: (GalleryAlbum) -> Unit,
    pinned: List<Long>,
    onTogglePin: (GalleryAlbum) -> Unit,
    contentPadding: PaddingValues,
    top: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize(), state, contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        top()
        items(albums, key = { it.bucketId }, contentType = { "album" }) { album ->
            AlbumTile(album, album.bucketId in pinned, onOpen, onTogglePin, Modifier.animateItem())
        }
    }
}

@Composable
private fun AlbumTile(album: GalleryAlbum, isPinned: Boolean, onOpen: (GalleryAlbum) -> Unit, onTogglePin: (GalleryAlbum) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // No clip or ripple on the whole tile: a rounded clip cut off the item count under the cover.
    // A long press on a folder pins it to the top (or unpins it).
    Column(
        modifier.padding(top = 12.dp, bottom = 9.dp)
            .combinedClickable(
                interactionSource = null, indication = null, role = androidx.compose.ui.semantics.Role.Button,
                onLongClick = if (album.virtual) null else ({ haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true }),
            ) { onOpen(album) },
    ) {
        PinMenu(menu, isPinned, onDismiss = { menu = false }) { menu = false; onTogglePin(album) }
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh)) {
            val cover = album.cover
            when {
                album.bucketId == GALLERY_AUDIOS -> if (cover != null) AudioArt(cover, Modifier.fillMaxSize(), sizePx = 256, iconSize = 40.dp)
                else Box(Modifier.fillMaxSize().background(LocalQtExtras.current.brand))
                cover != null -> GalleryThumbnail(cover, Modifier.fillMaxSize(), sizePx = 256)
            }
            if (isPinned) PinBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            // Virtual albums carry a small badge so they read as "all photos / videos / audios", not a folder.
            if (album.virtual) Box(
                Modifier.align(Alignment.BottomStart).padding(7.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (album.bucketId) { GALLERY_VIDEOS -> QtIcons.Videocam; GALLERY_AUDIOS -> QtIcons.MusicNote; else -> QtIcons.Image },
                    null, tint = Color.White, modifier = Modifier.size(15.dp),
                )
            }
        }
        Text(
            album.name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, lineHeight = 19.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text("${album.count}", style = MaterialTheme.typography.bodyMedium, fontSize = 12.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant)
    }
}

/** "Pin to top" / "Unpin" for a folder. */
@Composable
internal fun PinMenu(open: Boolean, isPinned: Boolean, onDismiss: () -> Unit, onToggle: () -> Unit) {
    androidx.compose.material3.DropdownMenu(open, onDismiss) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(if (isPinned) "Unpin" else "Pin to top") },
            leadingIcon = { Icon(QtIcons.PushPin, null) },
            onClick = onToggle,
        )
    }
}

/** The small pin on a pinned folder's cover. */
@Composable
internal fun PinBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(24.dp).clip(CircleShape).background(LocalQtExtras.current.brand),
        contentAlignment = Alignment.Center,
    ) { Icon(QtIcons.PushPin, "Pinned", tint = Color.White, modifier = Modifier.size(14.dp)) }
}
