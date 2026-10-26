package com.edureminder.quicktube.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.download.DownloadManager
import com.edureminder.quicktube.core.download.MediaMerger
import com.edureminder.quicktube.core.media.PlayItem
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.ContentType
import com.edureminder.quicktube.core.model.DownloadStatus
import com.edureminder.quicktube.core.model.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class LibraryFilter(val label: String) { ALL("All"), VIDEO("Video"), MUSIC("Music"), PICTURE("Pictures & more") }

data class LibraryUi(
    val active: List<DownloadTask> = emptyList(),
    val done: List<DownloadTask> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.ALL,
    val query: String = "",
    val loaded: Boolean = false,
)

class LibraryViewModel(
    private val library: LibraryRepository,
    private val settings: com.edureminder.quicktube.core.data.SettingsRepository,
    private val downloads: DownloadManager,
    private val player: PlayerController,
) : ViewModel() {
    val filter = MutableStateFlow(LibraryFilter.ALL)
    val query = MutableStateFlow("")
    val speeds: StateFlow<Map<Long, Long>> = downloads.speeds

    val ui: StateFlow<LibraryUi> = combine(library.downloads, filter, query) { all, f, q ->
        val visible = all.filter { !it.inVault }
        val done = visible.filter { it.status == DownloadStatus.COMPLETED }
            .filter {
                when (f) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.MUSIC -> it.type == ContentType.AUDIO
                    LibraryFilter.VIDEO -> it.type == ContentType.VIDEO
                    LibraryFilter.PICTURE -> it.type == ContentType.IMAGE || it.type == ContentType.OTHER
                }
            }
            .filter { q.isBlank() || it.title.contains(q, ignoreCase = true) }
        LibraryUi(visible.filter { it.status != DownloadStatus.COMPLETED }, done, f, q, true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUi())

    val active = library.downloads.map { l -> l.filter { it.status != DownloadStatus.COMPLETED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        player.onPositionSave = { id, pos ->
            id.removePrefix("dl:").toLongOrNull()?.let { viewModelScope.launch { library.savePosition(it, pos) } }
        }
    }

    /** Plays [task] with the rest of the visible list of the same kind as the queue. */
    fun play(task: DownloadTask, audioOnly: Boolean = false) {
        val list = ui.value.done.filter { it.type == task.type && File(it.filePath).exists() }
            .ifEmpty { listOf(task) }
        val index = list.indexOfFirst { it.id == task.id }.coerceAtLeast(0)
        player.play(list.map { it.toPlayItem() }, index, audioOnly)
    }

    /** Re-adds files already sitting in the download folder (after a reinstall or when copied in). */
    fun importExisting(done: (Int) -> Unit) = viewModelScope.launch {
        done(library.importFolder(settings.current().downloadDir))
    }

    fun delete(ids: List<Long>) = viewModelScope.launch { downloads.remove(ids) }
    fun toVault(ids: List<Long>) = viewModelScope.launch { library.setVault(ids, true) }
    fun rename(id: Long, title: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(library.rename(id, title)) }
    fun pause(id: Long) = downloads.pause(id)
    fun resume(id: Long) = downloads.resume(id)
    fun pauseAll() = downloads.pauseAll()
    fun resumeAll() = downloads.resumeAll()

    fun convertToAudio(task: DownloadTask, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val src = File(task.filePath)
                var out = File(src.parentFile, src.nameWithoutExtension + ".m4a")
                var n = 1
                while (out.exists()) out = File(src.parentFile, src.nameWithoutExtension + " ($n).m4a").also { n++ }
                MediaMerger.extractAudio(src, out)
                library.addLocalFile(
                    task.copy(
                        id = 0, filePath = out.absolutePath, mimeType = "audio/mp4", type = ContentType.AUDIO, quality = "M4A",
                        totalBytes = out.length(), downloadedBytes = out.length(), createdAt = System.currentTimeMillis(),
                        completedAt = System.currentTimeMillis(), audioUrl = null, lastPlayedPositionMs = 0,
                    ),
                )
            }.isSuccess
        }
        done(ok)
    }
}

fun DownloadTask.toPlayItem() = PlayItem(
    id = "dl:$id",
    uri = File(filePath).toURI().toString(),
    title = title,
    artist = sourceHost.orEmpty(),
    artwork = thumbnail,
    isVideo = type == ContentType.VIDEO,
    startPositionMs = lastPlayedPositionMs,
)
