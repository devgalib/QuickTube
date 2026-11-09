package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.database.LocalPlaylistDao
import com.edureminder.quicktube.core.database.LocalPlaylistEntity
import com.edureminder.quicktube.core.database.LocalPlaylistItemEntity
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.LocalPlaylist
import com.edureminder.quicktube.core.model.LocalPlaylistEntry
import com.edureminder.quicktube.core.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Playlists kept on the phone (no account), for videos and for songs, like Flow's local playlists. */
interface LocalPlaylistRepository {
    val playlists: Flow<List<LocalPlaylist>>
    fun playlist(id: Long): Flow<LocalPlaylist?>
    fun entries(id: Long): Flow<List<LocalPlaylistEntry>>
    suspend fun create(name: String, isMusic: Boolean, source: String? = null): Long
    suspend fun rename(id: Long, name: String)
    suspend fun delete(id: Long)
    /** False when it was already in the playlist. */
    suspend fun add(id: Long, item: MediaItem): Boolean
    suspend fun remove(entryId: Long)

    /**
     * A new playlist with the videos/songs of a YouTube, YouTube Music or SoundCloud playlist (up to [limit]).
     * Throws when the link isn't a playlist or can't be read.
     */
    suspend fun import(url: String, isMusic: Boolean, limit: Int = 500): Long
}

internal class LocalPlaylistRepositoryImpl(private val dao: LocalPlaylistDao, private val media: MediaRepository) : LocalPlaylistRepository {
    override val playlists: Flow<List<LocalPlaylist>> = combine(dao.observePlaylists(), dao.observeAllItems()) { lists, items ->
        val byList = items.groupBy { it.playlistId }
        lists.map { it.asModel(byList[it.id].orEmpty()) }
    }

    override fun playlist(id: Long): Flow<LocalPlaylist?> = combine(dao.observePlaylist(id), dao.observeItems(id)) { p, items -> p?.asModel(items) }

    override fun entries(id: Long): Flow<List<LocalPlaylistEntry>> = dao.observeItems(id).map { list ->
        list.map { LocalPlaylistEntry(it.id, MediaItem(url = it.url, title = it.title, thumbnail = it.thumbnail, uploader = it.uploader.orEmpty(), durationSec = it.durationSec)) }
    }

    override suspend fun create(name: String, isMusic: Boolean, source: String?): Long =
        dao.insertPlaylist(LocalPlaylistEntity(name = name.trim().ifBlank { if (isMusic) "My songs" else "My videos" }, isMusic = isMusic,
            createdAt = System.currentTimeMillis(), source = source))

    override suspend fun rename(id: Long, name: String) { if (name.isNotBlank()) dao.rename(id, name.trim()) }

    override suspend fun delete(id: Long) { dao.deleteItemsOf(id); dao.deletePlaylist(id) }

    override suspend fun add(id: Long, item: MediaItem): Boolean {
        if (dao.contains(id, item.url) > 0) return false
        dao.insertItem(LocalPlaylistItemEntity(
            playlistId = id, position = dao.nextPosition(id), url = item.url, title = item.title, thumbnail = item.thumbnail,
            uploader = item.uploader.ifBlank { null }, durationSec = item.durationSec, addedAt = System.currentTimeMillis(),
        ))
        return true
    }

    override suspend fun remove(entryId: Long) = dao.deleteItem(entryId)

    override suspend fun import(url: String, isMusic: Boolean, limit: Int): Long {
        require(media.linkKind(url) == ItemKind.PLAYLIST) { "That link isn't a playlist" }
        val first = media.playlist(url)
        val items = first.videos.items.toMutableList()
        var next = first.videos.nextPage
        while (next != null && items.size < limit) {
            val page = media.playlist(url, next).videos
            if (page.items.isEmpty()) break
            items += page.items
            next = page.nextPage
        }
        val id = create(first.item.title.ifBlank { "Imported playlist" }, isMusic, source = url)
        items.filter { it.kind == ItemKind.VIDEO }.take(limit).forEach { add(id, it) }
        return id
    }

    private fun LocalPlaylistEntity.asModel(items: List<LocalPlaylistItemEntity>) =
        LocalPlaylist(id, name, isMusic, items.size, items.mapNotNull { it.thumbnail }.take(4), source, createdAt)
}
