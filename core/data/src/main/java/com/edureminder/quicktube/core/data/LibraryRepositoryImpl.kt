package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.database.DeletedHistoryDao
import com.edureminder.quicktube.core.database.DeletedHistoryEntity
import com.edureminder.quicktube.core.database.DownloadDao
import com.edureminder.quicktube.core.database.asEntity
import com.edureminder.quicktube.core.database.asModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

internal class LibraryRepositoryImpl(
    private val dao: DownloadDao,
    private val history: DeletedHistoryDao,
) : LibraryRepository {
    override val downloads = dao.observeAll().map { list -> list.map { it.asModel() } }
    override val trash = dao.observeTrash().map { list -> list.map { it.asModel() } }
    override fun observe(id: Long) = dao.observe(id).map { it?.asModel() }
    override suspend fun get(id: Long) = dao.get(id)?.asModel()

    override suspend fun moveToTrash(ids: List<Long>) {
        val now = System.currentTimeMillis()
        ids.forEach { id -> dao.get(id)?.let { dao.update(it.copy(deletedAt = now)) } }
    }

    override suspend fun restore(ids: List<Long>) {
        ids.forEach { id -> dao.get(id)?.let { dao.update(it.copy(deletedAt = 0)) } }
    }

    override val deletedHistory = history.observe().map { list -> list.map { it.asModel() } }
    override suspend fun removeDeletedHistory(ids: List<Long>) = history.delete(ids)
    override suspend fun clearDeletedHistory() = history.clear()

    override suspend fun deleteForever(ids: List<Long>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Remember what was deleted so it can be downloaded again later.
        history.insertAll(
            ids.mapNotNull { dao.get(it) }.filter { it.status == com.edureminder.quicktube.core.model.DownloadStatus.COMPLETED }.map { e ->
                DeletedHistoryEntity(
                    title = e.title, pageUrl = e.pageUrl, url = e.url, thumbnail = e.thumbnail, mimeType = e.mimeType, quality = e.quality,
                    sizeBytes = e.totalBytes, durationSec = e.durationSec, deletedAt = now,
                )
            },
        )
        ids.forEach { id ->
            dao.get(id)?.let { e ->
                File(e.filePath).delete()
                File(e.filePath + ".part").delete()
                File(e.filePath + ".audio.part").delete()
                File(e.filePath + ".part.idx").delete()
                File(e.filePath + ".audio.part.idx").delete()
            }
        }
        dao.delete(ids)
    }

    override suspend fun purgeExpiredTrash() {
        val before = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TRASH_DAYS)
        deleteForever(dao.trashOlderThan(before).map { it.id })
    }

    override suspend fun setVault(ids: List<Long>, inVault: Boolean) {
        ids.forEach { id -> dao.get(id)?.let { dao.update(it.copy(inVault = inVault)) } }
    }

    override suspend fun rename(id: Long, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext false
        val old = File(e.filePath)
        val ext = old.extension
        val safe = newTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { return@withContext false }
        val target = File(old.parentFile, if (ext.isBlank()) safe else "$safe.$ext")
        if (target.exists() && target != old) return@withContext false
        if (old.exists() && !old.renameTo(target)) return@withContext false
        dao.update(e.copy(title = safe, filePath = target.absolutePath))
        true
    }

    override suspend fun importFolder(dir: String): Int = withContext(Dispatchers.IO) {
        val root = File(dir)
        if (!root.isDirectory) return@withContext 0
        val known = dao.allPaths().toHashSet()
        val media = root.walkTopDown().maxDepth(2).filter { f ->
            f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".video.mp4") && f.extension.lowercase() in MEDIA_EXT && f.absolutePath !in known
        }.toList()
        media.forEach { f ->
            val ext = f.extension.lowercase()
            val mime = when (ext) {
                "m4a", "aac" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "opus", "ogg" -> "audio/ogg"; "wav" -> "audio/wav"
                "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "ts" -> "video/mp2t"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
                else -> "video/mp4"
            }
            // File names look like "Title(720p).mp4": split the quality back out.
            val base = f.nameWithoutExtension
            val q = Regex("""\(([^()]{1,12})\)(?: \(\d+\))?$""").find(base)?.groupValues?.get(1)
            val title = if (q != null) base.substringBeforeLast("($q)").trim() else base
            val duration = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(f.absolutePath)
                    (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1000) / 1000
                } finally { r.release() }
            }.getOrDefault(-1)
            dao.insert(
                com.edureminder.quicktube.core.database.DownloadEntity(
                    title = title, pageUrl = null, sourceHost = null, thumbnail = null, url = f.toURI().toString(), audioUrl = null,
                    filePath = f.absolutePath, mimeType = mime, type = com.edureminder.quicktube.core.model.ContentType.fromMime(mime), quality = q,
                    totalBytes = f.length(), downloadedBytes = f.length(), audioTotalBytes = -1, audioDownloadedBytes = 0,
                    status = com.edureminder.quicktube.core.model.DownloadStatus.COMPLETED, error = null, durationSec = duration,
                    createdAt = f.lastModified(), completedAt = f.lastModified(), inVault = false, deletedAt = 0, lastPlayedPositionMs = 0,
                ),
            )
        }
        media.size
    }

    override suspend fun savePosition(id: Long, positionMs: Long) = dao.updatePosition(id, positionMs)

    override suspend fun addLocalFile(task: com.edureminder.quicktube.core.model.DownloadTask) =
        dao.insert(task.asEntity())

    companion object {
        private val MEDIA_EXT = setOf("mp4", "m4a", "mp3", "webm", "mkv", "mov", "3gp", "aac", "opus", "ogg", "wav", "ts", "jpg", "jpeg", "png")
        const val TRASH_DAYS = 30L
    }
}
