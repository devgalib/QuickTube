package com.edureminder.quicktube.feature.tools

import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.common.Permissions
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtCard
import com.edureminder.quicktube.core.designsystem.IconTile
import com.edureminder.quicktube.core.designsystem.pressable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.height
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.LoadingBox
import org.koin.compose.viewmodel.koinViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun StorageCleanScreen(onBack: () -> Unit) {
    val vm = koinViewModel<StorageCleanViewModel>()
    val items by vm.items.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf("cache", "parts")) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.scan() }
    Scaffold(topBar = { QtTopBar("Storage clean", onBack) }) { pad ->
        val list = items
        if (list == null) LoadingBox(Modifier.fillMaxSize().padding(pad))
        else Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.padding(20.dp)) {
                Text("${Format.bytes(storage.first)}", style = MaterialTheme.typography.headlineMedium)
                Text("used of ${Format.bytes(storage.second)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { if (storage.second > 0) storage.first.toFloat() / storage.second else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(8.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp)
            }
            QtCard {
                list.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    Row(
                        Modifier.fillMaxWidth().pressable(scaleDown = 0.985f, onClick = { selected = if (c.key in selected) selected - c.key else selected + c.key })
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(when (c.key) { "cache" -> QtIcons.CleaningServices; "parts" -> QtIcons.Downloading; else -> QtIcons.Delete }, size = 38.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(c.title, style = MaterialTheme.typography.bodyLarge)
                            Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(Format.bytes(c.bytes), style = MaterialTheme.typography.labelLarge)
                        Checkbox(c.key in selected, { selected = if (c.key in selected) selected - c.key else selected + c.key })
                    }
                }
            }
            val total = list.filter { it.key in selected }.sumOf { it.bytes }
            PillButton("Clean ${Format.bytes(total)}", onClick = {
                vm.clean(selected) { Toast.makeText(context, "Freed ${Format.bytes(total)}", Toast.LENGTH_SHORT).show() }
            }, modifier = Modifier.fillMaxWidth().padding(16.dp), enabled = total > 0)
        }
    }
}

@Composable
fun LargeFilesScreen(onBack: () -> Unit) {
    val vm = koinViewModel<LargeFilesViewModel>()
    val files by vm.files.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by remember { mutableStateOf(setOf<Uri>()) }
    val perms = Permissions.mediaRead
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.scan() }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) { vm.removeLocal(selected); selected = emptySet() }
    }
    LaunchedEffect(Unit) {
        if (Permissions.mediaUsable(context)) vm.scan()
        else permLauncher.launch(perms)
    }
    Scaffold(
        topBar = { QtTopBar("Large files", onBack) },
        bottomBar = {
            if (selected.isNotEmpty()) PillButton("Delete ${selected.size} (${Format.bytes(files.orEmpty().filter { it.uri in selected }.sumOf { it.size })})", onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    val pi = MediaStore.createDeleteRequest(context.contentResolver, selected.toList())
                    deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    selected.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                    vm.removeLocal(selected); selected = emptySet()
                }
            }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), icon = QtIcons.Delete)
        },
    ) { pad ->
        val list = files
        when {
            list == null -> LoadingBox(Modifier.fillMaxSize().padding(pad))
            list.isEmpty() -> EmptyBox("No large files", "Nothing over 10 MB was found", Modifier.fillMaxSize().padding(pad))
            else -> LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item { Text("${list.size} files · ${Format.bytes(list.sumOf { it.size })}", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                items(list, key = { it.uri.toString() }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (f.uri in selected) selected - f.uri else selected + f.uri }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.path?.removePrefix("/storage/emulated/0")?.substringBeforeLast('/') ?: f.mime, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Text(Format.bytes(f.size), style = MaterialTheme.typography.bodyMedium)
                        Checkbox(f.uri in selected, { selected = if (f.uri in selected) selected - f.uri else selected + f.uri })
                    }
                }
            }
        }
    }
}

@Composable
fun AppManagerScreen(onBack: () -> Unit) {
    val vm = koinViewModel<AppManagerViewModel>()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uninstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.load() }
    LaunchedEffect(Unit) { vm.load() }
    Scaffold(topBar = { QtTopBar("App uninstaller", onBack) }) { pad ->
        val list = apps
        if (list == null) LoadingBox(Modifier.fillMaxSize().padding(pad))
        else LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { Text("${list.size} installed apps", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
            items(list, key = { it.packageName }) { a ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1)
                        Text(
                            listOf(a.version.takeIf { it.isNotBlank() }?.let { "v$it" }, Format.bytes(a.apkSize),
                                if (a.installed > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.installed)) else null)
                                .filterNotNull().joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        uninstall.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.packageName}")))
                    }) { Icon(QtIcons.Delete, "Uninstall") }
                }
            }
        }
    }
}
