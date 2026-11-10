package com.edureminder.quicktube.feature.gallery

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.designsystem.QtChip
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.core.navigation.GALLERY_ALL
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Room the selection bar takes above the navigation bar, for lists to keep their last row clear of it. */
internal val SelectionBarSpace = 110.dp

/**
 * What to do with the selected media, in a floating glass bar: Share, Copy to…, Move to…, Delete, and More (select all;
 * play / add to queue for songs). Each action has its own colour so the bar reads at a glance.
 */
@Composable
fun SelectionBar(
    selected: List<GalleryMedia>,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** Songs: "Play" / "Add to queue" open the player through this. */
    onOpenPlayer: ((GalleryMedia) -> Unit)? = null,
    /** Photos: "Make collage" opens the collage maker with them. */
    onCollage: ((List<GalleryMedia>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val delete = rememberDeleteAction(onDone)
    val controller = koinInject<PlayerController>()
    var target by remember { mutableStateOf<Boolean?>(null) } // true = move, false = copy
    var more by remember { mutableStateOf(false) }
    val enabled = selected.isNotEmpty()
    val shape = RoundedCornerShape(30.dp)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth()
                .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(shape)
                .background(Brush.verticalGradient(listOf(cs.surfaceContainerHighest, cs.surfaceContainerHigh)))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), cs.outlineVariant.copy(alpha = 0.4f))), shape)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(70.dp)) {
                GlassAction(QtIcons.Share, "Share", Color(0xFF3D7BE0), enabled) { GalleryActions.share(context, selected) }
                GlassAction(QtIcons.ContentCopy, "Copy", Color(0xFF14A38B), enabled) { target = false }
                GlassAction(QtIcons.Folder, "Move", Color(0xFF8A5CF6), enabled) { target = true }
                GlassAction(QtIcons.Delete, "Delete", Color(0xFFE5484D), enabled) { delete(selected) }
                Box(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxSize()) { GlassAction(QtIcons.MoreHoriz, "More", cs.onSurfaceVariant, true) { more = true } }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text(if (allSelected) "Clear selection" else "Select all") }, leadingIcon = { Icon(QtIcons.SelectAll, null) },
                            onClick = { more = false; onSelectAll() })
                        val pics = selected.filter { it.isPhoto }
                        if (pics.isNotEmpty() && onCollage != null) DropdownMenuItem(
                            text = { Text("Make collage") }, leadingIcon = { Icon(QtIcons.Collections, null) },
                            onClick = {
                                more = false
                                if (pics.size > 9) Toast.makeText(context, "A collage holds up to 9 photos; using the first 9", Toast.LENGTH_SHORT).show()
                                onCollage(pics.take(9)); onDone()
                            },
                        )
                        val songs = selected.filter { it.isAudio }
                        if (songs.isNotEmpty() && onOpenPlayer != null) {
                            DropdownMenuItem(text = { Text("Play ${songs.size}") }, leadingIcon = { Icon(QtIcons.Play, null) }, onClick = {
                                more = false; GalleryQueue.play(controller, songs)?.let(onOpenPlayer); onDone()
                            })
                            DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(QtIcons.PlaylistAdd, null) }, onClick = {
                                more = false
                                if (GalleryQueue.isPlayingGallery(controller)) {
                                    GalleryQueue.add(controller, songs)
                                    Toast.makeText(context, "Added ${songs.size} to the queue", Toast.LENGTH_SHORT).show()
                                } else GalleryQueue.play(controller, songs)?.let(onOpenPlayer)
                                onDone()
                            })
                            DropdownMenuItem(text = { Text("Play next") }, leadingIcon = { Icon(QtIcons.SkipNext, null) }, onClick = {
                                more = false
                                if (GalleryQueue.isPlayingGallery(controller)) {
                                    GalleryQueue.add(controller, songs, next = true)
                                    Toast.makeText(context, "Plays next", Toast.LENGTH_SHORT).show()
                                } else GalleryQueue.play(controller, songs)?.let(onOpenPlayer)
                                onDone()
                            })
                        }
                    }
                }
            }
        }
    }
    target?.let { move -> CopyMoveSheet(selected, move, onDismiss = { target = null }, onDone = { target = null; onDone() }) }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GlassAction(icon: ImageVector, label: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) tint else tint.copy(alpha = 0.35f)
    Column(
        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(22.dp)).pressable(enabled = enabled, scaleDown = 0.88f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = color, modifier = Modifier.size(21.dp))
        }
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            fontSize = 11.sp, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

