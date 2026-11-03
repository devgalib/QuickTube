package com.edureminder.quicktube.core.playerui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.core.content.ContextCompat
import com.edureminder.quicktube.core.media.PlaybackState
import com.edureminder.quicktube.core.media.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/** Picture-in-picture state shared by the screens that play video and the activities hosting them. */
object PipState {
    /** A screen in the main activity shows a playing video: leaving the app should keep it going in picture-in-picture. */
    val wanted = MutableStateFlow(false)
    /** A screen in the main activity shows a video that is playing (whatever the picture-in-picture setting says). */
    val videoOnScreen = MutableStateFlow(false)
    /** That video's width / height, 0 when unknown. */
    val ratio = MutableStateFlow(0f)
    /** The floating player (its own activity, in picture-in-picture) is showing the video; the app shows a placeholder. */
    val floating = MutableStateFlow(false)
    /** Bumped to bring the video back from the floating window into the app (the window closes, playback goes on). */
    val recall = MutableStateFlow(0)
}

/** True inside the floating player itself: the one place that draws the video while [PipState.floating]. */
val LocalFloatingPlayer = compositionLocalOf { false }

/** True while the hosting activity is in picture-in-picture: player screens then draw only the video. */
val LocalPipMode = compositionLocalOf { false }

/** Whether leaving the video now should continue it in picture-in-picture (Settings → Player → Picture-in-picture). */
fun PlayerController.shouldPip(state: PlaybackState): Boolean =
    prefs.autoPip && state.isPlaying && !state.audioOnly && state.current?.isVideo == true && state.error == null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

fun PlaybackState.videoRatio(): Float = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else 0f

/** Marks this screen's video for [PipState] while it is shown (and playing). */
@Composable
fun AutoPipEffect(controller: PlayerController, state: PlaybackState) {
    val want = controller.shouldPip(state)
    val playing = state.isPlaying && !state.audioOnly && state.current?.isVideo == true
    val ratio = state.videoRatio()
    DisposableEffect(want, playing, ratio) {
        PipState.wanted.value = want
        PipState.videoOnScreen.value = playing
        PipState.ratio.value = ratio
        onDispose {
            PipState.wanted.value = false
            PipState.videoOnScreen.value = false
        }
    }
}

const val PIP_REWIND = "com.edureminder.quicktube.pip.REWIND"
const val PIP_PLAY = "com.edureminder.quicktube.pip.PLAY"
const val PIP_FORWARD = "com.edureminder.quicktube.pip.FORWARD"

/** Window shape and the rewind / play-pause / forward buttons; [autoEnter] lets Android 12+ shrink the app on leaving. */
@RequiresApi(Build.VERSION_CODES.O)
fun pipParams(context: Context, ratio: Float, playing: Boolean, autoEnter: Boolean): PictureInPictureParams {
    fun action(icon: Int, title: String, action: String, code: Int) = RemoteAction(
        Icon.createWithResource(context, icon), title, title,
        PendingIntent.getBroadcast(context, code, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE),
    )
    val r = if (ratio > 0f) ratio.coerceIn(1 / 2.39f, 2.39f) else 16f / 9f
    return PictureInPictureParams.Builder()
        .setAspectRatio(Rational((r * 1000).roundToInt(), 1000))
        .setActions(
            listOf(
                action(android.R.drawable.ic_media_rew, "Rewind", PIP_REWIND, 11),
                if (playing) action(android.R.drawable.ic_media_pause, "Pause", PIP_PLAY, 12)
                else action(android.R.drawable.ic_media_play, "Play", PIP_PLAY, 12),
                action(android.R.drawable.ic_media_ff, "Forward", PIP_FORWARD, 13),
            ),
        )
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true) }
        .build()
}

/** Handles the picture-in-picture window's buttons while [context] (the hosting activity) is alive. */
fun registerPipActions(context: Context, controller: PlayerController): BroadcastReceiver {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val step = controller.prefs.seekStepSec * 1000L
            when (intent.action) {
                PIP_REWIND -> controller.seekBy(-step)
                PIP_PLAY -> controller.togglePlay()
                PIP_FORWARD -> controller.seekBy(step)
            }
        }
    }
    val filter = IntentFilter().apply { addAction(PIP_REWIND); addAction(PIP_PLAY); addAction(PIP_FORWARD) }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    return receiver
}
