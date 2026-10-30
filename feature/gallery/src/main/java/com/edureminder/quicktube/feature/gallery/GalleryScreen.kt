package com.edureminder.quicktube.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
) {
    val vm = koinViewModel<GalleryViewModel>(key = "gallery-all") { parametersOf(GALLERY_ALL) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val (access, requestAccess) = rememberGalleryAccess()
    LaunchedEffect(access) { if (access != GalleryAccess.NONE) vm.load(force = true) }

    var page by rememberSaveable { mutableStateOf(0) }
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

    val gridState = if (page == 0) photosState else albumsState
    val collapsed by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 24 }
    }
    val title = if (page == 0) "Photos" else "Albums"

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GalleryToolbar(
                title = title,
                collapsed = collapsed,
                selectionCount = selection?.size,
                onCloseSelection = { selection = null },
                trailing = { if (access != GalleryAccess.NONE) PhotosAlbumsSwitch(page) { page = it; selection = null } },
            )
            if (access == GalleryAccess.NONE) {
                GalleryPermissionPrompt(requestAccess, Modifier.padding(bottom = LocalBottomBarHeight.current))
                return@Column
            }
            val bottom = LocalBottomBarHeight.current + 8.dp
            // The album grid has 16dp side margins of its own, so its title and banner drop theirs.
            fun largeTitle(inset: Boolean): LazyGridScope.() -> Unit = {
                item(key = "title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                    LargeTitle(if (selecting) "" else title, if (inset) 16.dp else 0.dp)
                }
                if (access == GalleryAccess.PARTIAL) item(key = "partial", span = { GridItemSpan(maxLineSpan) }, contentType = "banner") {
                    PartialAccessBanner(requestAccess, margin = if (inset) 16.dp else 0.dp)
                }
            }
            if (ui.loaded && ui.complete && ui.items.isEmpty()) {
                EmptyBox("No photos or videos", "Pictures and videos on your phone appear here", Modifier.fillMaxWidth().padding(top = 96.dp), QtIcons.Gallery)
                return@Column
            }
            AnimatedContent(page, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "page") { p ->
                if (p == 0) {
                    MediaGrid(
                        ui, photosState, selection, { selection = it },
                        onOpen = { if (it.isVideo) onOpenVideo(it) else onOpenViewer(it) },
                        contentPadding = PaddingValues(bottom = bottom),
                        top = largeTitle(inset = true),
                    )
                } else {
                    AlbumsGrid(ui.albums, albumsState, onOpenAlbum, PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom), largeTitle(inset = false))
                }
            }
        }
        selection?.let { sel ->
            SelectionBar(
                selected = remember(sel, ui.items) { ui.items.filter { it.id in sel } },
                allSelected = sel.size == ui.items.size,
                onSelectAll = { selection = if (sel.size == ui.items.size) emptySet() else ui.items.mapTo(HashSet()) { it.id } },
                onDone = { selection = null },
                modifier = Modifier.align(Alignment.BottomCenter),
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

/** Photos | Albums segmented pill with a sliding accent thumb (the floating tab bar's look, in miniature). */
@Composable
private fun PhotosAlbumsSwitch(selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ex = LocalQtExtras.current
    val labels = listOf("Photos", "Albums")
    val position by animateFloatAsState(selected.toFloat(), tween(220), label = "thumb")
    val segment = 68.dp
    val density = LocalDensity.current
    Box(Modifier.width(segment * 2 + 8.dp).height(34.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp)) {
        Box(
            Modifier.offset { IntOffset((position * with(density) { segment.toPx() }).roundToInt(), 0) }
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
    contentPadding: PaddingValues,
    top: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize(), state, contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        top()
        items(albums, key = { it.bucketId }, contentType = { "album" }) { album -> AlbumTile(album, onOpen) }
    }
}

@Composable
private fun AlbumTile(album: GalleryAlbum, onOpen: (GalleryAlbum) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(top = 12.dp, bottom = 9.dp)
            .clip(RoundedCornerShape(10.dp))
            .selectable(false, onClick = { onOpen(album) }),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh)) {
            GalleryThumbnail(album.cover, Modifier.fillMaxSize(), sizePx = 256)
        }
        Text(
            album.name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, lineHeight = 19.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text("${album.count}", style = MaterialTheme.typography.bodyMedium, fontSize = 12.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant)
    }
}
