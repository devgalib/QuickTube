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
)

fun DownloadEntity.asModel() = DownloadTask(
    id, title, pageUrl, sourceHost, thumbnail, url, audioUrl, filePath, mimeType, type, quality,
    totalBytes, downloadedBytes, audioTotalBytes, audioDownloadedBytes, status, error, durationSec,
    createdAt, completedAt, inVault, deletedAt, lastPlayedPositionMs, protocol = protocol,
)

fun DownloadTask.asEntity(headers: String? = null) = DownloadEntity(
    id, title, pageUrl, sourceHost, thumbnail, url, audioUrl, filePath, mimeType, type, quality,
    totalBytes, downloadedBytes, audioTotalBytes, audioDownloadedBytes, status, error, durationSec,
    createdAt, completedAt, inVault, deletedAt, lastPlayedPositionMs, headers, protocol,
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
