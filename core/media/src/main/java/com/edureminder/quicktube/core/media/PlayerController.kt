package com.edureminder.quicktube.core.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Something the shared player can play: a downloaded file or an online stream. */
data class PlayItem(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String = "",
    val artwork: String? = null,
    val isVideo: Boolean = true,
    /** Separate audio stream to merge (video-only online streams). */
    val audioUri: String? = null,
    val startPositionMs: Long = 0,
)

data class PlaybackState(
    val current: PlayItem? = null,
    val queue: List<PlayItem> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val speed: Float = 1f,
    val audioOnly: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val error: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

/** App-side handle to [PlaybackService]; UI observes [state] and renders [player]. */
@OptIn(UnstableApi::class)
class PlayerController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()
    private var queue: List<PlayItem> = emptyList()
    private var connecting = false
    private val pending = mutableListOf<(MediaController) -> Unit>()

    /** Called by the library so it can remember playback positions. */
    var onPositionSave: ((id: String, positionMs: Long) -> Unit)? = null

    private fun withController(block: (MediaController) -> Unit) {
        val c = _player.value as? MediaController
        if (c != null) { block(c); return }
        pending += block
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller.addListener(listener)
            _player.value = controller
            pending.toList().forEach { it(controller) }
            pending.clear()
            startTicker()
        }, MoreExecutors.directExecutor())
    }

    fun connect() = withController { }

    fun play(items: List<PlayItem>, startIndex: Int = 0, audioOnly: Boolean = false) = withController { c ->
        saveCurrentPosition()
        queue = items
        c.setMediaItems(items.map { it.toMediaItem() }, startIndex, items.getOrNull(startIndex)?.startPositionMs ?: 0)
        setAudioOnlyInternal(c, audioOnly || items.getOrNull(startIndex)?.isVideo == false)
        c.prepare()
        c.play()
        _state.update { it.copy(queue = items, index = startIndex, current = items.getOrNull(startIndex), error = null) }
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
    fun pause() = withController { it.pause() }
    fun resume() = withController { it.play() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun seekBy(deltaMs: Long) = withController { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) }
    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = withController { if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); it.play() }
    fun setSpeed(speed: Float) = withController { it.playbackParameters = PlaybackParameters(speed) }
    fun setRepeat(mode: Int) = withController { it.repeatMode = mode }
    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }
    fun setAudioOnly(on: Boolean) = withController { setAudioOnlyInternal(it, on) }

    fun stop() = withController {
        saveCurrentPosition()
        it.stop()
        it.clearMediaItems()
        queue = emptyList()
        _state.value = PlaybackState()
    }

    private fun setAudioOnlyInternal(c: MediaController, on: Boolean) {
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on)
            .build()
        _state.update { it.copy(audioOnly = on) }
    }

    private fun saveCurrentPosition() {
        val c = _player.value ?: return
        val cur = _state.value.current ?: return
        if (c.duration > 0) {
            val pos = if (c.currentPosition >= c.duration - 2000) 0 else c.currentPosition
            onPositionSave?.invoke(cur.id, pos)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val prev = _state.value.current
            if (prev != null) onPositionSave?.invoke(prev.id, 0)
        }
        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()) }
        }
    }

    private fun sync(p: Player) {
        val idx = p.currentMediaItemIndex
        val vs = p.videoSize
        _state.update {
            it.copy(
                current = queue.getOrNull(idx) ?: it.current,
                index = idx,
                queue = queue,
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0,
                bufferedMs = p.bufferedPosition,
                speed = p.playbackParameters.speed,
                repeatMode = p.repeatMode,
                shuffle = p.shuffleModeEnabled,
                videoWidth = vs.width,
                videoHeight = vs.height,
                error = if (p.playerError == null) null else it.error,
            )
        }
    }

    private fun startTicker() = scope.launch {
        var ticks = 0
        while (isActive) {
            _player.value?.let { p ->
                if (p.isPlaying) sync(p)
                if (p.isPlaying && ++ticks % 20 == 0) saveCurrentPosition()
            }
            delay(500)
        }
    }

    private fun PlayItem.toMediaItem(): MediaItem {
        val uri = Uri.parse(uri)
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uri)
                    .setExtras(PlaybackService.extras(id, isVideo, audioUri))
                    .build(),
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artwork?.let { a -> if (a.startsWith("/")) Uri.fromFile(java.io.File(a)) else Uri.parse(a) })
                    .setMediaType(if (isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
    }
}
