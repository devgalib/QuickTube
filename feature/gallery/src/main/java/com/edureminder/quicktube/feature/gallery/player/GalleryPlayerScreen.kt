package com.edureminder.quicktube.feature.gallery.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.feature.gallery.GalleryActions
import com.edureminder.quicktube.feature.gallery.ImmersiveBars
import com.edureminder.quicktube.feature.gallery.rememberDeleteAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt

private enum class Sheet { SPEED, MORE, SUBTITLE, AUDIO_TRACK, EQUALIZER, AUDIO_MORE }

private val TopBarHeight = 47.dp

/**
 * Gallery video player, laid out and behaving like the phone's own player: top bar (back, marquee title, CC, ⋮),
 * centre play/pause, seven round controls (lock, mute, picture-in-picture, aspect, speed, audio-only, rotate), a thick
 * seek bar, the strip of other videos, and gestures for brightness, volume, seeking, 2X hold, ±10s and zoom.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryPlayerScreen(mediaId: Long, bucketId: Long, onBack: () -> Unit) {
    val vm = koinViewModel<GalleryPlayerViewModel>(key = "player-$bucketId-$mediaId") { parametersOf(bucketId, mediaId) }
    val controller = vm.controller
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    val player by controller.player.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val inPip = rememberPipMode(activity)
    val current = ui.queue.getOrNull(state.index)

    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableLongStateOf(0L) }
    var locked by remember { mutableStateOf(false) }
    var lockFlash by remember { mutableLongStateOf(0L) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var info by remember { mutableStateOf(false) }
    var customTimer by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastAt by remember { mutableLongStateOf(0L) }
    val zoom = remember { VideoZoom() }
    val feedback = remember { GestureFeedback() }
    fun touch() { touched = System.nanoTime() }
    fun say(text: String) { toast = text; toastAt = System.nanoTime() }

    // Controls fade out 3.5s after the last touch (paused or not), like the system player.
    LaunchedEffect(controls, touched, sheet) { if (controls && sheet == null) { delay(3500); controls = false } }
    LaunchedEffect(toastAt) { if (toast != null) { delay(1500); toast = null } }
    LaunchedEffect(lockFlash) { if (lockFlash != 0L) { delay(3000); lockFlash = 0L } }
    LaunchedEffect(current?.id) { zoom.reset(); vm.refreshFavorite() }
    LaunchedEffect(ui.aspect) { zoom.reset() }
    // Landscape is always full-screen; the top bar shows battery and time instead of the status bar.
    ImmersiveBars(visible = state.audioOnly || (!landscape && ((controls && !locked) || sheet != null)))

    val window = activity?.window
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val leaveState by rememberUpdatedState(state.audioOnly)
    DisposableEffect(Unit) {
        onDispose {
            // Leaving the player stops the video; audio-only mode keeps playing in the background.
            if (!leaveState) controller.stop()
            window?.attributes = window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (!state.audioOnly) controller.pause() }
    // The decoder's size has the recording's rotation applied; MediaStore's often doesn't (portrait phone videos).
    val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth.toFloat() / state.videoHeight
    else current?.let { if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height else 0f } ?: 0f
    PipActions(activity, controller, state.isPlaying, inPip, ratio)
    BackHandler {
        when {
            sheet != null -> sheet = null
            locked -> lockFlash = System.nanoTime()
            else -> onBack()
        }
    }

    val delete = rememberDeleteAction(onDeleted = { if (ui.queue.size <= 1) onBack() else controller.next() })
    val favorite = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.refreshFavorite() }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val mime = when {
            name.endsWith(".vtt") -> "text/vtt"
            name.endsWith(".ass") || name.endsWith(".ssa") -> "text/x-ssa"
            else -> "application/x-subrip"
        }
        controller.addSubtitle(uri, mime)
        say("Subtitle loaded")
    }

    if (inPip) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { VideoLayer(player, AspectMode.FIT, ratio, zoom, Modifier.fillMaxSize()) }
        return
    }

    val actions = PlayerActions(
        toggleLock = { locked = !locked; if (locked) { controls = false; lockFlash = System.nanoTime() } else { controls = true; touch() } },
        toggleMute = { vm.toggleMute(); touch() },
        pip = { if (activity != null) enterPip(activity, ratio, state.isPlaying) },
        aspect = { say(vm.cycleAspect()); touch() },
        speed = { sheet = Sheet.SPEED },
        audioOnly = { controller.setAudioOnly(true) },
        rotate = {
            activity?.requestedOrientation =
                if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            touch()
        },
        subtitles = { sheet = Sheet.SUBTITLE },
        more = { sheet = Sheet.MORE },
        back = onBack,
        togglePlay = { controller.togglePlay(); touch() },
    )

    if (state.audioOnly && current != null) {
        AudioModeScreen(
            media = current, state = state, ui = ui, controller = controller,
            onBackToVideo = { controller.setAudioOnly(false) },
            onToMp3 = {
                say("Saving audio…")
                scope.launch { val n = vm.extractAudio(current); say(if (n != null) "Saved to Music/QuickTube" else "Couldn't extract audio") }
            },
            onFavorite = { vm.favoriteRequest()?.let { favorite.launch(IntentSenderRequest.Builder(it).build()) } },
            onMode = { say(vm.nextMode().toast) },
            onMore = { sheet = Sheet.AUDIO_MORE },
        )
    } else Box(Modifier.fillMaxSize().background(Color.Black)) {
        val fitArea = !landscape && ui.aspect == AspectMode.FIT
        val status = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
        val nav = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
        VideoLayer(
            player, ui.aspect, ratio, zoom,
            Modifier.fillMaxSize().then(if (fitArea) Modifier.padding(top = status + TopBarHeight, bottom = nav + 36.dp) else Modifier),
        )
        Subtitles(controller, Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls) 200.dp else 48.dp))

        val gestures = rememberGestureHandler(
            controller, state.durationMs, audio, window, zoom, feedback,
            locked = { locked },
            onTap = {
                if (locked) lockFlash = System.nanoTime()
                else { controls = !controls; touch() }
            },
        )
        Box(Modifier.fillMaxSize().playerGestures(gestures))

        AnimatedVisibility(controls && !locked, enter = fadeIn(tween(220)), exit = fadeOut(tween(260))) {
            val position = rememberLivePosition(controller, controls)
            if (landscape) LandscapeControls(ui, state.isPlaying, current, position, state.durationMs, state.index, controller, actions)
            else PortraitControls(ui, fitArea, state.isPlaying, current, position, state.durationMs, state.index, controller, actions)
        }
        // Locked: only the lock button, flashed by a tap.
        AnimatedVisibility(locked && lockFlash != 0L, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(if (landscape) Alignment.BottomStart else Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                        .padding(start = if (landscape) 52.dp else 25.dp, bottom = if (landscape) 30.dp else 143.dp),
                ) { ControlCircle(PlayerIcons.Lock, "Unlock", actions.toggleLock, plain = true) }
            }
        }
        GestureOverlays(feedback, state.durationMs, landscape)
        toast?.let { MessagePill(Modifier.align(Alignment.TopCenter).padding(top = status + 91.dp)) { Text(it, color = Color.White, fontSize = 15.sp) } }
    }

    // Sheets and dialogs over either mode.
    AnimatedVisibility(sheet != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        when (sheet) {
            Sheet.SPEED -> SpeedSheet(state.speed, { controller.setSpeed(it) }, { sheet = null })
            Sheet.SUBTITLE -> SubtitleSheet(
                onSelect = { sheet = null; subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "text/*", "application/octet-stream")) },
                onDismiss = { sheet = null },
            )
            Sheet.AUDIO_TRACK -> AudioTrackSheet(controller) { sheet = null }
            Sheet.EQUALIZER -> EqualizerSheet(vm.equalizer()) { sheet = null }
            Sheet.AUDIO_MORE -> if (current != null) AudioMoreSheet(
                media = current,
                onEqualizer = { sheet = Sheet.EQUALIZER },
                onTimer = { sheet = Sheet.MORE },
                onSpeed = { sheet = Sheet.SPEED },
                onShare = { GalleryActions.share(context, listOf(current)) },
                onInfo = { sheet = null; info = true },
                onDelete = { sheet = null; delete(listOf(current)) },
                onDismiss = { sheet = null },
            )
            else -> MoreSheet(
                ui,
                onTimer = { vm.setTimer(it); if (it > 0) say("Stops in $it min") },
                onCustomTimer = { customTimer = true },
                onMode = { vm.setMode(it); say(it.toast) },
                actions = buildList {
                    add(SheetAction(QtIcons.AudioTrack, "Audio Track") { sheet = Sheet.AUDIO_TRACK })
                    add(SheetAction(QtIcons.Equalizer, "Equalizer") { sheet = Sheet.EQUALIZER })
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(
                        SheetAction(if (ui.favorite) QtIcons.Favorite else QtIcons.FavoriteBorder, "Favorite") {
                            vm.favoriteRequest()?.let { favorite.launch(IntentSenderRequest.Builder(it).build()) }
                        },
                    )
                    add(SheetAction(QtIcons.Share, "Share") { current?.let { GalleryActions.share(context, listOf(it)) } })
                    add(SheetAction(QtIcons.Info, "Info") { sheet = null; info = true })
                    add(SheetAction(QtIcons.TouchApp, "Gesture") { sheet = null; vm.showTutorial() })
                    add(SheetAction(QtIcons.Delete, "Delete") { sheet = null; current?.let { delete(listOf(it)) } })
                },
                onDismiss = { sheet = null },
            )
        }
    }
    if (info && current != null) InfoDialog(current, state.durationMs) { info = false }
    if (customTimer) CustomTimerDialog(ui.timerMinutes.takeIf { it > 0 } ?: 90, { vm.setTimer(it); say("Stops in $it min") }) { customTimer = false }
    if (ui.showTutorial && !state.audioOnly) GestureGuide(vm::tutorialSeen)
}

/** Everything a control button can do, so both layouts share one set of callbacks. */
private class PlayerActions(
    val toggleLock: () -> Unit,
    val toggleMute: () -> Unit,
    val pip: () -> Unit,
    val aspect: () -> Unit,
    val speed: () -> Unit,
    val audioOnly: () -> Unit,
    val rotate: () -> Unit,
    val subtitles: () -> Unit,
    val more: () -> Unit,
    val back: () -> Unit,
    val togglePlay: () -> Unit,
)

