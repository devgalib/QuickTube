package com.edureminder.quicktube.feature.gallery.editor

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.data.GalleryRepository
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.feature.gallery.uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Tab(val label: String) { CROP("Crop"), ADJUST("Adjust"), FILTERS("Filters") }
private enum class Tool(val label: String) { BRIGHTNESS("Brightness"), CONTRAST("Contrast"), SATURATION("Saturation"), WARMTH("Warmth") }

/** Crop shapes: width / height, null = free. "Original" uses the photo's own shape. */
private val Aspects = listOf<Pair<String, Float?>>("Free" to null, "Original" to -1f, "1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)

private val EditorBg = Color(0xFF0E0F12)
private val Panel = Color(0xFF1A1C21)
private val Muted = Color.White.copy(alpha = 0.62f)

/**
 * A light photo editor: crop (free or fixed shapes, rotate, flip), adjust (brightness, contrast, saturation, warmth)
 * and filters. Edits are previewed live on a screen-sized copy and saved full size as a new photo in
 * Pictures/QuickTube; the original is never changed.
 */
@Composable
fun PhotoEditorScreen(mediaId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = koinInject<GalleryRepository>()
    val scope = rememberCoroutineScope()
    var media by remember { mutableStateOf<GalleryMedia?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var edit by remember { mutableStateOf(PhotoEdit()) }
    var tab by remember { mutableStateOf(Tab.CROP) }
    var tool by remember { mutableStateOf(Tool.BRIGHTNESS) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(mediaId) {
        repo.load()
        val m = repo.library.filterNotNull().first { l -> l.complete || l.items.any { it.id == mediaId } }.items.firstOrNull { it.id == mediaId }
        media = m
        if (m == null) { Toast.makeText(context, "Photo not found", Toast.LENGTH_SHORT).show(); onBack(); return@LaunchedEffect }
        preview = withContext(Dispatchers.IO) { loadBitmap(context, m.uri, maxSide = 1600) }
        if (preview == null) { Toast.makeText(context, "Couldn't open this photo", Toast.LENGTH_SHORT).show(); onBack() }
    }
    BackHandler { if (edit.isUnchanged || saving) onBack() else confirmDiscard = true }
    // Light status and navigation bar icons on the dark editor.
    val view = androidx.compose.ui.platform.LocalView.current
    val window = androidx.activity.compose.LocalActivity.current?.window
    if (window != null) com.edureminder.quicktube.feature.gallery.DarkSystemBars(remember(window, view) { androidx.core.view.WindowCompat.getInsetsController(window, view) })

    val oriented = remember(preview, edit.quarterTurns, edit.flipped) { preview?.let { orient(it, edit.quarterTurns, edit.flipped).asImageBitmap() } }
    val colorFilter = remember(edit) { edit.colorMatrix()?.let { ColorFilter.colorMatrix(ColorMatrix(it)) } }
    val thumb = remember(oriented, edit.crop) { preview?.let { p -> render(scaled(p, 220), edit.copy(filter = PhotoFilter.NONE, brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f)).asImageBitmap() } }

    fun applyAspect(a: Float?) {
        aspect = a
        val img = oriented ?: return
        val ratio = when (a) { null -> return; -1f -> img.width.toFloat() / img.height; else -> a }
        edit = edit.copy(crop = centeredCrop(ratio, img.width, img.height))
    }

    Column(Modifier.fillMaxSize().background(EditorBg).statusBarsPadding().navigationBarsPadding()) {
        // Top bar: close, reset, save.
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(QtIcons.Close, "Close") { if (edit.isUnchanged) onBack() else confirmDiscard = true }
            Text("Edit", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (!edit.isUnchanged) Text(
                "Reset", color = Muted, fontSize = 14.sp,
                modifier = Modifier.clip(CircleShape).pressable(onClick = { edit = PhotoEdit(); aspect = null }).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Box(
                Modifier.padding(start = 4.dp).clip(CircleShape).background(if (edit.isUnchanged) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                    .then(if (edit.isUnchanged) Modifier else Modifier.background(LocalQtExtras.current.brand))
                    .pressable(enabled = !edit.isUnchanged && !saving, onClick = {
                        val m = media ?: return@pressable
                        saving = true
                        scope.launch {
                            val name = withContext(Dispatchers.IO) { saveCopy(context, m.uri, m.name, edit) }
                            saving = false
                            Toast.makeText(context, if (name != null) "Saved to Pictures/QuickTube" else "Couldn't save the photo", Toast.LENGTH_SHORT).show()
                            if (name != null) onBack()
                        }
                    })
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) { Text("Save", color = if (edit.isUnchanged) Muted else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }

        // The picture.
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val img = oriented
            if (img == null) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            else if (tab == Tab.CROP) CropView(img, edit.crop, aspect, colorFilter) { edit = edit.copy(crop = it) }
            else CroppedImage(img, edit.crop, colorFilter)
            if (saving) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            }
        }

        // Tool panel for the open tab.
        Box(Modifier.fillMaxWidth().heightIn(min = 112.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "tab") { t ->
                when (t) {
                    Tab.CROP -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(Aspects) { (label, a) -> Chip(label, aspect == a) { applyAspect(a) } }
                        }
                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            ToolButton(QtIcons.PRotate, "Rotate") {
                                // A turn swaps width and height: the crop turns with the picture.
                                val c = edit.crop
                                edit = edit.copy(quarterTurns = (edit.quarterTurns + 1) % 4, crop = CropRect(1f - c.bottom, c.left, 1f - c.top, c.right))
                                if (aspect != null && aspect != -1f) aspect = 1f / aspect!!
                            }
                            ToolButton(FlipIcon, "Flip") {
                                val c = edit.crop
                                edit = edit.copy(flipped = !edit.flipped, quarterTurns = (4 - edit.quarterTurns) % 4, crop = CropRect(1f - c.right, c.top, 1f - c.left, c.bottom))
                            }
                        }
                    }
                    Tab.ADJUST -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(Tool.entries) { tl ->
                                val v = valueOf(edit, tl)
                                Chip(tl.label + if (v != 0f) " ${(v * 100).roundToInt()}" else "", tool == tl) { tool = tl }
                            }
                        }
                        val value = valueOf(edit, tool)
                        Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value, { v -> edit = withValue(edit, tool, if (abs(v) < 0.03f) 0f else v) }, Modifier.weight(1f), valueRange = -1f..1f,
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                            )
                            Text("${(value * 100).roundToInt()}", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(40.dp).padding(start = 8.dp))
                        }
                    }
                    Tab.FILTERS -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(PhotoFilter.entries) { f ->
                            FilterTile(f, thumb, edit.filter == f) { edit = edit.copy(filter = f) }
                        }
                    }
                }
            }
        }

        // Tabs.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { t ->
                val on = t == tab
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).pressable(onClick = { tab = t }).padding(horizontal = 18.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        when (t) { Tab.CROP -> QtIcons.PCrop; Tab.ADJUST -> QtIcons.Tune; Tab.FILTERS -> QtIcons.Palette }, null,
                        tint = if (on) Color.White else Muted, modifier = Modifier.size(24.dp),
                    )
                    Text(t.label, color = if (on) Color.White else Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (confirmDiscard) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard changes?") },
        text = { Text("Your edits to this photo won't be saved.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
    )
}

private fun valueOf(e: PhotoEdit, t: Tool) = when (t) {
    Tool.BRIGHTNESS -> e.brightness; Tool.CONTRAST -> e.contrast; Tool.SATURATION -> e.saturation; Tool.WARMTH -> e.warmth
}

private fun withValue(e: PhotoEdit, t: Tool, v: Float) = when (t) {
    Tool.BRIGHTNESS -> e.copy(brightness = v); Tool.CONTRAST -> e.copy(contrast = v)
    Tool.SATURATION -> e.copy(saturation = v); Tool.WARMTH -> e.copy(warmth = v)
}

/** The largest centered crop of [ratio] (width / height, in pixels) in a [w]x[h] picture, as fractions. */
private fun centeredCrop(ratio: Float, w: Int, h: Int): CropRect {
    val r = ratio * h / w // in fractions of width / height
    return if (r <= 1f) CropRect((1f - r) / 2f, 0f, (1f + r) / 2f, 1f) else CropRect(0f, (1f - 1f / r) / 2f, 1f, (1f + 1f / r) / 2f)
}

private fun scaled(b: Bitmap, maxSide: Int): Bitmap {
    val big = maxOf(b.width, b.height)
    if (big <= maxSide) return b
    val f = maxSide.toFloat() / big
    return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1), true)
}

