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
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.Player
import com.edureminder.quicktube.core.designsystem.pressable
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

/** YouTube's red, for the timeline and the autoplay switch. */
internal val YtRed = Color(0xFFFF0033)

/** An extra row in the player's settings sheet (under "Additional settings"), e.g. "Watch later" on the video page. */
class PlayerOption(val icon: ImageVector, val label: String, val selected: Boolean = false, val onClick: () -> Unit)

/** The Quality row of the settings sheet: [current] ("Auto (720p)", "1080p"…) and the [options] to switch to. */
class QualityMenu(val current: String, val options: List<String>, val onSelect: (String) -> Unit)

/** What autoplay plays when the video ends: shown with a countdown over the finished video. */
class UpNext(val title: String, val thumbnail: String?, val onPlay: () -> Unit)

/**
 * The app's video player, laid out like YouTube's: collapse at the top left, autoplay / picture-in-picture / captions /
 * settings at the top right, previous - play - next in the middle, time and full screen at the bottom over a red
 * timeline that stays as a thin line when the controls hide. The settings gear opens YouTube's sheet (quality, speed,
 * sleep timer, additional settings). Gestures: tap shows the controls, double tap skips (left / right), hold plays
 * faster, and the main player's swipes for brightness, volume and seeking (Settings → Player turns each one off).
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
    /** Extra rows for the settings sheet's "Additional settings". */
    options: List<PlayerOption> = emptyList(),
    /** Shows the picture-in-picture button (top right) and settings row when set. */
    onPip: (() -> Unit)? = null,
    quality: QualityMenu? = null,
    /** The autoplay switch (top right); null hides it. */
    autoplay: Boolean? = null,
    onAutoplayChange: (Boolean) -> Unit = {},
    /** Next button when the player has no queue of its own (the video page's next suggestion). */
    onNext: (() -> Unit)? = null,
    upNext: UpNext? = null,
) {
    val player by controller.player.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sheetOpen by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    val feedback = remember { GestureFeedback() }
    fun touch() { lastInteraction = System.currentTimeMillis() }
    LaunchedEffect(controlsVisible, lastInteraction, state.isPlaying, sheetOpen, scrubbing) {
        if (controlsVisible && state.isPlaying && !sheetOpen && !scrubbing) {
            delay(3000)
            controlsVisible = false
        }
    }
    // Like YouTube, the end of a video brings the controls back (with Replay in the middle).
    LaunchedEffect(state.ended) { if (state.ended) controlsVisible = true }
    // Sleep timer from the settings sheet (-1 = end of this video).
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0) { delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0)); controller.pause(); sleepAt = 0 }
    }
    KeepScreenOnWhilePlaying(state.isPlaying && !state.audioOnly && controller.prefs.keepScreenOn)
    // A brightness swipe only lasts while the player is on screen; the rest of the app goes back to the system level.
    val activity = context.findActivity()
    DisposableEffect(activity) {
        onDispose { activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } } }
    }
    val gestures = rememberBasicGestures(controller, state.durationMs, feedback) {
        if (overlayControls) { controlsVisible = !controlsVisible; touch() } else controller.togglePlay()
    }
    val position by rememberPlayerPosition(controller, state)
    val hasNext = onNext != null || (showPrevNext && state.queue.size > 1 && state.index < state.queue.lastIndex)
    val hasPrevious = showPrevNext && state.queue.size > 1 && state.index > 0

    val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth.toFloat() / state.videoHeight else 16f / 9f
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (!state.audioOnly) {
            val fitWide = maxWidth / maxHeight < ratio
            PlayerSurface(
                player = player,
                modifier = Modifier.then(if (fitWide) Modifier.fillMaxWidth() else Modifier.fillMaxSize().padding(0.dp))
                    .aspectRatio(ratio, matchHeightConstraintsFirst = !fitWide),
            )
        } else {
            Icon(QtIcons.Headphones, null, tint = Color.White.copy(.3f), modifier = Modifier.size(56.dp))
        }
        // Until the first frame: the video's own thumbnail instead of a black box, faded out as the picture arrives.
        val poster = state.current?.artwork?.takeIf { !state.audioOnly && state.error == null }
        androidx.compose.animation.AnimatedVisibility(poster != null && state.videoWidth == 0, enter = fadeIn(tween(0)), exit = fadeOut(tween(300))) {
            coil3.compose.AsyncImage(poster, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().playerGestures(gestures))
        if ((state.isBuffering || (poster != null && state.videoWidth == 0 && state.error == null)) && !(controlsVisible && overlayControls)) PlayerSpinner()
        state.error?.let { err ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("An error occurred. Tap to retry.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(err, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                if (onRetry != null) QtIconButton(QtIcons.Replay, "Retry", onRetry, tint = Color.White)
            }
        }
        // Collapsed controls: YouTube keeps a thin red progress line along the bottom edge.
        if (overlayControls && !fullscreen && !controlsVisible && state.durationMs > 0) LineProgress(
            position, state.bufferedMs, state.durationMs, Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        )
        AnimatedVisibility(overlayControls && controlsVisible, enter = fadeIn(tween(150)), exit = fadeOut(tween(250)), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
                val edge = if (fullscreen) 16.dp else 4.dp
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = edge, vertical = if (fullscreen) 10.dp else 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (onCollapse != null) TopIcon(if (fullscreen) QtIcons.ExpandMore else QtIcons.ExpandMore, "Minimize", onCollapse)
                    // Like YouTube: the title only shows full screen.
                    if (fullscreen) Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(state.current?.title.orEmpty(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        state.current?.artist?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color.White.copy(alpha = 0.7f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                    } else Spacer(Modifier.weight(1f))
                    if (autoplay != null) AutoplaySwitch(autoplay) { onAutoplayChange(it); touch() }
                    if (onPip != null) TopIcon(QtIcons.PictureInPicture, "Picture-in-picture", onPip)
                    TopIcon(QtIcons.ClosedCaption, "Captions") {
                        Toast.makeText(context, "Captions aren't available for this video", Toast.LENGTH_SHORT).show(); touch()
                    }
                    TopIcon(QtIcons.Settings, "Settings") { sheetOpen = true }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 72.dp else 44.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (showPrevNext || onNext != null) CenterIcon(QtIcons.SkipPrevious, "Previous", 30.dp, enabled = hasPrevious) { controller.previous(); touch() }
                    Box(
                        Modifier.size(if (fullscreen) 72.dp else 60.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f))
                            .pressable(scaleDown = 0.9f, onClick = {
                                if (state.ended) { controller.seekTo(0); controller.resume() } else controller.togglePlay(); touch()
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isBuffering) PlayerSpinner(Modifier.size(40.dp))
                        else Icon(
                            when { state.ended -> QtIcons.Replay; state.isPlaying -> QtIcons.Pause; else -> QtIcons.Play },
                            if (state.isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(if (fullscreen) 48.dp else 40.dp),
                        )
                    }
                    if (showPrevNext || onNext != null) CenterIcon(QtIcons.SkipNext, "Next", 30.dp, enabled = hasNext) {
                        if (onNext != null) onNext() else controller.next(); touch()
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = if (fullscreen) 20.dp else 0.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = if (fullscreen) 24.dp else 12.dp, end = edge), verticalAlignment = Alignment.CenterVertically) {
                        Text(ytTime(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(" / " + ytTime(state.durationMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        TopIcon(if (fullscreen) QtIcons.FullscreenExit else QtIcons.Fullscreen, if (fullscreen) "Exit full screen" else "Full screen", onToggleFullscreen)
                    }
                    YtSeekBar(
                        position, state.bufferedMs, state.durationMs,
                        onScrub = { ms -> scrubbing = ms != null; feedback.seekPreview = ms; touch() },
                        onSeek = { ms -> controller.seekTo(ms); touch() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = if (fullscreen) 16.dp else 0.dp),
                        edge = !fullscreen,
                    )
                }
            }
        }
        GestureOverlays(feedback, state.durationMs, controller.prefs)
        // Sleep timer "End of video" means stop here, not autoplay the next one.
        if (overlayControls && upNext != null && autoplay == true && state.ended && sleepAt != -1L) UpNextOverlay(upNext, fullscreen)
    }
    if (sheetOpen) YtSettingsSheet(
        state = state, controller = controller, quality = quality, onPip = onPip, extra = options,
        sleepAt = sleepAt, onSleep = { sleepAt = it },
        onDismiss = { sheetOpen = false; touch() },
    )
}

/** The player's loading ring: thin, white and round-capped on a soft shade, like YouTube's. */
@Composable
fun PlayerSpinner(modifier: Modifier = Modifier.size(46.dp)) {
    Box(modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = Color.White, strokeWidth = 3.dp, trackColor = Color.Transparent,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, modifier = Modifier.fillMaxSize().padding(4.dp),
        )
    }
}

/** "1:05" / "1:02:03", like YouTube's time labels. */
internal fun ytTime(ms: Long): String {
    val t = (ms.coerceAtLeast(0) / 1000)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The live position, read from the player several times a second so the timeline moves smoothly. */
@Composable
private fun rememberPlayerPosition(controller: PlayerController, state: PlaybackState): State<Long> {
    val pos = remember { mutableLongStateOf(state.positionMs) }
    LaunchedEffect(state.isPlaying, state.positionMs) {
        pos.longValue = controller.currentPositionMs
        while (state.isPlaying) { delay(200); pos.longValue = controller.currentPositionMs }
    }
    return pos
}

@Composable
private fun TopIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).pressable(scaleDown = 0.88f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun CenterIcon(icon: ImageVector, description: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).pressable(enabled = enabled, scaleDown = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(size)) }
}

/** YouTube's autoplay switch: a small track with a play / pause knob. */
@Composable
private fun AutoplaySwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val knob by animateDpAsState(if (on) 16.dp else 0.dp, label = "autoplay")
    Box(Modifier.size(width = 48.dp, height = 44.dp).pressable(scaleDown = 0.9f, onClick = { onChange(!on) }), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 34.dp, height = 14.dp).clip(CircleShape).background(Color.White.copy(alpha = if (on) 0.85f else 0.45f)))
        Box(
            Modifier.offset(x = knob - 8.dp).size(20.dp).clip(CircleShape).background(if (on) Color.White else Color(0xFF717171)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (on) QtIcons.Play else QtIcons.Pause, if (on) "Autoplay is on" else "Autoplay is off",
                tint = if (on) Color.Black else Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

/** Thin progress line along the bottom edge while the controls are hidden. */
@Composable
private fun LineProgress(position: Long, buffered: Long, duration: Long, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.height(2.dp)) {
        val d = duration.coerceAtLeast(1).toFloat()
        drawRect(Color.White.copy(alpha = 0.25f))
        drawRect(Color.White.copy(alpha = 0.45f), size = size.copy(width = size.width * (buffered / d).coerceIn(0f, 1f)))
        drawRect(YtRed, size = size.copy(width = size.width * (position / d).coerceIn(0f, 1f)))
    }
}

/**
 * YouTube's timeline: a thin red bar with a red dot, thicker while dragged; tapping or dragging anywhere on it seeks.
 * [onScrub] reports the target while dragging (null when done) so the player can show it.
 */
@Composable
private fun YtSeekBar(
    position: Long, buffered: Long, duration: Long,
    onScrub: (Long?) -> Unit, onSeek: (Long) -> Unit, modifier: Modifier, edge: Boolean,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val d = duration.coerceAtLeast(1)
    val fraction = dragFraction ?: (position.toFloat() / d).coerceIn(0f, 1f)
    val scrub by rememberUpdatedState(onScrub)
    val seek by rememberUpdatedState(onSeek)
    androidx.compose.foundation.Canvas(
        modifier.height(if (edge) 16.dp else 24.dp)
            .pointerInput(d) {
                detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f).times(d).toLong()) }
            }
            .pointerInput(d) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f); scrub((dragFraction!! * d).toLong()) },
                    onHorizontalDrag = { c, _ -> dragFraction = (c.position.x / size.width).coerceIn(0f, 1f); scrub((dragFraction!! * d).toLong()) },
                    onDragEnd = { dragFraction?.let { seek((it * d).toLong()) }; dragFraction = null; scrub(null) },
                    onDragCancel = { dragFraction = null; scrub(null) },
                )
            },
    ) {
        val thick = (if (dragFraction != null) 5.dp else 3.dp).toPx()
        // Along the very bottom when embedded, like YouTube; centred in its touch strip full screen.
        val y = if (edge) size.height - thick / 2 else size.height / 2
        fun bar(to: Float, color: Color) = drawLine(color, Offset(0f, y), Offset(size.width * to, y), strokeWidth = thick)
        bar(1f, Color.White.copy(alpha = 0.3f))
        bar((buffered.toFloat() / d).coerceIn(0f, 1f), Color.White.copy(alpha = 0.5f))
        bar(fraction, YtRed)
        drawCircle(YtRed, radius = (if (dragFraction != null) 9.dp else 6.dp).toPx(), center = Offset(size.width * fraction, y))
    }
}

/** End of the video with autoplay on: the next suggestion, playing by itself after a short countdown. */
@Composable
private fun UpNextOverlay(next: UpNext, fullscreen: Boolean) {
    var cancelled by remember(next) { mutableStateOf(false) }
    var left by remember(next) { mutableIntStateOf(5) }
    LaunchedEffect(next, cancelled) {
        if (cancelled) return@LaunchedEffect
        while (left > 0) { delay(1000); left-- }
        next.onPlay()
    }
    if (cancelled) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
            Text("Up next in $left", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            Text(next.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                style = if (fullscreen) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Cancel", color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).pressable(onClick = { cancelled = true })
                        .padding(horizontal = 20.dp, vertical = 10.dp))
                Text("Play now", color = Color.Black, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(CircleShape).background(Color.White).pressable(onClick = next.onPlay).padding(horizontal = 20.dp, vertical = 10.dp))
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

/** Keeps the screen awake while [on] (a video playing, Settings → Player → Keep screen on). */
@Composable
fun KeepScreenOnWhilePlaying(on: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
