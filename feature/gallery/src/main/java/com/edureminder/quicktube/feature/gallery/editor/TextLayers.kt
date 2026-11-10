package com.edureminder.quicktube.feature.gallery.editor

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.designsystem.pressable
import kotlin.math.roundToInt

/** Typefaces the phone already has (no downloads): each looks clearly different. */
enum class TextFont(val label: String, val family: String, val style: Int) {
    CLEAN("Clean", "sans-serif-medium", Typeface.NORMAL),
    BOLD("Bold", "sans-serif-black", Typeface.NORMAL),
    TALL("Tall", "sans-serif-condensed", Typeface.BOLD),
    SERIF("Serif", "serif", Typeface.BOLD),
    SCRIPT("Script", "cursive", Typeface.BOLD),
    HAND("Hand", "casual", Typeface.NORMAL),
    TYPE("Typewriter", "monospace", Typeface.BOLD),
    ;

    val typeface: Typeface by lazy { Typeface.create(family, style) }
}

/** How the letters are dressed. [accent] is the outline, label, glow or flower colour. */
enum class TextLook(val label: String) {
    PLAIN("Plain"), SHADOW("Shadow"), OUTLINE("Outline"), STICKER("Sticker"), LABEL("Label"), NEON("Neon"), BLOOM("Flowers"),
}

/** A line (or lines) of text on the picture, placed by its centre as fractions of the picture. */
data class TextLayer(
    val id: Long,
    val text: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** Letter height as a fraction of the picture's shorter side. */
    val size: Float = 0.08f,
    val color: Int = Color.WHITE,
    val accent: Int = Color.BLACK,
    val font: TextFont = TextFont.BOLD,
    val look: TextLook = TextLook.SHADOW,
)

/** Ready-made looks: a font, dressing and colours that go together. */
enum class TextTheme(val label: String, val font: TextFont, val look: TextLook, val color: Int, val accent: Int) {
    CUTE("Cute", TextFont.SCRIPT, TextLook.STICKER, 0xFFFF6F9C.toInt(), Color.WHITE),
    BLOOM("Bloom", TextFont.SCRIPT, TextLook.BLOOM, 0xFFFFFFFF.toInt(), 0xFFF7A1B5.toInt()),
    BOLD("Bold", TextFont.BOLD, TextLook.SHADOW, Color.WHITE, Color.BLACK),
    NEON("Neon", TextFont.TALL, TextLook.NEON, 0xFF59F3FF.toInt(), 0xFF59F3FF.toInt()),
    LABEL("Label", TextFont.CLEAN, TextLook.LABEL, 0xFF16161A.toInt(), Color.WHITE),
    RETRO("Retro", TextFont.SERIF, TextLook.OUTLINE, 0xFFFFD34D.toInt(), 0xFF6B3A1E.toInt()),
    NOTE("Note", TextFont.TYPE, TextLook.LABEL, Color.WHITE, 0xFF16161A.toInt()),
    HAND("Hand", TextFont.HAND, TextLook.SHADOW, 0xFFFFF4D6.toInt(), Color.BLACK),
    ;

    fun applyTo(l: TextLayer) = l.copy(font = font, look = look, color = color, accent = accent)
}

internal val TextColors = listOf(
    0xFFFFFFFF, 0xFF16161A, 0xFFFF6F9C, 0xFFFF4D4D, 0xFFFF9F43, 0xFFFFD34D, 0xFF7ED957, 0xFF2EC4B6, 0xFF59F3FF, 0xFF4C8DFF,
    0xFF9B6BFF, 0xFFF7A1B5, 0xFFB98B5E,
).map { it.toInt() }

internal object TextRender {
    private fun paint(l: TextLayer, side: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = l.font.typeface
        textSize = l.size * side
        textAlign = Paint.Align.CENTER
    }

