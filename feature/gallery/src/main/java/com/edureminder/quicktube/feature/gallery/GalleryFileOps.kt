package com.edureminder.quicktube.feature.gallery

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.edureminder.quicktube.core.model.GalleryAlbum
import com.edureminder.quicktube.core.model.GalleryMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A folder media can be copied or moved into, as a path under the phone's storage ("DCIM/Camera/"). */
data class MediaFolder(val name: String, val relativePath: String, val cover: GalleryMedia? = null)

/** What a copy or move did, for the message after it. */
data class FileOpResult(val done: Int, val failed: Int, val reason: String? = null)

/**
 * Copy and move for the gallery, like a file manager: a copy is a new file in the chosen folder; a move changes the
 * file's folder in place (Android asks once for permission to change files other apps made).
 */
object GalleryFileOps {
    private val root: File get() = Environment.getExternalStorageDirectory()

    /** "/storage/emulated/0/DCIM/Camera/a.jpg" → "DCIM/Camera/". */
    fun relativeDir(path: String, rootPath: String = Environment.getExternalStorageDirectory().absolutePath): String? {
        val dir = File(path).parent ?: return null
        if (!dir.startsWith(rootPath)) return null
        return dir.removePrefix(rootPath).trim('/').takeIf { it.isNotEmpty() }?.plus("/")
    }

    /** Where things can go: the app's download folder first, then every folder the gallery knows, without repeats. */
    fun destinations(albums: List<GalleryAlbum>, downloadDir: String): List<MediaFolder> {
        val out = LinkedHashMap<String, MediaFolder>()
        relativeDir("$downloadDir/x")?.let { out[it] = MediaFolder("QuickTube downloads", it) }
        albums.filter { !it.virtual }.forEach { a ->
            val rel = a.cover?.path?.let { relativeDir(it) } ?: return@forEach
            out.getOrPut(rel) { MediaFolder(a.name, rel, a.cover) }
        }
        return out.values.toList()
    }

    /** A name that isn't taken in [dir] yet: "a.jpg", "a (1).jpg"… */
    internal fun freeName(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        while (f.exists()) f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext").also { n++ }
        return f
    }

    suspend fun copy(context: Context, items: List<GalleryMedia>, to: String): FileOpResult = withContext(Dispatchers.IO) {
        val dir = File(root, to).apply { mkdirs() }
        var done = 0
        var reason: String? = null
        val made = ArrayList<String>()
        for (m in items) {
            val ok = runCatching {
                val dest = freeName(dir, m.name.ifBlank { "file" })
                context.contentResolver.openInputStream(m.uri)!!.use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
                dest.setLastModified(m.dateMs.takeIf { it > 0 } ?: System.currentTimeMillis())
                made += dest.absolutePath
            }.onFailure { reason = reasonFor(it, m, to) }.isSuccess
            if (ok) done++
        }
        if (made.isNotEmpty()) MediaScannerConnection.scanFile(context, made.toTypedArray(), null, null)
        FileOpResult(done, items.size - done, reason)
    }

    /**
     * Android 11+: the system's "allow QuickTube to modify these files?" request for [items] (null when none is needed,
     * as on older versions). Moving needs it once, then [move] does the rest.
     */
    fun moveRequest(context: Context, items: List<GalleryMedia>): IntentSender? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && items.isNotEmpty())
            MediaStore.createWriteRequest(context.contentResolver, items.map { it.uri }).intentSender
        else null

    suspend fun move(context: Context, items: List<GalleryMedia>, to: String): FileOpResult = withContext(Dispatchers.IO) {
        var done = 0
        var reason: String? = null
        val changed = ArrayList<String>()
        for (m in items) {
            if (relativeDir(m.path) == to) { done++; continue }
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // The media library moves the file itself: same file, new folder.
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, to) }
                    check(context.contentResolver.update(m.uri, values, null, null) > 0) { "not moved" }
                } else {
                    val dest = freeName(File(root, to).apply { mkdirs() }, m.name)
                    if (!File(m.path).renameTo(dest)) {
                        File(m.path).inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                        File(m.path).delete()
                    }
                    changed += m.path; changed += dest.absolutePath
                }
            }.onFailure { reason = reasonFor(it, m, to) }.isSuccess
            if (ok) done++
        }
        if (changed.isNotEmpty()) MediaScannerConnection.scanFile(context, changed.toTypedArray(), null, null)
        FileOpResult(done, items.size - done, reason)
    }

    /** Why a file couldn't go there, in words: Android keeps photos out of Music, songs out of DCIM, and so on. */
    private fun reasonFor(e: Throwable, m: GalleryMedia, to: String): String {
        val top = to.substringBefore('/')
        val kind = when { m.isAudio -> "Audio"; m.isVideo -> "Videos"; else -> "Photos" }
        return if (e is IllegalArgumentException || e.message.orEmpty().contains("not allowed", true) || e is SecurityException || e.message.orEmpty().contains("EPERM"))
            "$kind can't be put in $top on Android. Try Download or Pictures."
        else e.message ?: "Couldn't copy"
    }
}
