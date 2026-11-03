package com.edureminder.quicktube.core.playerui

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.AppSettings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** What the gesture overlays of [VideoPlayerBox] show right now. */
internal class GestureFeedback {
    /** (brightness?, 0..1) while a vertical swipe is on. */
    var level by mutableStateOf<Pair<Boolean, Float>?>(null)
    var seekPreview by mutableStateOf<Long?>(null)
    var holding by mutableStateOf(false)
    var doubleTap by mutableStateOf<TapZone?>(null)
    var doubleTapAt by mutableLongStateOf(0L)
    /** Seconds skipped by this run of double taps ("30 seconds" after three), like YouTube. */
    var tapSeconds by mutableStateOf(0)
}

/**
 * The main player's gestures for [VideoPlayerBox], on the shared [playerGestures] detector: double tap skips by the
 * seek step, hold plays at the hold speed, left swipes set the screen brightness, right swipes the volume, horizontal
 * swipes seek. Each one follows Settings → Player.
 */
@Composable
internal fun rememberBasicGestures(
    controller: PlayerController,
    durationMs: Long,
    f: GestureFeedback,
    onTap: () -> Unit,
): PlayerGestureHandler {
    val context = LocalContext.current
    val view = LocalView.current
    val tap by rememberUpdatedState(onTap)
    val duration by rememberUpdatedState(durationMs)
    return remember(controller) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val window = context.findActivity()?.window
        object : PlayerGestureHandler {
            val prefs: AppSettings get() = controller.prefs
            var holdPrevious = 1f
            var level = 0f
            var verticalOff = false
            var seekOff = false
            var seekBase = 0L
            var seekAcc = 0f
            var lastSeek = 0L
            override val enabled get() = true
            override val zoomed get() = false
            override val holding get() = f.holding
            override fun onTap() = tap()
            override fun onDoubleTap(zone: TapZone) {
                if (!prefs.gestureDoubleTap) return tap()
                val step = prefs.seekStepSec * 1000L
                when (zone) {
                    TapZone.LEFT -> controller.seekBy(-step)
                    TapZone.RIGHT -> controller.seekBy(step)
                    TapZone.CENTER -> controller.togglePlay()
                }
                // Taps on the same side in quick succession add up.
                val again = f.doubleTap == zone && System.nanoTime() - f.doubleTapAt < 900_000_000L
                f.tapSeconds = if (again) f.tapSeconds + prefs.seekStepSec else prefs.seekStepSec
                f.doubleTap = zone
                f.doubleTapAt = System.nanoTime()
            }
            override fun onHoldStart(position: Offset, size: IntSize) {
                if (!prefs.gestureHoldSpeed) return
                holdPrevious = controller.state.value.speed
                controller.setSpeed(prefs.holdSpeed)
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                f.holding = true
            }
            override fun onHoldMove(y: Float) {}
            override fun onHoldEnd() {
                if (!f.holding) return
                controller.setSpeed(holdPrevious)
                f.holding = false
            }
            override fun onVerticalStart(left: Boolean) {
                verticalOff = if (left) !prefs.gestureBrightness || window == null else !prefs.gestureVolume
                if (verticalOff) return
                level = if (left) {
                    window?.attributes?.screenBrightness?.takeIf { it >= 0 }
                        ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                } else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                f.level = left to level
            }
            override fun onVertical(left: Boolean, delta: Float) {
                if (verticalOff) return
                // A full-height swipe covers the whole range.
                level = (level + delta * 1.2f).coerceIn(0f, 1f)
                if (left) window?.attributes = window?.attributes?.apply { screenBrightness = level.coerceAtLeast(0.01f) }
                else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = (level * max).roundToInt()
                    if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                }
                f.level = left to level
            }
            override fun onSeekStart() {
                seekOff = !prefs.gestureSeek || duration <= 0
                if (seekOff) return
                seekBase = controller.currentPositionMs
                seekAcc = 0f
                f.seekPreview = seekBase
            }
            override fun onSeek(delta: Float) {
                if (seekOff) return
                // A full-width swipe spans the whole video, or three minutes of a long one.
                seekAcc += delta
                val span = duration.coerceIn(30_000, 180_000)
                val target = (seekBase + seekAcc * span).toLong().coerceIn(0, duration.coerceAtLeast(0))
                f.seekPreview = target
                val now = System.currentTimeMillis()
                if (now - lastSeek > 150) { controller.seekTo(target); lastSeek = now }
            }
            override fun onGestureEnd() {
                f.seekPreview?.let { controller.seekTo(it) }
                f.seekPreview = null
                f.level = null
            }
            override fun onTransform(zoom: Float, pan: Offset, centroid: Offset) {}
        }
    }
}

/** Brightness/volume level, seek time, hold speed and double-tap pills over the video. */
@Composable
internal fun GestureOverlays(f: GestureFeedback, durationMs: Long, prefs: AppSettings) {
    LaunchedEffect(f.doubleTapAt) { if (f.doubleTap != null) { delay(800); f.doubleTap = null } }
    Box(Modifier.fillMaxSize()) {
        f.level?.let { (brightness, v) ->
            Pill(Modifier.align(Alignment.Center)) {
                Icon(if (brightness) QtIcons.Brightness else if (v <= 0f) QtIcons.VolumeOff else QtIcons.VolumeUp, null,
                    tint = Color.White, modifier = Modifier.size(18.dp))
                Box(Modifier.width(96.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxSize().padding(end = (96 * (1f - v)).dp).clip(CircleShape).background(Color.White))
                }
            }
        }
        f.seekPreview?.let { p ->
            Pill(Modifier.align(Alignment.Center)) {
                Text("${Format.durationMs(p)} / ${Format.durationMs(durationMs)}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // YouTube: "2x ▶▶" at the top while held.
        if (f.holding) Pill(Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) {
            val s = prefs.holdSpeed
            Text(if (s % 1f == 0f) "${s.toInt()}x" else "${s}x", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Icon(QtIcons.FastForward, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        // YouTube: a half-circle ripple on that side with the arrows and how far it skipped.
        f.doubleTap?.let { zone ->
            if (zone != TapZone.CENTER) {
                val right = zone == TapZone.RIGHT
                Box(
                    Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.4f)
                        .clip(if (right) RoundedCornerShape(topStartPercent = 100, bottomStartPercent = 100) else RoundedCornerShape(topEndPercent = 100, bottomEndPercent = 100))
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (right) QtIcons.FastForward else QtIcons.FastRewind, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Text("${f.tapSeconds} seconds", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
