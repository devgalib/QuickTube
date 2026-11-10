package com.edureminder.quicktube.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edureminder.quicktube.core.common.DownloadErrors
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.designsystem.Badge
import com.edureminder.quicktube.core.designsystem.QtChip
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.download.DownloadManager
import com.edureminder.quicktube.core.model.ContentType
import com.edureminder.quicktube.core.model.DownloadStatus
import com.edureminder.quicktube.core.model.DownloadTask
import com.edureminder.quicktube.core.ui.CornerLabel
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.Thumbnail
import com.edureminder.quicktube.core.ui.rememberToaster
import com.edureminder.quicktube.core.ui.show
import com.edureminder.quicktube.desktop.player.DesktopPlayer
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.awt.Desktop
import java.io.File

private enum class Filter(val label: String) { ALL("All"), VIDEO("Videos"), MUSIC("Music"), ACTIVE("Downloading"), FAILED("Failed") }

/**
 * Library: downloads in progress at the top, finished files below, as a grid of cards that gets more columns as the
 * window grows. Files play in the app; "Show in folder" opens the file manager.
 */
@Composable
fun DownloadsScreen(onPlay: () -> Unit, onOpenSource: (String) -> Unit, onBack: (() -> Unit)? = null) {
    val library = koinInject<LibraryRepository>()
    val manager = koinInject<DownloadManager>()
    val player = koinInject<DesktopPlayer>()
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    val all by library.downloads.collectAsState(emptyList())
    val speeds by manager.speeds.collectAsState()
    var filter by remember { mutableStateOf(Filter.ALL) }
    val tasks = all.filter { !it.inVault }.filter {
        when (filter) {
            Filter.ALL -> true
            Filter.VIDEO -> it.type == ContentType.VIDEO && it.status == DownloadStatus.COMPLETED
            Filter.MUSIC -> it.type == ContentType.AUDIO && it.status == DownloadStatus.COMPLETED
            Filter.ACTIVE -> it.status.isActive || it.status == DownloadStatus.PAUSED
            Filter.FAILED -> it.status == DownloadStatus.FAILED
        }
    }.sortedWith(compareByDescending<DownloadTask> { it.status != DownloadStatus.COMPLETED }.thenByDescending { it.createdAt })

    fun play(t: DownloadTask) {
        if (!File(t.filePath).exists()) { toast.show("The file was moved or deleted"); return }
        player.play(t.filePath, t.title, key = t.filePath, isVideo = t.type == ContentType.VIDEO, artwork = t.thumbnail, historyKey = t.filePath)
        onPlay()
    }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) QtTopBar("Downloads", onBack) else Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            val running = all.count { it.status.isActive }
            if (running > 0) {
                QtIconButton(QtIcons.Pause, "Pause all", manager::pauseAll)
            }
            if (all.any { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }) QtIconButton(QtIcons.Play, "Resume all", manager::resumeAll)
            QtIconButton(QtIcons.Folder, "Open download folder", {
                all.firstOrNull()?.let { File(it.filePath).parentFile }?.let { openInFileManager(it) } ?: toast.show("Nothing downloaded yet")
            })
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { Filter.entries.forEach { f -> QtChip(f.label, f == filter, { filter = f }) } }
        if (tasks.isEmpty()) {
            EmptyBox("No downloads yet", "Search or paste a link on the Download tab, then choose a quality.", icon = QtIcons.DownloadDone)
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Adaptive(300.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(tasks, key = { it.id }) { t ->
                DownloadCard(
                    t, speeds[t.id] ?: 0,
                    onPlay = { play(t) },
                    onPause = { manager.pause(t.id) },
                    onResume = { manager.resume(t.id) },
                    onDelete = { scope.launch { manager.remove(listOf(t.id)) } },
                    onShow = { openInFileManager(File(t.filePath)) },
                    onSource = t.pageUrl?.let { { onOpenSource(it) } },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun DownloadCard(
    t: DownloadTask, speed: Long, onPlay: () -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    onDelete: () -> Unit, onShow: () -> Unit, onSource: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val done = t.status == DownloadStatus.COMPLETED
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .pressable(scaleDown = 0.985f, role = null, onClick = { if (done) onPlay() else if (t.status == DownloadStatus.PAUSED || t.status == DownloadStatus.FAILED) onResume() }),
    ) {
        Thumbnail(t.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 18) {
            if (done) CornerLabel(Format.duration(t.durationSec))
            if (!done) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(
                    when (t.status) { DownloadStatus.PAUSED -> QtIcons.Pause; DownloadStatus.FAILED -> QtIcons.Error; DownloadStatus.MERGING -> QtIcons.Sync; else -> QtIcons.Downloading },
                    null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.width(40.dp).height(40.dp),
                )
            }
            if (t.type == ContentType.AUDIO) Icon(QtIcons.MusicNote, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    t.quality?.let { Badge(it); Box(Modifier.width(6.dp)) }
                    Text(statusLine(t, speed), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (t.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                QtIconButton(QtIcons.MoreVert, "More", { menu = true }, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 36.dp)
                DropdownMenu(menu, { menu = false }) {
                    if (done) DropdownMenuItem(text = { Text("Play") }, leadingIcon = { Icon(QtIcons.Play, null) }, onClick = { menu = false; onPlay() })
                    if (done) DropdownMenuItem(text = { Text("Open with system player") }, leadingIcon = { Icon(QtIcons.OpenInNew, null) }, onClick = {
                        menu = false; runCatching { Desktop.getDesktop().open(File(t.filePath)) }
                    })
                    DropdownMenuItem(text = { Text("Show in folder") }, leadingIcon = { Icon(QtIcons.Folder, null) }, onClick = { menu = false; onShow() })
                    if (t.status.isActive) DropdownMenuItem(text = { Text("Pause") }, leadingIcon = { Icon(QtIcons.Pause, null) }, onClick = { menu = false; onPause() })
                    if (t.status == DownloadStatus.PAUSED || t.status == DownloadStatus.FAILED) DropdownMenuItem(
                        text = { Text(if (t.status == DownloadStatus.FAILED) "Retry" else "Resume") }, leadingIcon = { Icon(QtIcons.Refresh, null) },
                        onClick = { menu = false; onResume() },
                    )
                    onSource?.let { DropdownMenuItem(text = { Text("Go to the video's page") }, leadingIcon = { Icon(QtIcons.Link, null) }, onClick = { menu = false; it() }) }
                    DropdownMenuItem(text = { Text(if (done) "Move to recycle bin" else "Cancel download") }, leadingIcon = { Icon(QtIcons.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        if (!done && t.status != DownloadStatus.FAILED) LinearProgressIndicator(
            progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
    }
}

private fun statusLine(t: DownloadTask, speed: Long): String = when (t.status) {
    DownloadStatus.COMPLETED -> listOf(Format.bytes(t.totalBytes), t.sourceHost.orEmpty()).filter { it.isNotBlank() && it != "--" }.joinToString(" • ")
    DownloadStatus.RUNNING -> {
        val total = t.totalBytes + t.audioTotalBytes.coerceAtLeast(0)
        val done = t.downloadedBytes + t.audioDownloadedBytes
        "${Format.bytes(done)} / ${if (total > 0) Format.bytes(total) else "…"} • ${Format.speed(speed)}"
    }
    DownloadStatus.QUEUED -> "Waiting…"
    DownloadStatus.WAITING_NETWORK -> "Waiting for the internet"
    DownloadStatus.MERGING -> "Finishing… ${(t.progress * 100).toInt()}%"
    DownloadStatus.PAUSED -> "Paused • ${(t.progress * 100).toInt()}%"
    DownloadStatus.FAILED -> DownloadErrors.friendly(t.error)
}

/** Opens the folder (and selects the file where the OS supports it). */
internal fun openInFileManager(file: File) {
    val dir = if (file.isDirectory) file else file.parentFile ?: return
    runCatching {
        val d = Desktop.getDesktop()
        if (!file.isDirectory && d.isSupported(Desktop.Action.BROWSE_FILE_DIR)) d.browseFileDirectory(file) else d.open(dir)
    }.recoverCatching {
        // Linux desktops without AWT support: ask the file manager over D-Bus, else just open the folder.
        val select = ProcessBuilder("dbus-send", "--session", "--dest=org.freedesktop.FileManager1", "--type=method_call",
            "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems", "array:string:${file.toURI()}", "string:").start()
        if (select.waitFor() != 0) ProcessBuilder("xdg-open", dir.path).start()
    }
}
