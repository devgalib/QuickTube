package com.edureminder.quicktube.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.data.PlaybackHistoryRepository
import com.edureminder.quicktube.core.designsystem.QtCard
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.core.model.VideoEndMode
import com.edureminder.quicktube.core.ui.ConfirmDialog
import org.koin.compose.viewmodel.koinViewModel

private val DefaultSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val HoldSpeeds = listOf(1.5f, 2f, 2.5f, 3f)
private val SeekSteps = listOf(5, 10, 15, 30)

private fun speedLabel(speed: Float) = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

/** A one-of-many setting picked in a dialog. */
private class Choice<T>(val title: String, val options: List<T>, val label: (T) -> String, val selected: T, val onPick: (T) -> Unit)

/** Settings → Player: history and resume, playback, gestures and picture-in-picture, like the phone's own video player. */
@Composable
fun PlayerSettingsScreen(onBack: () -> Unit) {
    val vm = koinViewModel<SettingsViewModel>()
    val s by vm.settings.collectAsStateWithLifecycle()
    val historyCount by vm.historyCount.collectAsStateWithLifecycle()
    var choice by remember { mutableStateOf<Choice<*>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    fun set(transform: (AppSettings) -> AppSettings) = vm.update(transform)

    Scaffold(topBar = { QtTopBar("Player", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            GroupLabel("History")
            QtCard {
                SwitchRow(
                    "Save playback history", "Remembers where you stopped in the last ${PlaybackHistoryRepository.MAX_ENTRIES} videos",
                    s.playHistory, QtIcons.History,
                ) { v -> set { it.copy(playHistory = v) } }
                if (s.playHistory) {
                    RowDivider()
                    SwitchRow("Resume playback", "Reopening a video continues where you left it", s.resumePlayback, QtIcons.Restore) { v ->
                        set { it.copy(resumePlayback = v) }
                    }
                }
                RowDivider()
                NavRow(
                    QtIcons.DeleteSweep, "Clear playback history",
                    if (historyCount == 0) "Nothing saved yet" else "$historyCount video${if (historyCount == 1) "" else "s"} remembered",
                    tint = androidx.compose.ui.graphics.Color(0xFFFF5C93),
                ) { if (historyCount > 0) confirmClear = true }
            }
            GroupLabel("Playback")
            QtCard {
                NavRow(QtIcons.Speed, "Default speed", trailing = speedLabel(s.playbackSpeed)) {
                    choice = Choice("Default speed", DefaultSpeeds, ::speedLabel, s.playbackSpeed) { v -> set { it.copy(playbackSpeed = v) } }
                }
                RowDivider()
                NavRow(QtIcons.Repeat, "When a video ends", s.videoEndMode.label) {
                    choice = Choice("When a video ends", VideoEndMode.entries, { it.label }, s.videoEndMode) { v -> set { it.copy(videoEndMode = v) } }
                }
                RowDivider()
                SwitchRow("Keep screen on", "While a video is playing", s.keepScreenOn, QtIcons.LightMode) { v -> set { it.copy(keepScreenOn = v) } }
                RowDivider()
                SwitchRow(
                    "Picture-in-picture", "Leaving a playing video keeps it in a small floating window", s.autoPip, QtIcons.PictureInPicture,
                ) { v -> set { it.copy(autoPip = v) } }
                RowDivider()
                SwitchRow(
                    "Background play", "Keep the sound playing after leaving the app (when not in picture-in-picture)",
                    s.backgroundPlay, QtIcons.Headphones,
                ) { v -> set { it.copy(backgroundPlay = v) } }
            }
            GroupLabel("Gestures")
            QtCard {
                SwitchRow("Brightness", "Swipe up or down on the left side", s.gestureBrightness, QtIcons.Brightness) { v ->
                    set { it.copy(gestureBrightness = v) }
                }
                RowDivider()
                SwitchRow("Volume", "Swipe up or down on the right side", s.gestureVolume, QtIcons.VolumeUp) { v -> set { it.copy(gestureVolume = v) } }
                RowDivider()
                SwitchRow("Seek", "Swipe left or right", s.gestureSeek, QtIcons.FastForward) { v -> set { it.copy(gestureSeek = v) } }
                RowDivider()
                SwitchRow("Double tap to skip", "Left side back, right side forward", s.gestureDoubleTap, QtIcons.TouchApp) { v ->
                    set { it.copy(gestureDoubleTap = v) }
                }
                if (s.gestureDoubleTap) {
                    RowDivider()
                    NavRow(QtIcons.Forward10, "Skip by", trailing = "${s.seekStepSec} s") {
                        choice = Choice("Skip by", SeekSteps, { "$it seconds" }, s.seekStepSec) { v -> set { it.copy(seekStepSec = v) } }
                    }
                }
                RowDivider()
                SwitchRow("Hold for speed", "Touch and hold to play faster", s.gestureHoldSpeed, QtIcons.Bolt) { v -> set { it.copy(gestureHoldSpeed = v) } }
                if (s.gestureHoldSpeed) {
                    RowDivider()
                    NavRow(QtIcons.SlowMotion, "Hold speed", trailing = speedLabel(s.holdSpeed)) {
                        choice = Choice("Hold speed", HoldSpeeds, ::speedLabel, s.holdSpeed) { v -> set { it.copy(holdSpeed = v) } }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Suppress("UNCHECKED_CAST")
    (choice as Choice<Any?>?)?.let { ChoiceDialog(it) { choice = null } }
    if (confirmClear) ConfirmDialog(
        "Clear playback history?", "Videos will start from the beginning again.", "Clear",
        onConfirm = vm::clearPlayHistory, onDismiss = { confirmClear = false },
    )
}

@Composable
private fun <T> ChoiceDialog(c: Choice<T>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.title) },
        text = { Column { c.options.forEach { o -> RadioRow(c.label(o), o == c.selected) { c.onPick(o); onDismiss() } } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
