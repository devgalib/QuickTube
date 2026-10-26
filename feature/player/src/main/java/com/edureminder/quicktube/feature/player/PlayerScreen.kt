package com.edureminder.quicktube.feature.player

import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.pressable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.playerui.FullscreenEffect
import com.edureminder.quicktube.core.playerui.SeekBar
import com.edureminder.quicktube.core.playerui.SpeedButton
import com.edureminder.quicktube.core.playerui.VideoPlayerBox
import org.koin.compose.koinInject

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val controller = koinInject<PlayerController>()
    val state by controller.state.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    LaunchedEffect(state.current) { if (state.current == null) onBack() }
    FullscreenEffect(fullscreen)
    BackHandler(fullscreen) { fullscreen = false }
    val current = state.current ?: return

    if (fullscreen) {
        VideoPlayerBox(controller, state, Modifier.fillMaxSize(), fullscreen = true, onToggleFullscreen = { fullscreen = false })
        return
    }
    val showVideo = current.isVideo && !state.audioOnly
    val brand = com.edureminder.quicktube.core.designsystem.LocalQtExtras.current.brand
    val brandStart = com.edureminder.quicktube.core.designsystem.LocalQtExtras.current.brandStart

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            QtIconButton(QtIcons.ExpandMore, "Collapse", onBack)
            Text(if (state.queue.size > 1) "${state.index + 1} / ${state.queue.size}" else "", Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            if (showVideo) {
                VideoPlayerBox(
                    controller, state,
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .aspectRatio(if (state.videoWidth > 0 && state.videoHeight > 0) (state.videoWidth.toFloat() / state.videoHeight).coerceIn(0.5f, 2f) else 16f / 9f),
                    onToggleFullscreen = { fullscreen = true },
                    overlayControls = false,
                )
            } else {
                Box(
                    Modifier.fillMaxWidth(0.82f).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(QtIcons.MusicNote, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                    if (current.artwork != null) AsyncImage(current.artwork, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Text(
            current.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        SeekBar(state, controller, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            Text(Format.durationMs(state.positionMs), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(Format.durationMs(state.durationMs), style = MaterialTheme.typography.labelSmall)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                SpeedButton(state.speed) { controller.setSpeed(it) }
            }
            QtIconButton(QtIcons.SkipPrevious, "Previous", controller::previous, size = 56.dp, iconSize = 34.dp)
            Box(
                Modifier.size(76.dp).shadow(16.dp, CircleShape, ambientColor = brandStart.copy(alpha = .5f), spotColor = brandStart.copy(alpha = .5f))
                    .clip(CircleShape).background(brand).pressable(scaleDown = 0.9f, haptic = true, onClick = controller::togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (state.isPlaying) QtIcons.Pause else QtIcons.Play, "Play/Pause",
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
            }
            QtIconButton(QtIcons.SkipNext, "Next", controller::next, size = 56.dp, iconSize = 34.dp)
            if (current.isVideo) QtIconButton(QtIcons.Fullscreen, "Fullscreen", { if (state.audioOnly) controller.setAudioOnly(false); fullscreen = true },
                container = MaterialTheme.colorScheme.surfaceContainerHigh)
            else QtIconButton(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) QtIcons.RepeatOne else QtIcons.Repeat, "Repeat",
                {
                    controller.setRepeat(when (state.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF })
                },
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).pressable(scaleDown = 0.97f, onClick = { showQueue = true }).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(QtIcons.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary)
                Text(if (current.isVideo) "All videos" else "All music", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 10.dp))
                if (state.queue.size > 1) Text("  ${state.queue.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            QtIconButton(QtIcons.Shuffle, "Shuffle", { controller.setShuffle(!state.shuffle) },
                tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (current.isVideo) Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (state.audioOnly) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary)
                    .pressable(scaleDown = 0.94f, haptic = true, onClick = { controller.setAudioOnly(!state.audioOnly) }).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val c = if (state.audioOnly) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
                Icon(if (state.audioOnly) QtIcons.Videocam else QtIcons.Headphones, null, tint = c, modifier = Modifier.size(18.dp))
                Text(if (state.audioOnly) "Watch" else "Listen", color = c, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showQueue) ModalBottomSheet(onDismissRequest = { showQueue = false }) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            itemsIndexed(state.queue) { i, item ->
                Row(
                    Modifier.fillMaxWidth().clickable { controller.skipTo(i); showQueue = false }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 88.dp, height = 50.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        if (item.artwork != null) AsyncImage(item.artwork, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(
                        item.title, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (i == state.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (i == state.index) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Floating card shown above the bottom navigation while something is playing. */
@Composable
fun MiniPlayer(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val controller = koinInject<PlayerController>()
    val state by controller.state.collectAsStateWithLifecycle()
    val current = state.current ?: return
    val brand = com.edureminder.quicktube.core.designsystem.LocalQtExtras.current.brand
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.padding(horizontal = 14.dp).padding(top = 6.dp).fillMaxWidth()
            .shadow(5.dp, shape, ambientColor = Color.Black.copy(.4f), spotColor = Color.Black.copy(.4f))
            .clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pressable(scaleDown = 0.98f, onClick = onExpand),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                Icon(QtIcons.MusicNote, null, tint = Color.White)
                if (current.artwork != null) AsyncImage(current.artwork, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (current.artist.isNotBlank()) Text(current.artist, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            QtIconButton(
                if (state.isPlaying) QtIcons.Pause else QtIcons.Play, "Play/Pause", controller::togglePlay,
                tint = MaterialTheme.colorScheme.onPrimary, container = MaterialTheme.colorScheme.primary, size = 40.dp, iconSize = 22.dp,
            )
            QtIconButton(QtIcons.Close, "Close", controller::stop, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f)
                .height(3.dp).background(brand))
        }
    }
}