    /** Where [l] sits on a [w]x[h] picture, its dressing included (for tapping and the selection box). */
    fun bounds(l: TextLayer, w: Float, h: Float): RectF {
        val side = minOf(w, h)
        val p = paint(l, side)
        val lines = l.text.split('\n')
        val lh = p.fontSpacing
        val tw = lines.maxOf { p.measureText(it) }
        val padX = p.textSize * (if (l.look == TextLook.BLOOM) 1.5f else 0.45f)
        val padY = p.textSize * 0.3f
        val th = lh * lines.size
        val cx = l.x * w; val cy = l.y * h
        return RectF(cx - tw / 2 - padX, cy - th / 2 - padY, cx + tw / 2 + padX, cy + th / 2 + padY)
    }

    fun drawAll(c: Canvas, layers: List<TextLayer>, w: Float, h: Float) = layers.forEach { draw(c, it, w, h) }

    fun draw(c: Canvas, l: TextLayer, w: Float, h: Float) {
        if (l.text.isBlank()) return
        val side = minOf(w, h)
        val p = paint(l, side)
        val ts = p.textSize
        val lines = l.text.split('\n')
        val lh = p.fontSpacing
        val cx = l.x * w; val cy = l.y * h
        // Baseline of the first line, so the block is centred on (cx, cy).
        val top = cy - lh * lines.size / 2 - p.fontMetrics.ascent - (lh - (p.fontMetrics.descent - p.fontMetrics.ascent)) / 2
        fun each(paint: Paint) = lines.forEachIndexed { i, s -> c.drawText(s, cx, top + i * lh, paint) }
        val fill = Paint(p).apply { color = l.color }
        when (l.look) {
            TextLook.PLAIN -> each(fill)
            TextLook.SHADOW -> each(fill.apply { setShadowLayer(ts * 0.12f, 0f, ts * 0.05f, Color.argb(170, 0, 0, 0)) })
            TextLook.OUTLINE -> {
                each(Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = ts * 0.12f; strokeJoin = Paint.Join.ROUND; color = l.accent })
                each(fill)
            }
            TextLook.STICKER -> {
                each(Paint(p).apply {
                    style = Paint.Style.STROKE; strokeWidth = ts * 0.26f; strokeJoin = Paint.Join.ROUND; color = l.accent
                    setShadowLayer(ts * 0.1f, 0f, ts * 0.05f, Color.argb(90, 0, 0, 0))
                })
                each(fill)
            }
            TextLook.LABEL -> {
                val b = bounds(l, w, h)
                val r = (b.height() / lines.size) * 0.38f
                c.drawRoundRect(b, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.accent })
                each(fill)
            }
            TextLook.NEON -> {
                each(Paint(p).apply { color = l.accent; maskFilter = BlurMaskFilter(ts * 0.35f, BlurMaskFilter.Blur.NORMAL) })
                each(Paint(p).apply { color = l.accent; maskFilter = BlurMaskFilter(ts * 0.12f, BlurMaskFilter.Blur.NORMAL) })
                each(Paint(p).apply { color = Frames.lighten(l.color, 0.7f) })
            }
            TextLook.BLOOM -> {
                each(fill.apply { setShadowLayer(ts * 0.1f, 0f, ts * 0.04f, Color.argb(120, 0, 0, 0)) })
                // A flower and a leaf at each end of the text.
                val b = bounds(l, w, h)
                val fr = ts * 0.42f
                for ((x, flip) in listOf(b.left + fr * 1.2f to 1f, b.right - fr * 1.2f to -1f)) {
                    Decor.leaf(c, x, cy + fr * 0.4f, fr * 1.5f, if (flip > 0) 150f else 30f, 0xFF6FA36B.toInt())
                    Decor.flower(c, x, cy, fr, l.accent, 0xFFFFD25A.toInt(), rot = if (flip > 0) 10f else 40f)
                }
            }
        }
    }

    /** The dashed box round the selected text on screen. */
    fun drawSelection(c: Canvas, l: TextLayer, w: Float, h: Float, density: Float) {
        val b = bounds(l, w, h)
        c.drawRoundRect(b, 6 * density, 6 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.WHITE
            pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
            setShadowLayer(2 * density, 0f, 0f, Color.BLACK)
        })
    }

    /** The top layer under ([x], [y]) on a [w]x[h] picture. */
    fun hit(layers: List<TextLayer>, x: Float, y: Float, w: Float, h: Float): TextLayer? =
        layers.lastOrNull { bounds(it, w, h).apply { inset(-12f, -12f) }.contains(x, y) }
}

