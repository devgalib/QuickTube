package com.edureminder.quicktube.feature.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.edureminder.quicktube.core.model.GalleryMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** content:// URI to open, share or delete this item (the per-type collection, which other apps accept). */
val GalleryMedia.uri: Uri
    get() = ContentUris.withAppendedId(
        when {
            isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            isAudio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        },
        id,
    )

/** Thumbnail size for grid cells: a 4-column grid on a 720px-wide phone has ~178px cells. */
const val THUMB_PX = 192

private data class ThumbKey(val id: Long, val modifiedSec: Long, val px: Int)

/**
 * MediaStore's own cached thumbnails, kept in a small LRU. Much cheaper than decoding photos or video frames,
 * and lighter per cell than a general image loader: no request objects, painters or listeners while flinging.
 */
private object Thumbs {
    // An eighth of the heap, as Android's caching guide suggests; ~190 grid thumbnails on a 256MB heap.
    private val cache = object : LruCache<ThumbKey, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: ThumbKey, value: ImageBitmap) = value.width * value.height * 4
    }

    // A few at a time: more parallel decodes only make the UI thread wait for CPU on a 4-core phone.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(3)

    // Files with no thumbnail (most audio has no album art) aren't asked again while the app runs.
    private val misses = java.util.concurrent.ConcurrentHashMap.newKeySet<ThumbKey>()

    fun cached(key: ThumbKey): ImageBitmap? = cache.get(key)
    fun missed(key: ThumbKey): Boolean = key in misses
    private val ungrabbable = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun load(context: android.content.Context, media: GalleryMedia, key: ThumbKey): ImageBitmap? = withContext(io) {
        val resolver = context.contentResolver
        cache.get(key) ?: try {
            val platform: Bitmap? = runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(media.uri, android.util.Size(key.px, key.px), null)
            } else if (media.isAudio) {
                null // Album art for audio needs Android 10's loadThumbnail.
            } else {
                @Suppress("DEPRECATION")
                if (media.isVideo) MediaStore.Video.Thumbnails.getThumbnail(resolver, media.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                else MediaStore.Images.Thumbnails.getThumbnail(resolver, media.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            } }.getOrNull()
            // Videos MediaStore can't preview (MPEG-PS and others) get a frame decoded through Media3's extractors.
            // Tried once per file: a video the phone can't decode isn't decoded again for every thumbnail size.
            val bitmap = platform ?: if (media.isVideo && media.id !in ungrabbable) {
                FrameGrabber.grab(context, media.uri, key.px) ?: run { ungrabbable += media.id; null }
            } else null
            // Upload to the GPU here rather than on the first frame that draws it.
            bitmap?.prepareToDraw()
            bitmap?.asImageBitmap()?.also { cache.put(key, it) } ?: run { misses += key; null }
        } catch (_: Exception) {
            misses += key
            null // Unreadable or deleted file: the cell keeps its placeholder colour.
        }
    }
}

/**
 * A thumbnail drawn straight onto its box: centre-cropped like the system gallery's cells, or fitted ([crop] false)
 * for the viewer, where it sits under the full picture while that decodes.
 */
@Composable
fun GalleryThumbnail(media: GalleryMedia, modifier: Modifier = Modifier, sizePx: Int = THUMB_PX, crop: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val key = ThumbKey(media.id, media.modifiedSec, sizePx)
    val image = remember(key) { mutableStateOf(Thumbs.cached(key)) }
    if (image.value == null && !Thumbs.missed(key)) LaunchedEffect(key) { image.value = Thumbs.load(context, media, key) }
    // The state is read only while drawing, so a finished load redraws the cell without recomposing it.
    Box(modifier.drawBehind { image.value?.let { if (crop) drawCropped(it) else drawFitted(it) } })
}

private fun DrawScope.drawCropped(img: ImageBitmap) {
    val scale = maxOf(size.width / img.width, size.height / img.height)
    val w = (size.width / scale).roundToInt().coerceIn(1, img.width)
    val h = (size.height / scale).roundToInt().coerceIn(1, img.height)
    drawImage(
        img, srcOffset = IntOffset((img.width - w) / 2, (img.height - h) / 2), srcSize = IntSize(w, h),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low,
    )
}

private fun DrawScope.drawFitted(img: ImageBitmap) {
    val scale = minOf(size.width / img.width, size.height / img.height)
    val w = (img.width * scale).roundToInt()
    val h = (img.height * scale).roundToInt()
    drawImage(
        img, dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
        dstSize = IntSize(w, h), filterQuality = FilterQuality.Low,
    )
}

/** Album art for an audio file, over an accent tile with a music note for files that have none. */
@Composable
fun AudioArt(media: GalleryMedia, modifier: Modifier = Modifier, sizePx: Int = THUMB_PX, iconSize: androidx.compose.ui.unit.Dp = 24.dp) {
    Box(modifier.background(com.edureminder.quicktube.core.designsystem.LocalQtExtras.current.brand), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.material3.Icon(
            com.edureminder.quicktube.core.designsystem.icon.QtIcons.MusicNote, null,
            tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(iconSize),
        )
        GalleryThumbnail(media, Modifier.matchParentSize(), sizePx)
    }
}
