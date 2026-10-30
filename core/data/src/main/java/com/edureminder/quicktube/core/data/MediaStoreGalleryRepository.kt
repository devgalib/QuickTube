package com.edureminder.quicktube.core.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.edureminder.quicktube.core.model.GalleryLibrary
import com.edureminder.quicktube.core.model.GalleryMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Reads images and videos from the MediaStore Files table, newest first, in pages: the first page is small so the
 * grid shows something at once, later pages are bigger. Only light columns are read; thumbnails come from MediaStore.
 */
internal class MediaStoreGalleryRepository(context: Context) : GalleryRepository {
    private val resolver = context.applicationContext.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<GalleryLibrary?>(null)
    override val library: StateFlow<GalleryLibrary?> = state.asStateFlow()
    private var job: Job? = null
    private var observing = false
    private var sortByExpression = true

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        // Media changes come in bursts (a camera shot, a download); reload once they settle.
        override fun onChange(selfChange: Boolean) = reload(debounceMs = 600)
    }

    @Synchronized
    override fun load(force: Boolean) {
        if (!observing) {
            resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
            observing = true
        }
        if (force || state.value == null || job == null) reload(debounceMs = 0)
    }

    @Synchronized
    private fun reload(debounceMs: Long) {
        job?.cancel()
        job = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            // A refresh keeps showing the old list until the new one is complete, so the grid never jumps back.
            val progressive = state.value == null
            val all = ArrayList<GalleryMedia>(state.value?.items?.size ?: FIRST_PAGE)
            val names = HashMap<Long, String>()
            var offset = 0
            var pageSize = FIRST_PAGE
            while (true) {
                ensureActive()
                // No permission (SecurityException) or a provider error ends the list where it is.
                val page = try { queryPage(offset, pageSize, names) } catch (e: RuntimeException) { emptyList<GalleryMedia>() to true }
                ensureActive()
                all.addAll(page.first)
                val done = page.second || page.first.size < pageSize
                if (progressive || done) state.value = GalleryLibrary(ArrayList(all), complete = done)
                if (done) break
                offset += page.first.size
                pageSize = PAGE
            }
        }
    }

    /** One page; the flag is true when the provider ignored the limit and returned everything at once. */
    private fun queryPage(offset: Int, limit: Int, names: HashMap<Long, String>): Pair<List<GalleryMedia>, Boolean> {
        val cursor = try {
            query(offset, limit, sortByExpression)
        } catch (e: IllegalArgumentException) {
            // Some providers refuse expressions in ORDER BY; plain column order is the next best thing.
            if (!sortByExpression) throw e
            sortByExpression = false
            query(offset, limit, false)
        } ?: return emptyList<GalleryMedia>() to true
        return cursor.use { c ->
            val unlimited = c.count > limit
            val out = ArrayList<GalleryMedia>(c.count)
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val type = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val taken = c.getColumnIndexOrThrow(DATE_TAKEN)
            val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val duration = c.getColumnIndexOrThrow(DURATION)
            val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val orientation = c.getColumnIndexOrThrow(ORIENTATION)
            val bucket = c.getColumnIndexOrThrow(BUCKET_ID)
            val bucketName = c.getColumnIndexOrThrow(BUCKET_NAME)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val data = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (c.moveToNext()) {
                val path = c.getStringOrNull(data).orEmpty()
                val bucketId = c.getLong(bucket)
                val rotated = c.getInt(orientation).let { it == 90 || it == 270 }
                val w = c.getInt(width)
                val h = c.getInt(height)
                val mod = c.getLong(modified)
                out += GalleryMedia(
                    id = c.getLong(id),
                    isVideo = c.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                    mimeType = c.getStringOrNull(mime).orEmpty(),
                    dateMs = c.getLong(taken).takeIf { it > 0 } ?: (mod * 1000),
                    modifiedSec = mod,
                    durationMs = c.getLong(duration),
                    width = if (rotated) h else w,
                    height = if (rotated) w else h,
                    bucketId = bucketId,
                    // One shared String per album instead of one per row.
                    bucketName = names.getOrPut(bucketId) { c.getStringOrNull(bucketName) ?: folderName(path) },
                    name = c.getStringOrNull(name) ?: path.substringAfterLast('/'),
                    sizeBytes = c.getLong(size),
                    path = path,
                )
            }
            out to unlimited
        }
    }

    private fun query(offset: Int, limit: Int, expression: Boolean): Cursor? {
        val sort = if (expression) "COALESCE($DATE_TAKEN, ${MediaStore.MediaColumns.DATE_MODIFIED} * 1000) DESC, ${MediaStore.MediaColumns._ID} DESC"
        else "$DATE_TAKEN DESC, ${MediaStore.MediaColumns._ID} DESC"
        val uri: Uri = MediaStore.Files.getContentUri("external")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, SELECTION)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            resolver.query(uri, PROJECTION, args, null)
        } else {
            resolver.query(uri, PROJECTION, SELECTION, null, "$sort LIMIT $limit OFFSET $offset")
        }
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun folderName(path: String) = File(path).parentFile?.name?.takeIf { it.isNotBlank() && it != "0" } ?: "Internal storage"

    private companion object {
        const val FIRST_PAGE = 120
        const val PAGE = 600
        // The Files table has these columns on every supported version, even before the constants were public.
        const val DATE_TAKEN = "datetaken"
        const val DURATION = "duration"
        const val ORIENTATION = "orientation"
        const val BUCKET_ID = "bucket_id"
        const val BUCKET_NAME = "bucket_display_name"
        val SELECTION = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ${MediaStore.MediaColumns.SIZE} > 0"
        @Suppress("DEPRECATION")
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.MIME_TYPE, DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED, DURATION, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            ORIENTATION, BUCKET_ID, BUCKET_NAME, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA,
        )
    }
}
