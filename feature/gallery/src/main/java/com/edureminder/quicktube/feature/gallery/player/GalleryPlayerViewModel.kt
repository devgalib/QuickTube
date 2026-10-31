package com.edureminder.quicktube.feature.gallery.player

import android.app.Application
import android.content.ContentValues
import android.content.IntentSender
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.edureminder.quicktube.core.data.GalleryRepository
import com.edureminder.quicktube.core.download.MediaMerger
import com.edureminder.quicktube.core.media.PlayItem
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.feature.gallery.GallerySections
import com.edureminder.quicktube.feature.gallery.uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How the video fills the screen; cycled by the aspect button like the system player. */
enum class AspectMode(val label: String) { FIT("Fit to Screen"), STRETCH("Stretch"), CROP("Crop") }

/** What happens when a video ends. [toast] is what the system player says when switching to it. */
enum class PlayMode(val label: String, val toast: String) {
    REPEAT_ALL("Repeat All", "Repeat All"),
    AUTOPLAY_OFF("Autoplay Off", "Autoplay Off"),
    REPEAT_ONE("Repeat One", "Repeat One"),
    SHUFFLE("Shuffle", "Switched to shuffle mode"),
}

data class GalleryPlayerUi(
    /** The videos in the strip, which is also the player's queue (indices match). */
    val queue: List<GalleryMedia> = emptyList(),
    val muted: Boolean = false,
    val aspect: AspectMode = AspectMode.FIT,
    val mode: PlayMode = PlayMode.REPEAT_ONE,
    val timerMinutes: Int = 0,
    val timerEndsAt: Long = 0,
    val favorite: Boolean = false,
    val showTutorial: Boolean = false,
)

/**
 * State behind the gallery video player. Playback itself runs in the app's [PlayerController] (so audio mode keeps
 * going in the background and in picture-in-picture); this keeps the queue, modes and the player's own settings.
 */
