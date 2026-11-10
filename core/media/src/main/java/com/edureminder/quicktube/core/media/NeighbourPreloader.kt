package com.edureminder.quicktube.core.media

import android.content.Context
import android.net.Uri
import android.os.HandlerThread
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Keeps the files next to the playing one in the queue (the gallery player's strip) opened, parsed and buffered at
 * the spot each will start from, so switching to one shows its picture at once instead of after the file is read:
 * the system gallery's player switches like that. Only files on the phone are preloaded, and only once the playing
 * item is ready, so preloading never slows the video being opened.
 *
 * It builds the player ([buildPlayer]) so both share renderers, track selection and loading; the player's sources for
 * files on the phone come from the preload manager, which hands over a prepared source when the player reaches it.
 */
@OptIn(UnstableApi::class)
internal class NeighbourPreloader(private val context: Context, private val base: MediaSource.Factory, renderers: RenderersFactory) {
    /** Queue position of each item (by media id), and where each starts; set by the controller with each queue. */
    @Volatile var order: Map<String, Int> = emptyMap()
    @Volatile var startOf: (String) -> Long = { 0L }

    private var current = 0
    private var ready = false
    private val ids = HashMap<Int, String>()
    private val added = HashMap<String, MediaItem>()
    private val sizes = HashMap<String, Long>()

    private val control = TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { rank ->
        val d = rank - current
        val id = ids[rank]
        // The playing item keeps what was preloaded for it: dropping it here (as "not a neighbour") would throw away the
        // very data the player is about to take over when it switches to it.
        if (id == null || d !in -1..2 || !small(id) || (!ready && d != 0)) DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        else DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(startFor(id), PRELOAD_MS)
    }

    // Shared with the player, as DefaultPreloadManager.Builder.buildExoPlayer would (that one also replaces the
    // player's media source factory with the manager's, so preloaded sources would never reach the player).
    private val playbackThread = HandlerThread("ExoPlayer:Playback", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val loadControl = DefaultLoadControl()
    private val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(context)
    private val manager: DefaultPreloadManager = DefaultPreloadManager.Builder(context, control)
        .setMediaSourceFactory(base)
        .setRenderersFactory(renderers)
        .setLoadControl(loadControl)
        .setBandwidthMeter(bandwidthMeter)
        .setPreloadLooper(playbackThread.looper)
        .build()

    /** The app's player, on the preload manager's playback thread and components; [player] takes the rest of the setup. */
    fun buildPlayer(player: ExoPlayer.Builder): ExoPlayer =
        player.setMediaSourceFactory(factory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setPlaybackLooper(playbackThread.looper)
            .build().also(::attach)

    val factory: MediaSource.Factory = object : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider) = apply { base.setDrmSessionManagerProvider(p) }
        override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy) = apply { base.setLoadErrorHandlingPolicy(p) }
        override fun getSupportedTypes() = base.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val rank = order[mediaItem.mediaId]
            if (rank == null || !onPhone(mediaItem)) return base.createMediaSource(mediaItem)
            // A replaced item (captions added) takes its predecessor's place.
            added.remove(mediaItem.mediaId)?.let { manager.remove(it) }
            manager.add(mediaItem, rank)
            added[mediaItem.mediaId] = mediaItem
            ids[rank] = mediaItem.mediaId
            return manager.getMediaSource(mediaItem) ?: base.createMediaSource(mediaItem)
        }
    }

    /** Follows the player: the playing index ranks the neighbours, and preloading waits until it's ready. */
    private fun attach(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            // Re-ranked only once the new item plays: until then the player is still taking over its preloaded data.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { ready = false }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !ready) { ready = true; update(player); alignNeighbours() }
            }
        })
    }

    /** A new queue: drops what was preloaded for the old one. Called before the player gets the new items. */
    fun newQueue(order: Map<String, Int>) {
        manager.reset()
        added.clear(); ids.clear()
        ready = false
        this.order = order
    }

    fun release() {
        lookups.shutdownNow()
        manager.release()
        playbackThread.quitSafely()
    }

    /**
     * Where [id] starts when switched to: its saved spot moved back to the key frame there (once looked up). A preloaded
     * start can't be moved to a key frame later, so starting exactly on the saved spot would decode everything from
     * the key frame before it first (up to 7 s of 1080p60, a second or more of waiting). The switch uses this too:
     * the preloaded data is only taken over at the exact spot it was preloaded from.
     */
    fun startFor(id: String): Long {
        val saved = startOf(id)
        return keyframes[id]?.takeIf { it.first == saved }?.second ?: saved
    }

    private val keyframes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    private val lookups = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Looks up the neighbours' key frames off the main thread, then preloads them there. */
    private fun alignNeighbours() {
        val wanted = (-1..2).filter { it != 0 }.mapNotNull { d -> ids[current + d] }
            .mapNotNull { id -> added[id]?.localConfiguration?.uri?.let { Triple(id, it, startOf(id)) } }
            .filter { (id, _, saved) -> saved > 0 && keyframes[id]?.first != saved }
        if (wanted.isEmpty()) return
        lookups.execute {
            for ((id, uri, saved) in wanted) keyframeBefore(uri, saved)?.let { keyframes[id] = saved to it }
            main.post { manager.invalidate() }
        }
    }

    /**
     * The video key frame at or before [ms] in the file at [uri], 1 ms after it: should Media3 time it a hair later
     * than the platform does, a start just past it still begins at that key frame and not the one before.
     */
    private fun keyframeBefore(uri: Uri, ms: Long): Long? = runCatching {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(track)
            extractor.seekTo(ms * 1000, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            extractor.sampleTime.takeIf { it >= 0 }?.let { it / 1000 + 1 }?.coerceAtMost(ms)
        } finally {
            extractor.release()
        }
    }.getOrNull()

    private fun update(player: ExoPlayer) {
        current = order[player.currentMediaItem?.mediaId] ?: player.currentMediaItemIndex
        manager.setCurrentPlayingIndex(current)
        manager.invalidate()
    }

    private fun onPhone(item: MediaItem): Boolean {
        val scheme = item.localConfiguration?.uri?.scheme ?: return false
        return scheme == "content" || scheme == "file"
    }

    /**
     * Files up to 2 GB: a 47-hour course's sample tables take ~50 MB of heap, too much to hold for a neighbour.
     * Sizes are looked up once per item.
     */
    private fun small(id: String): Boolean {
        val size = sizes.getOrPut(id) {
            val uri: Uri = added[id]?.localConfiguration?.uri ?: return false
            runCatching {
                if (uri.scheme == "file") java.io.File(uri.path ?: "").length()
                else context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: C.LENGTH_UNSET.toLong()
            }.getOrDefault(C.LENGTH_UNSET.toLong())
        }
        return size in 0..MAX_BYTES
    }

    private companion object {
        /** How much of each neighbour to buffer: enough for its first frames and the audio under them. */
        const val PRELOAD_MS = 2_000L
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    }
}