/** Where a [w]x[h] picture sits when fitted into [box]. */
private fun fit(w: Int, h: Int, box: Size): Rect {
    val s = minOf(box.width / w, box.height / h)
    val dw = w * s; val dh = h * s
    return Rect(Offset((box.width - dw) / 2f, (box.height - dh) / 2f), Size(dw, dh))
}

/** The crop's part of the picture, fitted. */
@Composable
private fun CroppedImage(img: ImageBitmap, crop: CropRect, filter: ColorFilter?) {
    Canvas(Modifier.fillMaxSize()) {
        val sw = (crop.width * img.width).roundToInt().coerceAtLeast(1)
        val sh = (crop.height * img.height).roundToInt().coerceAtLeast(1)
        val dst = fit(sw, sh, size)
        drawImage(
            img, srcOffset = IntOffset((crop.left * img.width).roundToInt(), (crop.top * img.height).roundToInt()), srcSize = IntSize(sw, sh),
            dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()), dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
            colorFilter = filter,
        )
    }
}

private enum class Handle { TL, TR, BL, BR, MOVE }

/** The whole picture with the crop frame on top: drag a corner to resize (keeping [aspect]), drag inside to move it. */
@Composable
private fun CropView(img: ImageBitmap, crop: CropRect, aspect: Float?, filter: ColorFilter?, onCrop: (CropRect) -> Unit) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(crop)
    val change by rememberUpdatedState(onCrop)
    val ratio = when (aspect) { null -> null; -1f -> img.width.toFloat() / img.height; else -> aspect }
    val fixed by rememberUpdatedState(ratio?.let { it * img.height / img.width })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val box = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val dst = fit(img.width, img.height, box)
        val grab = with(density) { 44.dp.toPx() }
        Canvas(
            Modifier.fillMaxSize().pointerInput(img.width, img.height, dst) {
                var handle = Handle.MOVE
                detectDragGestures(
                    onDragStart = { p ->
                        val c = current
                        val corners = mapOf(
                            Handle.TL to Offset(dst.left + c.left * dst.width, dst.top + c.top * dst.height),
                            Handle.TR to Offset(dst.left + c.right * dst.width, dst.top + c.top * dst.height),
                            Handle.BL to Offset(dst.left + c.left * dst.width, dst.top + c.bottom * dst.height),
                            Handle.BR to Offset(dst.left + c.right * dst.width, dst.top + c.bottom * dst.height),
                        )
                        handle = corners.minByOrNull { (it.value - p).getDistance() }?.takeIf { (it.value - p).getDistance() < grab }?.key ?: Handle.MOVE
                    },
                    onDrag = { pc, d ->
                        pc.consume()
                        change(dragCrop(current, handle, d.x / dst.width, d.y / dst.height, fixed))
                    },
                )
            },
        ) {
            drawImage(img, dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()), dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt()), colorFilter = filter)
            val r = Rect(dst.left + crop.left * dst.width, dst.top + crop.top * dst.height, dst.left + crop.right * dst.width, dst.top + crop.bottom * dst.height)
            val shade = Color.Black.copy(alpha = 0.55f)
            // Dim what's cut away.
            drawRect(shade, Offset(dst.left, dst.top), Size(dst.width, r.top - dst.top))
            drawRect(shade, Offset(dst.left, r.bottom), Size(dst.width, dst.bottom - r.bottom))
            drawRect(shade, Offset(dst.left, r.top), Size(r.left - dst.left, r.height))
            drawRect(shade, Offset(r.right, r.top), Size(dst.right - r.right, r.height))
            // Frame, thirds grid and corner handles.
            drawRect(Color.White, r.topLeft, r.size, style = Stroke(1.5.dp.toPx()))
            for (i in 1..2) {
                val x = r.left + r.width * i / 3f
                val y = r.top + r.height * i / 3f
                drawLine(Color.White.copy(alpha = 0.35f), Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.35f), Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
            }
            val l = 18.dp.toPx(); val t = 3.5.dp.toPx()
            listOf(r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f), r.bottomLeft to Offset(1f, -1f), r.bottomRight to Offset(-1f, -1f)).forEach { (p, dir) ->
                drawLine(Color.White, p, p + Offset(l * dir.x, 0f), t)
                drawLine(Color.White, p, p + Offset(0f, l * dir.y), t)
            }
        }
    }
}

