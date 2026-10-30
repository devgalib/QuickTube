package com.edureminder.quicktube.feature.gallery.player

import android.media.audiofx.Equalizer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.GalleryMedia
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal val SheetColor = Color(0xEB161616)
private val Faint = Color.White.copy(alpha = 0.08f)
private val Dim = Color.White.copy(alpha = 0.6f)

/** Dark translucent bottom sheet over the video, rounded on top; tapping above it closes it. */
@Composable
fun PlayerSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }, contentAlignment = Alignment.BottomCenter) {
        Column(
            // Every sheet in the system player shares one height: its top edge sits at ~38% of the screen.
            Modifier.fillMaxWidth().fillMaxHeight(0.62f).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(SheetColor)
                // Swallow taps inside the sheet.
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding(),
            content = content,
        )
    }
}

private val SpeedPresets = listOf(0.5f, 1f, 1.25f, 2f)
private fun speedLabel(s: Float) = (if (s * 100 % 10 == 0f) "%.1fX" else "%.2fX").format(Locale.US, s)

/** Preset chips plus a ruler from 0.25X to 4.0X in 0.25 steps, as in the system player. */
@Composable
fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    val accent = LocalQtExtras.current.brand
    PlayerSheet(onDismiss) {
        Spacer(Modifier.height(160.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
            SpeedPresets.forEach { s ->
                val on = s == speed
                Box(
                    Modifier.width(59.dp).height(38.dp).clip(RoundedCornerShape(8.dp))
                        .then(if (on) Modifier.background(accent) else Modifier.background(Faint))
                        .pressable(scaleDown = 0.92f) { onSpeed(s) },
                    contentAlignment = Alignment.Center,
                ) { Text(speedLabel(s), color = if (on) Color.White else Dim, fontSize = 14.sp) }
            }
        }
        Spacer(Modifier.height(70.dp))
        SpeedRuler(speed, onSpeed, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(72.dp))
    }
}

@Composable
private fun SpeedRuler(speed: Float, onSpeed: (Float) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalQtExtras.current.brandStart
    val set by rememberUpdatedState(onSpeed)
    BoxWithConstraints(modifier.fillMaxWidth().height(106.dp).clip(RoundedCornerShape(16.dp)).background(Faint)) {
        val edge = 23.dp
        val span = maxWidth - edge * 2
        fun xOf(s: Float) = edge + span * ((s - 0.25f) / 3.75f)
        fun pick(px: Float, width: Float, edgePx: Float) =
            (((px - edgePx) / (width - 2 * edgePx)).coerceIn(0f, 1f) * 15).roundToInt().let { 0.25f + it * 0.25f }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o -> set(pick(o.x, size.width.toFloat(), edge.toPx())) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { c, _ -> set(pick(c.position.x, size.width.toFloat(), edge.toPx())) }
                }
                .drawBehind {
                    val e = edge.toPx()
                    val step = (size.width - 2 * e) / 15
                    val cy = 59.dp.toPx()
                    for (i in 0..15) {
                        val s = 0.25f + i * 0.25f
                        val major = (i + 1) % 4 == 0
                        val x = e + step * i
                        drawLine(
                            if (major) Color.White else Color.White.copy(alpha = 0.4f), Offset(x, cy - 8.dp.toPx()), Offset(x, cy + 8.dp.toPx()),
                            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                        )
                        if (s == speed) drawLine(accent, Offset(x, cy - 15.dp.toPx()), Offset(x, cy + 15.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
                    }
                },
        )
        Text(
            speedLabel(speed), color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.offset(x = xOf(speed) - 22.dp, y = 14.dp).width(44.dp), textAlign = TextAlign.Center,
        )
        listOf(1f, 2f, 3f, 4f).forEach { s ->
            Text(
                speedLabel(s), color = Dim, fontSize = 10.sp, textAlign = TextAlign.Center,
                modifier = Modifier.offset(x = xOf(s) - 20.dp, y = 80.dp).width(40.dp),
            )
        }
    }
}

