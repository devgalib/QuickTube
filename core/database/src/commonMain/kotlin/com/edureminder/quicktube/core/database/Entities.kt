package com.edureminder.quicktube.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.edureminder.quicktube.core.model.Bookmark
import com.edureminder.quicktube.core.model.ContentType
import com.edureminder.quicktube.core.model.DownloadStatus
import com.edureminder.quicktube.core.model.DownloadTask
import com.edureminder.quicktube.core.model.HistoryEntry
import com.edureminder.quicktube.core.model.Site
import com.edureminder.quicktube.core.model.StreamProtocol

@Entity(tableName = "downloads", indices = [Index("status"), Index("deletedAt")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val pageUrl: String?,
    val sourceHost: String?,
    val thumbnail: String?,
    val url: String,
    val audioUrl: String?,
    val filePath: String,
    val mimeType: String,
    val type: ContentType,
    val quality: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val audioTotalBytes: Long,
    val audioDownloadedBytes: Long,
    val status: DownloadStatus,
    val error: String?,
    val durationSec: Long,
    val createdAt: Long,
    val completedAt: Long,
    val inVault: Boolean,
    val deletedAt: Long,
    val lastPlayedPositionMs: Long,
    val headers: String? = null,
    @ColumnInfo(defaultValue = "HTTP") val protocol: StreamProtocol = StreamProtocol.HTTP,
    @ColumnInfo(defaultValue = "0") val extractAudio: Boolean = false,
    @ColumnInfo(defaultValue = "0") val clipStartMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val clipEndMs: Long = 0,
    /** A photo post with music made into a video: its pictures (JSON array of URLs); [url] is then the music. */
    val slideImages: String? = null,
    /** "Download later": not started before this time (epoch ms); 0 = as soon as possible. */
    @ColumnInfo(defaultValue = "0") val startAfter: Long = 0,
    /** Waits for Wi-Fi even when downloads may use mobile data. */
    @ColumnInfo(defaultValue = "0") val wifiOnly: Boolean = false,
)

/** A bulk download: many links saved into one folder. */
@Entity(tableName = "bulk_jobs")
data class BulkJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val folder: String,
    val createdAt: Long,
    /** The quality picked for every link (a BatchQuality name). */
    @ColumnInfo(defaultValue = "P720") val quality: String = "P720",
)

/** One link of a bulk download and what became of it. */
@Entity(tableName = "bulk_items", indices = [Index("jobId")])
data class BulkItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val position: Int,
    val link: String,
    val title: String?,
    /** PENDING, CHECKING, QUEUED, SKIPPED (already downloaded), FAILED, NOT_FOUND. */
    val status: String,
    val message: String?,
    /** The download this link became (or the earlier one it matched); 0 = none. */
    val downloadId: Long,
)

/** A playlist made in the app (videos or songs), played in order. */
@Entity(tableName = "local_playlists")
data class LocalPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isMusic: Boolean,
    val createdAt: Long,
    /** The YouTube playlist or channel it was imported from, if any. */
    val source: String? = null,
)

@Entity(tableName = "local_playlist_items", indices = [Index("playlistId")])
data class LocalPlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val position: Int,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val uploader: String?,
    val durationSec: Long,
    val addedAt: Long,
)

fun DownloadEntity.asModel() = DownloadTask(
    id, title, pageUrl, sourceHost, thumbnail, url, audioUrl, filePath, mimeType, type, quality,
    totalBytes, downloadedBytes, audioTotalBytes, audioDownloadedBytes, status, error, durationSec,
    createdAt, completedAt, inVault, deletedAt, lastPlayedPositionMs, protocol = protocol,
    clipStartMs = clipStartMs, clipEndMs = clipEndMs, startAfter = startAfter, wifiOnly = wifiOnly,
)

fun DownloadTask.asEntity(headers: String? = null) = DownloadEntity(
    id, title, pageUrl, sourceHost, thumbnail, url, audioUrl, filePath, mimeType, type, quality,
    totalBytes, downloadedBytes, audioTotalBytes, audioDownloadedBytes, status, error, durationSec,
    createdAt, completedAt, inVault, deletedAt, lastPlayedPositionMs, headers, protocol,
    clipStartMs = clipStartMs, clipEndMs = clipEndMs, startAfter = startAfter, wifiOnly = wifiOnly,
)

@Entity(tableName = "sites")
data class SiteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val builtIn: Boolean,
    val position: Int,
)

fun SiteEntity.asModel() = Site(id, name, url, builtIn, position)

@Entity(tableName = "browser_history", indices = [Index("url")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val visitedAt: Long,
)

fun HistoryEntity.asModel() = HistoryEntry(id, title, url, visitedAt)

@Entity(tableName = "bookmarks", indices = [Index("url", unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long,
)

fun BookmarkEntity.asModel() = Bookmark(id, title, url, createdAt)

@Entity(tableName = "search_history")
data class SearchQueryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)

@Entity(tableName = "deleted_history", indices = [Index("deletedAt")])
data class DeletedHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val pageUrl: String?,
    val url: String,
    val thumbnail: String?,
    val mimeType: String,
    val quality: String?,
    val sizeBytes: Long,
    val durationSec: Long,
    val deletedAt: Long,
)

fun DeletedHistoryEntity.asModel() =
    com.edureminder.quicktube.core.model.DeletedDownload(id, title, pageUrl, url, thumbnail, mimeType, quality, sizeBytes, durationSec, deletedAt)

/** Where a played video was left ([itemKey]: file path or page URL). Capped; the least recently opened rows go first. */
@Entity(tableName = "play_history", indices = [Index("openedAt")])
data class PlayHistoryEntity(
    @PrimaryKey val itemKey: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val openedAt: Long,
    /** Picture and channel, for the watch history list (online videos and songs). */
    val thumbnail: String? = null,
    val uploader: String? = null,
)

fun PlayHistoryEntity.asModel() = com.edureminder.quicktube.core.model.PlayHistoryEntry(itemKey, title, positionMs, durationMs, openedAt, thumbnail, uploader)

/** Videos saved with "Watch later", by page URL. */
@Entity(tableName = "saved_videos", indices = [Index("savedAt")])
data class SavedVideoEntity(
    @PrimaryKey val url: String,
    val title: String,
    val thumbnail: String?,
    val uploader: String,
    val durationSec: Long,
    val savedAt: Long,
)

fun SavedVideoEntity.asModel() = com.edureminder.quicktube.core.model.SavedVideo(url, title, thumbnail, uploader, durationSec, savedAt)
fun com.edureminder.quicktube.core.model.SavedVideo.asEntity() = SavedVideoEntity(url, title, thumbnail, uploader, durationSec, savedAt)

/** A followed channel; [lastVideo] is the newest upload already seen (for "new video" alerts). */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val url: String,
    val name: String,
    val avatar: String?,
    val subscribedAt: Long,
    val lastVideo: String? = null,
)
