package com.edureminder.quicktube.core.downloadsheet

import com.edureminder.quicktube.core.designsystem.shimmer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.edureminder.quicktube.core.common.Permissions
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.Badge
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.pressable
import androidx.compose.foundation.shape.CircleShape
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import com.edureminder.quicktube.core.ui.CornerLabel
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.LoadingBox
import com.edureminder.quicktube.core.ui.Thumbnail
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Place once near the root; shows the format picker whenever [DownloadSheetController.open] is called. */
@Composable
fun DownloadSheetHost(
    onDownloadStarted: () -> Unit = {},
    onOpenInBrowser: ((String) -> Unit)? = null,
    /**
     * Called when the quick lookup found nothing: the caller loads the page in a real (invisible) browser, the
     * same way a pasted link is opened, and reopens the sheet with what it finds.
     */
    onDeepSearch: ((String) -> Unit)? = null,
    /** "Open" in the "already downloaded" dialog: show that download (Play tab, or Downloading if unfinished). */
    onOpenExisting: (com.edureminder.quicktube.core.model.DownloadTask) -> Unit = {},
) {
    val controller = koinInject<DownloadSheetController>()
    val target by controller.target.collectAsStateWithLifecycle()
    val t = target ?: return
    val vm = koinViewModel<DownloadSheetViewModel>()
    LaunchedEffect(t) { vm.load(t) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAll by remember(t) { mutableStateOf(false) }
    var deepSearching by remember(t) { mutableStateOf(false) }
    val notFound = !ui.loading && ui.options.isEmpty() && ui.error != null && !ui.isLive && t is SheetTarget.Page
    LaunchedEffect(t, notFound) {
        val page = ui.pageUrl ?: (t as? SheetTarget.Page)?.url
        if (!notFound || onDeepSearch == null || page == null) return@LaunchedEffect
        deepSearching = true
        onDeepSearch(page)
        // The browser reopens the sheet with a new target when it finds the video; otherwise show the error.
        kotlinx.coroutines.delay(35_000)
        deepSearching = false
    }
    // Android 10 and older need storage permission before saving into Downloads.
    var pending by remember { mutableStateOf<DownloadOption?>(null) }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val o = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (result.values.all { it }) {
            vm.download(o)
            Toast.makeText(context, "Added to downloads", Toast.LENGTH_SHORT).show()
            controller.dismiss()
            onDownloadStarted()
        } else Toast.makeText(context, "Storage permission is needed to save downloads", Toast.LENGTH_LONG).show()
    }

    val downloadNow = start@{ o: DownloadOption ->
        if (!Permissions.granted(context, Permissions.storageWrite)) {
            pending = o
            storageLauncher.launch(Permissions.storageWrite)
            return@start
        }
        vm.download(o)
        Toast.makeText(context, "Added to downloads", Toast.LENGTH_SHORT).show()
        controller.dismiss()
        onDownloadStarted()
    }
    // Same video in the same quality already saved (or still downloading): ask before downloading it again.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var duplicate by remember(t) { mutableStateOf<Pair<com.edureminder.quicktube.core.model.DownloadTask, DownloadOption>?>(null) }
    val startDownload = { o: DownloadOption ->
        scope.launch {
            val existing = vm.existing(o)
            if (existing != null) duplicate = existing to o else downloadNow(o)
        }
        Unit
    }
    duplicate?.let { (task, option) ->
        AlreadyDownloadedDialog(
            task = task,
            onOpen = { duplicate = null; controller.dismiss(); onOpenExisting(task) },
            onDownloadAgain = { duplicate = null; downloadNow(option) },
            onDismiss = { duplicate = null },
        )
    }
    // A sensible choice is ready: ~720p video (or the best below it), else the first format.
    var selectedId by remember(t) { mutableStateOf<String?>(null) }
    val selected = ui.options.firstOrNull { it.id == selectedId } ?: defaultChoice(ui.options)

    ModalBottomSheet(onDismissRequest = controller::dismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            AnimatedContent(showAll, label = "formats") { all ->
                Column(Modifier.fillMaxWidth()) {
                    if (all) {
                        Row(Modifier.padding(start = 8.dp, end = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            QtIconButton(QtIcons.ArrowBack, "Back", { showAll = false })
                            Text("All formats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp))
                        }
                    } else if (!ui.loading) {
                        Header(ui)
                    }
                    when {
                        ui.loading -> SheetSkeleton(null)
                        deepSearching -> SheetSkeleton("Looking for the video on the page…")
                        ui.options.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            ErrorBox(ui.error ?: "No downloadable media found on this page", if (ui.isLive) null else vm::retry,
                                Modifier.fillMaxWidth().heightIn(min = 180.dp))
                            if (ui.report.isNotEmpty()) TechDetails(ui.report)
                            val page = ui.pageUrl
                            if (onOpenInBrowser != null && page != null && !ui.isLive) PillButton(
                                "Open in QuickTube browser", { controller.dismiss(); onOpenInBrowser(page) },
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                        else -> {
                            val list = if (all) ordered(ui.options) else quickPicks(ui.options)
                            LazyColumn(Modifier.heightIn(max = if (all) 470.dp else 380.dp)) {
                                val audio = list.filter { it.kind == FormatKind.AUDIO }
                                val video = list.filter { it.kind == FormatKind.VIDEO }
                                if (audio.isNotEmpty()) {
                                    item { SectionLabel("Music") }
                                    items(audio, key = { it.id }) { OptionRow(it, all, it.id == selected?.id) { selectedId = it.id } }
                                }
                                if (video.isNotEmpty()) {
                                    item { SectionLabel("Video") }
                                    items(video, key = { it.id }) { OptionRow(it, all, it.id == selected?.id) { selectedId = it.id } }
                                }
                                if (!all && ui.options.size > list.size) item {
                                    Row(
                                        Modifier.fillMaxWidth().padding(top = 4.dp).pressable(scaleDown = 0.99f, onClick = { showAll = true })
                                            .padding(horizontal = 20.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("More formats", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f))
                                        Text("All ${ui.options.size}", style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Icon(QtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!ui.loading && !deepSearching && selected != null) {
                val size = selected.shownSize()
                PillButton(
                    "Download · " + shortName(selected) + (if (size > 0) " · " + Format.bytes(size) else ""),
                    { startDownload(selected) },
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp),
                    icon = QtIcons.Download,
                )
            }
        }
    }
}

/** ~720p video, or the best below it; audio only when the page has no video. */
private fun defaultChoice(all: List<DownloadOption>): DownloadOption? {
    val video = ordered(all).filter { it.kind == FormatKind.VIDEO }
    return video.lastOrNull { it.height in 1..720 } ?: video.getOrNull(video.size / 2) ?: all.firstOrNull()
}

private fun DownloadOption.shownSize(): Long = if (extractAudio && totalSize > 0) totalSize / 8 else totalSize

/** "720p", "HD", "M4A" — what the Download button says. */
private fun shortName(o: DownloadOption): String = when {
    o.kind == FormatKind.VIDEO && o.height > 0 -> "${o.height}p"
    else -> o.title.substringAfterLast('(', "").substringBefore(')').takeIf { it.isNotBlank() && it.length <= 8 }
        ?: o.title.substringBefore(" (").take(14)
}

/** Shimmering placeholder shaped like the sheet, so loading feels like the content arriving. */
@Composable
private fun SheetSkeleton(caption: String?) {
    val bone = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(104.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(bone).shimmer())
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Box(Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(bone).shimmer())
                Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(bone).shimmer())
            }
        }
        Spacer(Modifier.height(18.dp))
        repeat(4) { i ->
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(bone).shimmer())
                Box(Modifier.padding(start = 14.dp).width(if (i % 2 == 0) 120.dp else 150.dp).height(13.dp).clip(RoundedCornerShape(7.dp)).background(bone).shimmer())
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(48.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(bone).shimmer())
                Box(Modifier.padding(start = 14.dp).size(20.dp).clip(CircleShape).background(bone).shimmer())
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(bone).shimmer())
        caption?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
        }
    }
}

/**
 * Lowest to highest quality. Some sites don't say a file's resolution (Facebook's SD/HD files), so when every
 * video's size is known, size decides; otherwise resolution does.
 */
private fun ordered(all: List<DownloadOption>): List<DownloadOption> {
    val audio = all.filter { it.kind == FormatKind.AUDIO }
    val video = all.filter { it.kind == FormatKind.VIDEO }
    val sorted = if (video.isNotEmpty() && video.all { it.totalSize > 0 }) video.sortedBy { it.totalSize } else video.sortedBy { it.height }
    return audio + sorted
}

/** Like SnapTube: the two best audio formats and three videos (low, middle, best — up to 4K). "More formats" lists all. */
private fun quickPicks(all: List<DownloadOption>): List<DownloadOption> {
    val audio = all.filter { it.kind == FormatKind.AUDIO }.take(2)
    val video = ordered(all).filter { it.kind == FormatKind.VIDEO }
    if (video.size <= 3) return audio + video
    val byHeight = video.all { it.height > 0 }
    val low = if (byHeight) video.lastOrNull { it.height <= 360 } ?: video.first() else video.first()
    val mid = if (byHeight) video.lastOrNull { it.height in 361..720 } ?: video[video.size / 2] else video[video.size / 2]
    val picks = listOf(low, mid, video.last()).distinct()
    val filled = if (picks.size < 3) picks + (video - picks.toSet()).take(3 - picks.size) else picks
    return audio + filled.sortedBy { video.indexOf(it) }
}

/** Collapsible diagnostics so users can tell us exactly why a site failed. */
@Composable
private fun TechDetails(report: List<String>) {
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(onClick = { open = !open }).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(QtIcons.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Technical details", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Icon(QtIcons.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            val text = report.joinToString("\n")
            Text(
                text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
            )
            Text(
                "Copy details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).pressable(onClick = {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QuickTube details", text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }).padding(8.dp),
            )
        }
    }
}

