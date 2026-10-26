package com.edureminder.quicktube.feature.home

import com.edureminder.quicktube.core.designsystem.LocalBottomBarHeight
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import com.edureminder.quicktube.core.designsystem.IconTile
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.ui.BrandTile
import com.edureminder.quicktube.core.ui.FeedSkeleton
import com.edureminder.quicktube.core.ui.TilesSkeleton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import com.edureminder.quicktube.core.designsystem.shimmer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.designsystem.BrandTitle
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.SectionHeader
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.FooterLoading
import com.edureminder.quicktube.core.ui.LoadingBox
import com.edureminder.quicktube.core.ui.MediaTile
import com.edureminder.quicktube.core.ui.OnNearEnd
import com.edureminder.quicktube.core.ui.VideoFeedCard
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Callbacks the app supplies so this feature never depends on other features. */
class HomeActions(
    val openSearch: (String) -> Unit,
    val openUrl: (String) -> Unit,
    /** A link the user pasted/copied: go straight to downloading it. */
    val downloadUrl: (String) -> Unit,
    val openItem: (MediaItem) -> Unit,
    val openChannel: (String) -> Unit,
    val openHowTo: (String) -> Unit,
    val openSites: () -> Unit,
    val openLibrary: () -> Unit = {},
    val openVault: () -> Unit = {},
)

private val tabs = listOf("Search", "YouTube", "Music", "More")

@Composable
fun HomeScreen(actions: HomeActions, modifier: Modifier = Modifier) {
    val vm = koinViewModel<HomeViewModel>()
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val sheet = koinInject<DownloadSheetController>()

    LaunchedEffect(pager.currentPage) {
        when (pager.currentPage) {
            // The Search page shows previews of both, so it warms them up too.
            0 -> { vm.ensureFeed(); vm.ensureMusic() }
            1 -> vm.ensureFeed()
            2 -> vm.ensureMusic()
        }
    }

    Column(modifier.fillMaxSize()) {
        HomeTabs(pager.currentPage + pager.currentPageOffsetFraction) { i -> scope.launch { pager.animateScrollToPage(i) } }
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
            when (page) {
                0 -> DiscoverTab(vm, actions, onDownload = sheet::open, onSeeTrending = { scope.launch { pager.animateScrollToPage(1) } }, onSeeMusic = { scope.launch { pager.animateScrollToPage(2) } })
                1 -> FeedTab(vm, actions, onDownload = sheet::open)
                2 -> MusicTab(vm, actions, onDownload = sheet::open)
                else -> MoreTab(actions)
            }
        }
    }
}

