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

/** 4x5 color matrices (row-major, like android.graphics.ColorMatrix) in plain Kotlin, so looks can be tested off-device. */
internal object Cm {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** [b] applied after [a]. */
    fun then(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (r in 0..3) {
            for (c in 0..3) out[r * 5 + c] = (0..3).sumOf { k -> (b[r * 5 + k] * a[k * 5 + c]).toDouble() }.toFloat()
            out[r * 5 + 4] = (0..3).sumOf { k -> (b[r * 5 + k] * a[k * 5 + 4]).toDouble() }.toFloat() + b[r * 5 + 4]
        }
        return out
    }

    fun chain(vararg ms: FloatArray) = ms.fold(IDENTITY) { acc, m -> then(acc, m) }

    fun saturation(s: Float): FloatArray {
        val lr = 0.213f * (1 - s); val lg = 0.715f * (1 - s); val lb = 0.072f * (1 - s)
        return floatArrayOf(lr + s, lg, lb, 0f, 0f, lr, lg + s, lb, 0f, 0f, lr, lg, lb + s, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    fun contrast(c: Float): FloatArray { val t = 128f * (1f - c); return floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f) }
    fun offset(r: Float, g: Float, b: Float) = floatArrayOf(1f, 0f, 0f, 0f, r, 0f, 1f, 0f, 0f, g, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)
    fun scale(r: Float, g: Float, b: Float) = floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    /** Lifts blacks and lowers whites (a matte, faded look); [f] 0..1. */
    fun fade(f: Float): FloatArray { val k = 1f - 0.28f * f; val o = 255f * 0.17f * f; return floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f) }
    val SEPIA = floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** Hue turned by [deg] degrees (keeps brightness). */
    fun hue(deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble()); val cos = kotlin.math.cos(a).toFloat(); val sin = kotlin.math.sin(a).toFloat()
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        return floatArrayOf(
            lr + cos * (1 - lr) + sin * -lr, lg + cos * -lg + sin * -lg, lb + cos * -lb + sin * (1 - lb), 0f, 0f,
            lr + cos * -lr + sin * 0.143f, lg + cos * (1 - lg) + sin * 0.140f, lb + cos * -lb + sin * -0.283f, 0f, 0f,
            lr + cos * -lr + sin * -(1 - lr), lg + cos * -lg + sin * lg, lb + cos * (1 - lb) + sin * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** [m] at [amount] (0 = no change, 1 = full). */
    fun mix(m: FloatArray, amount: Float) = FloatArray(20) { i -> IDENTITY[i] + (m[i] - IDENTITY[i]) * amount }

    /** One RGB pixel through [m] (for tests). */
    fun apply(m: FloatArray, r: Float, g: Float, b: Float) = Triple(
        (m[0] * r + m[1] * g + m[2] * b + m[4]).coerceIn(0f, 255f),
        (m[5] * r + m[6] * g + m[7] * b + m[9]).coerceIn(0f, 255f),
        (m[10] * r + m[11] * g + m[12] * b + m[14]).coerceIn(0f, 255f),
    )
}

/** Filter shelves, like a camera app's: what each look is for. */
enum class FilterGroup(val label: String) { NATURAL("Natural"), WARM("Warm"), COOL("Cool"), FILM("Film"), MONO("B&W"), MOODY("Moody") }

/** A preset look, as a 4x5 color matrix. */
enum class PhotoFilter(val label: String, val group: FilterGroup, val matrix: FloatArray?) {
    NONE("Original", FilterGroup.NATURAL, null),
    VIVID("Vivid", FilterGroup.NATURAL, Cm.chain(Cm.saturation(1.4f), Cm.contrast(1.08f))),
    CRISP("Crisp", FilterGroup.NATURAL, Cm.chain(Cm.contrast(1.18f), Cm.saturation(1.12f), Cm.offset(-4f, -4f, -4f))),
    BRIGHT("Bright", FilterGroup.NATURAL, Cm.chain(Cm.offset(18f, 18f, 18f), Cm.contrast(0.96f), Cm.saturation(1.08f))),
    SOFT("Soft", FilterGroup.NATURAL, Cm.chain(Cm.fade(0.35f), Cm.saturation(0.92f), Cm.offset(6f, 4f, 4f))),
    GOLDEN("Golden", FilterGroup.WARM, Cm.chain(Cm.scale(1.08f, 1.02f, 0.86f), Cm.offset(10f, 6f, -6f), Cm.saturation(1.12f))),
    PEACH("Peach", FilterGroup.WARM, Cm.chain(Cm.scale(1.06f, 0.98f, 0.95f), Cm.offset(16f, 6f, 4f), Cm.fade(0.2f))),
    ROSE("Rosé", FilterGroup.WARM, Cm.chain(Cm.scale(1.07f, 0.96f, 1.0f), Cm.offset(12f, 0f, 8f), Cm.saturation(1.05f))),
    SUNSET("Sunset", FilterGroup.WARM, Cm.chain(Cm.scale(1.12f, 0.97f, 0.82f), Cm.offset(14f, -2f, -10f), Cm.contrast(1.08f))),
    COOL("Cool", FilterGroup.COOL, Cm.chain(Cm.scale(0.92f, 1.0f, 1.1f), Cm.offset(-6f, 2f, 14f))),
    MINT("Mint", FilterGroup.COOL, Cm.chain(Cm.scale(0.95f, 1.05f, 1.0f), Cm.offset(-4f, 10f, 6f), Cm.fade(0.15f))),
    LAVENDER("Lavender", FilterGroup.COOL, Cm.chain(Cm.scale(1.0f, 0.95f, 1.08f), Cm.offset(8f, 0f, 16f), Cm.saturation(0.9f))),
    ARCTIC("Arctic", FilterGroup.COOL, Cm.chain(Cm.saturation(0.75f), Cm.scale(0.92f, 1.0f, 1.12f), Cm.offset(4f, 10f, 22f))),
    PORTRA("Portra", FilterGroup.FILM, Cm.chain(Cm.scale(1.05f, 1.0f, 0.94f), Cm.fade(0.25f), Cm.saturation(0.9f), Cm.offset(6f, 4f, 0f))),
    KODA("Gold 200", FilterGroup.FILM, Cm.chain(Cm.scale(1.08f, 1.03f, 0.88f), Cm.contrast(1.1f), Cm.fade(0.15f))),
    TEAL_ORANGE("Teal & Orange", FilterGroup.FILM, Cm.chain(Cm.scale(1.1f, 0.98f, 0.92f), Cm.offset(6f, 0f, 10f), Cm.contrast(1.12f), Cm.saturation(1.15f))),
    VINTAGE("Vintage", FilterGroup.FILM, Cm.chain(Cm.mix(Cm.SEPIA, 0.35f), Cm.fade(0.4f), Cm.contrast(0.94f))),
    MATTE("Matte", FilterGroup.FILM, Cm.chain(Cm.fade(0.55f), Cm.saturation(0.85f))),
    MONO("Mono", FilterGroup.MONO, Cm.saturation(0f)),
    NOIR("Noir", FilterGroup.MONO, Cm.chain(Cm.saturation(0f), Cm.contrast(1.35f), Cm.offset(-10f, -10f, -10f))),
    SILVER("Silver", FilterGroup.MONO, Cm.chain(Cm.saturation(0f), Cm.fade(0.35f), Cm.offset(6f, 6f, 10f))),
    SEPIA("Sepia", FilterGroup.MONO, Cm.SEPIA),
    MOODY("Moody", FilterGroup.MOODY, Cm.chain(Cm.saturation(0.78f), Cm.contrast(1.15f), Cm.offset(-14f, -10f, -4f))),
    DRAMA("Drama", FilterGroup.MOODY, Cm.chain(Cm.contrast(1.3f), Cm.saturation(1.1f), Cm.offset(-16f, -16f, -16f))),
    DUSK("Dusk", FilterGroup.MOODY, Cm.chain(Cm.scale(0.95f, 0.9f, 1.05f), Cm.offset(-6f, -10f, 6f), Cm.contrast(1.12f), Cm.saturation(0.85f))),
}

/**
 * Everything the editor changes, applied in this order: rotation and flip, crop ([crop] as fractions of the rotated
 * picture), the filter (at [filterStrength]) and the adjustments, then vignette and grain, then the [frame]. The same
 * edit is drawn on the preview and on the full-size save.
 */
data class PhotoEdit(
    /** Clockwise quarter turns, 0..3. */
    val quarterTurns: Int = 0,
    val flipped: Boolean = false,
    /** left, top, right, bottom in 0..1 of the rotated picture. */
    val crop: CropRect = CropRect(),
    val filter: PhotoFilter = PhotoFilter.NONE,
    /** 0..1, how much of the filter. */
    val filterStrength: Float = 1f,
    /** -1..1, 0 = unchanged. */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    /** Green ↔ magenta, -1..1. */
    val tint: Float = 0f,
    /** -1..1 = -60°..60°. */
    val hue: Float = 0f,
    /** 0..1: matte, lifted blacks. */
    val fade: Float = 0f,
    /** 0..1: darker corners. */
    val vignette: Float = 0f,
    /** 0..1: film grain. */
    val grain: Float = 0f,
    val frame: PhotoFrame = PhotoFrame.NONE,
    /** The frame's colour choice (index into its palette). */
    val frameColor: Int = 0,
    /** Words on the picture, drawn last (over the frame), bottom one first. */
    val texts: List<TextLayer> = emptyList(),
) {
    val isUnchanged get() = this == PhotoEdit()

    /** Filter, then the adjustments, as one color matrix (null = no color change). */
    fun colorMatrix(): FloatArray? {
        val parts = buildList {
            filter.matrix?.let { add(Cm.mix(it, filterStrength)) }
            if (saturation != 0f) add(Cm.saturation(1f + saturation))
            if (contrast != 0f) add(Cm.contrast(1f + contrast * 0.6f))
            if (brightness != 0f) add(Cm.offset(brightness * 80f, brightness * 80f, brightness * 80f))
            if (warmth != 0f) add(Cm.offset(warmth * 30f, warmth * 6f, -warmth * 30f))
            if (tint != 0f) add(Cm.offset(tint * 18f, -tint * 22f, tint * 18f))
            if (hue != 0f) add(Cm.hue(hue * 60f))
            if (fade != 0f) add(Cm.fade(fade))
        }
        return if (parts.isEmpty()) null else Cm.chain(*parts.toTypedArray())
    }
}

data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width get() = right - left
    val height get() = bottom - top
}

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