class GalleryPlayerViewModel(
    app: Application,
    private val mediaId: Long,
    private val repo: GalleryRepository,
    val controller: PlayerController,
) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("gallery_player", 0)
    private val _ui = MutableStateFlow(
        GalleryPlayerUi(
            aspect = AspectMode.entries.getOrElse(prefs.getInt("aspect", 0)) { AspectMode.FIT },
            mode = PlayMode.entries.getOrElse(prefs.getInt("mode", PlayMode.REPEAT_ONE.ordinal)) { PlayMode.REPEAT_ONE },
            showTutorial = !prefs.getBoolean("tutorial_seen", false),
        ),
    )
    val ui: StateFlow<GalleryPlayerUi> = _ui.asStateFlow()
    private var timerJob: Job? = null
    private var equalizer: Equalizer? = null

    init {
        repo.load()
        viewModelScope.launch {
            val all = repo.library.filterNotNull().first { it.complete }.items
            // Always the opened file's own folder (and kind), whichever album or tab it was opened from.
            val media = all.firstOrNull { it.id == mediaId } ?: return@launch
            val videos = GallerySections.folderQueue(all, media)
            val at = videos.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            // A window around the tapped video keeps the queue (and the session's binder traffic) small.
            val from = (at - QUEUE / 2).coerceAtLeast(0)
            val queue = videos.subList(from, minOf(videos.size, from + QUEUE)).toList()
            _ui.update { it.copy(queue = queue) }
            if (queue.isEmpty()) return@launch
            controller.play(queue.map { it.toPlayItem() }, at - from, audioOnly = media.isAudio)
            controller.setVolume(1f)
            applyMode(_ui.value.mode)
            refreshFavorite()
        }
    }

    val current: GalleryMedia? get() = _ui.value.queue.getOrNull(controller.state.value.index)

    fun skipTo(index: Int) = controller.skipTo(index)

    fun toggleMute() {
        val muted = !_ui.value.muted
        controller.setVolume(if (muted) 0f else 1f)
        _ui.update { it.copy(muted = muted) }
    }

    /** Next aspect mode; returns the toast text ("Switched to Stretch"). */
    fun cycleAspect(): String {
        val next = AspectMode.entries[(_ui.value.aspect.ordinal + 1) % AspectMode.entries.size]
        prefs.edit().putInt("aspect", next.ordinal).apply()
        _ui.update { it.copy(aspect = next) }
        return "Switched to ${next.label}"
    }

    fun setMode(mode: PlayMode) {
        prefs.edit().putInt("mode", mode.ordinal).apply()
        _ui.update { it.copy(mode = mode) }
        applyMode(mode)
    }

    /** Cycles the audio-mode repeat button through the same modes. */
    fun nextMode(): PlayMode = PlayMode.entries[(_ui.value.mode.ordinal + 1) % PlayMode.entries.size].also(::setMode)

    private fun applyMode(mode: PlayMode) {
        controller.setRepeat(
            when (mode) {
                PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
                PlayMode.AUTOPLAY_OFF -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_ALL
            },
        )
        controller.setShuffle(mode == PlayMode.SHUFFLE)
        controller.setPauseAtEndOfItems(mode == PlayMode.AUTOPLAY_OFF)
    }

    /** Sleep timer: pauses after [minutes]; 0 turns it off. */
    fun setTimer(minutes: Int) {
        timerJob?.cancel()
        if (minutes <= 0) { _ui.update { it.copy(timerMinutes = 0, timerEndsAt = 0) }; return }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _ui.update { it.copy(timerMinutes = minutes, timerEndsAt = end) }
        timerJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            controller.pause()
            _ui.update { it.copy(timerMinutes = 0, timerEndsAt = 0) }
        }
    }

    fun tutorialSeen() {
        prefs.edit().putBoolean("tutorial_seen", true).apply()
        _ui.update { it.copy(showTutorial = false) }
    }

    fun showTutorial() = _ui.update { it.copy(showTutorial = true) }

    fun refreshFavorite() {
        val media = current ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        viewModelScope.launch {
            val fav = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.query(media.uri, arrayOf(MediaStore.MediaColumns.IS_FAVORITE), null, null, null)
                        ?.use { c -> c.moveToFirst() && c.getInt(0) == 1 } ?: false
                }.getOrDefault(false)
            }
            _ui.update { it.copy(favorite = fav) }
        }
    }

    /** Android 11+: the system's own "add to favourites?" prompt. Null where favourites don't exist. */
    fun favoriteRequest(): IntentSender? {
        val media = current ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return MediaStore.createFavoriteRequest(getApplication<Application>().contentResolver, listOf(media.uri), !_ui.value.favorite).intentSender
    }

    /** "Video to MP3": copies the audio track (no re-encoding) into Music/QuickTube. Returns the saved name or null. */
    suspend fun extractAudio(media: GalleryMedia): String? = withContext(Dispatchers.IO) {
        runCatching {
            val app = getApplication<Application>()
            val name = media.name.substringBeforeLast('.') + ".m4a"
            val tmp = File(app.cacheDir, "audio-${media.id}.m4a").also { it.delete() }
            MediaMerger.extractAudio(File(media.path), tmp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/QuickTube")
                }
                val out = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert")
                app.contentResolver.openOutputStream(out)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "QuickTube").apply { mkdirs() }
                tmp.copyTo(File(dir, name), overwrite = true)
            }
            tmp.delete()
            name
        }.getOrNull()
    }

    /** The player's equalizer, created on first use and kept while the player is open. */
    fun equalizer(): Equalizer? {
        equalizer?.let { return it }
        val session = controller.audioSessionId
        if (session == 0) return null
        return runCatching { Equalizer(0, session) }.getOrNull()?.also { equalizer = it }
    }

    override fun onCleared() {
        equalizer?.release()
        timerJob?.cancel()
    }

    private fun GalleryMedia.toPlayItem() = PlayItem(
        id = "gallery:$id", uri = uri.toString(), title = name.substringBeforeLast('.'), artist = artist, isVideo = !isAudio,
    )

    private companion object {
        const val QUEUE = 100
    }
}
