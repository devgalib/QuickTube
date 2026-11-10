package com.edureminder.quicktube.feature.video

import com.edureminder.quicktube.core.ui.readableWidth
import com.edureminder.quicktube.core.ui.rememberToaster
import com.edureminder.quicktube.core.ui.show
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.data.LocalPlaylistRepository
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.LocalPlaylist
import com.edureminder.quicktube.core.model.LocalPlaylistEntry
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.ui.ConfirmDialog
import com.edureminder.quicktube.core.ui.CornerLabel
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.Thumbnail
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

class LocalPlaylistsViewModel(private val music: Boolean, private val repo: LocalPlaylistRepository) : ViewModel() {
    val playlists = repo.playlists.map { l -> l.filter { it.isMusic == music } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    var importing by mutableStateOf(false)
        private set

    fun create(name: String, onCreated: (Long) -> Unit) = viewModelScope.launch { onCreated(repo.create(name, music)) }

    /** A YouTube / YouTube Music / SoundCloud playlist link → a local copy; [onDone] gets its id or the problem. */
    fun import(url: String, onDone: (Result<Long>) -> Unit) = viewModelScope.launch {
        importing = true
        val r = runCatching { repo.import(url.trim(), music) }
        importing = false
        onDone(r)
    }
}

class LocalPlaylistViewModel(private val id: Long, private val repo: LocalPlaylistRepository) : ViewModel() {
    val playlist = repo.playlist(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val entries = repo.entries(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun rename(name: String) = viewModelScope.launch { repo.rename(id, name) }
    fun delete() = viewModelScope.launch { repo.delete(id) }
    fun remove(e: LocalPlaylistEntry) = viewModelScope.launch { repo.remove(e.id) }
}

/** The phone's playlists of videos ([music] false) or songs: create one, or copy a YouTube / YouTube Music playlist. */
@Composable
fun LocalPlaylistsScreen(music: Boolean, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val vm = koinViewModel<LocalPlaylistsViewModel>(key = "lp$music") { org.koin.core.parameter.parametersOf(music) }
    val lists by vm.playlists.collectAsStateWithLifecycle()
    val toast = rememberToaster()
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    Scaffold(topBar = { QtTopBar(if (music) "Music playlists" else "Video playlists", onBack, subtitle = "Saved on ${com.edureminder.quicktube.core.ui.thisDevice}") }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).readableWidth(900.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "actions") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("New playlist", { creating = true }, Modifier.weight(1f), icon = QtIcons.Add)
                    PillButton(if (vm.importing) "Importing…" else "Import link", { importing = true }, Modifier.weight(1f), icon = QtIcons.Link,
                        secondary = true, enabled = !vm.importing)
                }
            }
            val l = lists
            if (l != null && l.isEmpty()) item(key = "empty") {
                EmptyBox("No playlists yet", if (music) "Make one, or save songs from the music player" else "Make one, or save videos from the video page",
                    Modifier.fillMaxWidth().padding(top = 60.dp), QtIcons.PlaylistPlay)
            }
            items(l.orEmpty(), key = { it.id }) { p -> PlaylistCard(p) { onOpen(p.id) } }
        }
    }
    if (creating) NameDialog("New playlist", "", onDismiss = { creating = false }) { name -> vm.create(name, onOpen) }
    if (importing) NameDialog(
        "Import a playlist", "", label = if (music) "YouTube Music or SoundCloud playlist link" else "YouTube playlist link",
        confirm = "Import", onDismiss = { importing = false },
    ) { link ->
        vm.import(link) { r ->
            r.onSuccess(onOpen).onFailure { toast.show(it.message ?: "Couldn't read that playlist", true) }
        }
    }
}