/** The full edit on [src] (any size): orient, crop, colors, vignette and grain, then the frame. */
internal fun render(src: Bitmap, edit: PhotoEdit): Bitmap {
    val oriented = orient(src, edit.quarterTurns, edit.flipped)
    val c = edit.crop
    val x = (c.left * oriented.width).roundToInt().coerceIn(0, oriented.width - 1)
    val y = (c.top * oriented.height).roundToInt().coerceIn(0, oriented.height - 1)
    val w = (c.width * oriented.width).roundToInt().coerceIn(1, oriented.width - x)
    val h = (c.height * oriented.height).roundToInt().coerceIn(1, oriented.height - y)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { edit.colorMatrix()?.let { colorFilter = ColorMatrixColorFilter(it) } }
    val canvas = Canvas(out)
    canvas.drawBitmap(oriented, -x.toFloat(), -y.toFloat(), paint)
    if (edit.vignette > 0f) Effects.vignette(canvas, w, h, edit.vignette)
    if (edit.grain > 0f) Effects.grain(canvas, w, h, edit.grain)
    val framed = if (edit.frame == PhotoFrame.NONE) out else Frames.draw(out, edit.frame, edit.frameColor)
    if (edit.texts.isNotEmpty()) TextRender.drawAll(Canvas(framed), edit.texts, framed.width.toFloat(), framed.height.toFloat())
    return framed
}

/**
 * Saves the edited photo as a new picture in Pictures/QuickTube (the original is kept). Returns its name, or null when
 * it couldn't be saved.
 */
internal fun saveCopy(context: Context, source: Uri, originalName: String, edit: PhotoEdit): String? = runCatching {
    // Full size, but bounded so a 200 MP photo doesn't run out of memory.
    val full = loadBitmap(context, source, maxSide = 4096) ?: return null
    val base = originalName.substringBeforeLast('.').ifBlank { "photo" }
    saveBitmap(context, render(full, edit), "${base}_edited_${System.currentTimeMillis() / 1000}.jpg")
}.getOrNull()

/** Saves [bitmap] as a JPEG called [name] in Pictures/QuickTube; returns the name, or null when it couldn't be saved. */
internal fun saveBitmap(context: Context, bitmap: Bitmap, name: String): String? = runCatching {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QuickTube")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: return null
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "QuickTube").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/jpeg"), null)
    }
    name
}.getOrNull()
