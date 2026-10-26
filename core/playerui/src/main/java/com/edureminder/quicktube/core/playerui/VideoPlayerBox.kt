package com.edureminder.quicktube.core.playerui

import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.PlayerSurface
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.media.PlaybackState
import com.edureminder.quicktube.core.media.PlayerController
import kotlinx.coroutines.delay

val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * Video surface with overlay controls. Tap shows/hides controls, double-tap left/right seeks 10s.
 * Pass [fillScreen] for fullscreen layouts; otherwise it keeps the video's aspect ratio.
 */
@Composable
fun VideoPlayerBox(
    controller: PlayerController,
    state: PlaybackState,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onCollapse: (() -> Unit)? = null,
    showPrevNext: Boolean = true,
    onRetry: (() -> Unit)? = null,
    /** false = gestures only (tap to pause, double-tap to seek), for screens with their own controls. */
    overlayControls: Boolean = true,
) {
    val player by controller.player.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableStateOf(System.currentTimeMillis()) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controlsVisible, lastInteraction, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(3500)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekFeedback) { if (seekFeedback != null) { delay(700); seekFeedback = null } }

    val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth.toFloat() / state.videoHeight else 16f / 9f
    BoxWithConstraints(
        modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (overlayControls) { controlsVisible = !controlsVisible; lastInteraction = System.currentTimeMillis() }
                        else controller.togglePlay()
                    },
                    onDoubleTap = { o ->
                        if (o.x < size.width / 2) { controller.seekBy(-10_000); seekFeedback = "-10s" }
                        else { controller.seekBy(10_000); seekFeedback = "+10s" }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!state.audioOnly) {
            val fitWide = maxWidth / maxHeight < ratio
            PlayerSurface(
                player = player,
                modifier = Modifier.then(if (fitWide) Modifier.fillMaxWidth() else Modifier.fillMaxSize().padding(0.dp))
                    .aspectRatio(ratio, matchHeightConstraintsFirst = !fitWide),
            )
        } else {
            Icon(QtIcons.Play, null, tint = Color.White.copy(.3f), modifier = Modifier.size(64.dp))
        }
        if (state.isBuffering) CircularProgressIndicator(color = Color.White)
        seekFeedback?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(.5f)).padding(16.dp))
        }
        state.error?.let { err ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Playback error: $err", color = Color.White, style = MaterialTheme.typography.bodySmall)
                if (onRetry != null) QtIconButton(QtIcons.Replay, "Retry", onRetry, tint = Color.White)
            }
        }
        AnimatedVisibility(overlayControls && controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onCollapse != null) IconButton(onClick = onCollapse) {
                        Icon(QtIcons.ExpandMore, "Collapse", tint = Color.White)
                    }
                    Text(state.current?.title.orEmpty(), color = Color.White, maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    SpeedButton(state.speed) { controller.setSpeed(it); lastInteraction = System.currentTimeMillis() }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showPrevNext) IconButton(onClick = { controller.previous(); lastInteraction = System.currentTimeMillis() }) {
                        Icon(QtIcons.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    IconButton(
                        onClick = { controller.togglePlay(); lastInteraction = System.currentTimeMillis() },
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(.4f)),
                    ) {
                        Icon(if (state.isPlaying) QtIcons.Pause else QtIcons.Play, "Play/Pause", tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                    if (showPrevNext) IconButton(onClick = { controller.next(); lastInteraction = System.currentTimeMillis() }) {
                        Icon(QtIcons.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.durationMs(state.positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    SeekBar(state, controller, Modifier.weight(1f).padding(horizontal = 8.dp)) { lastInteraction = System.currentTimeMillis() }
                    Text(Format.durationMs(state.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(if (fullscreen) QtIcons.FullscreenExit else QtIcons.Fullscreen, "Fullscreen", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun SeekBar(state: PlaybackState, controller: PlayerController, modifier: Modifier = Modifier, onInteract: () -> Unit = {}) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = state.durationMs.coerceAtLeast(1)
    val value = if (dragging) dragValue else (state.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    Slider(
        value = value,
        onValueChange = { dragging = true; dragValue = it; onInteract() },
        onValueChangeFinished = { controller.seekTo((dragValue * dur).toLong()); dragging = false },
        modifier = modifier,
        colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = .3f)),
    )
}

@Composable
fun SpeedButton(speed: Float, onSelect: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(QtIcons.Speed, "Speed", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        if (speed != 1f) Text("${speed}x", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter))
        DropdownMenu(open, onDismissRequest = { open = false }) {
            speeds.forEach { s ->
                DropdownMenuItem(text = { Text("${s}x" + if (s == speed) "  ✓" else "") }, onClick = { onSelect(s); open = false })
            }
        }
    }
}


fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Landscape + immersive while [enabled]; restores portrait and system bars afterwards. */
@Composable
fun FullscreenEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(enabled) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (enabled) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                insets.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