private fun aspectIcon(a: AspectMode) = when (a) {
    AspectMode.FIT -> PlayerIcons.AspectFit
    AspectMode.STRETCH -> PlayerIcons.AspectStretch
    AspectMode.CROP -> PlayerIcons.AspectCrop
}

@Composable
private fun ControlButtons(ui: GalleryPlayerUi, a: PlayerActions, plain: Boolean, spacing: androidx.compose.ui.unit.Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        ControlCircle(PlayerIcons.LockOpen, "Lock", a.toggleLock, plain)
        ControlCircle(if (ui.muted) PlayerIcons.Mute else PlayerIcons.Volume, "Mute", a.toggleMute, plain)
        ControlCircle(PlayerIcons.Pip, "Picture in picture", a.pip, plain)
        ControlCircle(aspectIcon(ui.aspect), "Aspect ratio", a.aspect, plain)
        ControlCircle(PlayerIcons.Speed, "Speed", a.speed, plain)
        ControlCircle(PlayerIcons.Headphones, "Play as audio", a.audioOnly, plain)
        ControlCircle(PlayerIcons.Rotate, "Rotate", a.rotate, plain)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleText(title: String, modifier: Modifier) {
    Text(
        title, modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200), color = Color.White,
        fontSize = 21.sp, fontWeight = FontWeight.Medium, maxLines = 1,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortraitControls(
    ui: GalleryPlayerUi, fitArea: Boolean, playing: Boolean, current: GalleryMedia?, position: State<Long>, durationMs: Long,
    index: Int, controller: PlayerController, a: PlayerActions,
) {
    val strip = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().then(if (fitArea) Modifier.background(Color.Black) else Modifier)) {
            Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility))
            Row(Modifier.fillMaxWidth().height(TopBarHeight).padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ControlCircle(QtIcons.ExpandMore, "Back", a.back, plain = true, size = 48.dp, iconSize = 34.dp)
                TitleText(current?.name?.substringBeforeLast('.').orEmpty(), Modifier.weight(1f).padding(start = 8.dp, end = 8.dp))
                ControlCircle(QtIcons.ClosedCaption, "Subtitles", a.subtitles, plain = true, size = 40.dp)
                ControlCircle(QtIcons.MoreVert, "More", a.more, plain = true, size = 40.dp)
            }
        }
        CenterPlayPause(playing, a.togglePlay, Modifier.align(Alignment.Center).offset(y = (-24).dp))
        // Each piece sits where the system player has it, measured from the navigation bar's top edge.
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 25.dp, bottom = 143.dp)) { ControlButtons(ui, a, plain = false, spacing = 12.dp) }
            PillSeekBar(position, durationMs, controller::seekTo, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 30.dp, end = 30.dp, bottom = 93.dp))
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 83.dp)) {
                LiveClock(position)
                Spacer(Modifier.weight(1f))
                Text(clock(durationMs), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
            VideoStrip(ui.queue, index, strip, controller::skipTo, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LandscapeControls(
    ui: GalleryPlayerUi, playing: Boolean, current: GalleryMedia?, position: State<Long>, durationMs: Long,
    index: Int, controller: PlayerController, a: PlayerActions,
) {
    val strip = rememberLazyListState()
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 36.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ControlCircle(QtIcons.ExpandMore, "Back", a.back, plain = true, size = 48.dp)
            TitleText(current?.name?.substringBeforeLast('.').orEmpty(), Modifier.weight(1f).padding(start = 14.dp, end = 24.dp))
            ControlCircle(QtIcons.ClosedCaption, "Subtitles", a.subtitles, plain = true, size = 40.dp)
            Spacer(Modifier.width(16.dp))
            ControlCircle(QtIcons.MoreVert, "More", a.more, plain = true, size = 40.dp)
            Spacer(Modifier.width(16.dp))
            BatteryClock()
        }
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(60.dp), verticalAlignment = Alignment.CenterVertically) {
            ControlCircle(QtIcons.Replay10, "Back 10 seconds", { controller.seekBy(-10_000) }, plain = true, size = 56.dp, iconSize = 40.dp)
            CenterPlayPause(playing, a.togglePlay, size = 56.dp)
            ControlCircle(QtIcons.Forward10, "Forward 10 seconds", { controller.seekBy(10_000) }, plain = true, size = 56.dp, iconSize = 40.dp)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            PillSeekBar(position, durationMs, controller::seekTo, Modifier.fillMaxWidth().padding(horizontal = 60.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 52.dp).offset(y = (-4).dp)) {
                LiveClock(position)
                Spacer(Modifier.weight(1f))
                Text(clock(durationMs), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(start = 46.dp)) { ControlButtons(ui, a, plain = true, spacing = 9.dp) }
                VideoStrip(ui.queue, index, strip, controller::skipTo, Modifier.weight(1f).padding(start = 40.dp))
            }
        }
    }
}