/** Text tabs with a gradient pill indicator that glides between them while swiping. */
@Composable
private fun HomeTabs(position: Float, onSelect: (Int) -> Unit) {
    val brand = LocalQtExtras.current.brand
    var widths by remember { mutableStateOf(List(tabs.size) { 0 }) }
    var offsets by remember { mutableStateOf(List(tabs.size) { 0 }) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, title ->
                val active = kotlin.math.abs(position - i) < 0.5f
                Box(
                    Modifier.weight(1f).height(44.dp).onGloballyPositioned { c ->
                        widths = widths.toMutableList().also { it[i] = c.size.width }
                        offsets = offsets.toMutableList().also { it[i] = c.positionInParent().x.toInt() }
                    }.clip(RoundedCornerShape(14.dp)).pressable(scaleDown = 0.94f, onClick = { onSelect(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title, style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Indicator interpolates between tab centres as the pager moves.
        val lo = position.toInt().coerceIn(0, tabs.lastIndex)
        val hi = (lo + 1).coerceAtMost(tabs.lastIndex)
        val f = position - lo
        val center = (offsets[lo] + widths[lo] / 2f) * (1 - f) + (offsets[hi] + widths[hi] / 2f) * f
        val pillW = with(density) { 22.dp.toPx() }
        Box(
            Modifier.align(Alignment.BottomStart).offset { IntOffset((center - pillW / 2).toInt(), 0) }
                .size(22.dp, 4.dp).clip(RoundedCornerShape(50)).background(brand),
        )
    }
}

@Composable
private fun DiscoverTab(
    vm: HomeViewModel,
    actions: HomeActions,
    onDownload: (MediaItem) -> Unit,
    onSeeTrending: () -> Unit,
    onSeeMusic: () -> Unit,
) {
    val context = LocalContext.current
    val ex = LocalQtExtras.current
    val feed by vm.feed.collectAsStateWithLifecycle()
    val music by vm.music.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    var clip by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { clip = readClipboardUrl(context) }
    val list = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        // Background glow scrolls away slower than the content (parallax), read in the draw phase only.
        Box(
            Modifier.align(Alignment.TopCenter).size(420.dp)
                .graphicsLayer {
                    val y = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 1000f
                    translationY = -y * 0.35f
                    alpha = (1f - y / 700f).coerceIn(0f, 1f)
                }
                .background(Brush.radialGradient(listOf(ex.brandStart.copy(alpha = if (ex.isDark) 0.22f else 0.15f), Color.Transparent))),
        )
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp + LocalBottomBarHeight.current)) {
            item(key = "hero") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 22.dp)
                        .graphicsLayer {
                            val y = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 0f
                            translationY = y * 0.45f
                            alpha = (1f - y / 420f).coerceIn(0f, 1f)
                            val sc = 1f - (y / 2400f).coerceIn(0f, 0.08f)
                            scaleX = sc; scaleY = sc
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(64.dp).shadow(6.dp, RoundedCornerShape(20.dp), ambientColor = ex.brandStart, spotColor = ex.brandStart)
                            .clip(RoundedCornerShape(20.dp)).background(ex.brand),
                        contentAlignment = Alignment.Center,
                    ) { Icon(QtIcons.Download, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp)) }
                    Text("QuickTube", style = BrandTitle.copy(brush = ex.brand), modifier = Modifier.padding(top = 14.dp))
                    Text(
                        "Search, paste a link, or share from any app",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            item(key = "search") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(60.dp)
                            .shadow(4.dp, RoundedCornerShape(30.dp), ambientColor = ex.brandStart.copy(alpha = 0.4f), spotColor = ex.brandStart.copy(alpha = 0.4f))
                            .clip(RoundedCornerShape(30.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(30.dp))
                            .pressable(scaleDown = 0.98f, onClick = { actions.openSearch("") })
                            .padding(start = 20.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(QtIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Text("Search or paste link", Modifier.weight(1f).padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        Box(Modifier.size(48.dp).clip(CircleShape).background(ex.brand), contentAlignment = Alignment.Center) {
                            Icon(QtIcons.Download, "Download", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                        }
                    }
                    AnimatedVisibility(clip != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        val url = clip.orEmpty()
                        Row(
                            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .pressable(scaleDown = 0.98f, onClick = { actions.downloadUrl(url) })
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BrandTile(url, size = 40.dp, label = Urls.host(url) ?: "L")
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Download copied link", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                            }
                            Icon(QtIcons.Download, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    QuickSites(actions)
                }
            }
            if (feed.items.isNotEmpty() || feed.loading) item(key = "trending") {
                DiscoverHeader("Trending now", QtIcons.TrendingUp, onSeeTrending)
                TrendingCarousel(feed.items.take(12), loading = feed.items.isEmpty(), onOpen = actions.openItem, onDownload = onDownload)
            }
            val songs = music.sections.firstOrNull()?.items.orEmpty()
            if (songs.isNotEmpty()) item(key = "music") {
                DiscoverHeader(music.sections.first().title, QtIcons.LibraryMusic, onSeeMusic)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(songs, key = { it.url }) { item ->
                        MediaTile(item, onClick = { actions.openItem(item) }, onDownload = { if (item.kind == ItemKind.VIDEO) onDownload(item) else actions.openItem(item) })
                    }
                }
            }
            if (recent.isNotEmpty()) item(key = "recent") {
                DiscoverHeader("Your recent downloads", QtIcons.DownloadDone, actions.openLibrary)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.id }) { t ->
                        Column(Modifier.width(150.dp).pressable(scaleDown = 0.96f, onClick = actions.openLibrary)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center) {
                                Icon(if (t.type == com.edureminder.quicktube.core.model.ContentType.AUDIO) QtIcons.MusicNote else QtIcons.PlayCircle, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                val model: Any? = t.thumbnail ?: java.io.File(t.filePath).takeIf { it.exists() && t.type == com.edureminder.quicktube.core.model.ContentType.VIDEO }
                                if (model != null) coil3.compose.AsyncImage(model, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            item(key = "features") {
                DiscoverHeader("Do more with QuickTube", QtIcons.Bolt, null)
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FeatureTile("Share to download", "From YouTube, Facebook, TikTok…", QtIcons.Share, Color(0xFF2FCB7A), Modifier.weight(1f)) { actions.openHowTo("YOUTUBE") }
                        FeatureTile("Browse any site", "Videos are detected automatically", QtIcons.Public, Color(0xFF4C8DFF), Modifier.weight(1f), actions.openSites)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FeatureTile("Private vault", "Hide files behind a PIN", QtIcons.Lock, Color(0xFFFF5C93), Modifier.weight(1f), actions.openVault)
                        FeatureTile("WhatsApp status", "Save statuses you've viewed", QtIcons.Downloading, Color(0xFF25D366), Modifier.weight(1f)) { actions.openHowTo("WHATSAPP") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onMore: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (onMore != null) Row(
            Modifier.clip(RoundedCornerShape(50)).pressable(scaleDown = 0.94f, onClick = onMore).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(QtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

/** Big snapping cards; the thumbnail drifts against the card as it moves (parallax), computed in the draw phase. */
@Composable
private fun TrendingCarousel(items: List<MediaItem>, loading: Boolean, onOpen: (MediaItem) -> Unit, onDownload: (MediaItem) -> Unit) {
    val state = rememberLazyListState()
    val fling = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state)
    LazyRow(state = state, flingBehavior = fling, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (loading) items(3) {
            Box(Modifier.width(290.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(24.dp)).shimmer())
        }
        itemsIndexed(items, key = { _, it -> it.url }) { index, item ->
            Box(
                Modifier.width(290.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pressable(scaleDown = 0.97f, onClick = { onOpen(item) }),
            ) {
                coil3.compose.AsyncImage(
                    item.thumbnail, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                        if (info != null) {
                            val center = info.offset + info.size / 2f
                            val viewport = state.layoutInfo.viewportEndOffset / 2f
                            val d = ((center - viewport) / viewport).coerceIn(-1f, 1f)
                            scaleX = 1.18f; scaleY = 1.18f
                            translationX = -d * size.width * 0.08f
                        }
                    },
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.85f)))))
                if (item.isLive || item.durationSec > 0) Text(
                    if (item.isLive) "LIVE" else com.edureminder.quicktube.core.common.Format.duration(item.durationSec),
                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (item.isLive) Color(0xFFE5243B) else Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Row(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.uploader, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                    if (!item.isLive) Box(
                        Modifier.padding(start = 10.dp).size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))
                            .pressable(scaleDown = 0.85f, onClick = { onDownload(item) }),
                        contentAlignment = Alignment.Center,
                    ) { Icon(QtIcons.Download, "Download", tint = Color.White, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(
    title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color,
    modifier: Modifier, onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.16f), MaterialTheme.colorScheme.surfaceContainerLow)))
            .border(1.dp, tint.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
            .pressable(scaleDown = 0.96f, onClick = onClick).padding(16.dp),
    ) {
        IconTile(icon, tint = tint, size = 42.dp)
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun QuickSites(actions: HomeActions) {
    val sites = listOf("YouTube" to "https://m.youtube.com/", "Instagram" to "https://www.instagram.com/",
        "Facebook" to "https://m.facebook.com/", "TikTok" to "https://www.tiktok.com/")
    Row(Modifier.fillMaxWidth()) {
        sites.forEach { (name, url) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).pressable(scaleDown = 0.9f, onClick = { actions.openUrl(url) }).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandTile(name, size = 52.dp)
                Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).pressable(scaleDown = 0.9f, onClick = actions.openSites).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(QtIcons.Apps, null, tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("Sites", style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun readClipboardUrl(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    Urls.extractUrl(text)
}.getOrNull()

@Composable
private fun FeedTab(vm: HomeViewModel, actions: HomeActions, onDownload: (MediaItem) -> Unit) {
    val ui by vm.feed.collectAsStateWithLifecycle()
    when {
        ui.loading && ui.items.isEmpty() -> FeedSkeleton()
        ui.error != null && ui.items.isEmpty() -> ErrorBox(ui.error!!, { vm.loadFeed(false) })
        else -> PullToRefreshBox(ui.refreshing, onRefresh = { vm.loadFeed(true) }, Modifier.fillMaxSize()) {
            val state = rememberLazyListState()
            state.OnNearEnd { vm.loadMoreFeed() }
            LazyColumn(state = state, contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp + LocalBottomBarHeight.current)) {
                items(ui.items, key = { it.url }) { item ->
                    Box(Modifier.animateItem()) { VideoFeedCard(
                        item,
                        onClick = { actions.openItem(item) },
                        onDownload = { onDownload(item) },
                        onChannel = item.uploaderUrl?.let { u -> { actions.openChannel(u) } },
                    ) }
                }
                if (ui.loadingMore) item { FooterLoading() }
            }
        }
    }
}

@Composable
private fun MusicTab(vm: HomeViewModel, actions: HomeActions, onDownload: (MediaItem) -> Unit) {
    val ui by vm.music.collectAsStateWithLifecycle()
    when {
        ui.loading -> TilesSkeleton()
        ui.error != null && ui.sections.isEmpty() -> ErrorBox(ui.error!!, vm::loadMusic)
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarHeight.current)) {
            items(ui.sections, key = { it.title }) { section ->
                SectionHeader(section.title)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(section.items, key = { it.url }) { item ->
                        MediaTile(item, onClick = { actions.openItem(item) }, onDownload = {
                            if (item.kind == ItemKind.VIDEO) onDownload(item) else actions.openItem(item)
                        })
                    }
                }
            }
        }
    }
}
