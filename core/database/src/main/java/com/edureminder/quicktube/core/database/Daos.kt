package com.edureminder.quicktube.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.edureminder.quicktube.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE deletedAt = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) AND deletedAt = 0 ORDER BY createdAt ASC")
    suspend fun byStatus(statuses: List<DownloadStatus>): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun trashOlderThan(before: Long): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE pageUrl = :pageUrl AND quality = :quality AND deletedAt = 0 LIMIT 1")
    suspend fun findExisting(pageUrl: String, quality: String?): DownloadEntity?

    @Query("SELECT filePath FROM downloads")
    suspend fun allPaths(): List<String>

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    /**
     * An earlier download of the same video in the same quality: same page, or (Facebook gives a new share link
     * each time) the same title from the same site. Items in the recycle bin don't count.
     */
    @Query(
        "SELECT * FROM downloads WHERE deletedAt = 0 AND quality IS :quality AND " +
            "((:page IS NOT NULL AND pageUrl = :page) OR (title = :title AND sourceHost IS :host)) ORDER BY id DESC LIMIT 1",
    )
    suspend fun findSame(page: String?, title: String, host: String?, quality: String?): DownloadEntity?

    @Query("SELECT COUNT(*) FROM downloads WHERE filePath = :path")
    suspend fun countWithPath(path: String): Int

    @Query("UPDATE downloads SET downloadedBytes = :video, audioDownloadedBytes = :audio WHERE id = :id")
    suspend fun updateProgress(id: Long, video: Long, audio: Long)

    @Query("UPDATE downloads SET downloadedBytes = :video, audioDownloadedBytes = :audio, totalBytes = :videoTotal, audioTotalBytes = :audioTotal WHERE id = :id")
    suspend fun updateTransfer(id: Long, video: Long, audio: Long, videoTotal: Long, audioTotal: Long)

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus, error: String? = null)

    @Query("UPDATE downloads SET lastPlayedPositionMs = :pos WHERE id = :id")
    suspend fun updatePosition(id: Long, pos: Long)

    @Query("DELETE FROM downloads WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
interface SiteDao {
    @Query("SELECT * FROM sites ORDER BY position ASC, id ASC")
    fun observe(): Flow<List<SiteEntity>>

    @Query("SELECT COUNT(*) FROM sites")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(sites: List<SiteEntity>)

    @Insert
    suspend fun insert(site: SiteEntity): Long

    @Query("DELETE FROM sites WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM browser_history ORDER BY visitedAt DESC LIMIT 500")
    fun observe(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entry: HistoryEntity)

    @Query("DELETE FROM browser_history WHERE url = :url")
    suspend fun deleteUrl(url: String)

    @Query("DELETE FROM browser_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM browser_history")
    suspend fun clear()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observe(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun observeIsSaved(url: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteUrl(url: String)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 20")
    fun observe(): Flow<List<SearchQueryEntity>>

    @Upsert
    suspend fun upsert(entity: SearchQueryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface DeletedHistoryDao {
    @Query("SELECT * FROM deleted_history ORDER BY deletedAt DESC")
    fun observe(): Flow<List<DeletedHistoryEntity>>

    @Insert
    suspend fun insertAll(items: List<DeletedHistoryEntity>)

    @Query("DELETE FROM deleted_history WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM deleted_history")
    suspend fun clear()
}
