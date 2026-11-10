package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.database.BookmarkDao
import com.edureminder.quicktube.core.database.BookmarkEntity
import com.edureminder.quicktube.core.database.LocalPlaylistDao
import com.edureminder.quicktube.core.database.LocalPlaylistEntity
import com.edureminder.quicktube.core.database.LocalPlaylistItemEntity
import com.edureminder.quicktube.core.database.PlayHistoryDao
import com.edureminder.quicktube.core.database.PlayHistoryEntity
import com.edureminder.quicktube.core.database.SavedVideoDao
import com.edureminder.quicktube.core.database.SavedVideoEntity
import com.edureminder.quicktube.core.database.SearchHistoryDao
import com.edureminder.quicktube.core.database.SearchQueryEntity
import com.edureminder.quicktube.core.database.SiteDao
import com.edureminder.quicktube.core.database.SiteEntity
import com.edureminder.quicktube.core.database.SubscriptionDao
import com.edureminder.quicktube.core.database.SubscriptionEntity
import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.core.model.CaptionStyle
import com.edureminder.quicktube.core.model.ThemeAccent
import com.edureminder.quicktube.core.model.ThemeMode
import com.edureminder.quicktube.core.model.VideoEndMode
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything worth keeping when changing phones (no downloaded files, no vault PIN, no passwords). */
@Serializable
data class BackupFile(
    val app: String = "QuickTube",
    val version: Int = 1,
    val createdAt: Long = 0,
    val history: List<HistoryRow> = emptyList(),
    val watchLater: List<SavedRow> = emptyList(),
    val playlists: List<PlaylistRow> = emptyList(),
    val subscriptions: List<SubscriptionRow> = emptyList(),
    val bookmarks: List<BookmarkRow> = emptyList(),
    val sites: List<SiteRow> = emptyList(),
    val searches: List<SearchRow> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
) {
    @Serializable data class HistoryRow(val key: String, val title: String, val positionMs: Long, val durationMs: Long, val openedAt: Long, val thumbnail: String? = null, val uploader: String? = null)
    @Serializable data class SavedRow(val url: String, val title: String, val thumbnail: String? = null, val uploader: String = "", val durationSec: Long = -1, val savedAt: Long = 0)
    @Serializable data class PlaylistRow(val name: String, val isMusic: Boolean, val createdAt: Long, val source: String? = null, val items: List<PlaylistItemRow> = emptyList())
    @Serializable data class PlaylistItemRow(val url: String, val title: String, val thumbnail: String? = null, val uploader: String? = null, val durationSec: Long = -1)
    @Serializable data class SubscriptionRow(val url: String, val name: String, val avatar: String? = null, val subscribedAt: Long = 0)
    @Serializable data class BookmarkRow(val title: String, val url: String, val createdAt: Long = 0)
    @Serializable data class SiteRow(val name: String, val url: String)
    @Serializable data class SearchRow(val query: String, val searchedAt: Long)

    /** "12 history · 3 playlists …" for the confirmation message. */
    fun summary() = listOfNotNull(
        history.size.takeIf { it > 0 }?.let { "$it watched" }, watchLater.size.takeIf { it > 0 }?.let { "$it watch later" },
        playlists.size.takeIf { it > 0 }?.let { "$it playlists" }, subscriptions.size.takeIf { it > 0 }?.let { "$it subscriptions" },
        bookmarks.size.takeIf { it > 0 }?.let { "$it bookmarks" }, sites.size.takeIf { it > 0 }?.let { "$it sites" },
    ).joinToString(" · ").ifBlank { "settings" }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }
        fun encode(b: BackupFile) = json.encodeToString(serializer(), b)
        fun decode(text: String): BackupFile = json.decodeFromString(serializer(), text).also {
            require(it.app == "QuickTube") { "This isn't a QuickTube backup" }
        }

        /** The settings a backup carries (look and playback and download choices; never the folder or the PIN). */
        fun settingsOf(s: AppSettings): Map<String, String> = mapOf(
            "themeMode" to s.themeMode.name, "accent" to s.accent.name, "amoled" to "${s.amoled}", "dynamicColor" to "${s.dynamicColor}",
            "maxTasksWifi" to "${s.maxTasksWifi}", "maxTasksMobile" to "${s.maxTasksMobile}", "speedLimitKbps" to "${s.speedLimitKbps}",
            "allowMobileData" to "${s.allowMobileData}", "notifyCompleted" to "${s.notifyCompleted}", "notifyRecommended" to "${s.notifyRecommended}",
            "contentRegion" to s.contentRegion, "playbackSpeed" to "${s.playbackSpeed}", "seekStepSec" to "${s.seekStepSec}", "holdSpeed" to "${s.holdSpeed}",
            "videoEndMode" to s.videoEndMode.name, "keepScreenOn" to "${s.keepScreenOn}", "backgroundPlay" to "${s.backgroundPlay}",
            "autoPip" to "${s.autoPip}", "autoplayNext" to "${s.autoplayNext}", "autoSubtitles" to "${s.autoSubtitles}",
            "saveSubtitles" to "${s.saveSubtitles}", "defaultQuality" to s.defaultQuality, "sponsorBlock" to "${s.sponsorBlock}",
            "captionStyle" to s.captionStyle.encode(),
        )

        /** [s] with a backup's settings on top (unknown or broken values are left as they were). */
        fun applySettings(s: AppSettings, m: Map<String, String>): AppSettings {
            fun b(k: String, d: Boolean) = m[k]?.toBooleanStrictOrNull() ?: d
            fun i(k: String, d: Int) = m[k]?.toIntOrNull() ?: d
            fun f(k: String, d: Float) = m[k]?.toFloatOrNull() ?: d
            return s.copy(
                themeMode = m["themeMode"]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: s.themeMode,
                accent = m["accent"]?.let { runCatching { ThemeAccent.valueOf(it) }.getOrNull() } ?: s.accent,
                amoled = b("amoled", s.amoled), dynamicColor = b("dynamicColor", s.dynamicColor),
                maxTasksWifi = i("maxTasksWifi", s.maxTasksWifi), maxTasksMobile = i("maxTasksMobile", s.maxTasksMobile),
                speedLimitKbps = i("speedLimitKbps", s.speedLimitKbps), allowMobileData = b("allowMobileData", s.allowMobileData),
                notifyCompleted = b("notifyCompleted", s.notifyCompleted), notifyRecommended = b("notifyRecommended", s.notifyRecommended),
                contentRegion = m["contentRegion"] ?: s.contentRegion, playbackSpeed = f("playbackSpeed", s.playbackSpeed),
                seekStepSec = i("seekStepSec", s.seekStepSec), holdSpeed = f("holdSpeed", s.holdSpeed),
                videoEndMode = m["videoEndMode"]?.let { runCatching { VideoEndMode.valueOf(it) }.getOrNull() } ?: s.videoEndMode,
                keepScreenOn = b("keepScreenOn", s.keepScreenOn), backgroundPlay = b("backgroundPlay", s.backgroundPlay),
                autoPip = b("autoPip", s.autoPip), autoplayNext = b("autoplayNext", s.autoplayNext), autoSubtitles = b("autoSubtitles", s.autoSubtitles),
                saveSubtitles = b("saveSubtitles", s.saveSubtitles), defaultQuality = m["defaultQuality"] ?: s.defaultQuality,
                sponsorBlock = b("sponsorBlock", s.sponsorBlock),
                captionStyle = m["captionStyle"]?.let { CaptionStyle.decode(it) } ?: s.captionStyle,
            )
        }
    }
}

