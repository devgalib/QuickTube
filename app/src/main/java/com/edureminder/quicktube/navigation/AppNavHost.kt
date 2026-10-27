package com.edureminder.quicktube.navigation

import com.edureminder.quicktube.core.designsystem.QtTransitions
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import androidx.compose.animation.fadeIn
import com.edureminder.quicktube.core.designsystem.LocalBottomBarHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetHost
import com.edureminder.quicktube.core.model.DownloadStatus
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.navigation.*
import com.edureminder.quicktube.feature.browser.BookmarksScreen
import com.edureminder.quicktube.feature.browser.BrowserScreen
import com.edureminder.quicktube.feature.browser.HistoryScreen
import com.edureminder.quicktube.feature.browser.SitesScreen
import com.edureminder.quicktube.feature.home.HomeActions
import com.edureminder.quicktube.feature.settings.AboutScreen
import com.edureminder.quicktube.feature.settings.DownloadSettingsScreen
import com.edureminder.quicktube.feature.settings.NotificationSettingsScreen
import com.edureminder.quicktube.feature.settings.RegionScreen
import com.edureminder.quicktube.feature.settings.SettingsActions
import com.edureminder.quicktube.feature.settings.SettingsScreen
import com.edureminder.quicktube.feature.settings.ThemeSettingsScreen
import com.edureminder.quicktube.feature.tools.AppManagerScreen
import com.edureminder.quicktube.feature.tools.DeletedHistoryScreen
import com.edureminder.quicktube.feature.tools.LargeFilesScreen
import com.edureminder.quicktube.feature.tools.RecycleBinScreen
import com.edureminder.quicktube.feature.tools.StatusSaverScreen
import com.edureminder.quicktube.feature.tools.StorageCleanScreen
import com.edureminder.quicktube.feature.tools.VaultScreen
import com.edureminder.quicktube.feature.home.HomeScreen
import com.edureminder.quicktube.feature.home.HowToScreen
import com.edureminder.quicktube.feature.library.DownloadingScreen
import com.edureminder.quicktube.feature.library.LibraryScreen
import com.edureminder.quicktube.feature.player.MiniPlayer
import com.edureminder.quicktube.feature.player.PlayerScreen
import com.edureminder.quicktube.feature.search.SearchResultsScreen
import com.edureminder.quicktube.feature.search.SearchScreen
import com.edureminder.quicktube.feature.video.ListPageScreen
import com.edureminder.quicktube.feature.video.VideoScreen
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

private val tabs = listOf(
    NavTab(HomeRoute, "Download", QtIcons.Download, QtIcons.Download),
    NavTab(LibraryRoute, "Play", QtIcons.PlayCircleOutline, QtIcons.PlayCircle),
    NavTab(SettingsRoute, "Settings", QtIcons.Settings, QtIcons.SettingsFilled),
)

