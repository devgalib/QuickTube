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
        else LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(active, key = { it.id }) { t ->
                Row(Modifier.animateItem().fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileThumb(t, Modifier.width(104.dp).aspectRatio(16f / 9f))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        if (t.status == DownloadStatus.MERGING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        else GradientProgress(t.progress, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        Text(statusText(t, speeds[t.id] ?: 0) + "  ${(t.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                            color = if (t.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (t.status) {
                        DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK ->
                            QtIconButton(QtIcons.Pause, "Pause", { vm.pause(t.id) })
                        DownloadStatus.PAUSED -> QtIconButton(QtIcons.Play, "Resume", { vm.resume(t.id) })
                        DownloadStatus.FAILED -> QtIconButton(QtIcons.Refresh, "Retry", { vm.resume(t.id) })
                        else -> {}
                    }
                    QtIconButton(QtIcons.Close, "Cancel", { cancel = t.id })
                }
            }
        }
    }
    cancel?.let { id ->
        ConfirmDialog("Cancel download?", "The partially downloaded file will be removed.", "Cancel download",
            onConfirm = { vm.delete(listOf(id)) }, onDismiss = { cancel = null })
    }
}