/** Backup & restore (Settings): one JSON file in, one out. Restoring adds to what's here; nothing is deleted. */
class BackupManager(
    private val history: PlayHistoryDao,
    private val saved: SavedVideoDao,
    private val playlists: LocalPlaylistDao,
    private val subscriptions: SubscriptionDao,
    private val bookmarks: BookmarkDao,
    private val sites: SiteDao,
    private val searches: SearchHistoryDao,
    private val settings: SettingsRepository,
) {
    suspend fun export(): String {
        val items = playlists.observeAllItems().first().groupBy { it.playlistId }
        return BackupFile.encode(BackupFile(
            createdAt = System.currentTimeMillis(),
            history = history.all().map { BackupFile.HistoryRow(it.itemKey, it.title, it.positionMs, it.durationMs, it.openedAt, it.thumbnail, it.uploader) },
            watchLater = saved.observe().first().map { BackupFile.SavedRow(it.url, it.title, it.thumbnail, it.uploader, it.durationSec, it.savedAt) },
            playlists = playlists.observePlaylists().first().map { p ->
                BackupFile.PlaylistRow(p.name, p.isMusic, p.createdAt, p.source, items[p.id].orEmpty().map {
                    BackupFile.PlaylistItemRow(it.url, it.title, it.thumbnail, it.uploader, it.durationSec)
                })
            },
            subscriptions = subscriptions.all().map { BackupFile.SubscriptionRow(it.url, it.name, it.avatar, it.subscribedAt) },
            bookmarks = bookmarks.observe().first().map { BackupFile.BookmarkRow(it.title, it.url, it.createdAt) },
            sites = sites.observe().first().filter { !it.builtIn }.map { BackupFile.SiteRow(it.name, it.url) },
            searches = searches.observe().first().map { BackupFile.SearchRow(it.query, it.searchedAt) },
            settings = BackupFile.settingsOf(settings.current()),
        ))
    }

    /** Adds the backup's contents; returns what it held. */
    suspend fun import(text: String): BackupFile {
        val b = BackupFile.decode(text)
        b.history.forEach { history.upsert(PlayHistoryEntity(it.key, it.title, it.positionMs, it.durationMs, it.openedAt, it.thumbnail, it.uploader)) }
        b.watchLater.forEach { saved.upsert(SavedVideoEntity(it.url, it.title, it.thumbnail, it.uploader, it.durationSec, it.savedAt)) }
        val existing = playlists.observePlaylists().first()
        b.playlists.forEach { p ->
            // Same name and kind already here: add the missing items to it instead of making a copy.
            val id = existing.firstOrNull { it.name == p.name && it.isMusic == p.isMusic }?.id
                ?: playlists.insertPlaylist(LocalPlaylistEntity(name = p.name, isMusic = p.isMusic, createdAt = p.createdAt, source = p.source))
            p.items.forEach { i ->
                if (playlists.contains(id, i.url) == 0) playlists.insertItem(LocalPlaylistItemEntity(
                    playlistId = id, position = playlists.nextPosition(id), url = i.url, title = i.title, thumbnail = i.thumbnail,
                    uploader = i.uploader, durationSec = i.durationSec, addedAt = System.currentTimeMillis(),
                ))
            }
        }
        b.subscriptions.forEach { subscriptions.upsert(SubscriptionEntity(it.url, it.name, it.avatar, it.subscribedAt)) }
        b.bookmarks.forEach { if (!bookmarks.observeIsSaved(it.url).first()) bookmarks.insert(BookmarkEntity(title = it.title, url = it.url, createdAt = it.createdAt)) }
        b.sites.forEach { if (sites.countUrlOrName(it.url, it.name) == 0) sites.insert(SiteEntity(name = it.name, url = it.url, builtIn = false, position = 100)) }
        b.searches.forEach { searches.upsert(SearchQueryEntity(it.query, it.searchedAt)) }
        if (b.settings.isNotEmpty()) settings.update { BackupFile.applySettings(it, b.settings) }
        return b
    }
}
