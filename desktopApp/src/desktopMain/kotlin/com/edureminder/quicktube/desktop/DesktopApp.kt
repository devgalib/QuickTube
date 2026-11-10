package com.edureminder.quicktube.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.QtTransitions
import com.edureminder.quicktube.core.designsystem.QuickTubeTheme
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetHost
import com.edureminder.quicktube.core.downloadsheet.toRequest
import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.ThemeMode
import com.edureminder.quicktube.core.navigation.BulkJobRoute
import com.edureminder.quicktube.core.navigation.BulkRoute
import com.edureminder.quicktube.core.navigation.ChannelRoute
import com.edureminder.quicktube.core.navigation.DownloadingRoute
import com.edureminder.quicktube.core.navigation.HomeRoute
import com.edureminder.quicktube.core.navigation.HomeTabs
import com.edureminder.quicktube.core.navigation.LibraryRoute
import com.edureminder.quicktube.core.navigation.PlayerRoute
import com.edureminder.quicktube.core.navigation.PlaylistRoute
import com.edureminder.quicktube.core.navigation.SearchResultsRoute
import com.edureminder.quicktube.core.navigation.SearchRoute
import com.edureminder.quicktube.core.navigation.SettingsRoute
import com.edureminder.quicktube.core.navigation.VideoRoute
import com.edureminder.quicktube.core.ui.DesktopBack
import com.edureminder.quicktube.core.ui.DesktopToasts
import com.edureminder.quicktube.core.ui.LocalWidthClass
import com.edureminder.quicktube.core.ui.QtNavItem
import com.edureminder.quicktube.core.ui.QtNavRail
import com.edureminder.quicktube.core.ui.Thumbnail
import com.edureminder.quicktube.core.ui.WidthClass
import com.edureminder.quicktube.core.ui.isWide
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.desktop.player.DesktopPlayer
import com.edureminder.quicktube.desktop.player.LineProgress
import com.edureminder.quicktube.desktop.screens.DesktopPlayerScreen
import com.edureminder.quicktube.desktop.screens.SitesPage
import com.edureminder.quicktube.desktop.player.DesktopMusic
import com.edureminder.quicktube.feature.video.ListPageScreen
import com.edureminder.quicktube.feature.video.LocalPlaylistScreen
import com.edureminder.quicktube.feature.video.LocalPlaylistsScreen
import com.edureminder.quicktube.feature.video.SavedVideosScreen
import com.edureminder.quicktube.feature.video.SubscriptionsScreen
import com.edureminder.quicktube.feature.video.WatchHistoryScreen
import com.edureminder.quicktube.core.navigation.LocalPlaylistRoute
import com.edureminder.quicktube.core.navigation.LocalPlaylistsRoute
import com.edureminder.quicktube.core.navigation.SavedVideosRoute
import com.edureminder.quicktube.core.navigation.SitesRoute
import com.edureminder.quicktube.core.navigation.SubscriptionsRoute
import com.edureminder.quicktube.core.navigation.VideoQueue
import com.edureminder.quicktube.core.navigation.WatchHistoryRoute
import com.edureminder.quicktube.desktop.screens.DesktopSettingsScreen
import com.edureminder.quicktube.desktop.screens.DownloadsScreen
import com.edureminder.quicktube.desktop.screens.VideoPage
import com.edureminder.quicktube.feature.home.BulkJobScreen
import com.edureminder.quicktube.feature.home.BulkScreen
import com.edureminder.quicktube.feature.home.HomeActions
import com.edureminder.quicktube.feature.home.HomeScreen
import com.edureminder.quicktube.feature.search.SearchResultsScreen
import com.edureminder.quicktube.feature.search.SearchScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

/** Things the window (keys) asks of the app: Back (Esc), focus search (Ctrl+L). */
class ShellState {
    internal var nav: NavHostController? = null

    fun back(): Boolean {
        if (DesktopBack.dispatch()) return true
        return nav?.let { if (it.previousBackStackEntry != null) it.popBackStack() else false } ?: false
    }

