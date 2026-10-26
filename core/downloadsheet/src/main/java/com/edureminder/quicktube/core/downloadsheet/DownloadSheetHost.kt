package com.edureminder.quicktube.core.downloadsheet

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
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Place once near the root; shows the format picker whenever [DownloadSheetController.open] is called. */
@Composable
fun DownloadSheetHost(onDownloadStarted: () -> Unit = {}, onOpenInBrowser: ((String) -> Unit)? = null) {
    val controller = koinInject<DownloadSheetController>()
    val target by controller.target.collectAsStateWithLifecycle()
    val t = target ?: return
    val vm = koinViewModel<DownloadSheetViewModel>()
    LaunchedEffect(t) { vm.load(t) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAll by remember(t) { mutableStateOf(false) }
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

    ModalBottomSheet(onDismissRequest = controller::dismiss, sheetState = sheetState) {
        AnimatedContent(showAll, label = "formats") { all ->
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                if (all) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        QtIconButton(QtIcons.ArrowBack, "Back", { showAll = false })
                        Text("More formats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp))
                    }
                } else {
                    Header(ui)
                }
                when {
                    ui.loading -> LoadingBox(Modifier.fillMaxWidth().heightIn(min = 220.dp))
                    ui.error != null && ui.options.isEmpty() || ui.options.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        ErrorBox(ui.error ?: "No downloadable media found on this page", if (ui.isLive) null else vm::retry, Modifier.fillMaxWidth().heightIn(min = 200.dp))
                        if (ui.report.isNotEmpty()) TechDetails(ui.report)
                        val page = ui.pageUrl
                        if (onOpenInBrowser != null && page != null && !ui.isLive) PillButton(
                            "Open in QuickTube browser", { controller.dismiss(); onOpenInBrowser(page) },
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                    else -> {
                        val onPick = pick@{ o: DownloadOption ->
                            if (!Permissions.granted(context, Permissions.storageWrite)) {
                                pending = o
                                storageLauncher.launch(Permissions.storageWrite)
                                return@pick
                            }
                            vm.download(o)
                            Toast.makeText(context, "Added to downloads", Toast.LENGTH_SHORT).show()
                            controller.dismiss()
                            onDownloadStarted()
                        }
                        val list = if (all) ui.options else quickPicks(ui.options)
                        LazyColumn(Modifier.heightIn(max = 560.dp)) {
                            val audio = list.filter { it.kind == FormatKind.AUDIO }
                            val video = list.filter { it.kind == FormatKind.VIDEO }
                            if (audio.isNotEmpty()) {
                                item { GroupTitle("Music", QtIcons.MusicNote) }
                                items(audio, key = { it.id }) { OptionRow(it, all, onPick) }
                            }
                            if (video.isNotEmpty()) {
                                item { GroupTitle("Video", QtIcons.Movie) }
                                items(video, key = { it.id }) { OptionRow(it, all, onPick) }
                            }
                            if (!all && ui.options.size > list.size) item {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .pressable(scaleDown = 0.98f, onClick = { showAll = true }).padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(QtIcons.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Text("More formats", modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleSmall)
                                    Text("${ui.options.size} options", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Icon(QtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Two music + two video choices, like the quick picker in the original flow. */
private fun quickPicks(all: List<DownloadOption>): List<DownloadOption> {
    val audio = all.filter { it.kind == FormatKind.AUDIO }
    val video = all.filter { it.kind == FormatKind.VIDEO }.sortedBy { it.height }
    if (video.isEmpty() || video.all { it.height == 0 }) return audio.take(2) + video.take(4)
    val fast = video.lastOrNull { it.height in 1..360 } ?: video.first()
    val hq = video.lastOrNull { it.height in 361..720 } ?: video.last()
    return audio.take(2) + listOf(fast, hq).distinct()
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
    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumbnail(ui.thumbnail, Modifier.width(120.dp).aspectRatio(16f / 9f), corner = 14) { CornerLabel(Format.duration(ui.durationSec)) }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text("Download video as", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(ui.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun GroupTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun OptionRow(o: DownloadOption, detailed: Boolean, onPick: (DownloadOption) -> Unit) {
    val audio = o.kind == FormatKind.AUDIO
    val tint = if (audio) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).pressable(scaleDown = 0.98f, haptic = true, onClick = { onPick(o) })
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 46.dp, height = 34.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(o.ext.uppercase().take(4), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = tint)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                o.label?.let { Badge(it, Modifier.padding(start = 8.dp), color = if (it == "HD") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (detailed) Text(o.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Audio-only from a website video: the file kept is roughly an eighth of the video.
        val shown = if (o.extractAudio && o.totalSize > 0) o.totalSize / 8 else o.totalSize
        Text(if (shown > 0) (if (o.extractAudio) "~" else "") + Format.bytes(shown) else "", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(QtIcons.Download, null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}