@Composable
private fun PlaylistCard(p: LocalPlaylist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(p, Modifier.width(120.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(itemCount(p.count, p.isMusic), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(QtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The first pictures as a 2×2 mosaic (one fills it all), like a music app's playlist cover. */
@Composable
private fun Cover(p: LocalPlaylist, modifier: Modifier) {
    Box(modifier.aspectRatio(if (p.isMusic) 1f else 16f / 10f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center) {
        val c = p.covers
        when {
            c.isEmpty() -> Icon(QtIcons.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            c.size < 4 -> coil3.compose.AsyncImage(c.first(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Column(Modifier.fillMaxSize()) {
                for (r in 0..1) Row(Modifier.weight(1f)) {
                    for (k in 0..1) coil3.compose.AsyncImage(c[r * 2 + k], null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, label: String = "Name", confirm: String = "Create", onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(label) }, shape = RoundedCornerShape(14.dp)) },
        confirmButton = { TextButton(onClick = { onDone(text); onDismiss() }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One local playlist: Play all / Shuffle (videos play one after another; songs in the music player), Share (the links,
 * which QuickTube opens as a bulk download), Download all (a bulk download named after the playlist).
 */
@Composable
fun LocalPlaylistScreen(
    id: Long,
    onBack: () -> Unit,
    onPlay: (playlist: LocalPlaylist, items: List<MediaItem>, index: Int) -> Unit,
    onDownloadAll: (name: String, links: List<String>) -> Unit,
) {
    val vm = koinViewModel<LocalPlaylistViewModel>(key = "lpl$id") { org.koin.core.parameter.parametersOf(id) }
    val p by vm.playlist.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val share = com.edureminder.quicktube.core.ui.rememberTextSharer()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val list = p
    val items = entries.orEmpty().map { it.item }
    Scaffold(topBar = {
        QtTopBar(list?.name ?: "", onBack, subtitle = list?.let { itemCount(it.count, it.isMusic) }) {
            QtIconButton(QtIcons.Share, "Share playlist", {
                if (list == null || items.isEmpty()) return@QtIconButton
                // One link per line: shared to QuickTube it becomes a bulk download of the whole playlist.
                val text = "${list.name}\n" + items.joinToString("\n") { it.url } + "\n\nShared with QuickTube"
                share(list.name, text)
            })
            QtIconButton(QtIcons.Edit, "Rename", { renaming = true })
            QtIconButton(QtIcons.Delete, "Delete playlist", { deleting = true })
        }
    }) { pad ->
        if (list == null) { Box(Modifier.fillMaxSize().padding(pad)); return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad).readableWidth(900.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(list, Modifier.width(if (list.isMusic) 120.dp else 168.dp))
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(list.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val total = items.sumOf { it.durationSec.coerceAtLeast(0) }
                            Text(itemCount(list.count, list.isMusic) + if (total > 0) " · ${Format.duration(total)}" else "",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (list.source != null) Text("Imported playlist", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Play all", { if (items.isNotEmpty()) onPlay(list, items, 0) }, Modifier.weight(1f), icon = QtIcons.Play, enabled = items.isNotEmpty())
                        PillButton("Shuffle", { if (items.isNotEmpty()) onPlay(list, items.shuffled(), 0) }, Modifier.weight(1f), icon = QtIcons.Shuffle,
                            secondary = true, enabled = items.isNotEmpty())
                    }
                    PillButton("Download all", { onDownloadAll(list.name, items.map { it.url }) }, Modifier.fillMaxWidth().padding(top = 10.dp),
                        icon = QtIcons.Download, secondary = true, enabled = items.isNotEmpty())
                }
            }
            if (entries?.isEmpty() == true) item(key = "empty") {
                EmptyBox("Nothing here yet", if (list.isMusic) "Tap + in the music player to save songs here" else "Tap + on a video page to save videos here",
                    Modifier.fillMaxWidth().padding(top = 40.dp), QtIcons.PlaylistAdd)
            }
            itemsIndexed(entries.orEmpty(), key = { _, e -> e.id }) { i, e -> EntryRow(e, list.isMusic, { onPlay(list, items, i) }, { vm.remove(e) }) }
        }
    }
    if (renaming && list != null) NameDialog("Rename playlist", list.name, confirm = "Save", onDismiss = { renaming = false }) { vm.rename(it) }
    if (deleting) ConfirmDialog("Delete this playlist?", "Only the list is removed; downloaded files stay.", "Delete", { vm.delete(); onBack() }, { deleting = false })
}

@Composable
private fun EntryRow(e: LocalPlaylistEntry, music: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, role = null, onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(e.item.thumbnail, Modifier.width(if (music) 56.dp else 128.dp).aspectRatio(if (music) 1f else 16f / 9f), corner = 12) {
            if (!music && e.item.durationSec > 0) CornerLabel(Format.duration(e.item.durationSec))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(e.item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.item.uploader.isNotBlank()) Text(e.item.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        QtIconButton(QtIcons.Close, "Remove from playlist", onRemove, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** "1 song", "12 videos". */
internal fun itemCount(n: Int, music: Boolean) = "$n " + when { music && n == 1 -> "song"; music -> "songs"; n == 1 -> "video"; else -> "videos" }
