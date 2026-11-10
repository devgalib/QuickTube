package com.edureminder.quicktube.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The video picture (or the artwork for music), letterboxed to fit. */
@Composable
fun VideoSurface(player: DesktopPlayer, artwork: String?, modifier: Modifier = Modifier) {
    val tick by player.frameTick
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val frame = player.frame
        if (frame == null) {
            if (artwork != null) AsyncImage(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION") tick
                val scale = minOf(size.width / frame.width, size.height / frame.height)
                val w = frame.width * scale
                val h = frame.height * scale
                drawImage(
                    frame, dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()), filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

/**
 * The player with its controls: click to play/pause, double-click for full screen, and keys like YouTube's
 * (Space/K play, ←/→ or J/L seek, ↑/↓ volume, M mute, F full screen).
 */
@Composable
fun PlayerView(
    player: DesktopPlayer,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
    val s by player.state.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(lastMove, s.playing) {
        controlsVisible = true
        if (s.playing) { delay(2800); controlsVisible = false }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastMove = System.currentTimeMillis()
                when (e.key) {
                    Key.Spacebar, Key.K -> { player.togglePlay(); true }
                    Key.DirectionRight, Key.L -> { player.seekBy(if (e.key == Key.L) 10_000 else 5_000); true }
                    Key.DirectionLeft, Key.J -> { player.seekBy(if (e.key == Key.J) -10_000 else -5_000); true }
                    Key.DirectionUp -> { player.setVolume(s.volume + 5); true }
                    Key.DirectionDown -> { player.setVolume(s.volume - 5); true }
                    Key.M -> { player.toggleMute(); true }
                    Key.F -> { onToggleFullscreen(); true }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Move) { lastMove = System.currentTimeMillis() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { runCatching { focus.requestFocus() }; player.togglePlay(); lastMove = System.currentTimeMillis() },
                    onDoubleTap = { onToggleFullscreen() },
                )
            },
    ) {
        VideoSurface(player, s.artwork.takeIf { !s.isVideo || player.frame == null }, Modifier.fillMaxSize())
        if (s.buffering && s.error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(46.dp), color = Color.White, strokeWidth = 3.dp)
        s.error?.let { err ->
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(QtIcons.CloudOff, null, tint = Color.White, modifier = Modifier.size(40.dp))
                Text(if (!player.available) "Install VLC (videolan.org) to play videos here" else err, color = Color.White, modifier = Modifier.padding(top = 10.dp))
                if (onRetry != null && player.available) Text(
                    "Try again", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).pointerInput(Unit) { detectTapGestures { onRetry() } }.padding(8.dp),
                )
            }
        }
        if (!s.playing && !s.buffering && s.error == null) {
            Box(
                Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) { Icon(if (s.ended) QtIcons.Refresh else QtIcons.Play, "Play", tint = Color.White, modifier = Modifier.size(42.dp)) }
        }
        AnimatedVisibility(controlsVisible || !s.playing, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Controls(player, s, fullscreen, onToggleFullscreen)
        }
    }
}

@Composable
private fun Controls(player: DesktopPlayer, s: DesktopPlayback, fullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = s.durationMs.coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .padding(horizontal = 12.dp).padding(top = 28.dp, bottom = 6.dp),
    ) {
        Slider(
            value = if (dragging) dragValue else (s.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { player.seekTo((dragValue * duration).toLong()); dragging = false },
            enabled = s.durationMs > 0,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFF0033), inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val roomy = maxWidth >= 520.dp
        val seekButtons = maxWidth >= 380.dp
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            QtIconButton(if (s.playing) QtIcons.Pause else QtIcons.Play, if (s.playing) "Pause" else "Play", player::togglePlay, tint = Color.White)
            if (seekButtons) {
                QtIconButton(QtIcons.Replay10, "Back 10 seconds", { player.seekBy(-10_000) }, tint = Color.White, size = 40.dp)
                QtIconButton(QtIcons.Forward10, "Forward 10 seconds", { player.seekBy(10_000) }, tint = Color.White, size = 40.dp)
            }
            QtIconButton(if (s.muted || s.volume == 0) QtIcons.VolumeOff else QtIcons.VolumeUp, "Mute", player::toggleMute, tint = Color.White, size = 40.dp)
            // Narrow windows keep just the mute button (↑/↓ still change the volume).
            if (roomy) Slider(
                value = if (s.muted) 0f else s.volume / 100f, onValueChange = { player.setVolume((it * 100).roundToInt()) },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.width(96.dp).height(24.dp),
            )
            Text(
                "${Format.durationMs(s.positionMs)} / ${Format.durationMs(s.durationMs)}",
                color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            SpeedMenu(s.speed, player::setSpeed)
            QtIconButton(if (fullscreen) QtIcons.FullscreenExit else QtIcons.Fullscreen, "Full screen", onToggleFullscreen, tint = Color.White)
        }
        }
    }
}

@Composable
private fun SpeedMenu(speed: Float, onSelect: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            if (speed == 1f) "1x" else "${speed}x", color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).pointerInput(Unit) { detectTapGestures { open = true } }.padding(10.dp),
        )
        DropdownMenu(open, { open = false }) {
            for (v in listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) {
                DropdownMenuItem(text = { Text(if (v == 1f) "Normal" else "${v}x") }, onClick = { onSelect(v); open = false })
            }
        }
    }
}

/** A thin progress line (the mini player's). */
@Composable
fun LineProgress(fraction: Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.height(2.dp)) {
        drawRect(color.copy(alpha = 0.25f), size = size)
        drawRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), topLeft = Offset.Zero)
    }
}
