package com.edureminder.quicktube.feature.player

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.media.PlayItem
import com.edureminder.quicktube.core.media.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * The music player: the song's cover large on a blurred, cover-coloured background, a seek bar that thickens while
 * dragged, and a play button that morphs between circle and rounded square. Swipe the cover to change song;
 * "Up next" shows the queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(onBack: () -> Unit) {
    val controller = koinInject<PlayerController>()
    val sheet = koinInject<DownloadSheetController>()
    val state by controller.state.collectAsStateWithLifecycle()
    val current = state.current
    BackHandler(onBack = onBack)
    // Closing the song (from the notification) leaves nothing to show.
    LaunchedEffect(current == null) { if (current == null) onBack() }
    if (current == null) return

    val accent = rememberArtworkColor(current.artwork)
    val base by animateColorAsState(accent, tween(900), label = "accent")
    var queueOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
        // Blurred cover behind everything (Android 12+ blurs; older phones get a soft, dimmed copy), tinted by its own colour.
        AsyncImage(
            current.artwork, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (Build.VERSION.SDK_INT >= 31) 0.85f else 0.35f; scaleX = 1.3f; scaleY = 1.3f }
                .blur(90.dp),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to base.copy(alpha = 0.55f), 0.55f to base.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.92f))))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            TopBar(onBack, onDownload = { current.historyKey?.let(sheet::open) }, onSave = { saveOpen = true }.takeIf { current.historyKey != null })
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight - 8.dp)
                Artwork(current, state.isPlaying, Modifier.size(side), onNext = controller::next, onPrevious = controller::previous)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                current.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
            )
            Text(
                current.artist.ifBlank { "YouTube" }, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(22.dp))
            SeekBar(state.positionMs, state.durationMs, state.bufferedMs, onSeek = controller::seekTo)
            Spacer(Modifier.height(14.dp))
            Controls(
                playing = state.isPlaying, buffering = state.isBuffering, shuffle = state.shuffle, repeat = state.repeatMode, accent = base,
                onPlay = controller::togglePlay, onNext = controller::next, onPrevious = controller::previous,
                onShuffle = { controller.setShuffle(!state.shuffle) },
                onRepeat = {
                    controller.setRepeat(when (state.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF })
                },
            )
            state.error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            Row(
                Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp).clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f)).pressable(scaleDown = 0.95f, onClick = { queueOpen = true })
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(QtIcons.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(20.dp))
                val next = state.queue.getOrNull(state.index + 1)
                Text(
                    if (next != null) "Up next · ${next.title}" else "Up next", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp).widthIn(max = 260.dp),
                )
            }
        }
    }

    val page = current.historyKey
    if (saveOpen && page != null) com.edureminder.quicktube.core.playerui.SaveToPlaylistSheet(
        com.edureminder.quicktube.core.model.MediaItem(url = page, title = current.title, thumbnail = current.artwork, uploader = current.artist),
        isMusic = true,
    ) { saveOpen = false }

    if (queueOpen) ModalBottomSheet(
        onDismissRequest = { queueOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Color(0xFF17171A),
    ) {
        Text("Up next", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        val list = rememberLazyListState(initialFirstVisibleItemIndex = state.index.coerceAtLeast(0))
        LazyColumn(state = list, modifier = Modifier.fillMaxHeight(0.7f)) {
            itemsIndexed(state.queue, key = { i, it -> "$i:${it.id}" }) { i, item ->
                QueueRow(item, playing = i == state.index, accent = base) { controller.skipTo(i) }
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, onDownload: () -> Unit, onSave: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(QtIcons.ExpandMore, "Close", onBack)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PLAYING FROM", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Music", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        if (onSave != null) { RoundButton(QtIcons.PlaylistAdd, "Save to playlist", onSave); Spacer(Modifier.width(8.dp)) }
        RoundButton(QtIcons.Download, "Download", onDownload)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, size: Int = 44, tint: Color = Color.White) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).pressable(scaleDown = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size((size * 0.55f).dp)) }
}

/** The cover: breathes slightly smaller while paused, follows the finger sideways and changes song past a third. */
@Composable
private fun Artwork(item: PlayItem, playing: Boolean, modifier: Modifier, onNext: () -> Unit, onPrevious: () -> Unit) {
    val scale by animateFloatAsState(if (playing) 1f else 0.88f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "art")
    var drag by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val settle by animateFloatAsState(drag, spring(dampingRatio = 0.85f, stiffness = 380f), label = "drag")
    Box(
        modifier.onSizeChanged { width = it.width.toFloat() }
            .offset { IntOffset(settle.roundToInt(), 0) }
            .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = settle / width * 6f }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag < -width / 3) onNext() else if (drag > width / 3) onPrevious()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, d -> drag += d }
            }
            .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(22.dp)).background(Color(0xFF26262B)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(QtIcons.MusicNote, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(72.dp))
        AnimatedContent(item.artwork, transitionSpec = { (fadeIn(tween(350)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(250)) }, label = "cover") { art ->
            AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Thin bar that thickens under the finger; times below. */
@Composable
private fun SeekBar(position: Long, duration: Long, buffered: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableFloatStateOf(1f) }
    val track by animateDpAsState(if (dragging != null) 12.dp else 6.dp, label = "track")
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column {
        Box(
            Modifier.fillMaxWidth().height(28.dp).onSizeChanged { width = it.width.toFloat() }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = (it.x / width).coerceIn(0f, 1f) },
                        onDragEnd = { dragging?.let { f -> onSeek((f * duration).toLong()) }; dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ -> dragging = (change.position.x / width).coerceIn(0f, 1f) }
                }
                .pointerInput(duration) { detectTapGestures { onSeek(((it.x / width).coerceIn(0f, 1f) * duration).toLong()) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))) {
                if (duration > 0) Box(Modifier.fillMaxWidth((buffered.toFloat() / duration).coerceIn(0f, 1f)).fillMaxHeight().background(Color.White.copy(alpha = 0.18f)))
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(50)).background(Color.White))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Format.duration(((dragging?.times(duration))?.toLong() ?: position) / 1000), color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(if (duration > 0) Format.duration(duration / 1000) else "--:--", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Controls(
    playing: Boolean, buffering: Boolean, shuffle: Boolean, repeat: Int, accent: Color,
    onPlay: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        ToggleIcon(QtIcons.Shuffle, "Shuffle", shuffle, onShuffle)
        Icon(QtIcons.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(52.dp).clip(CircleShape).pressable(scaleDown = 0.85f, onClick = onPrevious).padding(8.dp))
        // Round while paused, a rounded square while playing, like Flow's player.
        val corner by animateDpAsState(if (playing) 26.dp else 40.dp, spring(dampingRatio = 0.6f), label = "corner")
        Box(
            Modifier.size(80.dp).shadow(12.dp, RoundedCornerShape(corner)).clip(RoundedCornerShape(corner)).background(Color.White)
                .pressable(scaleDown = 0.9f, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            if (buffering && !playing) androidx.compose.material3.CircularProgressIndicator(color = accent.darken(), strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
            else Icon(if (playing) QtIcons.Pause else QtIcons.Play, if (playing) "Pause" else "Play", tint = Color(0xFF111114), modifier = Modifier.size(40.dp))
        }
        Icon(QtIcons.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(52.dp).clip(CircleShape).pressable(scaleDown = 0.85f, onClick = onNext).padding(8.dp))
        ToggleIcon(if (repeat == Player.REPEAT_MODE_ONE) QtIcons.RepeatOne else QtIcons.Repeat, "Repeat", repeat != Player.REPEAT_MODE_OFF, onRepeat)
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).pressable(scaleDown = 0.85f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (on) Color.White else Color.White.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
        if (on) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(4.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun QueueRow(item: PlayItem, playing: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (playing) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .pressable(scaleDown = 0.98f, role = null, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(item.artwork, null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF26262B)))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(item.title, color = if (playing) accent.lighten() else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium)
            if (item.artist.isNotBlank()) Text(item.artist, color = Color.White.copy(alpha = 0.6f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
        }
        if (playing) Icon(QtIcons.Equalizer, null, tint = accent.lighten(), modifier = Modifier.size(22.dp))
    }
}

/** The cover's most colourful tone (its average when it's grey), for the background tint; grey until it loads. */
@Composable
private fun rememberArtworkColor(url: String?): Color {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf(Color(0xFF3A3A44)) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                coil3.SingletonImageLoader.get(context)
                    .execute(ImageRequest.Builder(context).data(url).size(64).allowHardware(false).build())
                    .image?.toBitmap()
            }.getOrNull()
        } ?: return@LaunchedEffect
        color = withContext(Dispatchers.Default) { dominant(bitmap) }
    }
    return color
}

private fun dominant(b: Bitmap): Color {
    var best = 0f; var pick = 0
    var r = 0L; var g = 0L; var bl = 0L; var n = 0
    val hsv = FloatArray(3)
    val step = maxOf(1, minOf(b.width, b.height) / 24)
    for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
        val p = b.getPixel(x, y)
        android.graphics.Color.colorToHSV(p, hsv)
        // Vivid, mid-bright pixels make the best tint.
        val score = hsv[1] * (1f - kotlin.math.abs(hsv[2] - 0.6f))
        if (score > best) { best = score; pick = p }
        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
    }
    val c = if (best > 0.25f) pick else android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    android.graphics.Color.colorToHSV(c, hsv)
    hsv[2] = hsv[2].coerceIn(0.35f, 0.7f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun Color.lighten() = Color(red + (1 - red) * 0.45f, green + (1 - green) * 0.45f, blue + (1 - blue) * 0.45f)
private fun Color.darken() = Color(red * 0.6f, green * 0.6f, blue * 0.6f)
