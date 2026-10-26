package com.edureminder.quicktube.core.media

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
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

/** Hosts the single app-wide player so audio keeps going in the background and on the lock screen. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val dataSource = DefaultDataSource.Factory(this, ChunkedHttpDataSource.Factory(http))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(SplitStreamSourceFactory(DefaultMediaSourceFactory(dataSource), dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val launch = packageManager.getLaunchIntentForPackage(packageName)!!
            .putExtra("open", "player")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
                        item.buildUpon().setUri(uri).build()
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
        private val dataSource: DefaultDataSource.Factory,
    ) : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider) = apply { delegate.setDrmSessionManagerProvider(p) }
        override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy) = apply { delegate.setLoadErrorHandlingPolicy(p) }
        override fun getSupportedTypes() = delegate.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val audio = mediaItem.requestMetadata.extras?.getString(EXTRA_AUDIO_URI)
            if (audio.isNullOrBlank()) return delegate.createMediaSource(mediaItem)
            val progressive = ProgressiveMediaSource.Factory(dataSource)
            val video = progressive.createMediaSource(mediaItem)
            val audioSource = progressive.createMediaSource(MediaItem.fromUri(Uri.parse(audio)))
            return MergingMediaSource(true, video, audioSource)
        }
    }

    companion object {
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_ID = "item_id"
        const val EXTRA_IS_VIDEO = "is_video"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        fun extras(id: String, isVideo: Boolean, audioUri: String?) = Bundle().apply {
            putString(EXTRA_ID, id)
            putBoolean(EXTRA_IS_VIDEO, isVideo)
            audioUri?.let { putString(EXTRA_AUDIO_URI, it) }
        }
    }
}