/**
 * The texts drawn live over a picture of [imageW]x[imageH] shown fitted (centred) in this box: tap one to pick it,
 * drag to move it, tap empty space to let go.
 */
@Composable
internal fun TextOverlay(
    imageW: Int, imageH: Int, layers: List<TextLayer>, selected: Long?,
    onSelect: (Long?) -> Unit, onMove: (id: Long, dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(layers)
    val sel by rememberUpdatedState(selected)
    var box by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    fun fitted(): RectF {
        if (imageW <= 0 || imageH <= 0 || box.width <= 0f) return RectF()
        val sc = minOf(box.width / imageW, box.height / imageH)
        val w = imageW * sc; val h = imageH * sc
        return RectF((box.width - w) / 2, (box.height - h) / 2, (box.width + w) / 2, (box.height + h) / 2)
    }
    ComposeCanvas(
        modifier.fillMaxSize()
            .pointerInput(imageW, imageH) {
                detectTapGestures { o ->
                    val r = fitted()
                    onSelect(TextRender.hit(current, o.x - r.left, o.y - r.top, r.width(), r.height())?.id)
                }
            }
            .pointerInput(imageW, imageH) {
                var dragging: Long? = null
                detectDragGestures(
                    onDragStart = { o ->
                        val r = fitted()
                        dragging = TextRender.hit(current, o.x - r.left, o.y - r.top, r.width(), r.height())?.id ?: sel
                        dragging?.let(onSelect)
                    },
                    onDragEnd = { dragging = null },
                ) { change, d: Offset ->
                    val id = dragging ?: return@detectDragGestures
                    change.consume()
                    val r = fitted()
                    if (r.width() > 0f) onMove(id, d.x / r.width(), d.y / r.height())
                }
            },
    ) {
        box = size
        val r = fitted()
        drawIntoCanvas { cv ->
            val nc = cv.nativeCanvas
            nc.save()
            nc.translate(r.left, r.top)
            nc.clipRect(0f, 0f, r.width(), r.height())
            TextRender.drawAll(nc, layers, r.width(), r.height())
            layers.firstOrNull { it.id == selected }?.let { TextRender.drawSelection(nc, it, r.width(), r.height(), density) }
            nc.restore()
        }
    }
}

private enum class TextPart(val label: String) { THEMES("Themes"), FONT("Font"), LOOK("Style"), COLOR("Colour"), SIZE("Size") }

/** The text tools: add, edit or remove a text, and dress the picked one (themes, font, style, colours, size). */
@Composable
internal fun TextPanel(
    layers: List<TextLayer>, selected: Long?,
    onAdd: () -> Unit, onEdit: (TextLayer) -> Unit, onDelete: (TextLayer) -> Unit, onChange: (TextLayer) -> Unit,
) {
    val layer = layers.firstOrNull { it.id == selected }
    var part by remember { mutableStateOf(TextPart.THEMES) }
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip("+ Add text", false, onAdd) }
            if (layer != null) {
                item { Chip("Edit", false) { onEdit(layer) } }
                item { Chip("Delete", false) { onDelete(layer) } }
                items(TextPart.entries) { p -> Chip(p.label, part == p) { part = p } }
            }
        }
        if (layer == null) Text(
            if (layers.isEmpty()) "Add a text, then drag it where you want it" else "Tap a text on the photo to change it",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp),
        ) else Box(Modifier.padding(top = 10.dp)) {
            when (part) {
                TextPart.THEMES -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(TextTheme.entries) { t -> ThemeTile(t, t.font == layer.font && t.look == layer.look && t.color == layer.color) { onChange(t.applyTo(layer)) } }
                }
                TextPart.FONT -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TextFont.entries) { f ->
                        val on = layer.font == f
                        Text(
                            f.label, color = if (on) Color.BLACK.toCompose() else Color.WHITE.toCompose(), fontSize = 15.sp,
                            fontFamily = FontFamily(f.typeface),
                            modifier = Modifier.clip(CircleShape).background(if (on) androidx.compose.ui.graphics.Color.White else Panel)
                                .pressable(scaleDown = 0.94f, onClick = { onChange(layer.copy(font = f)) }).padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                TextPart.LOOK -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TextLook.entries) { lk -> Chip(lk.label, layer.look == lk) { onChange(layer.copy(look = lk)) } }
                }
                TextPart.COLOR -> Column {
                    Swatches("Text", layer.color) { onChange(layer.copy(color = it)) }
                    if (layer.look != TextLook.PLAIN && layer.look != TextLook.SHADOW) Swatches(
                        when (layer.look) { TextLook.LABEL -> "Label"; TextLook.NEON -> "Glow"; TextLook.BLOOM -> "Flowers"; else -> "Edge" }, layer.accent,
                    ) { onChange(layer.copy(accent = it)) }
                }
                TextPart.SIZE -> Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Slider(layer.size, { onChange(layer.copy(size = it)) }, Modifier.weight(1f), valueRange = 0.03f..0.25f,
                        colors = SliderDefaults.colors(thumbColor = androidx.compose.ui.graphics.Color.White, activeTrackColor = androidx.compose.ui.graphics.Color.White,
                            inactiveTrackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f)))
                    Text("${(layer.size * 400).roundToInt()}", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp,
                        modifier = Modifier.width(40.dp).padding(start = 8.dp))
                }
            }
        }
    }
}

