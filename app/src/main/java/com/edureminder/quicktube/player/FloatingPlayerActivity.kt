package com.edureminder.quicktube.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.MainActivity
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.playerui.LocalFloatingPlayer
import com.edureminder.quicktube.core.playerui.LocalPipMode
import com.edureminder.quicktube.core.playerui.PipState
import com.edureminder.quicktube.core.playerui.VideoPlayerBox
import com.edureminder.quicktube.core.playerui.pipParams
import com.edureminder.quicktube.core.playerui.registerPipActions
import com.edureminder.quicktube.core.playerui.videoRatio
import org.koin.android.ext.android.inject

/**
 * The picture-in-picture window for videos that play inside the main app (the video page, the library player). It's its
 * own task, so QuickTube stays usable behind the window and opening the app doesn't close it. The activity is see-through
 * and goes into picture-in-picture as soon as it starts, so only the video is seen shrinking. Expanding the window goes
 * back to the app (to the video's page when it is still open); closing it pauses the video.
 */
class FloatingPlayerActivity : ComponentActivity() {
    private val player: PlayerController by inject()
    private var inPip by mutableStateOf(false)
    private var entered = false
    /** Leaving through expand or because the app shows the video again: the video keeps playing. */
    private var keepPlaying = false
    private var receiver: BroadcastReceiver? = null

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> {
        inPip = it.isInPictureInPictureMode
        if (it.isInPictureInPictureMode) return@Consumer
        if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // Closed (dragged to ✕ or the window's close button): Android stops the activity first, then ends PiP.
            player.pause()
        } else {
            // Expanded: back to the app, which shows the video full size.
            keepPlaying = true
            startActivity(Intent(this, MainActivity::class.java).putExtra("open", "expand").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player.connect()
        PipState.floating.value = true
        receiver = registerPipActions(this, player)
        addOnPictureInPictureModeChangedListener(pipListener)
        setContent {
            val state by player.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.isPlaying, state.videoWidth, state.videoHeight) {
                // Never while the window is being closed: a new layout mid-animation leaves it stuck.
                if (inPip && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching {
                    setPictureInPictureParams(pipParams(this@FloatingPlayerActivity, state.videoRatio(), state.isPlaying, autoEnter = false))
                }
            }
            // "Show here" in the app: the video goes back to its page, playing.
            val recall by PipState.recall.collectAsStateWithLifecycle()
            val recallAtStart = androidx.compose.runtime.remember { PipState.recall.value }
            LaunchedEffect(recall) { if (recall != recallAtStart) { keepPlaying = true; finishAndRemoveTask() } }
            // Nothing left to play (stopped from the notification): close the window.
            LaunchedEffect(state.current) { if (state.current == null && entered) { keepPlaying = true; finishAndRemoveTask() } }
            // Until it is in its window the activity draws nothing, so the app underneath stays as it is.
            if (inPip) CompositionLocalProvider(LocalPipMode provides true, LocalFloatingPlayer provides true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    VideoPlayerBox(player, state, Modifier.fillMaxSize(), fullscreen = true, showPrevNext = false, overlayControls = false)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (entered) return
        entered = true
        window.decorView.post {
            val s = player.state.value
            val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                runCatching { enterPictureInPictureMode(pipParams(this, s.videoRatio(), s.isPlaying, autoEnter = false)) }.getOrDefault(false)
            if (!ok) {
                Toast.makeText(this, "Picture-in-picture isn't available", Toast.LENGTH_SHORT).show()
                keepPlaying = true
                finishAndRemoveTask()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOnPictureInPictureModeChangedListener(pipListener)
        receiver?.let(::unregisterReceiver)
        PipState.floating.value = false
        if (isFinishing && !keepPlaying) player.pause()
    }

    companion object {
        fun start(context: Context) {
            // Set now, not in onCreate: the app stops (leaving for home) before this activity exists, and must not pause.
            PipState.floating.value = true
            context.startActivity(Intent(context, FloatingPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
