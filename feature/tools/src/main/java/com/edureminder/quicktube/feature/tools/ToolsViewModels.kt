package com.edureminder.quicktube.feature.tools

import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.media.PlayItem
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.ContentType
import com.edureminder.quicktube.core.model.DownloadStatus
import com.edureminder.quicktube.core.model.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- Recycle bin ----------

class RecycleBinViewModel(private val library: LibraryRepository) : ViewModel() {
    val items = library.trash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun restore(ids: List<Long>) = viewModelScope.launch { library.restore(ids) }
    fun deleteForever(ids: List<Long>) = viewModelScope.launch { library.deleteForever(ids) }
}

// ---------- Vault ----------

class VaultViewModel(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val player: PlayerController,
) : ViewModel() {
    val items = library.downloads.map { l -> l.filter { it.inVault && it.status == DownloadStatus.COMPLETED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pin = settings.settings.map { it.vaultPin }.stateIn(viewModelScope, SharingStarted.Eagerly, "loading")
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    fun setPin(p: String) = viewModelScope.launch { settings.update { it.copy(vaultPin = p) }; _unlocked.value = true }
    fun tryUnlock(p: String): Boolean = (p == pin.value).also { if (it) _unlocked.value = true }
    fun lock() { _unlocked.value = false }
    fun unlockItems(ids: List<Long>) = viewModelScope.launch { library.setVault(ids, false) }
    fun delete(ids: List<Long>) = viewModelScope.launch { library.moveToTrash(ids) }
    fun play(task: DownloadTask) {
        val list = items.value.filter { it.type == task.type }
        player.play(list.map {
            PlayItem("dl:${it.id}", File(it.filePath).toURI().toString(), it.title, it.sourceHost.orEmpty(), it.thumbnail, it.type == ContentType.VIDEO)
        }, list.indexOf(task).coerceAtLeast(0))
    }
}

// ---------- WhatsApp status saver ----------

data class StatusFile(val name: String, val uri: Uri, val isVideo: Boolean, val modified: Long, val size: Long)

class StatusSaverViewModel(
    private val app: Application,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) : AndroidViewModel(app) {
    private val _statuses = MutableStateFlow<List<StatusFile>>(emptyList())
    val statuses: StateFlow<List<StatusFile>> = _statuses
    private val _needsAccess = MutableStateFlow(false)
    val needsAccess: StateFlow<Boolean> = _needsAccess
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val prefs = app.getSharedPreferences("status_saver", 0)

    /** Folder URIs WhatsApp (and WhatsApp Business) keep statuses in on Android 11+. */
    val initialTreeUri: Uri = Uri.parse(
        "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia%2F.Statuses",
    )

    fun refresh() = viewModelScope.launch {
        _loading.value = true
        val list = withContext(Dispatchers.IO) {
            val found = mutableListOf<StatusFile>()
            if (Build.VERSION.SDK_INT >= 30) {
                val saved = prefs.getString("tree", null)?.let(Uri::parse)
                val granted = saved != null && app.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isReadPermission }
                if (!granted) { _needsAccess.value = true; return@withContext emptyList() }
                _needsAccess.value = false
                DocumentFile.fromTreeUri(app, saved!!)?.listFiles()?.forEach { f ->
                    val n = f.name ?: return@forEach
                    if (n.startsWith(".")) return@forEach
                    val video = n.endsWith(".mp4", true)
                    if (video || n.endsWith(".jpg", true) || n.endsWith(".png", true) || n.endsWith(".webp", true))
                        found += StatusFile(n, f.uri, video, f.lastModified(), f.length())
                }
            } else {
                listOf("WhatsApp/Media/.Statuses", "Android/media/com.whatsapp/WhatsApp/Media/.Statuses", "WhatsApp Business/Media/.Statuses")
                    .map { File(Environment.getExternalStorageDirectory(), it) }
                    .flatMap { it.listFiles()?.toList().orEmpty() }
                    .filter { !it.name.startsWith(".") }
                    .forEach { f -> found += StatusFile(f.name, Uri.fromFile(f), f.name.endsWith(".mp4", true), f.lastModified(), f.length()) }
            }
            found.sortedByDescending { it.modified }
        }
        _statuses.value = list
        _loading.value = false
    }

    fun onTreeGranted(uri: Uri) {
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString("tree", uri.toString()).apply()
        refresh()
    }

    fun save(file: StatusFile, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(settings.current().downloadDir, "Status").apply { mkdirs() }
                val target = File(dir, file.name)
                app.contentResolver.openInputStream(file.uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                android.media.MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), null, null)
                val mime = if (file.isVideo) "video/mp4" else "image/jpeg"
                library.addLocalFile(
                    DownloadTask(
                        title = "Status " + file.name.substringBeforeLast('.'), pageUrl = null, sourceHost = "WhatsApp", thumbnail = null,
                        url = file.uri.toString(), audioUrl = null, filePath = target.absolutePath, mimeType = mime,
                        type = ContentType.fromMime(mime), quality = null, totalBytes = target.length(), downloadedBytes = target.length(),
                        status = DownloadStatus.COMPLETED, completedAt = System.currentTimeMillis(),
                    ),
                )
            }.isSuccess
        }
        done(ok)
    }
}