    fun focusSearch() {
        nav?.navigate(SearchRoute()) { launchSingleTop = true }
    }
}

private val tabs = listOf(
    HomeRoute to QtNavItem("Download", QtIcons.Home, QtIcons.HomeFilled),
    LibraryRoute to QtNavItem("Library", QtIcons.Library, QtIcons.LibraryFilled),
    SettingsRoute to QtNavItem("Settings", QtIcons.Settings, QtIcons.SettingsFilled),
)

@Composable
fun DesktopApp(shell: ShellState) {
    val settingsRepo = koinInject<SettingsRepository>()
    val settings by settingsRepo.settings.collectAsState(AppSettings())
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    QuickTubeTheme(darkTheme = dark, accent = settings.accent, amoled = settings.amoled) {
        val nav = rememberNavController()
        shell.nav = nav
        val sheet = koinInject<DownloadSheetController>()
        val media = koinInject<MediaRepository>()
        val manager = koinInject<com.edureminder.quicktube.core.download.DownloadManager>()
        // For testing and launchers: QT_START=video:<url> | search:<query> | library | settings | youtube | music.
        LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val start = System.getenv("QT_START")?.takeIf { it.isNotBlank() } ?: return@withContext
            // Wait for the NavHost to set up its graph (the first screen is on the back stack).
            nav.currentBackStackEntryFlow.first()
            when {
                start.startsWith("video:") -> nav.navigate(VideoRoute(start.removePrefix("video:")))
                start.startsWith("search:") -> nav.navigate(SearchResultsRoute(start.removePrefix("search:")))
                start.startsWith("channel:") -> nav.navigate(ChannelRoute(start.removePrefix("channel:")))
                start == "library" -> nav.navigate(LibraryRoute)
                start.startsWith("sheet:") -> sheet.open(start.removePrefix("sheet:"))
                // End-to-end download test: the format closest to 720p (or the best audio for "fetch-audio:").
                start.startsWith("fetch:") || start.startsWith("fetch-audio:") -> {
                    val url = start.substringAfter(':')
                    val d = media.details(url)
                    val o = if (start.startsWith("fetch-audio:")) d.options.filter { it.kind == com.edureminder.quicktube.core.model.FormatKind.AUDIO }.maxByOrNull { it.bitrate }
                    else d.options.filter { it.kind == com.edureminder.quicktube.core.model.FormatKind.VIDEO && !it.extractAudio }.minByOrNull { kotlin.math.abs(it.height - 720) }
                    if (o != null) manager.enqueue(o.toRequest(d.item.title, url, d.item.thumbnail, d.item.durationSec))
                    // MP3 test: the best audio made into a 192K MP3, like the sheet's "MP3 (192K)".
                    if (start.startsWith("fetch-audio:") && o != null) manager.enqueue(
                        o.copy(ext = "mp3", mimeType = "audio/mpeg", bitrate = 192).toRequest(d.item.title, url, d.item.thumbnail, d.item.durationSec),
                    )
                    nav.navigate(LibraryRoute)
                }
                start == "settings" -> nav.navigate(SettingsRoute)
                start == "youtube" -> HomeTabs.show(HomeTabs.YOUTUBE)
                start.startsWith("ytsearch:") -> HomeTabs.searchYouTube(start.removePrefix("ytsearch:"))
                start.startsWith("ytshorts:") -> HomeTabs.searchYouTube(start.removePrefix("ytshorts:"), com.edureminder.quicktube.core.model.SearchFilter.SHORTS)
                start == "music" -> HomeTabs.show(2)
                start == "more" -> HomeTabs.show(3)
                start == "history" -> nav.navigate(WatchHistoryRoute)
                start == "subscriptions" -> nav.navigate(SubscriptionsRoute)
                start == "music-playlists" -> nav.navigate(LocalPlaylistsRoute(true))
                start == "sites" -> nav.navigate(SitesRoute)
            }
        } }
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val widthClass = WidthClass.of(maxWidth)
            CompositionLocalProvider(LocalWidthClass provides widthClass) {
                Shell(nav, widthClass)
            }
        }
    }
}

