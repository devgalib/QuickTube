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
    private val context: android.content.Context,
    /** Where files taken out of the vault go back to (the download folder setting). */
    private val downloadDir: suspend () -> String,
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
            ids.mapNotNull { dao.get(it) }.filter { it.status == com.edureminder.quicktube.core.model.DownloadStatus.COMPLETED }.map { it.toHistory(now) },
        )
        ids.forEach { id ->
            dao.get(id)?.let { e ->
                // Its caption files go with it.
                com.edureminder.quicktube.core.common.SubtitleSidecars.find(File(e.filePath)).forEach { it.second.delete() }
                File(e.filePath).delete()
                // The same temporary files the download engine cleans up (parts, resume state, merge marker, private parts).
                com.edureminder.quicktube.core.common.PartFiles.delete(e.filePath, e.id, File(context.noBackupFilesDir, "parts"))
            }
        }
        dao.delete(ids)
    }

    override suspend fun archiveMissing(): Int = withContext(Dispatchers.IO) {
        val gone = dao.byStatus(listOf(com.edureminder.quicktube.core.model.DownloadStatus.COMPLETED))
            .filter { !it.inVault && !File(it.filePath).exists() }
        if (gone.isEmpty()) return@withContext 0
        val now = System.currentTimeMillis()
        history.insertAll(gone.map { it.toHistory(now) })
        dao.delete(gone.map { it.id })
        gone.size
    }

    private fun com.edureminder.quicktube.core.database.DownloadEntity.toHistory(now: Long) = DeletedHistoryEntity(
        title = title, pageUrl = pageUrl, url = url, thumbnail = thumbnail, mimeType = mimeType, quality = quality,
        sizeBytes = totalBytes, durationSec = durationSec, deletedAt = now,
    )

    override suspend fun purgeExpiredTrash() {
        val before = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TRASH_DAYS)
        deleteForever(dao.trashOlderThan(before).map { it.id })
    }

    /**
     * The vault really hides files: they move (with their caption files) into the app's private storage, where no
     * gallery or file app can see them, and back to the download folder when taken out.
     */
    override suspend fun setVault(ids: List<Long>, inVault: Boolean) = withContext(Dispatchers.IO) {
        val target = if (inVault) vaultDir() else File(downloadDir()).apply { mkdirs() }
        ids.forEach { id ->
            val e = dao.get(id) ?: return@forEach
            val src = File(e.filePath)
            val moved = if (src.exists() && src.parentFile != target) moveInto(src, target) else src
            if (moved == null) return@forEach // couldn't move it: leave it where it is, as it was
            dao.update(e.copy(inVault = inVault, filePath = moved.absolutePath))
            // The phone's media index forgets the old place and learns the new one (none for the private vault).
            android.media.MediaScannerConnection.scanFile(context, listOfNotNull(src.absolutePath, moved.absolutePath.takeIf { !inVault }).toTypedArray(), null, null)
        }
    }

    private fun vaultDir() = File(context.filesDir, "vault").apply { mkdirs() }

    /** Moves [src] and its caption files into [dir] under a free name; null when it couldn't be moved. */
    private fun moveInto(src: File, dir: File): File? {
        var dest = File(dir, src.name)
        var n = 1
        while (dest.exists()) dest = File(dir, "${src.nameWithoutExtension} ($n).${src.extension}").also { n++ }
        val captions = com.edureminder.quicktube.core.common.SubtitleSidecars.find(src)
        if (!move(src, dest)) return null
        captions.forEach { (_, f) -> move(f, File(dir, dest.nameWithoutExtension + f.name.removePrefix(src.nameWithoutExtension))) }
        return dest
    }

    /** Rename when on the same storage, else copy then delete (public storage ↔ app storage). */
    private fun move(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        return runCatching {
            src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            if (dest.length() != src.length()) error("short copy")
            src.delete()
        }.onFailure { dest.delete() }.isSuccess
    }

    override suspend fun rename(id: Long, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext false
        val old = File(e.filePath)
        val ext = old.extension
        val safe = newTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { return@withContext false }
        val target = File(old.parentFile, if (ext.isBlank()) safe else "$safe.$ext")
        if (target.exists() && target != old) return@withContext false
        val captions = com.edureminder.quicktube.core.common.SubtitleSidecars.find(old)
        if (old.exists() && !old.renameTo(target)) return@withContext false
        // Caption files keep matching the video's name (and their language tag).
        captions.forEach { (_, f) -> f.renameTo(File(f.parentFile, target.nameWithoutExtension + f.name.removePrefix(old.nameWithoutExtension))) }
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
