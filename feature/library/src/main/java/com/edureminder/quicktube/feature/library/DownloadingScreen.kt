package com.edureminder.quicktube.feature.library

import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.edureminder.quicktube.core.model.DownloadTask
import com.edureminder.quicktube.core.designsystem.pressable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.model.DownloadStatus
import com.edureminder.quicktube.core.ui.ConfirmDialog
import com.edureminder.quicktube.core.ui.EmptyBox
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DownloadingScreen(onBack: () -> Unit) {
    val vm = koinViewModel<LibraryViewModel>()
    val active by vm.active.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
    var cancel by remember { mutableStateOf<Long?>(null) }
    val anyRunning = active.any { it.status.isActive }
    Scaffold(topBar = {
        QtTopBar("Downloading (${active.size})", onBack) {
            if (active.isNotEmpty()) TextButton(onClick = { if (anyRunning) vm.pauseAll() else vm.resumeAll() }) {
                Text(if (anyRunning) "Pause all" else "Start all")
            }
        }
    }) { pad ->
        if (active.isEmpty()) EmptyBox("Nothing downloading", "Finished files are in the Play tab", Modifier.fillMaxSize().padding(pad))
        else LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(active, key = { it.id }) { t ->
                DownloadCard(
                    t, speeds[t.id] ?: 0, Modifier.animateItem(),
                    onPause = { vm.pause(t.id) }, onResume = { vm.resume(t.id) }, onCancel = { cancel = t.id },
                )
            }
        }
    }
    cancel?.let { id ->
        ConfirmDialog("Cancel download?", "The partially downloaded file will be removed.", "Cancel download",
            onConfirm = { vm.delete(listOf(id)) }, onDismiss = { cancel = null })
    }
}

/**
 * One download as a card: thumbnail with a status badge, the title (two lines at most), a thin progress bar,
 * and a single status line that never wraps. Actions sit in a narrow column so the text keeps the width.
 */
@Composable
private fun DownloadCard(
    t: DownloadTask, speed: Long, modifier: Modifier,
    onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit,
) {
    val failed = t.status == DownloadStatus.FAILED
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerLow)
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(18.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(92.dp).aspectRatio(16f / 9f)) {
            FileThumb(t, Modifier.fillMaxSize())
            // Status badge on the thumbnail.
            val (icon, tint) = when (t.status) {
                DownloadStatus.FAILED -> QtIcons.Error to cs.error
                DownloadStatus.PAUSED -> QtIcons.Pause to cs.onSurface
                DownloadStatus.MERGING -> QtIcons.Tune to cs.primary
                DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK -> QtIcons.Schedule to cs.onSurface
                else -> QtIcons.Downloading to cs.primary
            }
            Box(
                Modifier.align(Alignment.BottomStart).padding(5.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (tint == cs.onSurface) Color.White else tint, modifier = Modifier.size(14.dp)) }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            Text(
                t.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp), fontWeight = FontWeight.SemiBold,
            )
            if (t.status == DownloadStatus.MERGING) GradientProgress(0f, Modifier.fillMaxWidth().padding(vertical = 7.dp), indeterminate = true)
            else GradientProgress(t.progress, Modifier.fillMaxWidth().padding(vertical = 7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusText(t, speed), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (failed) cs.error else cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (!failed && t.status != DownloadStatus.MERGING) Text(
                    "${(t.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = cs.onSurface, modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (t.status) {
                DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK -> RoundAction(QtIcons.Pause, "Pause", cs.primary, onPause)
                DownloadStatus.PAUSED -> RoundAction(QtIcons.Play, "Resume", cs.primary, onResume)
                DownloadStatus.FAILED -> RoundAction(QtIcons.Refresh, "Retry", cs.error, onResume)
                else -> Spacer(Modifier.size(36.dp))
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.size(28.dp).clip(CircleShape).pressable(onClick = onCancel), contentAlignment = Alignment.Center,
            ) { Icon(QtIcons.Close, "Cancel", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}