@Composable
private fun Shell(nav: NavHostController, widthClass: WidthClass) {
    val media = koinInject<MediaRepository>()
    val sheet = koinInject<DownloadSheetController>()
    val player = koinInject<DesktopPlayer>()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val toast = com.edureminder.quicktube.core.ui.rememberToaster()
    val music = koinInject<DesktopMusic>()
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val selected = tabs.indexOfFirst { (route, _) -> dest?.hierarchy?.any { it.hasRoute(route::class) } == true }
    val onTab = selected >= 0
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        DesktopToasts.messages.collectLatest { (msg, long) ->
            snackbar.showSnackbar(msg, duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }

    fun selectTab(i: Int) = nav.navigate(tabs[i].first) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun openInBrowser(url: String) {
        runCatching { uri.openUri(url) }
        toast.show("Opened in your browser. Copy a video's link there and paste it here", true)
    }

    // Links: YouTube & co. open in the app; any other site's link goes straight to the download sheet (the phone
    // app's in-app browser is a desktop's own web browser).
    fun openUrl(raw: String) {
        if (raw == com.edureminder.quicktube.core.model.BUILT_IN_YOUTUBE_URL) {
            HomeTabs.show(HomeTabs.YOUTUBE); selectTab(0); return
        }
        val url = Urls.normalize(raw)
        when (media.linkKind(url)) {
            ItemKind.VIDEO -> nav.navigate(VideoRoute(url))
            ItemKind.PLAYLIST -> nav.navigate(PlaylistRoute(url))
            ItemKind.CHANNEL -> nav.navigate(ChannelRoute(url))
            // One video or post (or a media file): straight to the download sheet. A site's home page or feed has no
            // single video, so it opens in the web browser, where the user copies the video's link.
            null -> if (Urls.namesOneMedia(url) || Urls.isMediaFile(url)) sheet.open(url) else openInBrowser(url)
        }
    }
    fun downloadUrl(raw: String) {
        val url = Urls.normalize(raw)
        if (media.linkKind(url) == ItemKind.VIDEO) nav.navigate(VideoRoute(url))
        sheet.open(url)
    }
    fun openItem(item: MediaItem) = nav.navigate(
        when (item.kind) {
            ItemKind.VIDEO -> VideoRoute(item.url, item.title.ifBlank { null }, item.thumbnail)
            ItemKind.CHANNEL -> ChannelRoute(item.url)
            ItemKind.PLAYLIST -> PlaylistRoute(item.url)
        },
    )

    val content: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(
            nav, startDestination = HomeRoute, modifier = modifier,
            enterTransition = QtTransitions.push, exitTransition = QtTransitions.pushExit,
            popEnterTransition = QtTransitions.popEnter, popExitTransition = QtTransitions.popExit,
        ) {
            composable<HomeRoute>(enterTransition = QtTransitions.tabEnter, exitTransition = QtTransitions.tabExit) {
                HomeScreen(
                    HomeActions(
                        openSearch = { nav.navigate(SearchRoute(it)) },
                        openUrl = ::openUrl,
                        downloadUrl = ::downloadUrl,
                        openItem = ::openItem,
                        openChannel = { nav.navigate(ChannelRoute(it)) },
                        openHowTo = { name ->
                            com.edureminder.quicktube.core.common.Platform.entries.firstOrNull { it.title == name }?.home?.takeIf { it.isNotBlank() }
                                ?.let { runCatching { uri.openUri(it) } }
                            toast.show("Copy a video's link in your browser, then paste it here", true)
                        },
                        openSites = { nav.navigate(SitesRoute) },
                        openLibrary = { selectTab(1) },
                        openBulk = { nav.navigate(BulkRoute()) },
                        playMusic = { songs, i -> music.play(songs, i); nav.navigate(PlayerRoute) { launchSingleTop = true } },
                        openWatchHistory = { nav.navigate(WatchHistoryRoute) },
                        openSubscriptions = { nav.navigate(SubscriptionsRoute) },
                        openPlaylists = { isMusic -> nav.navigate(LocalPlaylistsRoute(isMusic)) },
                        openLocalPlaylist = { id -> nav.navigate(LocalPlaylistRoute(id)) },
                    ),
                )
            }
            composable<SearchRoute> { e ->
                SearchScreen(
                    initial = e.toRoute<SearchRoute>().initial,
                    onBack = { nav.popBackStack() },
                    onSearch = { q -> nav.navigate(SearchResultsRoute(q)) { popUpTo<SearchRoute> { inclusive = true } } },
                    onOpenUrl = ::openUrl,
                    onViewSites = { nav.navigate(SitesRoute) },
                    onDownloadUrl = ::downloadUrl,
                )
            }
            composable<SearchResultsRoute> { e ->
                val q = e.toRoute<SearchResultsRoute>().query
                SearchResultsScreen(q, onBack = { nav.popBackStack() }, onEditQuery = { nav.navigate(SearchRoute(q)) }, onOpen = ::openItem, onOpenUrl = ::openUrl)
            }
            composable<VideoRoute> { e ->
                val r = e.toRoute<VideoRoute>()
                VideoPage(r.url, r.title, r.thumbnail, onBack = { nav.popBackStack() }, onOpen = ::openItem, onChannel = { nav.navigate(ChannelRoute(it)) })
            }
            composable<ChannelRoute> { e -> ListPageScreen(e.toRoute<ChannelRoute>().url, true, { nav.popBackStack() }, ::openItem) }
            composable<PlaylistRoute> { e -> ListPageScreen(e.toRoute<PlaylistRoute>().url, false, { nav.popBackStack() }, ::openItem) }
            composable<SubscriptionsRoute> { SubscriptionsScreen({ nav.popBackStack() }, onOpen = ::openItem, onChannel = { nav.navigate(ChannelRoute(it)) }) }
            composable<SavedVideosRoute> { SavedVideosScreen({ nav.popBackStack() }, onOpen = ::openItem) }
            composable<WatchHistoryRoute> { WatchHistoryScreen({ nav.popBackStack() }, onOpen = ::openItem) }
            composable<LocalPlaylistsRoute> { e ->
                LocalPlaylistsScreen(e.toRoute<LocalPlaylistsRoute>().music, { nav.popBackStack() }, onOpen = { id -> nav.navigate(LocalPlaylistRoute(id)) })
            }
            composable<LocalPlaylistRoute> { e ->
                LocalPlaylistScreen(
                    e.toRoute<LocalPlaylistRoute>().id, { nav.popBackStack() },
                    onPlay = { list, items, index ->
                        if (list.isMusic) {
                            music.play(items, index)
                            nav.navigate(PlayerRoute) { launchSingleTop = true }
                        } else {
                            // The video page plays the rest of the playlist one after another.
                            VideoQueue.start(list.name, items)
                            val v = items[index]
                            nav.navigate(VideoRoute(v.url, v.title.ifBlank { null }, v.thumbnail))
                        }
                    },
                    onDownloadAll = { name, links -> nav.navigate(BulkRoute(links.joinToString("\n"), name)) },
                )
            }
            composable<SitesRoute> {
                SitesPage({ nav.popBackStack() }) { site ->
                    if (site.url == com.edureminder.quicktube.core.model.BUILT_IN_YOUTUBE_URL) { HomeTabs.show(HomeTabs.YOUTUBE); selectTab(0) }
                    else openInBrowser(site.url)
                }
            }
            composable<LibraryRoute>(enterTransition = QtTransitions.tabEnter, exitTransition = QtTransitions.tabExit) {
                DownloadsScreen(onPlay = { nav.navigate(PlayerRoute) }, onOpenSource = ::openUrl)
            }
            composable<DownloadingRoute> { DownloadsScreen(onPlay = { nav.navigate(PlayerRoute) }, onOpenSource = ::openUrl, onBack = { nav.popBackStack() }) }
            composable<PlayerRoute> { DesktopPlayerScreen(onBack = { nav.popBackStack() }) }
            composable<SettingsRoute>(enterTransition = QtTransitions.tabEnter, exitTransition = QtTransitions.tabExit) { DesktopSettingsScreen() }
            composable<BulkRoute> { e ->
                val r = e.toRoute<BulkRoute>()
                BulkScreen(r.text, r.name, onBack = { nav.popBackStack() }, onOpenJob = { nav.navigate(BulkJobRoute(it)) { popUpTo<BulkRoute> { inclusive = true } } })
            }
            composable<BulkJobRoute> { e ->
                BulkJobScreen(e.toRoute<BulkJobRoute>().id, onBack = { nav.popBackStack() }, onOpenLink = ::openUrl, onOpenDownloads = { selectTab(1) })
            }
        }
    }

    val playing by player.state.collectAsState()
    val onPlayerPage = dest?.hierarchy?.any { it.hasRoute(PlayerRoute::class) || it.hasRoute(VideoRoute::class) } == true
    val mini: @Composable () -> Unit = {
        if (playing.active && !onPlayerPage) MiniPlayer(player) { nav.navigate(PlayerRoute) { launchSingleTop = true } }
    }

    Box(Modifier.fillMaxSize()) {
        if (widthClass.isWide) {
            Row(Modifier.fillMaxSize()) {
                QtNavRail(
                    tabs.map { it.second }, selected.coerceAtLeast(0), ::selectTab,
                    expanded = widthClass == WidthClass.EXPANDED && onTab,
                    header = { RailLogo(expanded = widthClass == WidthClass.EXPANDED && onTab) },
                )
                Column(Modifier.weight(1f)) {
                    content(Modifier.weight(1f).fillMaxWidth())
                    mini()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxWidth())
                mini()
                if (onTab) BottomBar(selected, ::selectTab)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)) { data ->
            Snackbar(data, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 520.dp))
        }
    }
    DownloadSheetHost(onOpenInBrowser = { runCatching { uri.openUri(it) } }, onOpenExisting = { selectTab(1) })
}

