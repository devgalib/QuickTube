package com.edureminder.quicktube.feature.settings

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import java.io.File

private val SharedDirs = listOf(
    Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_MUSIC,
    Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DOCUMENTS,
)

/** A place files can be saved: the phone's own storage, or an SD card / USB drive, with the folders offered on it. */
internal data class StorageRoot(val label: String, val removable: Boolean, val folders: List<File>)

/**
 * The phone's storage and every SD card or USB drive in it. Each offers its shared folders (Download, Movies, Music,
 * DCIM, Pictures, Documents; Android 11+ lets apps save there on cards too) and, on a card, QuickTube's own folder,
 * which every Android version allows (Android removes it with the app).
 */
internal fun storageRoots(context: Context): List<StorageRoot> {
    val phone = StorageRoot("Phone storage", false, SharedDirs.map { Environment.getExternalStoragePublicDirectory(it) })
    val sm = context.getSystemService(StorageManager::class.java)
    // App folders per storage, like /storage/1A2B-3C4D/Android/data/<app>/files/Download.
    val appDirs = context.getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS).filterNotNull()
    val cards = sm?.storageVolumes.orEmpty()
        .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        .mapNotNull { v ->
            val root = volumeDir(v, appDirs) ?: return@mapNotNull null
            val own = appDirs.firstOrNull { it.path.startsWith(root.path + "/") }?.let { File(it, "QuickTube") }
            StorageRoot(v.getDescription(context) ?: "SD card", true, SharedDirs.map { File(root, it) } + listOfNotNull(own))
        }
    return listOf(phone) + cards
}

/** The top folder of [v] ("/storage/1A2B-3C4D"): asked directly on Android 11+, else read from the app's folder on it. */
private fun volumeDir(v: StorageVolume, appDirs: List<File>): File? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory?.let { return it }
    val uuid = v.uuid ?: return null
    return appDirs.firstOrNull { it.path.contains("/$uuid/") }?.path?.substringBefore("/Android/")?.let(::File)
}

/** A short name for a saved path: "Phone · Download/QuickTube", "SD card · Movies". */
internal fun folderLabel(context: Context, path: String): String {
    if (path.isBlank()) return ""
    val primary = Environment.getExternalStorageDirectory().absolutePath
    if (path.startsWith(primary)) return "Phone · " + path.removePrefix(primary).trim('/')
    val root = storageRoots(context).firstOrNull { r -> r.removable && r.folders.any { path.startsWith(it.parentFile?.path.orEmpty()) } }
    val inside = path.substringAfter("/files/", "").takeIf { path.contains("/Android/data/") }?.let { "QuickTube folder/$it" }
        ?: path.split('/').drop(3).joinToString("/")
    return "${root?.label ?: "SD card"} · $inside"
}

/** True when files can really be saved in [dir] (it's created if needed, and a test file is written and removed). */
internal fun canSaveIn(dir: File): Boolean = runCatching {
    dir.mkdirs()
    val probe = File(dir, ".quicktube-${System.nanoTime()}.tmp")
    probe.writeText("ok")
    probe.delete()
}.getOrDefault(false)

/**
 * Pick any folder inside the phone's shared folders (Download, Movies, Music, DCIM, Pictures, Documents), open
 * subfolders, make new ones. Only folders the app can really write to are accepted.
 */
@Composable
internal fun FolderPickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val storages = remember { storageRoots(context) }
    val tops = remember(storages) { storages.flatMap { it.folders } }
    // Where the list is: a storage's folders (storage set, dir null), or inside a folder.
    var storage by remember { mutableStateOf<StorageRoot?>(null) }
    var dir by remember {
        mutableStateOf<File?>(File(current).takeIf { c -> tops.any { c.path == it.path || c.path.startsWith(it.path + "/") } })
    }
    LaunchedEffect(Unit) { dir?.let { d -> storage = storages.firstOrNull { s -> s.folders.any { d.path.startsWith(it.path) } } } }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val here = dir
    val children = remember(here, creating) {
        here?.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()
    }
    val st = storage
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    here != null -> folderLabel(context, here.path)
                    st != null -> st.label
                    else -> "Choose where to save"
                },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    when {
                        // Storages: the phone, then each SD card or USB drive.
                        st == null -> {
                            items(storages, key = { it.label + it.folders.first().path }) { s ->
                                FolderRow(s.label, if (s.removable) QtIcons.Sd else QtIcons.Smartphone) { storage = s; error = null }
                            }
                            if (storages.none { it.removable }) item {
                                Text(
                                    "No SD card or USB drive found. When one is in the phone, it shows up here.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        here == null -> {
                            item { FolderRow("All storage", QtIcons.ArrowBack) { storage = null; error = null } }
                            items(st.folders, key = { it.path }) { f ->
                                val own = f.path.contains("/Android/data/")
                                FolderRow(if (own) "QuickTube folder (removed with the app)" else f.name, QtIcons.Folder) { dir = f; error = null }
                            }
                        }
                        else -> {
                            if (st.folders.none { it == here }) item { FolderRow("..", QtIcons.ArrowBack) { dir = here.parentFile; error = null } }
                            else item { FolderRow(st.label, QtIcons.ArrowBack) { dir = null; error = null } }
                            items(children, key = { it.path }) { f -> FolderRow(f.name, QtIcons.Folder) { dir = f; error = null } }
                        }
                    }
                }
                if (creating && here != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it.take(60) }, Modifier.weight(1f), singleLine = true, label = { Text("New folder") })
                    TextButton(onClick = {
                        val name = newName.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
                        if (name.isBlank()) return@TextButton
                        val f = File(here, name)
                        if (canSaveIn(f)) { dir = f; creating = false; newName = ""; error = null } else error = "Couldn't make that folder here"
                    }) { Text("Make") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = here != null, onClick = {
                val d = here ?: return@TextButton
                error = when {
                    canSaveIn(d) -> { onPick(d.absolutePath); null }
                    st?.removable == true -> "This Android version doesn't let apps save in this card folder. Use the QuickTube folder on the card instead."
                    else -> "QuickTube can't save files in this folder. Choose another one."
                }
            }) { Text("Use this folder") }
        },
        dismissButton = {
            Row {
                if (here != null) TextButton(onClick = { creating = !creating }) { Text("New folder") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun FolderRow(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(name, modifier = Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