@Composable
private fun Header(ui: SheetUi) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumbnail(ui.thumbnail, Modifier.width(104.dp).aspectRatio(16f / 9f), corner = 12) { CornerLabel(Format.duration(ui.durationSec)) }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(ui.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            ui.pageUrl?.let { u ->
                com.edureminder.quicktube.core.common.Urls.host(u)?.removePrefix("www.")?.let { host ->
                    Text(host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** One-line hint under a format in the full list (what it's good for). */
private fun hint(o: DownloadOption): String = when {
    o.kind == FormatKind.AUDIO -> if (o.ext == "m4a") "M4A, best choice for phones" else "${o.ext.uppercase()} audio"
    o.height in 1..480 -> "Normal quality, saves data"
    o.height in 481..720 -> "Clear view, quick to download"
    o.height in 721..1100 -> "Sharp detail for full screen"
    o.height > 1100 -> "Best detail for big screens"
    else -> o.info
}

@Composable
private fun OptionRow(o: DownloadOption, detailed: Boolean, selected: Boolean, onSelect: () -> Unit) {
    val audio = o.kind == FormatKind.AUDIO
    val tint = if (audio) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(bg).pressable(scaleDown = 0.99f, haptic = true, onClick = onSelect)
            .heightIn(min = if (detailed) 58.dp else 50.dp).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Icon(if (audio) QtIcons.MusicNote else QtIcons.PlayCircle, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                o.label?.takeIf { it == "HD" }?.let { Badge(it, Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.primary) }
            }
            if (detailed) Text(hint(o), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val size = o.shownSize()
        Text(if (size > 0) (if (o.extractAudio) "~" else "") + Format.bytes(size) else "", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        // Radio mark: filled with a check when chosen.
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                .then(if (selected) Modifier else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(QtIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
        }
    }
}

/** "Already downloaded — download it again?" with the existing file shown, like other downloaders. */
@Composable
private fun AlreadyDownloadedDialog(
    task: com.edureminder.quicktube.core.model.DownloadTask,
    onOpen: () -> Unit,
    onDownloadAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val done = task.status == com.edureminder.quicktube.core.model.DownloadStatus.COMPLETED
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (done) "Already downloaded" else "Already downloading", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(if (done) "Do you want to download it again?" else "It's in your downloads now. Download another copy?",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                Row(
                    Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumbnail(task.thumbnail, Modifier.width(88.dp).aspectRatio(16f / 9f), corner = 10)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(task.title + (task.quality?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            task.durationSec.takeIf { it > 0 }?.let { Format.duration(it) },
                            task.totalBytes.takeIf { it > 0 }?.let { Format.bytes(it) },
                        ).joinToString("  ·  ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    PillButton(if (done) "Open" else "View", onOpen, Modifier.weight(1f), secondary = true)
                    PillButton("Download again", onDownloadAgain, Modifier.weight(1.9f))
                }
            }
        }
    }
}
