package com.edureminder.quicktube.feature.tools

import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtChip
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.pressable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.model.ContentType
import com.edureminder.quicktube.core.model.DownloadTask
import com.edureminder.quicktube.core.ui.ConfirmDialog
import com.edureminder.quicktube.core.ui.EmptyBox
import org.koin.compose.viewmodel.koinViewModel
import java.io.File
import java.util.concurrent.TimeUnit

private val binTabs = listOf("All", "Video", "Audio", "Photo", "Other")

@Composable
internal fun TaskThumb(task: DownloadTask, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val f = File(task.filePath)
        val model: Any? = task.thumbnail ?: f.takeIf { it.exists() && task.type != ContentType.AUDIO }
        Icon(if (task.type == ContentType.AUDIO) QtIcons.MusicNote else QtIcons.PlayCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun SelectDot(on: Boolean) {
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .border(2.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(QtIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
}

@Composable
fun RecycleBinScreen(onBack: () -> Unit, onHistory: () -> Unit = {}) {
    val vm = koinViewModel<RecycleBinViewModel>()
    val all by vm.items.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf(false) }
    val list = all.filter {
        when (tab) {
            1 -> it.type == ContentType.VIDEO; 2 -> it.type == ContentType.AUDIO; 3 -> it.type == ContentType.IMAGE
            4 -> it.type == ContentType.OTHER; else -> true
        }
    }
    Column(Modifier.fillMaxSize()) {
        QtTopBar(if (selected.isEmpty()) "Recover deleted files" else "${selected.size} selected", onBack) {
            if (list.isNotEmpty()) QtIconButton(QtIcons.SelectAll, "Select all", {
                selected = if (selected.size == list.size) emptySet() else list.map { it.id }.toSet()
            })
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(binTabs) { i, t -> QtChip(t, tab == i, { tab = i; selected = emptySet() }) }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                if (list.isEmpty()) item {
                    EmptyBox("Recycle bin is empty", "Deleted downloads stay here for 30 days", Modifier.fillMaxWidth().padding(top = 40.dp), QtIcons.RestoreFromTrash)
                }
                items(list, key = { it.id }) { t ->
                    val daysLeft = (30 - TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - t.deletedAt)).coerceAtLeast(0)
                    val on = t.id in selected
                    Row(
                        Modifier.animateItem().fillMaxWidth().pressable(scaleDown = 0.985f, onClick = { selected = if (on) selected - t.id else selected + t.id })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectDot(on)
                        Box(Modifier.padding(start = 14.dp)) {
                            TaskThumb(t, Modifier.width(112.dp).aspectRatio(16f / 9f))
                            Text(
                                "$daysLeft days", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .65f)).padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(Format.bytes(t.totalBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Files will be permanently deleted after 30 days.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Text(
                            "Check deleted download history to find more.", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onHistory).padding(10.dp),
                        )
                    }
                }
            }
        }
        if (all.isNotEmpty()) Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Delete", { confirm = true }, Modifier.weight(1f), icon = QtIcons.DeleteForever, enabled = selected.isNotEmpty(), secondary = true)
            PillButton("Recover", { vm.restore(selected.toList()); selected = emptySet() }, Modifier.weight(1f), icon = QtIcons.Restore, enabled = selected.isNotEmpty())
        }
    }
    if (confirm) ConfirmDialog("Delete permanently?", "${selected.size} file(s) will be removed from your phone. You can still find them in deleted history.", "Delete",
        { vm.deleteForever(selected.toList()); selected = emptySet() }, { confirm = false })
}
