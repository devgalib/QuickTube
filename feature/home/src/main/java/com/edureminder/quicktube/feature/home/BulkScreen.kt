package com.edureminder.quicktube.feature.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.Platform
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtCard
import com.edureminder.quicktube.core.designsystem.QtChip
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.downloadsheet.BatchQuality
import com.edureminder.quicktube.core.downloadsheet.BulkDownloader
import com.edureminder.quicktube.core.downloadsheet.BulkItem
import com.edureminder.quicktube.core.downloadsheet.BulkJob
import com.edureminder.quicktube.core.downloadsheet.BulkStatus
import com.edureminder.quicktube.core.model.DownloadStatus
import com.edureminder.quicktube.core.model.DownloadTask
import com.edureminder.quicktube.core.ui.ConfirmDialog
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.Thumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel

/** How a link of a bulk stands, with its download's progress folded in. */
enum class LinkState(val label: String) {
    WAITING("Waiting"), CHECKING("Checking link"), DOWNLOADING("Downloading"), DOWNLOADED("Downloaded"),
    SKIPPED("Already downloaded"), NOT_FOUND("Not found"), FAILED("Not downloaded"),
}

data class BulkRow(val item: BulkItem, val state: LinkState, val task: DownloadTask?) {
    val progress: Float? get() = task?.takeIf { state == LinkState.DOWNLOADING && it.totalBytes > 0 }
        ?.let { (it.downloadedBytes + it.audioDownloadedBytes.coerceAtLeast(0)).toFloat() / (it.totalBytes + it.audioTotalBytes.coerceAtLeast(0)) }
}

data class BulkView(val job: BulkJob, val rows: List<BulkRow>) {
    fun count(s: LinkState) = rows.count { it.state == s }
    val finished get() = rows.count { it.state !in setOf(LinkState.WAITING, LinkState.CHECKING, LinkState.DOWNLOADING) }
}

