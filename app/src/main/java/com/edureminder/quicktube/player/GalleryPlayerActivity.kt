package com.edureminder.quicktube.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import com.edureminder.quicktube.core.playerui.pipParams
import com.edureminder.quicktube.core.playerui.shouldPip
import com.edureminder.quicktube.core.playerui.videoRatio
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.feature.gallery.player.GalleryPlayerScreen
import com.edureminder.quicktube.ui.AppTheme
import org.koin.android.ext.android.inject

/**
 * Hosts the gallery player in its own task, so picture-in-picture shrinks only the player: the rest of the app stays
 * usable behind the floating window, and the launcher icon opens the app, not the video.
 */
class GalleryPlayerActivity : ComponentActivity() {
    private val settings: SettingsRepository by inject()
    private val player: PlayerController by inject()
    private var mediaId by mutableLongStateOf(-1L)
    // Bumped for each new video, so a video opened while another plays (even in PiP) gets a fresh screen.
    private var request by mutableIntStateOf(0)

    private val pipListener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> {
        // Closed (dragged to ✕ or the window's close button): Android stops the activity first, then ends PiP.
        if (!it.isInPictureInPictureMode && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) {
            if (!player.state.value.audioOnly) player.pause()
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addOnPictureInPictureModeChangedListener(pipListener)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1L)
        player.connect()
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle(AppSettings())
            AppTheme(s) {
                key(request) { GalleryPlayerScreen(mediaId, onBack = ::finish) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getLongExtra(EXTRA_MEDIA_ID, -1L)
        if (id != -1L && id != mediaId) {
            mediaId = id
            request++
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters on its own (auto-enter); older versions need asking.
        val s = player.state.value
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && player.shouldPip(s)) {
            runCatching { enterPictureInPictureMode(pipParams(this, s.videoRatio(), s.isPlaying, autoEnter = false)) }
        }
    }

    override fun onStop() {
        super.onStop()
        // Out of sight (home, another app, PiP closed) a video pauses; play-as-audio keeps going in the background.
        // Settings → Player → Background play keeps the sound going instead.
        if (!isInPictureInPictureMode && !isChangingConfigurations && !player.state.value.audioOnly && !player.prefs.backgroundPlay) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOnPictureInPictureModeChangedListener(pipListener)
        if (isFinishing && !player.state.value.audioOnly) player.stop()
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "media_id"

        fun start(context: Context, mediaId: Long) {
            context.startActivity(Intent(context, GalleryPlayerActivity::class.java).putExtra(EXTRA_MEDIA_ID, mediaId))
        }
    }
}
