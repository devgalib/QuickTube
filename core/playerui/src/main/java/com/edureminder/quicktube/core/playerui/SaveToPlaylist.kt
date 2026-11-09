package com.edureminder.quicktube.core.playerui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.data.LocalPlaylistRepository
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.LocalPlaylist
import com.edureminder.quicktube.core.model.MediaItem
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** "Save to playlist": the phone's [isMusic] playlists, or a new one, for [item]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToPlaylistSheet(item: MediaItem, isMusic: Boolean, onDismiss: () -> Unit) {
    val repo = koinInject<LocalPlaylistRepository>()
    val lists by remember(repo, isMusic) { repo.playlists.map { l -> l.filter { it.isMusic == isMusic } } }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun addTo(id: Long, title: String) = scope.launch {
        val added = repo.add(id, item)
        Toast.makeText(context, if (added) "Saved to $title" else "Already in $title", Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    fun createAndAdd() = scope.launch {
        val title = name.trim().ifBlank { if (isMusic) "My songs" else "My videos" }
        addTo(repo.create(title, isMusic), title)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Save to playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
            if (naming) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        name, { name = it.take(60) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Playlist name") },
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { createAndAdd() }),
                    )
                    TextButton(onClick = { createAndAdd() }) { Text("Create") }
                }
            } else PlaylistRow(null, "New playlist", null) { naming = true }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(lists, key = { it.id }) { p -> PlaylistRow(p, p.name, itemCount(p.count, isMusic)) { addTo(p.id, p.name) } }
            }
        }
    }
}

@Composable
private fun PlaylistRow(p: LocalPlaylist?, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            val cover = p?.covers?.firstOrNull()
            if (cover != null) coil3.compose.AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(if (p == null) QtIcons.Add else QtIcons.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/** "1 song", "12 videos". */
internal fun itemCount(n: Int, music: Boolean) = "$n " + when { music && n == 1 -> "song"; music -> "songs"; n == 1 -> "video"; else -> "videos" }
