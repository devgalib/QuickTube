package com.edureminder.quicktube.feature.gallery.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A preset look, as a 4x5 color matrix (row-major, like android.graphics.ColorMatrix). */
enum class PhotoFilter(val label: String, val matrix: FloatArray?) {
    NONE("Original", null),
    VIVID("Vivid", saturation(1.45f)),
    MONO("B&W", saturation(0f)),
    WARM("Warm", floatArrayOf(1.08f, 0f, 0f, 0f, 12f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, 0.88f, 0f, -8f, 0f, 0f, 0f, 1f, 0f)),
    COOL("Cool", floatArrayOf(0.9f, 0f, 0f, 0f, -6f, 0f, 1f, 0f, 0f, 2f, 0f, 0f, 1.12f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)),
    FADE("Fade", floatArrayOf(0.82f, 0f, 0f, 0f, 30f, 0f, 0.82f, 0f, 0f, 30f, 0f, 0f, 0.82f, 0f, 34f, 0f, 0f, 0f, 1f, 0f)),
    SEPIA("Sepia", floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
}

/**
 * Everything the editor changes, applied in this order: rotation and flip, crop ([crop] as fractions of the rotated
 * picture), then the filter and the adjustments. The same edit is drawn on the preview and on the full-size save.
 */
data class PhotoEdit(
    /** Clockwise quarter turns, 0..3. */
    val quarterTurns: Int = 0,
    val flipped: Boolean = false,
    /** left, top, right, bottom in 0..1 of the rotated picture. */
    val crop: CropRect = CropRect(),
    val filter: PhotoFilter = PhotoFilter.NONE,
    /** -1..1, 0 = unchanged. */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
) {
    val isUnchanged get() = this == PhotoEdit()

    /** Filter, then brightness / contrast / saturation / warmth, as one color matrix (null = no color change). */
    fun colorMatrix(): FloatArray? {
        if (filter == PhotoFilter.NONE && brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f) return null
        val m = android.graphics.ColorMatrix()
        filter.matrix?.let { m.set(it) }
        if (saturation != 0f) m.postConcat(android.graphics.ColorMatrix(saturation(1f + saturation)))
        if (contrast != 0f) {
            val c = 1f + contrast * 0.6f
            val t = 128f * (1f - c)
            m.postConcat(android.graphics.ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        }
        if (brightness != 0f) {
            val b = brightness * 80f
            m.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)))
        }
        if (warmth != 0f) {
            val w = warmth * 30f
            m.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, w, 0f, 1f, 0f, 0f, w * 0.2f, 0f, 0f, 1f, 0f, -w, 0f, 0f, 0f, 1f, 0f)))
        }
        return m.array
    }
}

data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width get() = right - left
    val height get() = bottom - top
}

internal fun saturation(s: Float): FloatArray = android.graphics.ColorMatrix().apply { setSaturation(s) }.array

/** Loads [uri] upright (EXIF rotation applied), scaled so its longer side is at most [maxSide]. */
internal fun loadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val big = max(info.size.width, info.size.height)
            if (big > maxSide) {
                val f = maxSide.toFloat() / big
                decoder.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
}.getOrNull()

/** [src] turned [quarterTurns] clockwise and mirrored when [flipped]. */
internal fun orient(src: Bitmap, quarterTurns: Int, flipped: Boolean): Bitmap {
    if (quarterTurns % 4 == 0 && !flipped) return src
    val m = Matrix().apply {
        if (flipped) postScale(-1f, 1f)
        postRotate(90f * (quarterTurns % 4))
    }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

/** The full edit on [src] (any size): orient, crop, colors. */
internal fun render(src: Bitmap, edit: PhotoEdit): Bitmap {
    val oriented = orient(src, edit.quarterTurns, edit.flipped)
    val c = edit.crop
    val x = (c.left * oriented.width).roundToInt().coerceIn(0, oriented.width - 1)
    val y = (c.top * oriented.height).roundToInt().coerceIn(0, oriented.height - 1)
    val w = (c.width * oriented.width).roundToInt().coerceIn(1, oriented.width - x)
    val h = (c.height * oriented.height).roundToInt().coerceIn(1, oriented.height - y)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { edit.colorMatrix()?.let { colorFilter = ColorMatrixColorFilter(it) } }
    Canvas(out).drawBitmap(oriented, -x.toFloat(), -y.toFloat(), paint)
    return out
}

/**
 * Saves the edited photo as a new picture in Pictures/QuickTube (the original is kept). Returns its name, or null when
 * it couldn't be saved.
 */
internal fun saveCopy(context: Context, source: Uri, originalName: String, edit: PhotoEdit): String? = runCatching {
    // Full size, but bounded so a 200 MP photo doesn't run out of memory.
    val full = loadBitmap(context, source, maxSide = 4096) ?: return null
    val out = render(full, edit)
    val base = originalName.substringBeforeLast('.').ifBlank { "photo" }
    val name = "${base}_edited_${System.currentTimeMillis() / 1000}.jpg"
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QuickTube")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: return null
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "QuickTube").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/jpeg"), null)
    }
    name
}.getOrNull()