/** "Copy to" / "Move to": QuickTube's download folder, every gallery folder, or a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopyMoveSheet(items: List<GalleryMedia>, move: Boolean, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val vm = koinViewModel<GalleryViewModel>(key = "gallery-all") { parametersOf(GALLERY_ALL) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings = koinInject<SettingsRepository>()
    val downloadDir by remember(settings) { settings.settings.map { it.downloadDir } }.collectAsStateWithLifecycle("")
    val folders = remember(ui.albums, ui.audioFolders, downloadDir) { GalleryFileOps.destinations(ui.albums + ui.audioFolders, downloadDir) }
    val here = remember(items) { items.mapNotNull { GalleryFileOps.relativeDir(it.path) }.toSet() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var pendingMove by remember { mutableStateOf<String?>(null) }

    fun report(r: FileOpResult, to: String) {
        val where = to.trimEnd('/').substringAfterLast('/')
        val verb = if (move) "Moved" else "Copied"
        val text = when {
            r.failed == 0 -> "$verb ${r.done} to $where"
            r.done == 0 -> r.reason ?: "Couldn't ${verb.lowercase().dropLast(1)}"
            else -> "$verb ${r.done} to $where · ${r.failed} couldn't: ${r.reason.orEmpty()}"
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        vm.load(force = true)
        onDone()
    }
    fun runMove(to: String) {
        busy = true
        scope.launch { report(GalleryFileOps.move(context, items, to), to); busy = false }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val to = pendingMove
        pendingMove = null
        if (r.resultCode == Activity.RESULT_OK && to != null) runMove(to) else busy = false
    }
    fun go(to: String) {
        if (busy) return
        if (!move) {
            busy = true
            scope.launch { report(GalleryFileOps.copy(context, items, to), to); busy = false }
            return
        }
        val request = runCatching { GalleryFileOps.moveRequest(context, items) }.getOrNull()
        if (request != null) { pendingMove = to; busy = true; consent.launch(IntentSenderRequest.Builder(request).build()) } else runMove(to)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text((if (move) "Move " else "Copy ") + "${items.size} " + (if (items.size == 1) "item" else "items") + " to",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            if (busy) Text(if (move) "Moving…" else "Copying…", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item(key = "new") { FolderRow(null, "New folder", "Make a folder and put them there") { newFolder = true } }
                items(folders, key = { it.relativePath }) { f ->
                    val same = move && f.relativePath in here && here.size == 1
                    FolderRow(f.cover, f.name, f.relativePath.trimEnd('/') + if (same) " · already here" else "", enabled = !same && !busy) { go(f.relativePath) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (newFolder) NewFolderDialog(onDismiss = { newFolder = false }) { rel -> newFolder = false; go(rel) }
}

@Composable
private fun FolderRow(cover: GalleryMedia?, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().pressable(enabled = enabled, scaleDown = 0.98f, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            when {
                cover == null -> Icon(if (title == "New folder") QtIcons.Add else QtIcons.Folder, null, tint = cs.primary)
                cover.isAudio -> AudioArt(cover, Modifier.fillMaxSize(), sizePx = 150, iconSize = 22.dp)
                else -> GalleryThumbnail(cover, Modifier.fillMaxSize(), sizePx = 150)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.4f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A new folder inside one of the phone's shared folders (the only places Android lets apps put media). */
@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val roots = listOf("Download", "Pictures", "DCIM", "Movies", "Music")
    var root by remember { mutableStateOf("Pictures") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            Column {
                Text("Inside", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    roots.forEach { r -> QtChip(r, root == r, { root = r }) }
                }
                OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Folder name") }, shape = RoundedCornerShape(14.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onCreate("$root/" + name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_") + "/")
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
