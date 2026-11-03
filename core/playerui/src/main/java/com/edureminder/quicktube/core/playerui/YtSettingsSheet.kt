package com.edureminder.quicktube.core.playerui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.media.PlaybackState
import com.edureminder.quicktube.core.media.PlayerController

private enum class Page { MAIN, QUALITY, SPEED, TIMER, MORE }

private val SheetSpeeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
/** Sleep timer choices in minutes; -1 = end of video. */
private val SleepChoices = listOf(0, 10, 15, 20, 30, 45, 60, -1)

private fun speedName(s: Float) = when {
    s == 1f -> "Normal"
    s % 1f == 0f -> "${s.toInt()}x"
    else -> "${s}x"
}

private fun sleepName(minutes: Int) = when (minutes) {
    0 -> "Off"
    -1 -> "End of video"
    60 -> "1 hour"
    else -> "$minutes minutes"
}

/**
 * The player's settings sheet, as on YouTube: Quality, Playback speed, Sleep timer and Additional settings, each opening
 * its own list in the same sheet (with a back arrow).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YtSettingsSheet(
    state: PlaybackState,
    controller: PlayerController,
    quality: QualityMenu?,
    onPip: (() -> Unit)?,
    extra: List<PlayerOption>,
    /** When the sleep timer pauses (epoch ms), -1 = end of video, 0 = off. */
    sleepAt: Long,
    onSleep: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(Page.MAIN) }
    var sleepChoice by remember { mutableStateOf(if (sleepAt == -1L) -1 else if (sleepAt > 0) -2 else 0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AnimatedContent(
            page,
            transitionSpec = {
                val forward = targetState != Page.MAIN
                (slideInHorizontally(tween(240)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(240)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(150)))
            },
            label = "settings",
        ) { p ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
                when (p) {
                    Page.MAIN -> {
                        if (quality != null && quality.options.isNotEmpty()) NavItem(QtIcons.Tune, "Quality", quality.current) { page = Page.QUALITY }
                        NavItem(QtIcons.Speed, "Playback speed", speedName(state.speed)) { page = Page.SPEED }
                        NavItem(QtIcons.Timer, "Sleep timer", when {
                            sleepAt == -1L -> "End of video"
                            sleepAt > 0 -> "${((sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min left"
                            else -> "Off"
                        }) { page = Page.TIMER }
                        NavItem(QtIcons.Settings, "Additional settings", null) { page = Page.MORE }
                    }
                    Page.QUALITY -> {
                        Header("Quality for current video") { page = Page.MAIN }
                        val auto = quality?.current?.startsWith("Auto") == true
                        Choice("Auto", "Recommended", auto) { quality?.onSelect("Auto"); onDismiss() }
                        quality?.options?.forEach { q -> Choice(q, null, !auto && quality.current == q) { quality.onSelect(q); onDismiss() } }
                    }
                    Page.SPEED -> {
                        Header("Playback speed") { page = Page.MAIN }
                        SheetSpeeds.forEach { s -> Choice(speedName(s), null, state.speed == s) { controller.setSpeed(s); onDismiss() } }
                    }
                    Page.TIMER -> {
                        Header("Sleep timer") { page = Page.MAIN }
                        SleepChoices.forEach { m ->
                            Choice(sleepName(m), null, sleepChoice == m) {
                                sleepChoice = m
                                onSleep(when (m) { 0 -> 0L; -1 -> -1L; else -> System.currentTimeMillis() + m * 60_000L })
                                onDismiss()
                            }
                        }
                    }
                    Page.MORE -> {
                        Header("Additional settings") { page = Page.MAIN }
                        val looping = state.repeatMode == Player.REPEAT_MODE_ONE
                        ToggleItem(QtIcons.RepeatOne, "Loop video", looping) {
                            controller.setRepeat(if (looping) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE)
                        }
                        if (onPip != null) NavItem(QtIcons.PictureInPicture, "Picture-in-picture", null) { onDismiss(); onPip() }
                        extra.forEach { o -> NavItem(o.icon, o.label, null, selected = o.selected) { o.onClick() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, title: String, value: String?, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(scaleDown = 0.98f, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 20.dp))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected) Icon(QtIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
        else Icon(QtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

@Composable
private fun ToggleItem(icon: ImageVector, title: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(scaleDown = 0.98f, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 20.dp))
        Switch(on, { onClick() }, colors = SwitchDefaults.colors(checkedTrackColor = YtRed, uncheckedBorderColor = Color.Transparent))
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.size(44.dp).pressable(scaleDown = 0.9f, onClick = onBack), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) { Icon(QtIcons.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun Choice(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressable(scaleDown = 0.98f, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp))
        Icon(QtIcons.Check, null, tint = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
