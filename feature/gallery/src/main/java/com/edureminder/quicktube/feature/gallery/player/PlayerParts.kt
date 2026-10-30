package com.edureminder.quicktube.feature.gallery.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.feature.gallery.GalleryThumbnail

internal val PillGray = Color(0x99505050)
/** The control row's translucent grey discs. */
internal val CircleGray = Color(0x8C4A4A4A)
internal val TrackGray = Color(0x4DFFFFFF)

/** The player position, read from the controller every frame while [active] (smooth bars without recomposing them). */
@Composable
fun rememberLivePosition(controller: PlayerController, active: Boolean): State<Long> {
    val pos = remember { mutableLongStateOf(controller.currentPositionMs) }
    LaunchedEffect(active) {
        pos.longValue = controller.currentPositionMs
        while (active) {
            withFrameMillis { }
            pos.longValue = controller.currentPositionMs
        }
    }
    return pos
}

/** "00:35" style time, as the system player shows it (h:mm:ss past an hour). */
fun clock(ms: Long): String = Format.durationMs(ms.coerceAtLeast(0)).let { if (it.length == 4) "0$it" else it }

/**
 * Thick pill seek bar with no thumb, like the system player's. Tap or drag anywhere to scrub; [onScrub] reports the
 * preview position while dragging (null when done) and [onSeek] the final one.
 */
@Composable
fun PillSeekBar(
    position: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    thickness: Dp = 10.dp,
    onScrub: (Long?) -> Unit = {},
) {
    var scrub by remember { mutableFloatStateOf(-1f) }
    val seek by rememberUpdatedState(onSeek)
    val scrubbing by rememberUpdatedState(onScrub)
    val dur = durationMs.coerceAtLeast(1)
    Box(
        modifier.height(28.dp)
            .pointerInput(dur) {
                detectTapGestures { o -> seek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) }
            }
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f); scrubbing((scrub * dur).toLong()) },
                    onHorizontalDrag = { c, _ -> scrub = (c.position.x / size.width).coerceIn(0f, 1f); scrubbing((scrub * dur).toLong()) },
                    onDragEnd = { seek((scrub * dur).toLong()); scrub = -1f; scrubbing(null) },
                    onDragCancel = { scrub = -1f; scrubbing(null) },
                )
            }
            .drawBehind {
                val h = thickness.toPx()
                val top = (size.height - h) / 2
                val r = CornerRadius(h / 2)
                drawRoundRect(TrackGray, Offset(0f, top), Size(size.width, h), r)
                val f = if (scrub >= 0) scrub else (position.value.toFloat() / dur).coerceIn(0f, 1f)
                if (f > 0f) drawRoundRect(Color.White, Offset(0f, top), Size(maxOf(h, size.width * f), h), r)
            },
    )
}

/** Live "00:35" that recomposes once a second, not every frame. */
@Composable
fun LiveClock(position: State<Long>, color: Color = Color.White.copy(alpha = 0.6f), fontSize: Int = 12) {
    val seconds by remember(position) { derivedStateOf { position.value / 1000 } }
    Text(clock(seconds * 1000), color = color, fontSize = fontSize.sp)
}

/** Round translucent control (33dp) of the portrait control row; [plain] drops the circle (landscape and lock). */
@Composable
fun ControlCircle(
    icon: ImageVector, description: String, onClick: () -> Unit, plain: Boolean = false, size: Dp = 33.dp,
    iconSize: Dp = if (plain) 24.dp else 25.dp,
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(if (plain) Modifier else Modifier.background(CircleGray))
            .pressable(scaleDown = 0.86f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** Grey pill used for every transient message (aspect toasts, seek time, 2.0X, +10S). */
@Composable
fun MessagePill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.height(36.dp).clip(CircleShape).background(PillGray).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
fun SeekTimePill(previewMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val accent = LocalQtExtras.current.brandStart
    MessagePill(modifier) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = accent)) { append(clock(previewMs)) }
                append("/" + clock(durationMs))
            },
            color = Color.White, fontSize = 15.sp,
        )
    }
}

/** Vertical level pill for brightness / volume, shown on the side opposite the finger. */
@Composable
fun LevelPill(level: Float, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier.width(36.dp).height(255.dp).clip(RoundedCornerShape(18.dp)).background(PillGray).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.weight(1f).width(6.dp).drawBehind {
                val r = CornerRadius(size.width / 2)
                drawRoundRect(TrackGray, cornerRadius = r)
                val h = size.height * level.coerceIn(0f, 1f)
                drawRoundRect(Color.White, Offset(0f, size.height - h), Size(size.width, h), r)
            },
        )
        Spacer(Modifier.height(12.dp))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/**
 * The strip of other videos under the seek bar: rounded thumbnails, the playing one framed in white. Tapping one
 * switches to it; the strip follows the current video.
 */
@Composable
fun VideoStrip(queue: List<GalleryMedia>, current: Int, state: LazyListState, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(current) { if (current >= 0) state.animateScrollToItem(current) }
    LazyRow(
        modifier, state,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(queue, key = { _, m -> m.id }) { i, media ->
            Box(
                Modifier.width(85.dp).height(47.dp)
                    .then(
                        if (i == current) Modifier.drawBehind {
                            val inset = 6.5.dp.toPx()
                            val stroke = 3.dp.toPx()
                            drawRoundRect(
                                Color.White, Offset(-inset + stroke / 2, -inset + stroke / 2),
                                Size(size.width + 2 * inset - stroke, size.height + 2 * inset - stroke),
                                CornerRadius(12.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                            )
                        } else Modifier,
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .pressable(scaleDown = 0.94f) { onSelect(i) },
            ) {
                GalleryThumbnail(media, Modifier.fillMaxWidth().fillMaxHeight())
            }
        }
    }
}

/** Big centre play / pause glyph (no circle, as in the system player). */
@Composable
fun CenterPlayPause(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    Box(modifier.size(size).clip(CircleShape).pressable(scaleDown = 0.85f, bounded = false, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(if (playing) QtIcons.Pause else QtIcons.Play, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(size))
    }
}

/** Gives a [Modifier] a white rounded border (strip selection, chips). */
fun Modifier.whiteOutline(radius: Dp) = border(3.dp, Color.White, RoundedCornerShape(radius))

/** Shorthand for the visible / hidden state of a transient pill. */
class Flash { var text by mutableStateOf<String?>(null) }