private fun Int.toCompose() = androidx.compose.ui.graphics.Color(this)

@Composable
private fun Swatches(label: String, current: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp).width(54.dp))
        LazyRow(contentPadding = PaddingValues(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TextColors) { c ->
                Box(Modifier.size(28.dp).clip(CircleShape).background(c.toCompose())
                    .border(if (c == current) 3.dp else 1.dp, if (c == current) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f), CircleShape)
                    .pressable(scaleDown = 0.85f, onClick = { onPick(c) }))
            }
        }
    }
}

/** A theme shown as "Aa" in its own look, on a dark tile. */
@Composable
private fun ThemeTile(t: TextTheme, selected: Boolean, onClick: () -> Unit) {
    val sample = remember(t) { TextLayer(0, "Aa", size = 0.42f, color = t.color, accent = t.accent, font = t.font, look = t.look) }
    Column(Modifier.pressable(scaleDown = 0.94f, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        ComposeCanvas(
            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(0xFF3A3D45))
                .then(if (selected) Modifier.border(2.dp, androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)) else Modifier),
        ) {
            drawIntoCanvas { TextRender.draw(it.nativeCanvas, sample.copy(size = if (t.look == TextLook.BLOOM) 0.26f else 0.42f), size.width, size.height) }
        }
        Text(t.label, color = if (selected) androidx.compose.ui.graphics.Color.White else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Type the words: new text, or changing [initial]. */
@Composable
internal fun TextEntryDialog(initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "Add text" else "Edit text") },
        text = {
            OutlinedTextField(text, { text = it.take(200) }, Modifier.fillMaxWidth().focusRequester(focus), minLines = 2, maxLines = 5,
                placeholder = { Text("Type something") }, textStyle = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Medium))
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onDone(text.trim()) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
