package com.edureminder.quicktube.core.media

import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer

/** Hosts the single app-wide player so audio keeps going in the background and on the lock screen. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        // Songs queued by page address get their stream when the player reaches them (see OnlineAudio).
        val dataSource = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, ChunkedHttpDataSource.Factory(http)),
            OnlineAudioResolver { org.koin.java.KoinJavaComponent.get(com.edureminder.quicktube.core.data.MediaRepository::class.java) },
        )
        // A downloaded video decoder pack has to be loaded before the first track selection to be used for it.
        VideoDecoderPack.loadIfInstalled(this)
        // Phone decoders first; FFmpeg only for what the phone can't decode: audio (else such files play silently) and
        // video such as 10-bit HEVC, which many phones' hardware and built-in software decoders lack.
        val renderers = FallbackRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val sources = SplitStreamSourceFactory(DefaultMediaSourceFactory(dataSource, LongMp4ExtractorsFactory(this)), dataSource)
        // Files next to the playing one are kept ready, so switching between them (the gallery's strip) is instant.
        val preload = NeighbourPreloader(this, sources, renderers).also { preloader = it }
        val player = preload.buildPlayer(ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK))
        val launch = packageManager.getLaunchIntentForPackage(packageName)!!
            .putExtra("open", "player")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        exo = player
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 7, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                // Controllers strip the URI; restore it from request metadata.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                        item.buildUpon().setUri(uri).setSubtitleConfigurations(subtitlesOf(item)).build()
                    }.toMutableList(),
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        exo = null
        preloader?.release()
        preloader = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Plays a separate audio URL alongside a video-only URL (high quality online streams). */
    private class SplitStreamSourceFactory(
        private val delegate: DefaultMediaSourceFactory,
        private val dataSource: DataSource.Factory,
    ) : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider) = apply { delegate.setDrmSessionManagerProvider(p) }
        override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy) = apply { delegate.setLoadErrorHandlingPolicy(p) }
        override fun getSupportedTypes() = delegate.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val audio = mediaItem.requestMetadata.extras?.getString(EXTRA_AUDIO_URI)
            if (audio.isNullOrBlank()) return delegate.createMediaSource(mediaItem)
            val progressive = ProgressiveMediaSource.Factory(dataSource)
            // The default factory keeps the item's caption tracks (YouTube plays video and audio as separate streams).
            val video = if (mediaItem.localConfiguration?.subtitleConfigurations.isNullOrEmpty()) progressive.createMediaSource(mediaItem)
            else delegate.createMediaSource(mediaItem)
            val audioSource = progressive.createMediaSource(MediaItem.fromUri(Uri.parse(audio)))
            return MergingMediaSource(true, video, audioSource)
        }
    }

    companion object {
        /**
         * The service's player, for the few things a MediaController can't do (pause at the end of each item, side-loaded
         * subtitles, the audio session for an equalizer). Same process only; null while the service isn't running.
         */
        @Volatile
        internal var exo: ExoPlayer? = null
        internal var preloader: NeighbourPreloader? = null

        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_ID = "item_id"
        const val EXTRA_IS_VIDEO = "is_video"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        const val EXTRA_SUBTITLE_URI = "subtitle_uri"

        fun extras(id: String, isVideo: Boolean, audioUri: String?, subtitleUri: String? = null) = Bundle().apply {
            putString(EXTRA_ID, id)
            putBoolean(EXTRA_IS_VIDEO, isVideo)
            audioUri?.let { putString(EXTRA_AUDIO_URI, it) }
            subtitleUri?.let { putString(EXTRA_SUBTITLE_URI, it) }
        }

        /** Captions an item asked for in its extras (controllers drop subtitle tracks, extras survive). */
        internal fun subtitlesOf(item: MediaItem): List<MediaItem.SubtitleConfiguration> {
            val uri = item.requestMetadata.extras?.getString(EXTRA_SUBTITLE_URI) ?: return emptyList()
            val mime = if (uri.substringBefore('?').endsWith(".srt", ignoreCase = true)) androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
            else androidx.media3.common.MimeTypes.TEXT_VTT
            return listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri)).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
        }
    }
}

/**
 * The default renderers plus an FFmpeg video renderer after the phone's decoders. Track selection prefers a renderer
 * that fully supports the format, so FFmpeg only plays video the phone's decoders report as unsupported.
 */
@UnstableApi
private class FallbackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
            allowedVideoJoiningTimeMs, out,
        )
        // The phone's decoders, with frames a little late shown rather than dropped (same settings as the default).
        val i = out.indexOfFirst { it.javaClass == MediaCodecVideoRenderer::class.java }
        if (i >= 0) out[i] = LenientVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
        )
        out += FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
    }
}

/**
 * Shows a decoded frame up to [LATE_LIMIT_US] late instead of dropping it after Media3's 30 ms. A decoder working at
 * its limit (1080p60 on a budget phone) delivers many frames slightly late; dropping all of them halved the frame
 * rate (28 fps shown against 45 in the phone's own VLC-based player, which shows late frames too). Frames later than
 * that are still dropped, and Media3 still skips ahead to the next key frame when video falls far behind.
 */
@UnstableApi
private class LenientVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    override fun shouldDropFrame(earlyUs: Long, elapsedSinceLastReleaseUs: Long, isLastFrame: Boolean) =
        earlyUs < -LATE_LIMIT_US && !isLastFrame

    private companion object {
        const val LATE_LIMIT_US = 100_000L
    }
}
