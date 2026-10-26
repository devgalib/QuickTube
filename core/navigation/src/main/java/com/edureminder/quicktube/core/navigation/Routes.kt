package com.edureminder.quicktube.core.navigation

import kotlinx.serialization.Serializable

// Bottom-bar destinations
@Serializable data object HomeRoute
@Serializable data object LibraryRoute
@Serializable data object SettingsRoute

// Discover / download
@Serializable data class SearchRoute(val initial: String = "")
@Serializable data class SearchResultsRoute(val query: String)
@Serializable data class VideoRoute(val url: String)
@Serializable data class ChannelRoute(val url: String)
@Serializable data class PlaylistRoute(val url: String)
@Serializable data class HowToRoute(val platform: String)

// Browser
/** [autoDownload]: open the download sheet by itself once the page's video is found (pasted/shared links). */
@Serializable data class BrowserRoute(val url: String, val autoDownload: Boolean = false)
@Serializable data object SitesRoute
@Serializable data object BrowserHistoryRoute
@Serializable data object BookmarksRoute

// Library / player
@Serializable data object DownloadingRoute
@Serializable data object PlayerRoute

// Settings
@Serializable data object DownloadSettingsRoute
@Serializable data object NotificationSettingsRoute
@Serializable data object ThemeSettingsRoute
@Serializable data object LanguageRoute
@Serializable data object AboutRoute

// Tools
@Serializable data object RecycleBinRoute
@Serializable data object DeletedHistoryRoute
@Serializable data object StatusSaverRoute
@Serializable data object VaultRoute
@Serializable data object LargeFilesRoute
@Serializable data object AppManagerRoute
@Serializable data object StorageCleanRoute
