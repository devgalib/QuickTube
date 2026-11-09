package com.edureminder.quicktube.core.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import com.edureminder.quicktube.core.model.CaptionBackground
import com.edureminder.quicktube.core.model.CaptionStyle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.SubtitleTrack

/** The video's caption tracks for the CC button; [selected] null = captions off. */
class CaptionMenu(val tracks: List<SubtitleTrack>, val selected: SubtitleTrack?, val onSelect: (SubtitleTrack?) -> Unit)

/**
 * Caption text over the video in the user's style (Settings, or "Caption style" in the captions sheet): placed at
 * [CaptionStyle.position] of the height, never under the controls when they show ([avoidBottom]).
 */
@Composable
fun CaptionOverlay(controller: PlayerController, fullscreen: Boolean, avoidBottom: Dp, modifier: Modifier = Modifier) {
    val text by controller.cues.collectAsStateWithLifecycle()
    val settings = org.koin.compose.koinInject<com.edureminder.quicktube.core.data.SettingsRepository>()
    val style by settings.settings.map { it.captionStyle }.collectAsStateWithLifecycle(CaptionStyle())
    if (text.isBlank()) return
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize().padding(bottom = avoidBottom, top = 8.dp)) {
        val bias = style.position * 2 - 1
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.BiasAlignment(0f, bias)) {
            StyledCaption(text, style, if (fullscreen) 20.sp else 15.sp)
        }
    }
}

/** One caption in [style] at [base] size (also the style editor's preview). */
@Composable
fun StyledCaption(text: String, style: CaptionStyle, base: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    val bg = Color(style.backgroundColor).copy(alpha = style.backgroundOpacity)
    val shadow = if (style.outline || style.background == CaptionBackground.NONE) {
        androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = if (style.outline) 6f else 4f)
    } else null
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = Color(style.textColor), fontSize = base * style.size, textAlign = TextAlign.Center,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Medium, shadow = shadow,
    )
    val box = when (style.background) {
        CaptionBackground.NONE -> Modifier
        CaptionBackground.BOX -> Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp)
        // A soft band: strongest under the text, fading out to both sides.
        CaptionBackground.GRADIENT -> Modifier.background(Brush.horizontalGradient(listOf(Color.Transparent, bg, bg, Color.Transparent)))
            .padding(horizontal = 36.dp, vertical = 6.dp)
    }
    Text(text, style = textStyle, modifier = modifier.padding(horizontal = 16.dp).then(box))
}

/** "Captions": Off and every track, as on YouTube. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptionSheet(menu: CaptionMenu, onDismiss: () -> Unit, onStyle: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Captions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onStyle) { Text("Caption style") }
        }
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            item { CaptionChoice("Off", menu.selected == null) { menu.onSelect(null); onDismiss() } }
            items(menu.tracks, key = { it.url }) { t -> CaptionChoice(t.label, menu.selected?.url == t.url) { menu.onSelect(t); onDismiss() } }
        }
    }
}

@Composable
private fun CaptionChoice(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressable(scaleDown = 0.98f, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp))
        Icon(QtIcons.Check, null, tint = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 20.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge) }
    }
}

private val TextColors = listOf(0xFFFFFFFF, 0xFFFFEB3B, 0xFF00E5FF, 0xFF76FF03, 0xFFFF80AB, 0xFF000000)
private val BackgroundColors = listOf(0xFF000000, 0xFF1A237E, 0xFF263238, 0xFFFFFFFF, 0xFFB71C1C)

/** Caption style, with a live preview over a dark frame: size, colors, background, position. Saved for both players. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionStyleSheet(onDismiss: () -> Unit) {
    val settings = org.koin.compose.koinInject<com.edureminder.quicktube.core.data.SettingsRepository>()
    val saved by settings.settings.map { it.captionStyle }.collectAsStateWithLifecycle(CaptionStyle())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun set(f: (CaptionStyle) -> CaptionStyle) = scope.launch { settings.update { it.copy(captionStyle = f(it.captionStyle)) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Caption style", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { set { CaptionStyle() } }) { Text("Reset") }
            }
            // Preview: where and how captions will look on a video.
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF3A4A5C), Color(0xFF12161C)))),
                contentAlignment = androidx.compose.ui.BiasAlignment(0f, saved.position * 2 - 1),
            ) { StyledCaption("This is how captions look", saved, 15.sp, Modifier.padding(vertical = 8.dp)) }
            Label("Text size  ${(saved.size * 100).toInt()}%")
            androidx.compose.material3.Slider(saved.size, { v -> set { it.copy(size = v) } }, valueRange = 0.6f..2.2f)
            Label("Text color")
            Swatches(TextColors, saved.textColor) { c -> set { it.copy(textColor = c) } }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StyleChip("Bold", saved.bold) { set { it.copy(bold = !it.bold) } }
                Spacer(Modifier.width(8.dp))
                StyleChip("Outline", saved.outline) { set { it.copy(outline = !it.outline) } }
            }
            Label("Background")
            Row {
                CaptionBackground.entries.forEach { b ->
                    StyleChip(when (b) { CaptionBackground.NONE -> "None"; CaptionBackground.BOX -> "Box"; CaptionBackground.GRADIENT -> "Gradient" }, saved.background == b) {
                        set { it.copy(background = b) }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
            if (saved.background != CaptionBackground.NONE) {
                Label("Background color")
                Swatches(BackgroundColors, saved.backgroundColor) { c -> set { it.copy(backgroundColor = c) } }
                Label("Background opacity  ${(saved.backgroundOpacity * 100).toInt()}%")
                androidx.compose.material3.Slider(saved.backgroundOpacity, { v -> set { it.copy(backgroundOpacity = v) } }, valueRange = 0.1f..1f)
            }
            Label("Position")
            Row {
                listOf("Top" to 0f, "Middle" to 0.5f, "Bottom" to 1f).forEach { (n, v) ->
                    StyleChip(n, kotlin.math.abs(saved.position - v) < 0.01f) { set { it.copy(position = v) } }
                    Spacer(Modifier.width(8.dp))
                }
            }
            androidx.compose.material3.Slider(saved.position, { v -> set { it.copy(position = v) } }, valueRange = 0f..1f)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
private fun StyleChip(text: String, selected: Boolean, onClick: () -> Unit) =
    com.edureminder.quicktube.core.designsystem.QtChip(text, selected, onClick)

@Composable
private fun Swatches(colors: List<Long>, selected: Long, onPick: (Long) -> Unit) {
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            Box(
                Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        androidx.compose.foundation.shape.CircleShape)
                    .pressable(scaleDown = 0.85f, onClick = { onPick(c) }),
            )
        }
    }
}
