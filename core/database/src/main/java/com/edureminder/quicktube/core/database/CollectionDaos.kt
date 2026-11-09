package com.edureminder.quicktube.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BulkDao {
    @Query("SELECT * FROM bulk_jobs ORDER BY createdAt DESC")
    fun observeJobs(): Flow<List<BulkJobEntity>>

    @Query("SELECT * FROM bulk_jobs WHERE id = :id")
    fun observeJob(id: Long): Flow<BulkJobEntity?>

    @Query("SELECT * FROM bulk_items WHERE jobId = :jobId ORDER BY position")
    fun observeItems(jobId: Long): Flow<List<BulkItemEntity>>

    @Query("SELECT * FROM bulk_items ORDER BY jobId, position")
    fun observeAllItems(): Flow<List<BulkItemEntity>>

    @Query("SELECT * FROM bulk_items WHERE jobId = :jobId ORDER BY position")
    suspend fun items(jobId: Long): List<BulkItemEntity>

    @Query("SELECT * FROM bulk_jobs WHERE id = :id")
    suspend fun job(id: Long): BulkJobEntity?

    /** Links not finished when the app was closed (they are checked again). */
    @Query("SELECT * FROM bulk_items WHERE status IN ('PENDING', 'CHECKING') ORDER BY jobId, position")
    suspend fun unfinished(): List<BulkItemEntity>

    @Insert
    suspend fun insertJob(job: BulkJobEntity): Long

    @Insert
    suspend fun insertItems(items: List<BulkItemEntity>)

    @Update
    suspend fun updateItem(item: BulkItemEntity)

    @Query("SELECT * FROM bulk_items WHERE id = :id")
    suspend fun item(id: Long): BulkItemEntity?

    @Query("DELETE FROM bulk_items WHERE jobId = :jobId")
    suspend fun deleteItems(jobId: Long)

    @Query("DELETE FROM bulk_jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: Long)
}

@Dao
interface LocalPlaylistDao {
    @Query("SELECT * FROM local_playlists ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<LocalPlaylistEntity>>

    @Query("SELECT * FROM local_playlists WHERE id = :id")
    fun observePlaylist(id: Long): Flow<LocalPlaylistEntity?>

    @Query("SELECT * FROM local_playlist_items WHERE playlistId = :id ORDER BY position")
    fun observeItems(id: Long): Flow<List<LocalPlaylistItemEntity>>

    @Query("SELECT * FROM local_playlist_items ORDER BY playlistId, position")
    fun observeAllItems(): Flow<List<LocalPlaylistItemEntity>>

    @Query("SELECT * FROM local_playlist_items WHERE playlistId = :id ORDER BY position")
    suspend fun items(id: Long): List<LocalPlaylistItemEntity>

    @Insert
    suspend fun insertPlaylist(p: LocalPlaylistEntity): Long

    @Query("UPDATE local_playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM local_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM local_playlist_items WHERE playlistId = :id")
    suspend fun deleteItemsOf(id: Long)

    @Insert
    suspend fun insertItem(item: LocalPlaylistItemEntity): Long

    @Query("DELETE FROM local_playlist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM local_playlist_items WHERE playlistId = :id")
    suspend fun nextPosition(id: Long): Int

    @Query("SELECT COUNT(*) FROM local_playlist_items WHERE playlistId = :id AND url = :url")
    suspend fun contains(id: Long, url: String): Int
}