@Composable
private fun RailLogo(expanded: Boolean) {
    val ex = LocalQtExtras.current
    Row(Modifier.padding(start = if (expanded) 8.dp else 0.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource("quicktube.png"), "QuickTube",
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)),
        )
        if (expanded) Text(
            "QuickTube", style = MaterialTheme.typography.titleLarge.copy(brush = ex.brand), fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** Narrow windows: tabs along the bottom, like the phone app. */
@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.surfaceContainerLow).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEachIndexed { i, (_, item) ->
            val on = i == selected
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).pressable(onClick = { onSelect(i) }).padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(if (on) item.selectedIcon else item.icon, null, tint = if (on) cs.primary else cs.onSurfaceVariant)
                Text(item.label, style = MaterialTheme.typography.labelMedium, color = if (on) cs.onSurface else cs.onSurfaceVariant)
            }
        }
    }
}

/** What's playing, docked at the bottom on every other screen; click it to go back to the player. */
@Composable
private fun MiniPlayer(player: DesktopPlayer, onOpen: () -> Unit) {
    val s by player.state.collectAsState()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            LineProgress(if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f, Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth().height(64.dp).pressable(scaleDown = 0.99f, role = null, onClick = onOpen).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(s.artwork, Modifier.size(width = 76.dp, height = 44.dp), corner = 8)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.subtitle.isNotBlank()) Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                QtIconButton(if (s.playing) QtIcons.Pause else QtIcons.Play, if (s.playing) "Pause" else "Play", player::togglePlay)
                QtIconButton(QtIcons.Close, "Close", player::stop, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
