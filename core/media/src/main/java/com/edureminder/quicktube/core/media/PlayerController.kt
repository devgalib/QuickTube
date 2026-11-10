package com.edureminder.quicktube.core.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
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
import com.edureminder.quicktube.core.data.PlaybackHistoryRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.model.AppSettings
import java.util.concurrent.ConcurrentHashMap

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
    /** Key in the playback history (file path, page URL); defaults to [id]. */
    val historyKey: String? = null,
    /** Captions to show from the start (WebVTT, or SRT for a ".srt" address). */
    val subtitleUri: String? = null,
) {
    val resumeKey: String get() = historyKey ?: id
}

data class PlaybackState(
    val current: PlayItem? = null,
    val queue: List<PlayItem> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Played to the end and stopped there (not repeating). */
    val ended: Boolean = false,
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
    /** The error is the phone not handling this file's format (container or codec), not a temporary failure. */
    val unsupported: Boolean = false,
    /** The file has video but this phone can't decode it (e.g. MPEG-2 on some phones): only the sound would play. */
    val videoUnsupported: Boolean = false,
    /** The error came from the video decoder (e.g. 10-bit HEVC the phone's decoder claims but can't play). */
    val videoDecodeFailed: Boolean = false,
    /** MIME type of the video that couldn't be decoded ([videoUnsupported] or [videoDecodeFailed]), e.g. video/hevc. */
    val videoMimeType: String? = null,
)

