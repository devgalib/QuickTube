package com.edureminder.quicktube.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onStop() {
        super.onStop()
        // Out of sight (home, another app, PiP closed) a video pauses; play-as-audio keeps going in the background.
        if (!isInPictureInPictureMode && !isChangingConfigurations && !player.state.value.audioOnly) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !player.state.value.audioOnly) player.stop()
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "media_id"

        fun start(context: Context, mediaId: Long) {
            context.startActivity(Intent(context, GalleryPlayerActivity::class.java).putExtra(EXTRA_MEDIA_ID, mediaId))
        }
    }
}