class BulkViewModel(private val bulk: BulkDownloader, library: LibraryRepository, settings: SettingsRepository) : ViewModel() {
    val jobs = combine(bulk.jobs, library.downloads) { jobs, tasks ->
        val byId = tasks.associateBy { it.id }
        jobs.map { view(it, byId) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val downloadDir = settings.settings.map { it.downloadDir }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun start(name: String, links: List<String>, quality: BatchQuality, onStarted: (Long) -> Unit) = viewModelScope.launch {
        onStarted(bulk.start(name, links, quality))
    }

    fun retry(id: Long) = bulk.retry(id)
    fun delete(id: Long) = bulk.delete(id)

    companion object {
        fun view(job: BulkJob, tasks: Map<Long, DownloadTask>) = BulkView(job, job.items.map { i ->
            val task = tasks[i.downloadId]?.takeIf { it.deletedAt == 0L }
            val state = when (i.status) {
                BulkStatus.PENDING -> LinkState.WAITING
                BulkStatus.CHECKING -> LinkState.CHECKING
                BulkStatus.SKIPPED -> LinkState.SKIPPED
                BulkStatus.NOT_FOUND -> LinkState.NOT_FOUND
                BulkStatus.FAILED -> LinkState.FAILED
                BulkStatus.QUEUED -> when (task?.status) {
                    DownloadStatus.COMPLETED -> LinkState.DOWNLOADED
                    DownloadStatus.FAILED -> LinkState.FAILED
                    // Removed from the downloads list: it was downloaded, then deleted.
                    null -> LinkState.DOWNLOADED
                    else -> LinkState.DOWNLOADING
                }
            }
            BulkRow(i, state, task)
        })
    }
}

class BulkJobViewModel(private val id: Long, private val bulk: BulkDownloader, library: LibraryRepository) : ViewModel() {
    val view = combine(bulk.job(id), library.downloads) { job, tasks -> job?.let { BulkViewModel.view(it, tasks.associateBy { t -> t.id }) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun retry() = bulk.retry(id)
    fun delete() = bulk.delete(id)
}

/**
 * Bulk download: paste hundreds of links (or open a .txt file with one per line) from any mix of sites, name the bulk,
 * pick one quality, and everything is saved together in a folder of that name. Earlier bulks are listed below.
 */
@Composable
fun BulkScreen(initialText: String, initialName: String, onBack: () -> Unit, onOpenJob: (Long) -> Unit) {
    val vm = koinViewModel<BulkViewModel>()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val dir by vm.downloadDir.collectAsStateWithLifecycle()
    Scaffold(topBar = { QtTopBar("Bulk download", onBack, subtitle = "Many links at once, saved in one folder") }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "new") { NewBulk(initialText, initialName, dir) { name, links, q -> vm.start(name, links, q, onOpenJob) } }
            val list = jobs
            if (!list.isNullOrEmpty()) {
                item(key = "h") {
                    Text("Your bulk downloads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 20.dp, top = 26.dp, bottom = 10.dp))
                }
                items(list, key = { it.job.id }) { v -> BulkCard(v) { onOpenJob(v.job.id) } }
            }
        }
    }
}

@Composable
private fun NewBulk(initialText: String, initialName: String, downloadDir: String, onStart: (String, List<String>, BatchQuality) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var name by remember { mutableStateOf(initialName) }
    var text by remember { mutableStateOf(initialText) }
    var quality by remember { mutableStateOf(BatchQuality.P720) }
    var fileNote by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    val links = remember(text) { Urls.extractUrls(text) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (read == null) { fileNote = "Couldn't read that file"; return@launch }
            val found = Urls.extractUrls(read)
            fileNote = if (found.isEmpty()) "No links in that file" else "${found.size} links added from the file"
            text = (if (text.isBlank()) "" else text.trimEnd() + "\n") + found.joinToString("\n")
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
        }
    }
    QtCard(Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                name, { name = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Folder name") }, placeholder = { Text("e.g. Workout songs") },
                leadingIcon = { Icon(QtIcons.Folder, null) }, shape = RoundedCornerShape(16.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
            )
            Spacer(Modifier.height(12.dp))
            // Grows with the list up to a point, then scrolls inside: hundreds of links don't push the page away.
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 240.dp),
                label = { Text("Links, one per line") },
                placeholder = { Text("https://youtu.be/…\nhttps://www.tiktok.com/@…/video/…\nhttps://www.instagram.com/reel/…") },
                shape = RoundedCornerShape(16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (links.size) { 0 -> "No links yet"; 1 -> "1 link"; else -> "${links.size} links" } +
                        (fileNote?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (text.isNotEmpty()) TextButton(onClick = { text = ""; fileNote = null }) { Text("Clear") }
                TextButton(onClick = { picker.launch(arrayOf("text/plain", "text/*")) }) {
                    Icon(QtIcons.AudioFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open .txt")
                }
            }
            if (links.isNotEmpty()) PlatformCounts(links)
            Text("Quality", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BatchQuality.entries.forEach { q -> QtChip(q.label, quality == q, { quality = q }) }
            }
            val folder = name.trim().takeIf { it.isNotEmpty() }?.let { Urls.safeFileName(it, 80) } ?: "Bulk (today's date)"
            Text("Saved in ${downloadDir.split('/').drop(if (downloadDir.startsWith("/storage/emulated/")) 4 else 3).joinToString("/").ifBlank { "Download/QuickTube" }}/$folder · links already downloaded are skipped",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            PillButton(
                if (links.isEmpty()) "Add links to start" else "Download ${links.size} " + if (links.size == 1) "link" else "links",
                onClick = { if (!started) { started = true; onStart(name, links, quality) } },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp), icon = QtIcons.Download, enabled = links.isNotEmpty() && !started,
            )
        }
    }
}

/** "YouTube 12 · TikTok 3 · Web 1": which sites the pasted links are from. */
@Composable
private fun PlatformCounts(links: List<String>) {
    val counts = remember(links) { links.groupingBy { siteOf(it) }.eachCount().toList().sortedByDescending { it.second } }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        counts.forEach { (site, n) ->
            val p = site.first
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(platformIcon(p), null, Modifier.size(16.dp), tint = platformColor(p))
                Text("${site.second} $n", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** The link's platform, and its name: the platform's, or the site's own ("dailymotion.com") for other sites. */
private fun siteOf(link: String): Pair<Platform, String> {
    val p = Urls.platformOf(link)
    return p to if (p == Platform.OTHER) Urls.host(link)?.removePrefix("www.")?.removePrefix("m.") ?: p.title else p.title
}

private fun platformIcon(p: Platform): ImageVector = when (p) {
    Platform.YOUTUBE -> com.edureminder.quicktube.core.designsystem.icon.BrandIcons.YouTube
    Platform.INSTAGRAM -> com.edureminder.quicktube.core.designsystem.icon.BrandIcons.Instagram
    Platform.FACEBOOK -> com.edureminder.quicktube.core.designsystem.icon.BrandIcons.Facebook
    Platform.TIKTOK -> com.edureminder.quicktube.core.designsystem.icon.BrandIcons.TikTok
    Platform.TWITTER -> com.edureminder.quicktube.core.designsystem.icon.BrandIcons.X
    Platform.SOUNDCLOUD -> com.edureminder.quicktube.core.designsystem.icon.BrandIcons.SoundCloud
    Platform.DAILYMOTION -> com.edureminder.quicktube.core.designsystem.icon.BrandIcons.Dailymotion
    Platform.VIMEO -> com.edureminder.quicktube.core.designsystem.icon.BrandIcons.Vimeo
    else -> QtIcons.Public
}

private fun platformColor(p: Platform): Color = when (p) {
    Platform.YOUTUBE -> Color(0xFFFF0033)
    Platform.INSTAGRAM -> Color(0xFFE1306C)
    Platform.FACEBOOK -> Color(0xFF1877F2)
    Platform.SOUNDCLOUD -> Color(0xFFFF5500)
    else -> Color.Unspecified
}

@Composable
private fun stateColor(s: LinkState): Color = when (s) {
    LinkState.DOWNLOADED -> Color(0xFF1E9E5A)
    LinkState.SKIPPED -> Color(0xFF3D7BE0)
    LinkState.NOT_FOUND -> Color(0xFFE08A00)
    LinkState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun BulkCard(v: BulkView, onClick: () -> Unit) {
    val total = v.rows.size.coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).pressable(scaleDown = 0.98f, onClick = onClick).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(QtIcons.Folder, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(v.job.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${v.rows.size} links · ${v.job.quality.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(QtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { v.finished.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(6.dp).clip(CircleShape),
        )
        StatusCounts(v, Modifier.padding(top = 10.dp))
    }
}

/** Coloured counts: "12 downloaded · 3 skipped · 1 not found". */
@Composable
private fun StatusCounts(v: BulkView, modifier: Modifier = Modifier, selected: LinkState? = null, onSelect: ((LinkState?) -> Unit)? = null) {
    val shown = listOf(LinkState.DOWNLOADED, LinkState.DOWNLOADING, LinkState.CHECKING, LinkState.WAITING, LinkState.SKIPPED, LinkState.NOT_FOUND, LinkState.FAILED)
        .filter { v.count(it) > 0 }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { s ->
            val color = stateColor(s)
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (selected == s) color.copy(alpha = 0.25f) else color.copy(alpha = 0.1f))
                    .then(if (onSelect != null) Modifier.pressable(scaleDown = 0.95f, onClick = { onSelect(if (selected == s) null else s) }) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text("${v.count(s)} ${s.label.lowercase()}", style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** One bulk: every link with what became of it; filter by status, retry the failed ones. */
@Composable
fun BulkJobScreen(id: Long, onBack: () -> Unit, onOpenLink: (String) -> Unit, onOpenDownloads: () -> Unit) {
    val vm = koinViewModel<BulkJobViewModel>(key = "bulk$id") { org.koin.core.parameter.parametersOf(id) }
    val v by vm.view.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<LinkState?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val view = v
    Scaffold(topBar = {
        QtTopBar(view?.job?.name ?: "Bulk download", onBack, subtitle = view?.let { "${it.finished} of ${it.rows.size} done · folder “${it.job.folder}”" }) {
            QtIconButton(QtIcons.Downloading, "Downloads", onOpenDownloads)
            QtIconButton(QtIcons.Delete, "Remove this bulk", { confirmDelete = true })
        }
    }) { pad ->
        if (view == null) { Box(Modifier.fillMaxSize().padding(pad)); return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "top") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    LinearProgressIndicator(
                        progress = { view.finished.toFloat() / view.rows.size.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).clip(CircleShape),
                    )
                    StatusCounts(view, Modifier.padding(top = 12.dp), filter) { filter = it }
                    val retryable = view.count(LinkState.FAILED) + view.count(LinkState.NOT_FOUND)
                    if (retryable > 0) PillButton("Try $retryable again", vm::retry, Modifier.padding(top = 12.dp), icon = QtIcons.Refresh, secondary = true)
                }
            }
            val rows = view.rows.filter { filter == null || it.state == filter }
            if (rows.isEmpty()) item(key = "empty") { EmptyBox("Nothing here", "No links with this status", Modifier.fillMaxWidth().padding(top = 40.dp)) }
            items(rows, key = { it.item.id }) { r -> LinkRow(r, onOpenLink) }
        }
    }
    if (confirmDelete) ConfirmDialog("Remove this bulk?", "The list is removed. Files already downloaded stay in the folder.", "Remove",
        { vm.delete(); onBack() }, { confirmDelete = false })
}

@Composable
private fun LinkRow(r: BulkRow, onOpenLink: (String) -> Unit) {
    val p = Urls.platformOf(r.item.link)
    val color = stateColor(r.state)
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, role = null, onClick = { onOpenLink(r.item.link) })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = r.task?.thumbnail
        Box(Modifier.width(96.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            if (thumb != null) Thumbnail(thumb, Modifier.fillMaxSize(), corner = 12)
            else Icon(platformIcon(p), null, tint = platformColor(p).takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(r.item.title ?: r.task?.title ?: r.item.link.removePrefix("https://").removePrefix("www."), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                val pct = r.progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                Text(r.state.label + pct, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(start = 6.dp))
                Text(" · ${siteOf(r.item.link).second}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val why = r.item.message?.takeIf { r.state == LinkState.NOT_FOUND || r.state == LinkState.FAILED } ?: r.task?.error?.takeIf { r.state == LinkState.FAILED }
            if (why != null) Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            r.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(CircleShape)) }
        }
    }
}
