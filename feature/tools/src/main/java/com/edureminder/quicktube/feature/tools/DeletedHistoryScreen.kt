package com.edureminder.quicktube.feature.tools

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.download.DownloadManager
import com.edureminder.quicktube.core.model.DeletedDownload
import com.edureminder.quicktube.core.model.DownloadRequest
import com.edureminder.quicktube.core.model.FormatKind
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.ui.ConfirmDialog
import com.edureminder.quicktube.core.ui.EmptyBox
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeletedHistoryViewModel(
    private val library: LibraryRepository,
    private val media: MediaRepository,
    private val downloads: DownloadManager,
) : ViewModel() {
    val items = library.deletedHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clear(ids: List<Long>) = viewModelScope.launch { library.removeDeletedHistory(ids) }

    /** Re-queues the files; YouTube & co. get fresh links in the same quality, other sites reuse the saved link. */
    fun downloadAgain(list: List<DeletedDownload>, done: (Int) -> Unit) = viewModelScope.launch {
        var ok = 0
        list.forEach { d ->
            val queued = runCatching {
                val page = d.pageUrl
                if (page != null && media.linkKind(page) == ItemKind.VIDEO) {
                    val details = media.details(page, forceRefresh = true)
                    val height = d.quality?.removeSuffix("p")?.toIntOrNull()
                    val wantAudio = d.mimeType.startsWith("audio")
                    val option = details.options.firstOrNull { height != null && it.height == height }
                        ?: details.options.firstOrNull { (it.kind == FormatKind.AUDIO) == wantAudio }
                        ?: details.options.first()
                    downloads.enqueue(
                        DownloadRequest(
                            title = d.title, url = option.url, audioUrl = option.audioUrl, ext = option.ext, mimeType = option.mimeType,
                            pageUrl = page, thumbnail = d.thumbnail, quality = d.quality, sizeBytes = option.sizeBytes,
                            audioSizeBytes = option.audioSizeBytes, durationSec = d.durationSec, protocol = option.protocol,
                        ),
                    )
                } else {
                    val ext = d.url.substringBefore('?').substringAfterLast('.', "").takeIf { it.length in 2..4 }
                        ?: if (d.mimeType.startsWith("audio")) "m4a" else "mp4"
                    downloads.enqueue(
                        DownloadRequest(
                            title = d.title, url = d.url, ext = ext, mimeType = d.mimeType, pageUrl = page, thumbnail = d.thumbnail,
                            quality = d.quality, durationSec = d.durationSec,
                        ),
                    )
                }
            }.isSuccess
            if (queued) { ok++; library.removeDeletedHistory(listOf(d.id)) }
        }
        done(ok)
    }
}

private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

@Composable
fun DeletedHistoryScreen(onBack: () -> Unit) {
    val vm = koinViewModel<DeletedHistoryViewModel>()
    val items by vm.items.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            QtTopBar(
                if (selected.isEmpty()) "Deleted history" else "${selected.size} selected", onBack,
                subtitle = if (selected.isEmpty() && items.isNotEmpty()) "${items.size} file${if (items.size == 1) "" else "s"} you deleted" else null,
            ) {
                if (items.isNotEmpty()) {
                    QtIconButton(QtIcons.SelectAll, "Select all", {
                        selected = if (selected.size == items.size) emptySet() else items.map { it.id }.toSet()
                    })
                    QtIconButton(QtIcons.DeleteSweep, "Clear history", { confirm = true })
                }
            }
            if (items.isEmpty()) EmptyBox("No deleted downloads", "Files you delete for good show up here so you can get them back", icon = QtIcons.History)
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                items.groupBy { dayFormat.format(Date(it.deletedAt)) }.forEach { (day, list) ->
                    item(key = "h$day") {
                        Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
                    }
                    items(list, key = { it.id }) { d ->
                        val on = d.id in selected
                        Row(
                            Modifier.animateItem().fillMaxWidth().pressable(scaleDown = 0.985f, onClick = { selected = if (on) selected - d.id else selected + d.id })
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(24.dp).clip(CircleShape)
                                    .border(2.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Icon(QtIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                            Box(
                                Modifier.padding(start = 14.dp).width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(if (d.mimeType.startsWith("audio")) QtIcons.MusicNote else QtIcons.PlayCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (d.thumbnail != null) AsyncImage(d.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(d.title + (d.quality?.let { " ($it)" } ?: ""), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOf(Format.duration(d.durationSec), Format.bytes(d.sizeBytes)).filter { it.isNotBlank() && it != "--" }.joinToString("  ·  "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            items.isNotEmpty(), modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it }, exit = slideOutVertically { it },
        ) {
            PillButton(
                "Download again" + if (selected.isNotEmpty()) " (${selected.size})" else "",
                onClick = {
                    val list = items.filter { it.id in selected }
                    vm.downloadAgain(list) { n -> Toast.makeText(context, "$n added to downloads", Toast.LENGTH_SHORT).show() }
                    selected = emptySet()
                },
                enabled = selected.isNotEmpty(), icon = QtIcons.Download,
                modifier = Modifier.navigationBarsPadding().padding(20.dp).fillMaxWidth(),
            )
        }
    }
    if (confirm) ConfirmDialog(
        "Clear history?", if (selected.isEmpty()) "All deleted-download records will be removed." else "${selected.size} records will be removed.",
        "Clear", onConfirm = { vm.clear(if (selected.isEmpty()) items.map { it.id } else selected.toList()); selected = emptySet() },
        onDismiss = { confirm = false },
    )
}
