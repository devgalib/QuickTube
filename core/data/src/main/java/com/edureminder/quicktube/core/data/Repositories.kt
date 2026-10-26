package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.core.model.Bookmark
import com.edureminder.quicktube.core.model.ChannelDetails
import com.edureminder.quicktube.core.model.Comment
import com.edureminder.quicktube.core.model.DownloadTask
import com.edureminder.quicktube.core.model.HistoryEntry
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.MediaSection
import com.edureminder.quicktube.core.model.Page
import com.edureminder.quicktube.core.model.PlaylistDetails
import com.edureminder.quicktube.core.model.SearchFilter
import com.edureminder.quicktube.core.model.Site
import com.edureminder.quicktube.core.model.VideoDetails
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    suspend fun suggestions(query: String): List<String>
    suspend fun search(query: String, filter: SearchFilter, page: Any? = null): Page<MediaItem>
    suspend fun trending(page: Any? = null): Page<MediaItem>
    suspend fun musicHome(): List<MediaSection>
    suspend fun details(url: String, forceRefresh: Boolean = false): VideoDetails
    suspend fun comments(url: String, page: Any? = null): Page<Comment>
    suspend fun channel(url: String, page: Any? = null): ChannelDetails
    suspend fun playlist(url: String, page: Any? = null): PlaylistDetails
    fun canExtract(url: String): Boolean
    /** VIDEO/CHANNEL/PLAYLIST when the URL is a supported page, null otherwise. */
    fun linkKind(url: String): com.edureminder.quicktube.core.model.ItemKind?
}

interface LibraryRepository {
    val downloads: Flow<List<DownloadTask>>
    val trash: Flow<List<DownloadTask>>
    fun observe(id: Long): Flow<DownloadTask?>
    suspend fun get(id: Long): DownloadTask?
    suspend fun moveToTrash(ids: List<Long>)
    suspend fun restore(ids: List<Long>)
    suspend fun deleteForever(ids: List<Long>)
    suspend fun purgeExpiredTrash()
    suspend fun setVault(ids: List<Long>, inVault: Boolean)
    suspend fun rename(id: Long, newTitle: String): Boolean
    suspend fun savePosition(id: Long, positionMs: Long)
    suspend fun addLocalFile(task: DownloadTask): Long
    val deletedHistory: Flow<List<com.edureminder.quicktube.core.model.DeletedDownload>>
    /** Adds media files found in the download folder that aren't in the library yet (e.g. after reinstalling). Returns how many. */
    suspend fun importFolder(dir: String): Int
    suspend fun removeDeletedHistory(ids: List<Long>)
    suspend fun clearDeletedHistory()
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
    val defaultDownloadDir: String
}

interface BrowserRepository {
    val sites: Flow<List<Site>>
    val history: Flow<List<HistoryEntry>>
    val bookmarks: Flow<List<Bookmark>>
    fun isBookmarked(url: String): Flow<Boolean>
    suspend fun addSite(name: String, url: String)
    suspend fun deleteSites(ids: List<Long>)
    suspend fun recordVisit(title: String, url: String)
    suspend fun deleteHistory(id: Long)
    suspend fun clearHistory()
    suspend fun toggleBookmark(title: String, url: String)
    suspend fun removeBookmark(url: String)
}

interface SearchHistoryRepository {
    val recent: Flow<List<String>>
    suspend fun add(query: String)
    suspend fun remove(query: String)
    suspend fun clear()
}
