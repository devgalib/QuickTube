package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.database.PlayHistoryDao
import com.edureminder.quicktube.core.database.PlayHistoryEntity
import com.edureminder.quicktube.core.database.SavedVideoDao
import com.edureminder.quicktube.core.database.asEntity
import com.edureminder.quicktube.core.database.asModel
import com.edureminder.quicktube.core.model.SavedVideo
import kotlinx.coroutines.flow.map

internal class PlaybackHistoryRepositoryImpl(private val dao: PlayHistoryDao) : PlaybackHistoryRepository {
    override val entries = dao.observe().map { list -> list.map { it.asModel() } }

    override suspend fun all() = dao.all().map { it.asModel() }

    override suspend fun save(
        key: String, title: String, positionMs: Long, durationMs: Long, opened: Boolean, thumbnail: String?, uploader: String?,
    ) {
        val old = dao.get(key)
        val now = System.currentTimeMillis()
        dao.upsert(
            PlayHistoryEntity(
                itemKey = key, title = title.ifBlank { old?.title.orEmpty() }, positionMs = positionMs,
                durationMs = if (durationMs > 0) durationMs else old?.durationMs ?: 0,
                openedAt = if (opened || old == null) now else old.openedAt,
                thumbnail = thumbnail ?: old?.thumbnail, uploader = uploader?.takeIf { it.isNotBlank() } ?: old?.uploader,
            ),
        )
        if (old == null) dao.trim(PlaybackHistoryRepository.MAX_ENTRIES)
    }

    override suspend fun clear() = dao.clear()
    override suspend fun remove(keys: List<String>) = dao.delete(keys)
}

internal class SavedVideosRepositoryImpl(private val dao: SavedVideoDao) : SavedVideosRepository {
    override val saved = dao.observe().map { list -> list.map { it.asModel() } }
    override fun isSaved(url: String) = dao.isSaved(url)
    override suspend fun save(video: SavedVideo) = dao.upsert(video.asEntity())
    override suspend fun remove(urls: List<String>) = dao.delete(urls)
}