private const val MIN = 0.08f

/** The crop after dragging [h] by (dx, dy) fractions; [fixed] is the shape as width / height in fractions, or free. */
private fun dragCrop(c: CropRect, h: Handle, dx: Float, dy: Float, fixed: Float?): CropRect {
    if (h == Handle.MOVE) {
        val x = dx.coerceIn(-c.left, 1f - c.right)
        val y = dy.coerceIn(-c.top, 1f - c.bottom)
        return CropRect(c.left + x, c.top + y, c.right + x, c.bottom + y)
    }
    var l = c.left; var t = c.top; var r = c.right; var b = c.bottom
    when (h) {
        Handle.TL -> { l += dx; t += dy }
        Handle.TR -> { r += dx; t += dy }
        Handle.BL -> { l += dx; b += dy }
        Handle.BR -> { r += dx; b += dy }
        else -> Unit
    }
    l = l.coerceIn(0f, c.right - MIN); r = r.coerceIn(c.left + MIN, 1f)
    t = t.coerceIn(0f, c.bottom - MIN); b = b.coerceIn(c.top + MIN, 1f)
    if (fixed != null) {
        // Keep the shape: the height follows the width, anchored at the opposite corner, within the picture.
        var w = r - l
        val anchorTop = h == Handle.BL || h == Handle.BR
        val room = if (anchorTop) 1f - c.top else c.bottom
        w = minOf(w, room * fixed)
        val hh = w / fixed
        if (h == Handle.TL || h == Handle.BL) l = r - w else r = l + w
        if (anchorTop) { t = c.top; b = t + hh } else { b = c.bottom; t = b - hh }
    }
    return CropRect(l, t, r, b)
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(CircleShape).background(if (selected) Color.White else Panel).pressable(scaleDown = 0.94f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).pressable(scaleDown = 0.92f, onClick = onClick).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).pressable(scaleDown = 0.9f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White)
    }
}

@Composable
private fun FilterTile(f: PhotoFilter, thumb: ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.pressable(scaleDown = 0.94f, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Panel)
                .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier),
        ) {
            if (thumb != null) Image(
                thumb, f.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                colorFilter = f.matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
            )
        }
        Text(f.label, color = if (selected) Color.White else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Mirror icon: two triangles either side of a dashed line. */
private val FlipIcon: ImageVector by lazy {
    ImageVector.Builder("Flip", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            moveTo(10f, 5f); lineTo(3f, 19f); lineTo(10f, 19f); close()
        }
        path(stroke = androidx.compose.ui.graphics.SolidColor(Color.White), strokeLineWidth = 1.6f) {
            moveTo(14f, 5f); lineTo(21f, 19f); lineTo(14f, 19f); close()
        }
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            for (y in listOf(3f, 8f, 13f, 18f)) { moveTo(11.4f, y); lineTo(12.6f, y); lineTo(12.6f, y + 3f); lineTo(11.4f, y + 3f); close() }
        }
    }.build()
}