// ---------- Large files ----------

data class LargeFile(val id: Long, val uri: Uri, val name: String, val size: Long, val mime: String, val path: String?)

class LargeFilesViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _files = MutableStateFlow<List<LargeFile>?>(null)
    val files: StateFlow<List<LargeFile>?> = _files

    fun scan(minBytes: Long = 10L * 1024 * 1024) = viewModelScope.launch {
        _files.value = null
        _files.value = withContext(Dispatchers.IO) {
            val out = mutableListOf<LargeFile>()
            val collections = listOf(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            )
            for (c in collections) runCatching {
                app.contentResolver.query(
                    c, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA),
                    "${MediaStore.MediaColumns.SIZE} >= ?", arrayOf(minBytes.toString()), "${MediaStore.MediaColumns.SIZE} DESC",
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getLong(0)
                        out += LargeFile(id, ContentUris.withAppendedId(c, id), cur.getString(1) ?: "file", cur.getLong(2), cur.getString(3) ?: "", cur.getString(4))
                    }
                }
            }
            out.sortedByDescending { it.size }
        }
    }

    fun removeLocal(uris: Set<Uri>) { _files.value = _files.value?.filterNot { it.uri in uris } }
}

// ---------- App uninstaller ----------

data class AppEntry(val packageName: String, val label: String, val version: String, val installed: Long, val apkSize: Long)

class AppManagerViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps: StateFlow<List<AppEntry>?> = _apps

    fun load() = viewModelScope.launch {
        _apps.value = withContext(Dispatchers.IO) {
            val pm = app.packageManager
            pm.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != app.packageName }
                .map { ai ->
                    val pi = runCatching { pm.getPackageInfo(ai.packageName, 0) }.getOrNull()
                    AppEntry(ai.packageName, ai.loadLabel(pm).toString(), pi?.versionName.orEmpty(), pi?.firstInstallTime ?: 0, File(ai.sourceDir).length())
                }
                .sortedBy { it.label.lowercase() }
        }
    }
}

// ---------- Storage clean ----------

data class CleanItem(val key: String, val title: String, val description: String, val bytes: Long)

class StorageCleanViewModel(
    private val app: Application,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) : AndroidViewModel(app) {
    private val _items = MutableStateFlow<List<CleanItem>?>(null)
    val items: StateFlow<List<CleanItem>?> = _items
    val storage = MutableStateFlow(0L to 0L)

    fun scan() = viewModelScope.launch {
        _items.value = null
        _items.value = withContext(Dispatchers.IO) {
            val st = StatFs(Environment.getExternalStorageDirectory().path)
            storage.value = (st.totalBytes - st.availableBytes) to st.totalBytes
            val cache = dirSize(app.cacheDir) + (app.externalCacheDir?.let(::dirSize) ?: 0)
            val dir = File(settings.current().downloadDir)
            val activeParts = library.downloads.let { flow ->
                flow.first().filter { it.status != DownloadStatus.COMPLETED }.flatMap { listOf(it.filePath + ".part", it.filePath + ".audio.part") }.toSet()
            }
            val leftovers = dir.listFiles()?.filter { it.name.endsWith(".part") && it.absolutePath !in activeParts }.orEmpty()
            val trash = library.trash.first().sumOf { File(it.filePath).length() }
            listOf(
                CleanItem("cache", "App cache", "Thumbnails and temporary data", cache),
                CleanItem("parts", "Unfinished download leftovers", "Pieces of cancelled downloads", leftovers.sumOf { it.length() }),
                CleanItem("trash", "Recycle bin", "Deleted downloads kept for 30 days", trash),
            )
        }
    }

    fun clean(keys: Set<String>, done: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            if ("cache" in keys) { app.cacheDir.deleteRecursively(); app.externalCacheDir?.deleteRecursively() }
            if ("parts" in keys) {
                val active = library.downloads.first().filter { it.status != DownloadStatus.COMPLETED }
                    .flatMap { listOf(it.filePath + ".part", it.filePath + ".audio.part") }.toSet()
                File(settings.current().downloadDir).listFiles()?.filter { it.name.endsWith(".part") && it.absolutePath !in active }?.forEach { it.delete() }
            }
            if ("trash" in keys) library.deleteForever(library.trash.first().map { it.id })
        }
        scan()
        done()
    }

    private fun dirSize(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0
}

