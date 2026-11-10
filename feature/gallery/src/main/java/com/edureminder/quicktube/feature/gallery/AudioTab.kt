package com.edureminder.quicktube.feature.gallery

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.GalleryAlbum
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.core.navigation.GALLERY_ALL
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Big round-cornered actions at the top of the Audios tab and of an audio folder. */
@Composable
internal fun AudioActions(actions: List<Triple<String, ImageVector, () -> Unit>>, modifier: Modifier = Modifier) {
    val ex = LocalQtExtras.current
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.forEachIndexed { i, (label, icon, onClick) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                    .then(if (i == 0) Modifier.background(ex.brand) else Modifier.background(cs.surfaceContainerHigh))
                    .pressable(scaleDown = 0.95f, onClick = onClick).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, null, tint = if (i == 0) cs.onPrimary else cs.onSurface, modifier = Modifier.size(22.dp))
                Text(label, color = if (i == 0) cs.onPrimary else cs.onSurface, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp), maxLines = 1)
            }
        }
    }
}

/**
 * The Audios tab: Play all / Shuffle / a new queue picked from any folder / the queue now playing, then every folder that
 * holds audio (Music, Download, WhatsApp Audio, recordings…).
 */
internal fun LazyListScope.audiosPage(
    ui: GalleryUi,
    playing: Boolean,
    onPlayAll: (shuffle: Boolean) -> Unit,
    onNewQueue: () -> Unit,
    onShowQueue: () -> Unit,
    onOpenFolder: (GalleryAlbum) -> Unit,
    pinned: List<Long> = emptyList(),
    onTogglePin: (GalleryAlbum) -> Unit = {},
) {
    item(key = "actions") {
        AudioActions(listOf(
            Triple("Play all", QtIcons.Play) { onPlayAll(false) },
            Triple("Shuffle", QtIcons.Shuffle) { onPlayAll(true) },
            Triple("New queue", QtIcons.PlaylistAdd, onNewQueue),
            Triple(if (playing) "Queue" else "Queue", QtIcons.PlaylistPlay, onShowQueue),
        ))
    }
    item(key = "count") {
        Text("${ui.audioFolders.size} folders · ${ui.audios.size} audios", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
    }
    items(ui.audioFolders, key = { "af${it.bucketId}" }) { f ->
        AudioFolderRow(f, ui.audios, f.bucketId in pinned, { onTogglePin(f) }, Modifier.animateItem()) { onOpenFolder(f) }
    }
}

@Composable
private fun AudioFolderRow(
    folder: GalleryAlbum, audios: List<GalleryMedia>, isPinned: Boolean, onTogglePin: () -> Unit, modifier: Modifier, onClick: () -> Unit,
) {
    val total = remember(folder, audios) { audios.filter { it.bucketId == folder.bucketId }.sumOf { it.durationMs.coerceAtLeast(0) } }
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().pressable(scaleDown = 0.98f, role = null, onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            folder.cover?.let { AudioArt(it, Modifier.fillMaxSize(), sizePx = 180, iconSize = 26.dp) }
            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center) { Icon(QtIcons.Folder, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPinned) Icon(QtIcons.PushPin, "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 4.dp).size(16.dp))
                Text(folder.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(listOfNotNull("${folder.count} " + if (folder.count == 1) "audio" else "audios", total.takeIf { it > 0 }?.let { Format.durationMs(it) })
                .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Pin to the top of the list, or unpin.
        Box {
            Box(Modifier.size(44.dp).clip(CircleShape).pressable(scaleDown = 0.85f, onClick = { menu = true }), contentAlignment = Alignment.Center) {
                Icon(QtIcons.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PinMenu(menu, isPinned, onDismiss = { menu = false }) { menu = false; onTogglePin() }
        }
    }
}

/**
 * The playing queue, editable: tap a song to play it, move it up or down, take it out, empty the whole queue, or add
 * more songs from any folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioQueueSheet(controller: PlayerController, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (state.queue.isEmpty()) "Nothing queued" else "${state.index + 1} of ${state.queue.size}",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                TextButton(onClick = onAdd) { Icon(QtIcons.Add, null, Modifier.size(18.dp)); Text(" Add") }
                if (state.queue.isNotEmpty()) TextButton(onClick = { controller.clearQueue(); onDismiss() }) { Text("Clear", color = cs.error) }
            }
            if (state.queue.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(QtIcons.PlaylistPlay, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Text("Add songs from any folder to start a queue", color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(state.queue, key = { i, it -> "$i:${it.id}" }) { i, item ->
                    val now = i == state.index
                    Row(
                        Modifier.fillMaxWidth().background(if (now) cs.primary.copy(alpha = 0.1f) else Color.Transparent)
                            .pressable(scaleDown = 0.99f, role = null, onClick = { controller.skipTo(i) })
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(if (now) cs.primary else cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            if (now) Icon(if (state.isPlaying) QtIcons.Equalizer else QtIcons.Pause, null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
                            else Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (now) FontWeight.Bold else FontWeight.Medium,
                                color = if (now) cs.primary else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.artist.isNotBlank()) Text(item.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                        QtIconButton(QtIcons.ExpandMore, "Move down", { controller.moveInQueue(i, i + 1) }, tint = if (i < state.queue.lastIndex) cs.onSurfaceVariant else Color.Transparent)
                        QtIconButton(QtIcons.Close, "Remove from queue", { controller.removeFromQueue(i) }, tint = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

/**
 * Pick songs from any folder (search, folder chips, tick as many as wanted): play them as a new queue, or add them to
 * the queue that is playing.
 */
@Composable
fun AudioPickerScreen(add: Boolean, onBack: () -> Unit, onOpenPlayer: (GalleryMedia) -> Unit) {
    val vm = koinViewModel<GalleryViewModel>(key = "gallery-all") { parametersOf(GALLERY_ALL) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val controller = koinInject<PlayerController>()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var folder by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    // Kept in the order they were ticked: that's the queue's order.
    var picked by remember { mutableStateOf(listOf<GalleryMedia>()) }
    BackHandler(onBack = onBack)
    val shown = remember(ui.audios, folder, query) {
        ui.audios.filter { (folder == null || it.bucketId == folder) && (query.isBlank() || it.name.contains(query, true) || it.artist.contains(query, true)) }
    }
    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                QtIconButton(QtIcons.ArrowBack, "Back", onBack, Modifier.padding(start = 6.dp))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(if (add) "Add to queue" else "New queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("${picked.size} selected", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (picked.isNotEmpty()) TextButton(onClick = { picked = emptyList() }) { Text("Clear") }
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(QtIcons.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (query.isEmpty()) Text("Search audios", color = cs.onSurfaceVariant)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.edureminder.quicktube.core.designsystem.QtChip("All folders", folder == null, { folder = null })
                ui.audioFolders.forEach { f -> com.edureminder.quicktube.core.designsystem.QtChip(f.name, folder == f.bucketId, { folder = f.bucketId }) }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 110.dp + com.edureminder.quicktube.core.designsystem.LocalBottomBarHeight.current)) {
                items(shown, key = { it.id }) { m ->
                    val n = picked.indexOfFirst { it.id == m.id }
                    Row(
                        Modifier.fillMaxWidth().pressable(scaleDown = 0.99f, role = null, onClick = { picked = if (n >= 0) picked - m else picked + m })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AudioArt(m, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), sizePx = 140, iconSize = 20.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                            Text(m.name.substringBeforeLast('.'), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(m.artist.ifBlank { m.bucketName }, Format.durationMs(m.durationMs)).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                        // The queue position it will take, or an empty circle.
                        Box(Modifier.size(28.dp).clip(CircleShape).background(if (n >= 0) cs.primary else cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            if (n >= 0) Text("${n + 1}", color = cs.onPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (picked.isNotEmpty()) Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            com.edureminder.quicktube.core.designsystem.PillButton(
                if (add) "Add ${picked.size} to queue" else "Play ${picked.size}",
                {
                    if (add && GalleryQueue.isPlayingGallery(controller)) {
                        GalleryQueue.add(controller, picked)
                        Toast.makeText(context, "Added ${picked.size} to the queue", Toast.LENGTH_SHORT).show()
                        onBack()
                    } else GalleryQueue.play(controller, picked)?.let(onOpenPlayer)
                },
                Modifier.weight(1f), icon = if (add) QtIcons.PlaylistAdd else QtIcons.Play,
            )
            if (add) com.edureminder.quicktube.core.designsystem.PillButton("Play next", {
                if (GalleryQueue.isPlayingGallery(controller)) {
                    GalleryQueue.add(controller, picked, next = true)
                    Toast.makeText(context, "Plays next", Toast.LENGTH_SHORT).show()
                    onBack()
                } else GalleryQueue.play(controller, picked)?.let(onOpenPlayer)
            }, Modifier.width(130.dp), secondary = true)
        }
    }
}