@Composable
fun AppNavHost(nav: NavHostController) {
    val router = koinInject<UrlRouter>()
    val library = koinInject<LibraryRepository>()
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val onTab = tabs.any { t -> dest?.hierarchy?.any { it.hasRoute(t.route::class) } == true }
    val showMini = dest?.let { d -> !d.hasRoute(PlayerRoute::class) && !d.hasRoute(VideoRoute::class) } ?: false
    val activeCount by library.downloads.map { l -> l.count { it.status != DownloadStatus.COMPLETED && it.deletedAt == 0L } }
        .collectAsStateWithLifecycle(0)

    fun openUrl(url: String) = nav.navigate(router.routeFor(url))
    fun downloadUrl(url: String) = nav.navigate(router.routeForPasted(url))
    fun openItem(item: MediaItem) = nav.navigate(
        when (item.kind) {
            ItemKind.VIDEO -> VideoRoute(item.url)
            ItemKind.CHANNEL -> ChannelRoute(item.url)
            ItemKind.PLAYLIST -> PlaylistRoute(item.url)
        },
    )

    val analytics = koinInject<com.edureminder.quicktube.core.analytics.Analytics>()
    // Screen views by route type only (e.g. "VideoRoute"), never the URL inside it.
    androidx.compose.runtime.LaunchedEffect(dest?.route) {
        dest?.route?.substringBefore('/')?.substringBefore('?')?.substringAfterLast('.')?.let(analytics::screen)
    }
    val density = LocalDensity.current
    var overlayHeight by remember { mutableStateOf(0.dp) }
    // Surface (not a plain Box) so screens get the theme's content colour, like Scaffold provided before.
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Box(Modifier.fillMaxSize()) {
        // Tab screens draw behind the floating bar (and see their content through the gaps around it);
        // other screens simply stop above the mini player.
        CompositionLocalProvider(LocalBottomBarHeight provides if (onTab) overlayHeight else 0.dp) {
        NavHost(
            nav, startDestination = HomeRoute, modifier = Modifier.fillMaxSize().padding(bottom = if (onTab) 0.dp else overlayHeight),
            enterTransition = QtTransitions.push,
            exitTransition = QtTransitions.pushExit,
            popEnterTransition = QtTransitions.popEnter,
            popExitTransition = QtTransitions.popExit,
        ) {
            composable<HomeRoute>(
                enterTransition = QtTransitions.tabEnter, exitTransition = QtTransitions.tabExit,
                popEnterTransition = QtTransitions.tabEnter, popExitTransition = QtTransitions.tabExit,
            ) {
                HomeScreen(
                    HomeActions(
                        openSearch = { nav.navigate(SearchRoute(it)) },
                        openUrl = ::openUrl,
                        downloadUrl = ::downloadUrl,
                        openItem = ::openItem,
                        openChannel = { nav.navigate(ChannelRoute(it)) },
                        openHowTo = { nav.navigate(HowToRoute(it)) },
                        openSites = { nav.navigate(SitesRoute) },
                        openLibrary = {
                            nav.navigate(LibraryRoute) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        openVault = { nav.navigate(VaultRoute) },
                    ),
                    Modifier.statusBarsPadding(),
                )
            }
            composable<LibraryRoute>(
                enterTransition = QtTransitions.tabEnter, exitTransition = QtTransitions.tabExit,
                popEnterTransition = QtTransitions.tabEnter, popExitTransition = QtTransitions.tabExit,
            ) {
                LibraryScreen(
                    onOpenDownloading = { nav.navigate(DownloadingRoute) },
                    onOpenPlayer = { nav.navigate(PlayerRoute) { launchSingleTop = true } },
                    onOpenSource = ::openUrl,
                )
            }
            composable<SettingsRoute>(
                enterTransition = QtTransitions.tabEnter, exitTransition = QtTransitions.tabExit,
                popEnterTransition = QtTransitions.tabEnter, popExitTransition = QtTransitions.tabExit,
            ) {
                SettingsScreen(
                    SettingsActions(
                        downloadSettings = { nav.navigate(DownloadSettingsRoute) },
                        notificationSettings = { nav.navigate(NotificationSettingsRoute) },
                        theme = { nav.navigate(ThemeSettingsRoute) },
                        recycleBin = { nav.navigate(RecycleBinRoute) },
                        deletedHistory = { nav.navigate(DeletedHistoryRoute) },
                        statusSaver = { nav.navigate(StatusSaverRoute) },
                        vault = { nav.navigate(VaultRoute) },
                        storageClean = { nav.navigate(StorageCleanRoute) },
                        largeFiles = { nav.navigate(LargeFilesRoute) },
                        appManager = { nav.navigate(AppManagerRoute) },
                        region = { nav.navigate(LanguageRoute) },
                        about = { nav.navigate(AboutRoute) },
                    ),
                )
            }
            composable<DownloadSettingsRoute> { DownloadSettingsScreen(nav::popBackStack) }
            composable<NotificationSettingsRoute> { NotificationSettingsScreen(nav::popBackStack) }
            composable<ThemeSettingsRoute> { ThemeSettingsScreen(nav::popBackStack) }
            composable<LanguageRoute> { RegionScreen(nav::popBackStack) }
            composable<AboutRoute> { AboutScreen(nav::popBackStack) }
            composable<RecycleBinRoute> { RecycleBinScreen(nav::popBackStack, onHistory = { nav.navigate(DeletedHistoryRoute) }) }
            composable<DeletedHistoryRoute> { DeletedHistoryScreen(nav::popBackStack) }
            composable<VaultRoute> { VaultScreen(nav::popBackStack, onOpenPlayer = { nav.navigate(PlayerRoute) { launchSingleTop = true } }) }
            composable<StorageCleanRoute> { StorageCleanScreen(nav::popBackStack) }
            composable<LargeFilesRoute> { LargeFilesScreen(nav::popBackStack) }
            composable<AppManagerRoute> { AppManagerScreen(nav::popBackStack) }
            composable<SearchRoute> { e ->
                SearchScreen(
                    e.toRoute<SearchRoute>().initial,
                    onBack = nav::popBackStack,
                    onSearch = { q -> nav.navigate(SearchResultsRoute(q)) { popUpTo<SearchRoute> { inclusive = true } } },
                    onOpenUrl = { u -> nav.navigate(router.routeFor(u)) { popUpTo<SearchRoute> { inclusive = true } } },
                    onViewSites = { nav.navigate(SitesRoute) },
                    onDownloadUrl = { u -> nav.navigate(router.routeForPasted(u)) { popUpTo<SearchRoute> { inclusive = true } } },
                )
            }
            composable<SearchResultsRoute> { e ->
                val q = e.toRoute<SearchResultsRoute>().query
                SearchResultsScreen(
                    q, onBack = nav::popBackStack,
                    onEditQuery = { nav.navigate(SearchRoute(q)) },
                    onOpen = ::openItem, onOpenUrl = ::openUrl,
                )
            }
            composable<VideoRoute> { e ->
                VideoScreen(e.toRoute<VideoRoute>().url, onBack = nav::popBackStack, onOpen = ::openItem,
                    onChannel = { nav.navigate(ChannelRoute(it)) })
            }
            composable<ChannelRoute> { e -> ListPageScreen(e.toRoute<ChannelRoute>().url, true, nav::popBackStack, ::openItem) }
            composable<PlaylistRoute> { e -> ListPageScreen(e.toRoute<PlaylistRoute>().url, false, nav::popBackStack, ::openItem) }
            composable<HowToRoute> { e ->
                HowToScreen(e.toRoute<HowToRoute>().platform, nav::popBackStack, openInBrowser = ::openUrl,
                    openStatusSaver = { nav.navigate(StatusSaverRoute) })
            }
            composable<DownloadingRoute> { DownloadingScreen(nav::popBackStack) }
            composable<PlayerRoute> { PlayerScreen(onBack = nav::popBackStack) }
            composable<BrowserRoute> { e ->
                val r = e.toRoute<BrowserRoute>()
                BrowserScreen(
                    r.url,
                    autoDownload = r.autoDownload,
                    onClose = nav::popBackStack,
                    onOpenVideo = { nav.navigate(VideoRoute(it)) },
                    onOpenHistory = { nav.navigate(BrowserHistoryRoute) },
                    onOpenBookmarks = { nav.navigate(BookmarksRoute) },
                )
            }
            composable<SitesRoute> { SitesScreen(nav::popBackStack, onOpen = ::openUrl, onHistory = { nav.navigate(BrowserHistoryRoute) }) }
            composable<BrowserHistoryRoute> { HistoryScreen(nav::popBackStack, onOpen = ::openUrl) }
            composable<BookmarksRoute> { BookmarksScreen(nav::popBackStack, onOpen = ::openUrl) }
            composable<StatusSaverRoute> { StatusSaverScreen(nav::popBackStack) }
        }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { overlayHeight = with(density) { it.height.toDp() } },
        ) {
            if (showMini) MiniPlayer(onExpand = { nav.navigate(PlayerRoute) { launchSingleTop = true } })
            if (onTab) {
                val selected = tabs.indexOfFirst { t -> dest?.hierarchy?.any { it.hasRoute(t.route::class) } == true }
                FloatingNavBar(
                    tabs, selected,
                    onSelect = { i ->
                        nav.navigate(tabs[i].route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    badges = mapOf(1 to activeCount),
                )
            } else if (showMini) Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
    }
    DownloadSheetHost(onOpenInBrowser = ::openUrl, onOpenExisting = { task ->
        val done = task.status == com.edureminder.quicktube.core.model.DownloadStatus.COMPLETED
        nav.navigate(if (done) LibraryRoute else DownloadingRoute) { launchSingleTop = true }
    })
}