/** Off / 15 / 30 / 45 / 60 / Custom, then the four play modes, then the action grid (system player's ⋮ sheet). */
@Composable
fun MoreSheet(
    ui: GalleryPlayerUi,
    onTimer: (Int) -> Unit,
    onCustomTimer: () -> Unit,
    onMode: (PlayMode) -> Unit,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    PlayerSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(30.dp))
            Text("Timer", color = Dim, fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))
            val presets = listOf(0, 15, 30, 45, 60)
            val selected = if (ui.timerMinutes == 0) 0 else presets.indexOf(ui.timerMinutes).takeIf { it > 0 } ?: 5
            Segmented(
                count = 6, selected = selected,
                onSelect = { i -> if (i == 5) onCustomTimer() else onTimer(presets[i]) },
            ) { i, on ->
                val text = when (i) { 0 -> "Off"; 5 -> if (selected == 5) "${ui.timerMinutes}m" else "Custom"; else -> "${presets[i]}" }
                Text(text, color = if (on) Color.Black else Dim, fontSize = 14.sp, maxLines = 1, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
            Spacer(Modifier.height(36.dp))
            Text(ui.mode.label, color = Dim, fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))
            Segmented(count = 4, selected = ui.mode.ordinal, onSelect = { onMode(PlayMode.entries[it]) }) { i, on ->
                Icon(modeIcon(PlayMode.entries[i]), PlayMode.entries[i].label, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(30.dp))
            actions.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { a ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).pressable(onClick = a.onClick).padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(a.icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(a.label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

data class SheetAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

fun modeIcon(mode: PlayMode) = when (mode) {
    PlayMode.REPEAT_ALL -> QtIcons.Repeat
    PlayMode.AUTOPLAY_OFF -> QtIcons.Play
    PlayMode.REPEAT_ONE -> QtIcons.RepeatOne
    PlayMode.SHUFFLE -> QtIcons.Shuffle
}

/** Rounded track with a white pill on the selected segment. */
@Composable
private fun Segmented(count: Int, selected: Int, onSelect: (Int) -> Unit, content: @Composable (Int, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(14.dp)).background(Faint).padding(3.dp)) {
        repeat(count) { i ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(11.dp))
                    .then(if (on) Modifier.background(Color.White) else Modifier)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { content(i, on) }
        }
    }
}

@Composable
fun SubtitleSheet(onSelect: () -> Unit, onDismiss: () -> Unit) {
    PlayerSheet(onDismiss) {
        Text("Subtitle", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(start = 24.dp, top = 34.dp, bottom = 18.dp))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 24.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Select Subtitle", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(QtIcons.ChevronRight, null, tint = Dim, modifier = Modifier.size(20.dp))
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = Color.White.copy(alpha = 0.12f))
        Spacer(Modifier.height(120.dp))
    }
}

/** Centred card with label / value rows and a full-width accent OK, as the system player's Info. */
@Composable
fun InfoDialog(media: GalleryMedia, durationMs: Long, onDismiss: () -> Unit) {
    val rows = remember(media, durationMs) {
        listOf(
            "Name" to media.name,
            "Type" to media.mimeType,
            "Duration" to clock(if (durationMs > 0) durationMs else media.durationMs),
            "Size" to Format.bytes(media.sizeBytes),
            "Date" to SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(media.dateMs)),
            "Path" to media.path.substringBeforeLast('/'),
        )
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF232427)).padding(horizontal = 24.dp, vertical = 22.dp)) {
            Text("Info", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            rows.forEach { (k, v) ->
                Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                    Text("$k : ", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    Text(v, color = Color.White, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(LocalQtExtras.current.brand).pressable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Text("OK", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun AudioTrackSheet(controller: PlayerController, onDismiss: () -> Unit) {
    val tracks = remember { controller.audioTracks() }
    PlayerSheet(onDismiss) {
        Text("Audio Track", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(start = 24.dp, top = 30.dp, bottom = 12.dp))
        if (tracks.isEmpty()) Text("No audio track", color = Dim, modifier = Modifier.padding(24.dp))
        tracks.forEach { t ->
            Row(
                Modifier.fillMaxWidth().clickable { controller.selectAudioTrack(t); onDismiss() }.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (t.selected) Icon(QtIcons.Check, null, tint = LocalQtExtras.current.brandStart)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** Android's built-in equalizer on the player's audio session: on/off, presets and per-band levels. */
@Composable
fun EqualizerSheet(eq: Equalizer?, onDismiss: () -> Unit) {
    PlayerSheet(onDismiss) {
        if (eq == null) {
            Text("Equalizer isn't available right now", color = Dim, modifier = Modifier.padding(24.dp))
            Spacer(Modifier.height(40.dp))
            return@PlayerSheet
        }
        var enabled by remember { mutableStateOf(eq.enabled) }
        var preset by remember { mutableIntStateOf(runCatching { eq.currentPreset.toInt() }.getOrDefault(-1)) }
        val range = eq.bandLevelRange
        val levels = remember { (0 until eq.numberOfBands).map { b -> mutableFloatStateOf(eq.getBandLevel(b.toShort()).toFloat()) } }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizer", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Switch(enabled, { enabled = it; eq.enabled = it })
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items((0 until eq.numberOfPresets).toList()) { p ->
                val on = p == preset
                Text(
                    eq.getPresetName(p.toShort()), color = if (on) Color.Black else Color.White, fontSize = 14.sp,
                    modifier = Modifier.clip(CircleShape).background(if (on) Color.White else Faint)
                        .clickable {
                            preset = p
                            eq.enabled = true; enabled = true
                            eq.usePreset(p.toShort())
                            levels.forEachIndexed { b, l -> l.floatValue = eq.getBandLevel(b.toShort()).toFloat() }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        levels.forEachIndexed { b, level ->
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                val hz = eq.getCenterFreq(b.toShort()) / 1000
                Text(if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz", color = Dim, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                Slider(
                    level.floatValue, { v ->
                        level.floatValue = v; preset = -1
                        eq.enabled = true; enabled = true
                        eq.setBandLevel(b.toShort(), v.toInt().toShort())
                    },
                    valueRange = range[0].toFloat()..range[1].toFloat(), modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Faint),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun CustomTimerDialog(initial: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableFloatStateOf(initial.coerceIn(5, 180).toFloat()) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF232427)).padding(24.dp)) {
            Text("Custom", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Text("Stop playing after ${minutes.roundToInt()} min", color = Color.White, fontSize = 15.sp)
            Slider(
                minutes, { minutes = it }, valueRange = 5f..180f, steps = 34,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = LocalQtExtras.current.brandStart),
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(LocalQtExtras.current.brand)
                    .pressable { onSet(minutes.roundToInt()); onDismiss() },
                contentAlignment = Alignment.Center,
            ) { Text("OK", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** The five-step gesture guide the system player shows on first use and from ⋮ › Gesture. */
@Composable
fun GestureGuide(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Text(
            "Skip", color = Dim, fontSize = 16.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 30.dp).clip(CircleShape).clickable(onClick = onDone).padding(8.dp),
        )
        Column(Modifier.align(Alignment.Center).padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (step) {
                0 -> Row(horizontalArrangement = Arrangement.spacedBy(110.dp)) {
                    GuideItem(QtIcons.LightMode, "Brightness", "↕")
                    GuideItem(QtIcons.VolumeUp, "Volume", "↕")
                }
                1 -> Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                    GuideItem(QtIcons.FastRewind, "Rewind -10s", "×2")
                    GuideItem(QtIcons.Pause, "Pause/Play", "×2")
                    GuideItem(QtIcons.FastForward, "Fast Forward\n+10s", "×2")
                }
                2 -> GuideItem(QtIcons.FastForward, "2.0X", "Hold")
                3 -> GuideItem(QtIcons.FitScreen, "Zoom In/Out", "Pinch")
                else -> GuideItem(QtIcons.TouchApp, "Move", "Drag")
            }
            Spacer(Modifier.height(48.dp))
            Box(
                Modifier.width(142.dp).height(44.dp).clip(CircleShape).border(1.5.dp, Color.White, CircleShape)
                    .clickable { if (step < 4) step++ else onDone() },
                contentAlignment = Alignment.Center,
            ) { Text(if (step < 4) "Continue" else "Got It", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun GuideItem(icon: ImageVector, label: String, hint: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(hint, color = Dim, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(18.dp))
        Text(label, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}