@Composable
private fun BatteryClock() {
    val context = LocalContext.current
    val time = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date()) }
    val level = remember {
        (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(QtIcons.Battery, "Battery $level%", tint = Color.White, modifier = Modifier.size(20.dp))
        Text(time.substringBefore(' '), color = Color.White, fontSize = 15.sp)
    }
}

/** Live subtitle text over the video. */
@Composable
private fun Subtitles(controller: PlayerController, modifier: Modifier) {
    val text by controller.cues.collectAsStateWithLifecycle()
    if (text.isNotBlank()) Text(
        text, color = Color.White, fontSize = 17.sp,
        modifier = modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** What the gesture overlays show right now. */
private class GestureFeedback {
    var level by mutableStateOf<Pair<Boolean, Float>?>(null) // (brightness?, 0..1)
    var seekPreview by mutableStateOf<Long?>(null)
    var holding by mutableStateOf(false)
    var doubleTap by mutableStateOf<TapZone?>(null)
    var doubleTapAt by mutableLongStateOf(0L)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GestureOverlays(f: GestureFeedback, durationMs: Long, landscape: Boolean) {
    LaunchedEffect(f.doubleTapAt) { if (f.doubleTap != null) { delay(650); f.doubleTap = null } }
    val status = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize()) {
        f.level?.let { (brightness, v) ->
            // Shown on the side away from the finger: brightness (left swipe) on the right, volume on the left.
            LevelPill(
                v, if (brightness) QtIcons.LightMode else if (v <= 0f) QtIcons.VolumeOff else QtIcons.VolumeUp,
                Modifier.align(if (brightness) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 24.dp),
            )
        }
        f.seekPreview?.let { p ->
            SeekTimePill(p, durationMs, Modifier.align(Alignment.TopCenter).padding(top = status + 93.dp))
            Row(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(start = 30.dp, end = 30.dp, bottom = if (landscape) 24.dp else 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(clock(p), color = Color.White, fontSize = 12.sp)
                PillSeekBar(rememberUpdatedState(p), durationMs, {}, Modifier.weight(1f).padding(horizontal = 16.dp), thickness = 5.dp)
                Text(clock(durationMs), color = Color.White, fontSize = 12.sp)
            }
        }
        if (f.holding) MessagePill(Modifier.align(Alignment.TopCenter).padding(top = status + 93.dp)) {
            Icon(QtIcons.Play, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            Text("2.0X Speed", color = Color.White, fontSize = 15.sp)
        }
        f.doubleTap?.let { zone ->
            if (zone != TapZone.CENTER) MessagePill(
                Modifier.align(if (zone == TapZone.RIGHT) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = if (landscape) 160.dp else 60.dp),
            ) {
                if (zone == TapZone.LEFT) Icon(QtIcons.FastRewind, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(if (zone == TapZone.RIGHT) "+10S" else "-10S", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (zone == TapZone.RIGHT) Icon(QtIcons.FastForward, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun rememberGestureHandler(
    controller: PlayerController,
    durationMs: Long,
    audio: AudioManager,
    window: android.view.Window?,
    videoZoom: VideoZoom,
    f: GestureFeedback,
    locked: () -> Boolean,
    onTap: () -> Unit,
): PlayerGestureHandler {
    val context = LocalContext.current
    val tap by rememberUpdatedState(onTap)
    val duration by rememberUpdatedState(durationMs)
    return remember {
        object : PlayerGestureHandler {
            var holdPrevious = 1f
            var level = 0f
            var seekBase = 0L
            var seekAcc = 0f
            var lastSeek = 0L
            override val enabled get() = !locked()
            override val zoomed get() = videoZoom.zoomed
            override fun onTap() = tap()
            override fun onDoubleTap(zone: TapZone) {
                when (zone) {
                    TapZone.LEFT -> controller.seekBy(-10_000)
                    TapZone.RIGHT -> controller.seekBy(10_000)
                    TapZone.CENTER -> controller.togglePlay()
                }
                f.doubleTap = zone
                f.doubleTapAt = System.nanoTime()
            }
            override fun onHoldStart() {
                holdPrevious = controller.state.value.speed
                controller.setSpeed(2f)
                f.holding = true
            }
            override fun onHoldEnd() {
                if (!f.holding) return
                controller.setSpeed(holdPrevious)
                f.holding = false
            }
            override fun onVerticalStart(left: Boolean) {
                level = if (left) {
                    window?.attributes?.screenBrightness?.takeIf { it >= 0 }
                        ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                } else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                f.level = left to level
            }
            override fun onVertical(left: Boolean, delta: Float) {
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
                seekBase = controller.currentPositionMs
                seekAcc = 0f
                f.seekPreview = seekBase
            }
            override fun onSeek(delta: Float) {
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
            override fun onTransform(zoom: Float, pan: Offset, centroid: Offset) = videoZoom.transform(zoom, pan, centroid)
        }
    }
}

@Composable
private fun rememberPipMode(activity: Activity?): Boolean {
    val component = activity as? ComponentActivity
    var pip by remember { mutableStateOf(component?.isInPictureInPictureMode == true) }
    DisposableEffect(component) {
        val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { pip = it.isInPictureInPictureMode }
        component?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { component?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return pip
}

private const val PIP_PREV = "com.edureminder.quicktube.gallery.PIP_PREV"
private const val PIP_PLAY = "com.edureminder.quicktube.gallery.PIP_PLAY"
private const val PIP_NEXT = "com.edureminder.quicktube.gallery.PIP_NEXT"

private fun pipParams(activity: Activity, ratio: Float, playing: Boolean): PictureInPictureParams {
    fun action(icon: Int, title: String, action: String, code: Int) = RemoteAction(
        Icon.createWithResource(activity, icon), title, title,
        PendingIntent.getBroadcast(activity, code, Intent(action).setPackage(activity.packageName), PendingIntent.FLAG_IMMUTABLE),
    )
    val r = if (ratio > 0f) ratio.coerceIn(1 / 2.39f, 2.39f) else 16f / 9f
    return PictureInPictureParams.Builder()
        .setAspectRatio(Rational((r * 1000).roundToInt(), 1000))
        .setActions(
            listOf(
                action(android.R.drawable.ic_media_previous, "Previous", PIP_PREV, 1),
                if (playing) action(android.R.drawable.ic_media_pause, "Pause", PIP_PLAY, 2)
                else action(android.R.drawable.ic_media_play, "Play", PIP_PLAY, 2),
                action(android.R.drawable.ic_media_next, "Next", PIP_NEXT, 3),
            ),
        )
        .build()
}

private fun enterPip(activity: Activity, ratio: Float, playing: Boolean) {
    runCatching { activity.enterPictureInPictureMode(pipParams(activity, ratio, playing)) }
        .onFailure { Toast.makeText(activity, "Picture-in-picture isn't available", Toast.LENGTH_SHORT).show() }
}

/** Previous / play-pause / next buttons on the picture-in-picture window. */
@Composable
private fun PipActions(activity: Activity?, controller: PlayerController, playing: Boolean, inPip: Boolean, ratio: Float) {
    if (activity == null) return
    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    PIP_PREV -> controller.previous()
                    PIP_PLAY -> controller.togglePlay()
                    PIP_NEXT -> controller.next()
                }
            }
        }
        val filter = IntentFilter().apply { addAction(PIP_PREV); addAction(PIP_PLAY); addAction(PIP_NEXT) }
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { activity.unregisterReceiver(receiver) }
    }
    LaunchedEffect(playing, inPip) {
        if (inPip) runCatching { activity.setPictureInPictureParams(pipParams(activity, ratio, playing)) }
    }
}