/** App-side handle to [PlaybackService]; UI observes [state] and renders [player]. */
@OptIn(UnstableApi::class)
class PlayerController(
    private val context: Context,
    private val history: PlaybackHistoryRepository? = null,
    settingsRepo: SettingsRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Settings → Player, kept current for synchronous reads. */
    @Volatile var prefs = AppSettings()
        private set
    /** Last saved position per history key, so [play] can resume without waiting for the database. */
    private val positions = ConcurrentHashMap<String, Long>()

    init {
        settingsRepo?.let { repo -> scope.launch { repo.settings.collect { prefs = it } } }
        // Follows the database, so clearing the history (Settings → Player) also stops resuming.
        history?.let { h ->
            scope.launch { h.entries.collect { list -> positions.keys.retainAll(list.mapTo(HashSet()) { it.key }); list.forEach { positions[it.key] = it.positionMs } } }
        }
    }
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()
    private var queue: List<PlayItem> = emptyList()
    private var connecting = false
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _cues = MutableStateFlow("")
    /** Subtitle text on screen right now (side-loaded or embedded captions), empty when none. */
    val cues: StateFlow<String> = _cues.asStateFlow()

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
        val first = items.getOrNull(startIndex)
        // Each player sets its own modes after this (the gallery's "repeat the video"): never carry them into the next one.
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.shuffleModeEnabled = false
        PlaybackService.exo?.pauseAtEndOfMediaItems = false
        if (_sleep.value == SLEEP_STOPPED) _sleep.value = SLEEP_OFF
        c.setMediaItems(items.map { it.toMediaItem() }, startIndex, first?.let(::startPosition) ?: 0)
        c.playbackParameters = PlaybackParameters(prefs.playbackSpeed)
        first?.let { markOpened(it) }
        setAudioOnlyInternal(c, audioOnly || items.getOrNull(startIndex)?.isVideo == false)
        c.prepare()
        c.play()
        _state.update { it.copy(queue = items, index = startIndex, current = items.getOrNull(startIndex), error = null) }
    }

    /**
     * Adds [items] to the playing queue: at the end, or right after the current one ([next]). Nothing playing yet:
     * they become the queue (paused at the first).
     */
    fun addToQueue(items: List<PlayItem>, next: Boolean = false) = withController { c ->
        if (items.isEmpty()) return@withController
        if (queue.isEmpty() || c.mediaItemCount == 0) {
            queue = items
            c.setMediaItems(items.map { it.toMediaItem() }, 0, 0)
            c.prepare()
        } else {
            val at = if (next) (c.currentMediaItemIndex + 1).coerceAtMost(queue.size) else queue.size
            queue = queue.subList(0, at) + items + queue.subList(at, queue.size)
            c.addMediaItems(at, items.map { it.toMediaItem() })
        }
        sync(c)
    }

    /** Takes the [index]th item out of the queue (the playing one too: the next one plays). */
    fun removeFromQueue(index: Int) = withController { c ->
        if (index !in queue.indices) return@withController
        queue = queue.filterIndexed { i, _ -> i != index }
        c.removeMediaItem(index)
        sync(c)
    }

    /** Moves the queue item at [from] to [to]. */
    fun moveInQueue(from: Int, to: Int) = withController { c ->
        if (from !in queue.indices || to !in queue.indices || from == to) return@withController
        queue = queue.toMutableList().apply { add(to, removeAt(from)) }
        c.moveMediaItem(from, to)
        sync(c)
    }

    /** Empties the queue and stops (an empty queue to fill with [addToQueue]). */
    fun clearQueue() = withController { c ->
        saveCurrentPosition()
        queue = emptyList()
        c.stop()
        c.clearMediaItems()
        _state.update { it.copy(queue = emptyList(), index = 0, current = null, isPlaying = false) }
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
    fun pause() = withController { it.pause() }
    fun resume() = withController { it.play() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun seekBy(deltaMs: Long) = withController { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) }
    fun next() = withController { if (it.hasNextMediaItem()) { it.seekToNextMediaItem(); recover(it) } }
    fun previous() = withController {
        if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem()
        recover(it)
    }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); recover(it); it.play() }

    /**
     * Plays the current item again from where it was, choosing its tracks afresh: after [VideoDecoderPack] is installed,
     * video the phone couldn't decode now goes to the FFmpeg renderer.
     */
    fun reloadCurrent() = withController { c ->
        _state.update { it.copy(error = null, videoDecodeFailed = false) }
        c.stop()
        c.prepare()
        c.play()
    }

    /** After a file failed to play the player sits idle; moving to another item has to prepare it again. */
    private fun recover(c: MediaController) {
        if (c.playbackState == Player.STATE_IDLE) { _state.update { it.copy(error = null) }; c.prepare() }
    }
    fun setSpeed(speed: Float) = withController { it.playbackParameters = PlaybackParameters(speed) }
    fun setRepeat(mode: Int) = withController { it.repeatMode = mode }
    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }
    fun setAudioOnly(on: Boolean) = withController { setAudioOnlyInternal(it, on) }
    /** Player volume (0 = muted), independent of the device volume. */
    fun setVolume(volume: Float) = withController { it.volume = volume }

    /** Live position for smooth progress bars (the controller extrapolates it between updates). */
    val currentPositionMs: Long get() = _player.value?.currentPosition?.coerceAtLeast(0) ?: 0

    /** Stop at the end of each item instead of moving on ("autoplay off"). */
    fun setPauseAtEndOfItems(on: Boolean) { PlaybackService.exo?.pauseAtEndOfMediaItems = on }

    /** For an [android.media.audiofx.Equalizer]; 0 when unknown. */
    val audioSessionId: Int get() = PlaybackService.exo?.audioSessionId ?: 0

    /** Adds a subtitle file (SRT, VTT, SSA…) to the current item and keeps the position. */
    fun addSubtitle(uri: Uri, mimeType: String) = setSubtitle(uri, mimeType)

    /**
     * Shows the captions at [uri] (WebVTT by default) on the current item, or none when null. The item is rebuilt
     * with them and continues where it was.
     */
    fun setSubtitle(uri: Uri?, mimeType: String = androidx.media3.common.MimeTypes.TEXT_VTT) {
        val exo = PlaybackService.exo ?: return
        val item = exo.currentMediaItem ?: return
        val configs = uri?.let {
            listOf(MediaItem.SubtitleConfiguration.Builder(it).setMimeType(mimeType).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
        }.orEmpty()
        if (item.localConfiguration?.subtitleConfigurations.orEmpty().map { it.uri } == configs.map { it.uri }) return
        val index = exo.currentMediaItemIndex
        val position = exo.currentPosition
        // Not replaceMediaItem: for the same address it updates the item in place and ignores new caption tracks.
        exo.addMediaItem(index + 1, item.buildUpon().setSubtitleConfigurations(configs).build())
        exo.seekTo(index + 1, position)
        exo.removeMediaItem(index)
        _cues.value = ""
    }

    data class AudioTrack(val label: String, val group: Int, val track: Int, val selected: Boolean)

    fun audioTracks(): List<AudioTrack> {
        val p = _player.value ?: return emptyList()
        return p.currentTracks.groups.withIndex().filter { it.value.type == C.TRACK_TYPE_AUDIO }.flatMap { (g, group) ->
            (0 until group.length).map { t ->
                val f = group.getTrackFormat(t)
                val name = listOfNotNull(f.label, f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage })
                    .firstOrNull { it.isNotBlank() } ?: "Track ${g + 1}"
                val channels = if (f.channelCount > 0) " · ${f.channelCount}ch" else ""
                AudioTrack(name + channels, g, t, group.isTrackSelected(t))
            }
        }
    }

    fun selectAudioTrack(track: AudioTrack) = withController { c ->
        val group = c.currentTracks.groups.getOrNull(track.group) ?: return@withController
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.track))
            .build()
    }

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
            record(cur, pos, c.duration)
        }
    }

    /** Where [item] starts: its own start position, else where it was left last time (Settings → Player → Resume). */
    private fun startPosition(item: PlayItem): Long = when {
        item.startPositionMs > 0 -> item.startPositionMs
        prefs.playHistory && prefs.resumePlayback -> positions[item.resumeKey]?.takeIf { it > RESUME_MIN_MS } ?: 0
        else -> 0
    }

    private fun record(item: PlayItem, positionMs: Long, durationMs: Long, opened: Boolean = false) {
        if (!prefs.playHistory) return
        positions[item.resumeKey] = positionMs
        val h = history ?: return
        // Online videos and songs keep their picture and channel for the watch history.
        val online = !item.resumeKey.startsWith("/")
        scope.launch {
            runCatching { h.save(item.resumeKey, item.title, positionMs, durationMs, opened, item.artwork.takeIf { online }, item.artist.takeIf { online }) }
        }
    }

    private fun markOpened(item: PlayItem) = record(item, positions[item.resumeKey] ?: 0, 0, opened = true)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val prev = _state.value.current
            if (prev != null) onPositionSave?.invoke(prev.id, 0)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            // Played to the end: start over next time. Skipped away: remember where it was.
            if (prev != null && prev.id != mediaItem?.mediaId) {
                val st = _state.value
                val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || st.positionMs >= st.durationMs - 2000
                record(prev, if (finished) 0 else st.positionMs, st.durationMs)
            }
            val next = queue.firstOrNull { it.id == mediaItem?.mediaId } ?: return
            markOpened(next)
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                val at = startPosition(next)
                if (at > 0) _player.value?.seekTo(at)
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveCurrentPosition()
        }
        override fun onCues(cueGroup: CueGroup) {
            _cues.value = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
        }
        override fun onPlayerError(error: PlaybackException) {
            // The controller only gets a copy of the error; the service's player (same process) knows which renderer failed.
            val videoFormat = (PlaybackService.exo?.playerError)
                ?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
                ?.takeIf { MimeTypes.isVideo(it.sampleMimeType) }
            // Reading a file too big for the player's memory (an index of tens of millions of samples) ends in an
            // "io unspecified" error that says nothing; say what to do instead.
            val outOfMemory = generateSequence<Throwable>(PlaybackService.exo?.playerError ?: error) { it.cause }.take(10)
                .any { it is OutOfMemoryError }
            _state.update {
                it.copy(
                    error = if (outOfMemory) "this video is too long for QuickTube's player. Use Open with in its ⋮ menu to play it in another app"
                    else error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase(),
                    unsupported = error.errorCode in UNSUPPORTED,
                    videoDecodeFailed = videoFormat != null,
                    videoMimeType = videoFormat?.sampleMimeType ?: it.videoMimeType,
                )
            }
        }
    }

    private fun sync(p: Player) {
        val idx = p.currentMediaItemIndex
        val vs = p.videoSize
        val videoGroups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        // (isSupported(true): a video above the decoder's rated size, like 4K, still plays.)
        val videoUnsupported = videoGroups.isNotEmpty() && videoGroups.none { it.isSupported(true) }
        val unsupportedMime = videoGroups.firstOrNull()?.getTrackFormat(0)?.sampleMimeType
        _state.update {
            it.copy(
                current = queue.getOrNull(idx) ?: it.current,
                index = idx,
                queue = queue,
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                ended = p.playbackState == Player.STATE_ENDED,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0,
                bufferedMs = p.bufferedPosition,
                speed = p.playbackParameters.speed,
                repeatMode = p.repeatMode,
                shuffle = p.shuffleModeEnabled,
                videoWidth = vs.width,
                videoHeight = vs.height,
                error = if (p.playerError == null) null else it.error,
                unsupported = p.playerError != null && it.unsupported,
                videoDecodeFailed = p.playerError != null && it.videoDecodeFailed,
                videoUnsupported = videoUnsupported,
                videoMimeType = if (videoUnsupported) unsupportedMime else if (p.playerError != null) it.videoMimeType else null,
            )
        }
    }

    private fun startTicker() = scope.launch {
        var ticks = 0
        while (isActive) {
            _player.value?.let { p ->
                if (p.isPlaying) sync(p)
                if (p.isPlaying && ++ticks % 20 == 0) saveCurrentPosition()
                checkSleep(p)
            }
            delay(500)
        }
    }

    private val _sleep = MutableStateFlow(SLEEP_OFF)

    /**
     * The sleep timer, kept here so it goes on when the player's screen is left (mini player, background audio):
     * [SLEEP_OFF], [SLEEP_END_OF_ITEM], [SLEEP_STOPPED] (it paused at the end; nothing autoplays), or when to pause (epoch ms).
     */
    val sleep: StateFlow<Long> = _sleep

    fun setSleep(at: Long) {
        _sleep.value = at
        // "End of video": stop when this one ends, wherever the queue or repeat would go next.
        PlaybackService.exo?.pauseAtEndOfMediaItems = at == SLEEP_END_OF_ITEM
    }

    private fun checkSleep(p: Player) {
        val at = _sleep.value
        when {
            at > 0 && System.currentTimeMillis() >= at -> { p.pause(); _sleep.value = SLEEP_OFF }
            at == SLEEP_END_OF_ITEM -> {
                val atEnd = p.playbackState == Player.STATE_ENDED ||
                    (!p.isPlaying && p.duration > 0 && p.currentPosition >= p.duration - 600)
                if (atEnd) {
                    p.pause()
                    PlaybackService.exo?.pauseAtEndOfMediaItems = false
                    _sleep.value = SLEEP_STOPPED
                }
            }
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
                    .setExtras(PlaybackService.extras(id, isVideo, audioUri, subtitleUri))
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

    companion object {
        const val SLEEP_OFF = 0L
        const val SLEEP_END_OF_ITEM = -1L
        const val SLEEP_STOPPED = -2L

        /** Positions this close to the start aren't worth resuming. */
        private const val RESUME_MIN_MS = 3_000L
        private val UNSUPPORTED = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
    }
}
