package com.edureminder.quicktube.feature.video

import com.edureminder.quicktube.core.ui.readableWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.data.PlaybackHistoryRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.PlayHistoryEntry
import com.edureminder.quicktube.core.ui.ConfirmDialog
import com.edureminder.quicktube.core.ui.CornerLabel
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.Thumbnail
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.util.Calendar

/**
 * YouTube watch history, kept on the phone (like NewPipe and Flow): every YouTube video and song played in the app,
 * newest first, with where it was left. Nothing is sent anywhere and no account is needed.
 */
class WatchHistoryViewModel(private val history: PlaybackHistoryRepository, private val settings: SettingsRepository) : ViewModel() {
    val entries = history.entries.map { list -> list.filter { e -> e.pageUrl?.let(Urls::youtubeVideoId) != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val enabled = settings.settings.map { it.playHistory }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun remove(e: PlayHistoryEntry) = viewModelScope.launch { history.remove(listOf(e.key)) }
    fun clear() = viewModelScope.launch { history.remove(entries.value.orEmpty().map { it.key }) }
    fun setEnabled(on: Boolean) = viewModelScope.launch { settings.update { it.copy(playHistory = on) } }
}

@Composable
fun WatchHistoryScreen(onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val vm = koinViewModel<WatchHistoryViewModel>()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        QtTopBar("Watch history", onBack) { if (!entries.isNullOrEmpty()) TextButton(onClick = { confirm = true }) { Text("Clear all") } }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep watch history", style = MaterialTheme.typography.bodyLarge)
                    Text("Saved only on ${com.edureminder.quicktube.core.ui.thisDevice}. Videos continue where you left off.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(enabled, vm::setEnabled)
            }
            val list = entries
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyBox("No videos watched yet", "YouTube videos you watch here show up in this list", Modifier.fillMaxSize(), QtIcons.History)
                else -> LazyColumn(Modifier.fillMaxSize().readableWidth(900.dp)) {
                    groupByDay(list).forEach { (day, items) ->
                        item(key = "h-$day") {
                            Text(day, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                        }
                        items(items, key = { it.key }) { e -> HistoryRow(e, onOpen, onRemove = { vm.remove(e) }) }
                    }
                }
            }
        }
    }
    if (confirm) ConfirmDialog("Clear watch history?", "Your YouTube history and saved positions are removed from ${com.edureminder.quicktube.core.ui.thisDevice}.", "Clear",
        { vm.clear() }, { confirm = false })
}

@Composable
private fun HistoryRow(e: PlayHistoryEntry, onOpen: (MediaItem) -> Unit, onRemove: () -> Unit) {
    val url = e.pageUrl ?: return
    // Entries saved before pictures were kept get YouTube's own thumbnail for the video.
    val thumbnail = e.thumbnail ?: Urls.youtubeVideoId(url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    val item = MediaItem(url = url, title = e.title, thumbnail = thumbnail, uploader = e.uploader.orEmpty(), durationSec = e.durationMs / 1000)
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, role = null, onClick = { onOpen(item) }).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(thumbnail, Modifier.width(148.dp).aspectRatio(16f / 9f), corner = 12) {
            val left = (e.durationMs - e.positionMs).coerceAtLeast(0) / 1000
            if (e.durationMs > 0) CornerLabel(if (e.progress > 0.95f) "Watched" else Format.duration(left) + " left")
            // YouTube's red "watched" bar along the bottom.
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(e.progress).background(Color(0xFFFF0033)))
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(e.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!e.uploader.isNullOrBlank()) Text(e.uploader!!, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        QtIconButton(QtIcons.Close, "Remove from history", onRemove, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Today", "Yesterday", "Earlier" sections, newest first. */
private fun groupByDay(list: List<PlayHistoryEntry>): List<Pair<String, List<PlayHistoryEntry>>> {
    val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val day = 24 * 60 * 60 * 1000L
    return list.groupBy { e -> when { e.openedAt >= start -> "Today"; e.openedAt >= start - day -> "Yesterday"; else -> "Earlier" } }
        .toList().sortedBy { listOf("Today", "Yesterday", "Earlier").indexOf(it.first) }
}
