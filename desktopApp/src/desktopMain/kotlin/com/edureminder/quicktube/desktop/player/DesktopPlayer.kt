package com.edureminder.quicktube.desktop.player

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.edureminder.quicktube.core.common.QtLog
import com.edureminder.quicktube.core.data.PlaybackHistoryRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer

/** What the desktop player is doing; the screens draw from this. */
data class DesktopPlayback(
    val title: String = "",
    val subtitle: String = "",
    val artwork: String? = null,
    /** The page or file being played (to reopen its screen). */
    val key: String? = null,
    val isVideo: Boolean = true,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: String? = null,
) {
    val active get() = key != null
}

/**
 * Plays video and music through libVLC (VLC 3), which decodes every format the sites use (H.264, VP9, AV1, HEVC, Opus…)
 * and plays a separate audio stream next to a video-only one. Frames are copied into a Skia bitmap that Compose draws,
 * so the app's own controls sit on top of the picture, the same as on Android.
 */
class DesktopPlayer(
    private val history: PlaybackHistoryRepository? = null,
    private val settings: SettingsRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DesktopPlayback())

    /** Called when the current item plays to its end (the music queue goes on to the next song). */
    var onEnded: (() -> Unit)? = null

    /** Where this item is remembered in the watch history ("web:<page>", "music:<page>", or a file path). */
    @Volatile private var historyKey: String? = null
    @Volatile private var lastSaved = 0L
    val state: StateFlow<DesktopPlayback> = _state

    /** Bumped for every new frame; [frame] is the picture to draw. */
    val frameTick = mutableIntStateOf(0)
    @Volatile var frame: ImageBitmap? = null
        private set

    /** Null when libVLC isn't installed (the screens then say so). */
    val available: Boolean
    private val factory: MediaPlayerFactory?
    private val player: EmbeddedMediaPlayer?

    init {
        val found = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
        factory = if (found) runCatching { MediaPlayerFactory("--quiet", "--no-video-title-show", "--network-caching=1500") }.getOrNull() else null
        player = factory?.mediaPlayers()?.newEmbeddedMediaPlayer()
        available = player != null
        if (!available) QtLog.w(TAG, "libVLC not found; playback is off")
        player?.let { p ->
            p.videoSurface().set(factory!!.videoSurfaces().newVideoSurface(Formats(), Frames(), true))
            p.events().addMediaPlayerEventListener(Events())
        }
    }

    /**
     * Plays [url] (with a separate [audioUrl] when the video stream has no sound). With a [historyKey] it's saved in the
     * watch history as it plays and, when [startMs] is 0, continues where it was left (Settings → Player on Android).
     */
    fun play(
        url: String, title: String, key: String, audioUrl: String? = null, isVideo: Boolean = true,
        subtitle: String = "", artwork: String? = null, startMs: Long = 0, userAgent: String? = null, historyKey: String? = null,
    ) {
        val p = player ?: run { _state.update { it.copy(error = "Install VLC to play videos in QuickTube") }; return }
        saveProgress()
        frame = null
        this.historyKey = historyKey
        _state.value = DesktopPlayback(
            title = title, subtitle = subtitle, artwork = artwork, key = key, isVideo = isVideo, buffering = true,
            volume = _state.value.volume, muted = _state.value.muted, speed = 1f,
        )
        scope.launch {
            val prefs = settings?.current()
            val keep = historyKey != null && prefs?.playHistory != false
            val resumeAt = if (startMs == 0L && keep && prefs?.resumePlayback != false) {
                history?.all()?.firstOrNull { it.key == historyKey }?.takeIf { it.durationMs <= 0 || it.positionMs < it.durationMs - 5_000 }?.positionMs ?: 0
            } else startMs
            if (keep) history?.save(historyKey!!, title, resumeAt, 0, opened = true, thumbnail = artwork, uploader = subtitle)
            start(p, url, audioUrl, isVideo, userAgent, resumeAt)
        }
    }

    private fun start(p: EmbeddedMediaPlayer, url: String, audioUrl: String?, isVideo: Boolean, userAgent: String?, startMs: Long) {
        val options = buildList {
            audioUrl?.let { add(":input-slave=$it") }
            userAgent?.let { add(":http-user-agent=$it") }
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
            if (!isVideo) add(":no-video")
        }
        val mrl = if (url.startsWith("/") || (url.length > 2 && url[1] == ':')) File(url).toURI().toASCIIString() else url
        p.media().play(mrl, *options.toTypedArray())
        p.audio().setVolume(_state.value.volume)
        // Off-screen layout tests (QT_SNAPSHOT) stay silent.
        if (System.getenv("QT_SNAPSHOT") != null) p.audio().isMute = true
    }

    fun togglePlay() {
        val p = player ?: return
        if (_state.value.ended) { p.controls().setTime(0); p.controls().play() } else p.controls().pause()
    }

    fun pause() { player?.takeIf { it.status().isPlaying }?.controls()?.setPause(true) }
    fun resume() { player?.controls()?.setPause(false) }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val d = _state.value.durationMs
        p.controls().setTime(if (d > 0) ms.coerceIn(0, d) else ms.coerceAtLeast(0))
        _state.update { it.copy(positionMs = ms, ended = false) }
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    fun setVolume(v: Int) {
        player?.audio()?.setVolume(v.coerceIn(0, 150))
        _state.update { it.copy(volume = v.coerceIn(0, 150), muted = false) }
    }

    fun toggleMute() {
        val m = !_state.value.muted
        player?.audio()?.isMute = m
        _state.update { it.copy(muted = m) }
    }

    fun setSpeed(speed: Float) {
        player?.controls()?.setRate(speed)
        _state.update { it.copy(speed = speed) }
    }

    /** Saves where the current item is (watch history), at most every few seconds unless [now]. */
    private fun saveProgress(now: Boolean = true) {
        val key = historyKey ?: return
        val s = _state.value
        if (s.positionMs <= 0) return
        val t = System.currentTimeMillis()
        if (!now && t - lastSaved < 5_000) return
        lastSaved = t
        scope.launch {
            if (settings?.current()?.playHistory == false) return@launch
            history?.save(key, s.title, s.positionMs, s.durationMs, thumbnail = s.artwork, uploader = s.subtitle)
        }
    }

    fun stop() {
        saveProgress()
        historyKey = null
        player?.controls()?.stop()
        frame = null
        _state.update { DesktopPlayback(volume = it.volume, muted = it.muted) }
    }

    fun release() {
        // The window is closing: write where the video was left before the process ends.
        historyKey?.let { key ->
            val s = _state.value
            if (s.positionMs > 0) runCatching { kotlinx.coroutines.runBlocking { history?.save(key, s.title, s.positionMs, s.durationMs) } }
        }
        runCatching { player?.release(); factory?.release() }
    }

    private inner class Events : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = true, buffering = false, ended = false, error = null) }
        override fun paused(mediaPlayer: MediaPlayer) { _state.update { it.copy(playing = false) }; saveProgress() }
        override fun stopped(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false) }
        override fun finished(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(playing = false, ended = true, positionMs = it.durationMs) }
            saveProgress()
            onEnded?.let { cb -> java.awt.EventQueue.invokeLater { cb() } }
        }
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = _state.update { it.copy(buffering = newCache < 100f) }
        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { _state.update { it.copy(positionMs = newTime) }; saveProgress(now = false) }
        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = _state.update { it.copy(durationMs = newLength) }
        override fun error(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false, buffering = false, error = "Couldn't play this video") }
    }

    private inner class Formats : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            _state.update { it.copy(videoWidth = sourceWidth, videoHeight = sourceHeight) }
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {
            _state.update { it.copy(videoWidth = displayWidth, videoHeight = displayHeight) }
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    }

    /** Copies each decoded frame into one of two bitmaps (one is drawn while the next is filled). */
    private inner class Frames : RenderCallback {
        private val bitmaps = arrayOfNulls<Bitmap>(2)
        private var next = 0
        private var bytes = ByteArray(0)

        override fun lock(mediaPlayer: MediaPlayer) = Unit
        override fun unlock(mediaPlayer: MediaPlayer) = Unit

        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
            val w = bufferFormat.width
            val h = bufferFormat.height
            val buf = nativeBuffers.firstOrNull() ?: return
            val size = w * h * 4
            if (bytes.size != size) bytes = ByteArray(size)
            buf.rewind()
            buf.get(bytes, 0, minOf(size, buf.remaining()))
            val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            val bmp = bitmaps[next]?.takeIf { it.width == w && it.height == h } ?: Bitmap().also { it.allocPixels(info); bitmaps[next] = it }
            bmp.installPixels(info, bytes, w * 4)
            frame = bmp.asComposeImageBitmap()
            next = 1 - next
            frameTick.intValue++
        }
    }

    private companion object {
        const val TAG = "DesktopPlayer"
    }
}
