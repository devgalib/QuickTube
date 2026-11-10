package com.edureminder.quicktube.feature.gallery.editor

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.common.Permissions
import com.edureminder.quicktube.core.data.GalleryRepository
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.feature.gallery.uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private enum class CTab(val label: String) { LAYOUT("Layout"), STYLE("Style"), FILTER("Filter"), FRAME("Frame"), TEXT("Text") }

private const val MaxPhotos = 9

/**
 * Collage maker: up to nine photos in one picture. The layouts follow the photo count; the shape, spacing, corners,
 * background, one look for every photo and a frame round the whole are all choosable. Tap two photos in the strip to
 * swap their places. Saved as a new photo in Pictures/QuickTube.
 */
@Composable
fun CollageScreen(initialIds: List<Long>, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = koinInject<GalleryRepository>()
    val scope = rememberCoroutineScope()
    val uris = remember { mutableStateListOf<Uri>() }
    // Screen-sized copies, one per uri.
    val photos = remember { mutableStateMapOf<Uri, Bitmap>() }
    var style by remember { mutableStateOf(CollageStyle()) }
    var tab by remember { mutableStateOf(CTab.LAYOUT) }
    var swapFrom by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(initialIds.isEmpty()) }
    var pickedText by remember { mutableStateOf<Long?>(null) }
    var typing by remember { mutableStateOf<TextLayer?>(null) }

    LaunchedEffect(Unit) {
        if (initialIds.isEmpty()) return@LaunchedEffect
        repo.load()
        val items = repo.library.filterNotNull().first().items.associateBy { it.id }
        uris += initialIds.mapNotNull { items[it]?.takeIf { m -> !m.isVideo && !m.isAudio }?.uri }.take(MaxPhotos)
        loaded = true
    }
    // Load what's new, drop what was removed.
    LaunchedEffect(uris.toList()) {
        for (u in uris.toList()) if (u !in photos) withContext(Dispatchers.IO) { loadBitmap(context, u, maxSide = 1200) }?.let { photos[u] = it }
        photos.keys.filter { it !in uris }.forEach { photos.remove(it) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MaxPhotos)) { picked ->
        val room = MaxPhotos - uris.size
        if (picked.size > room) Toast.makeText(context, "A collage holds up to $MaxPhotos photos", Toast.LENGTH_SHORT).show()
        uris += picked.filter { it !in uris }.take(room)
        style = style.copy(layout = 0)
    }
    fun addPhotos() {
        if (uris.size >= MaxPhotos) Toast.makeText(context, "A collage holds up to $MaxPhotos photos", Toast.LENGTH_SHORT).show()
        else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val ready = uris.mapNotNull { photos[it] }.takeIf { it.size == uris.size && it.isNotEmpty() }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    // On the Text tab the words are drawn live over the collage, so they can be dragged smoothly.
    val textTab = tab == CTab.TEXT
    LaunchedEffect(ready, if (textTab) style.copy(texts = emptyList()) else style) {
        val list = ready ?: run { preview = null; return@LaunchedEffect }
        preview = withContext(Dispatchers.Default) { CollageRenderer.render(list, style, 1080, withText = !textTab).asImageBitmap() }
    }
    // Small previews of every frame on the collage as it is now.
    val frameThumbs = remember(ready, style.copy(frame = PhotoFrame.NONE), style.frameColor) {
        ready?.let { list ->
            val base = CollageRenderer.render(list.map { scaled(it, 160) }, style.copy(frame = PhotoFrame.NONE), 200)
            PhotoFrame.entries.associateWith { f -> Frames.draw(base, f, if (f == style.frame) style.frameColor else 0).asImageBitmap() }
        }.orEmpty()
    }
    val filterThumb = remember(ready) { ready?.firstOrNull()?.let { scaled(it, 200).asImageBitmap() } }

    fun save() {
        if (uris.isEmpty()) return
        saving = true
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    // Bigger copies for the saved picture; fewer photos can each be larger.
                    val side = if (uris.size <= 4) 2048 else 1400
                    val full = uris.mapNotNull { loadBitmap(context, it, side) }
                    saveBitmap(context, CollageRenderer.render(full, style, 2160), "collage_${System.currentTimeMillis() / 1000}.jpg")
                }.getOrNull()
            }
            saving = false
            Toast.makeText(context, if (name != null) "Saved to Pictures/QuickTube" else "Couldn't save the collage", Toast.LENGTH_SHORT).show()
            if (name != null) onBack()
        }
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) save() else Toast.makeText(context, "Storage permission is needed to save the collage", Toast.LENGTH_LONG).show()
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler { if (uris.isEmpty() || saving) onBack() else confirmDiscard = true }
    val view = androidx.compose.ui.platform.LocalView.current
    val window = androidx.activity.compose.LocalActivity.current?.window
    if (window != null) com.edureminder.quicktube.feature.gallery.DarkSystemBars(remember(window, view) { androidx.core.view.WindowCompat.getInsetsController(window, view) })

    Column(Modifier.fillMaxSize().background(EditorBg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(QtIcons.Close, "Close") { if (uris.isEmpty()) onBack() else confirmDiscard = true }
            Text("Collage", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
            val can = ready != null && !saving
            Box(
                Modifier.clip(CircleShape).then(if (can) Modifier.background(LocalQtExtras.current.brand) else Modifier.background(Color.White.copy(alpha = 0.12f)))
                    .pressable(enabled = can, onClick = { if (Permissions.granted(context, Permissions.storageWrite)) save() else storage.launch(Permissions.storageWrite) })
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) { Text("Save", color = if (can) Color.White else Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }

        // The collage.
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            when {
                uris.isEmpty() && loaded -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Panel).pressable(onClick = { addPhotos() }), contentAlignment = Alignment.Center) {
                        Icon(QtIcons.Add, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Text("Add up to $MaxPhotos photos", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
                }
                preview == null -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                else -> {
                    val p = preview!!
                    Image(p, "Collage", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    if (textTab) TextOverlay(
                        p.width, p.height, style.texts, pickedText, onSelect = { pickedText = it },
                        onMove = { id, dx, dy ->
                            style = style.copy(texts = style.texts.map { t -> if (t.id == id) t.copy(x = (t.x + dx).coerceIn(0f, 1f), y = (t.y + dy).coerceIn(0f, 1f)) else t })
                        },
                    )
                }
            }
            if (saving) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            }
        }

        // Photos: tap one, then another, to swap them; × removes.
        if (uris.isNotEmpty()) {
            Text(
                if (swapFrom != null) "Now tap where it should go" else "Tap two photos to swap them",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(uris.toList(), key = { _, u -> u.toString() }) { i, u ->
                    Box(Modifier.size(56.dp)) {
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Panel)
                                .then(if (swapFrom == i) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                                .pressable(scaleDown = 0.92f, onClick = {
                                    val from = swapFrom
                                    swapFrom = when (from) {
                                        null -> i
                                        i -> null
                                        else -> { val a = uris[from]; uris[from] = uris[i]; uris[i] = a; null }
                                    }
                                }),
                        ) {
                            photos[u]?.let { b -> Image(remember(b) { scaled(b, 160).asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            Text("${i + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp))
                        }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.65f))
                                .pressable(scaleDown = 0.85f, onClick = { uris.removeAt(i); swapFrom = null; style = style.copy(layout = 0) }),
                            contentAlignment = Alignment.Center,
                        ) { Icon(QtIcons.Close, "Remove", tint = Color.White, modifier = Modifier.size(13.dp)) }
                    }
                }
                if (uris.size < MaxPhotos) item {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .pressable(scaleDown = 0.92f, onClick = { addPhotos() }),
                        contentAlignment = Alignment.Center,
                    ) { Icon(QtIcons.Add, "Add photos", tint = Color.White) }
                }
            }
        }

        // Tool panel.
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(top = 12.dp), contentAlignment = Alignment.Center) {
            when (tab) {
                CTab.LAYOUT -> Column {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CollageShape.entries) { s -> Chip(s.label, style.shape == s) { style = style.copy(shape = s) } }
                    }
                    LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val layouts = CollageLayouts.forCount(uris.size.coerceAtLeast(1))
                        itemsIndexed(layouts) { i, l -> LayoutTile(l, style.shape.ratio, style.layout == i) { style = style.copy(layout = i) } }
                    }
                }
                CTab.STYLE -> Column {
                    LazyRow(Modifier.padding(bottom = 4.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CellShape.entries) { sh -> Chip(sh.label, style.cellShape == sh) { style = style.copy(cellShape = sh) } }
                    }
                    LabeledSlider("Spacing", style.spacing) { style = style.copy(spacing = it) }
                    LabeledSlider("Corners", style.corners) { style = style.copy(corners = it) }
                    LazyRow(Modifier.padding(top = 4.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(CollageBackground.entries) { b ->
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(b.from), Color(b.to))))
                                    .border(if (style.background == b) 3.dp else 1.dp, if (style.background == b) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                                    .pressable(scaleDown = 0.85f, onClick = { style = style.copy(background = b) }),
                            )
                        }
                    }
                }
                CTab.FILTER -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(PhotoFilter.entries) { f -> FilterTile(f, filterThumb, style.filter == f) { style = style.copy(filter = f) } }
                }
                CTab.TEXT -> TextPanel(
                    style.texts, pickedText,
                    onAdd = { typing = TextLayer(System.nanoTime(), "") },
                    onEdit = { typing = it },
                    onDelete = { l -> style = style.copy(texts = style.texts.filter { it.id != l.id }); pickedText = null },
                    onChange = { l -> style = style.copy(texts = style.texts.map { if (it.id == l.id) l else it }) },
                )
                CTab.FRAME -> Column {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(PhotoFrame.entries) { f ->
                            FrameTile(f, frameThumbs[f], style.frame == f) { style = style.copy(frame = f, frameColor = if (style.frame == f) style.frameColor else 0) }
                        }
                    }
                    if (style.frame.colors.size > 1) Row(Modifier.padding(start = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        style.frame.colors.forEachIndexed { i, c ->
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                                .border(if (i == style.frameColor) 3.dp else 1.dp, if (i == style.frameColor) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                                .pressable(scaleDown = 0.85f, onClick = { style = style.copy(frameColor = i) }))
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            CTab.entries.forEach { t ->
                val on = t == tab
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).pressable(onClick = { tab = t }).padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        when (t) { CTab.LAYOUT -> QtIcons.CropSquare; CTab.STYLE -> QtIcons.Tune; CTab.FILTER -> QtIcons.Palette; CTab.FRAME -> QtIcons.PCrop; CTab.TEXT -> QtIcons.Edit }, null,
                        tint = if (on) Color.White else Muted, modifier = Modifier.size(24.dp),
                    )
                    Text(t.label, color = if (on) Color.White else Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    typing?.let { t ->
        TextEntryDialog(t.text, onDismiss = { typing = null }) { words ->
            style = if (style.texts.any { it.id == t.id }) style.copy(texts = style.texts.map { if (it.id == t.id) it.copy(text = words) else it })
            else style.copy(texts = style.texts + TextTheme.CUTE.applyTo(t.copy(text = words)))
            pickedText = t.id
            typing = null
        }
    }
    if (confirmDiscard) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard collage?") },
        text = { Text("This collage won't be saved.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Slider(value, onChange, Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)))
        Text("${(value * 100).roundToInt()}", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(36.dp).padding(start = 6.dp))
    }
}

/** A small drawing of a layout: its cells as rounded blocks, in the collage's shape. */
@Composable
private fun LayoutTile(layout: CollageLayout, ratio: Float, selected: Boolean, onClick: () -> Unit) {
    val h = 56.dp
    val w = (56 * ratio.coerceIn(0.56f, 1.5f)).dp
    Column(Modifier.pressable(scaleDown = 0.94f, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(
            Modifier.size(w, h).clip(RoundedCornerShape(8.dp)).background(Panel)
                .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier),
        ) {
            val gap = 3.dp.toPx()
            layout.cells.forEach { c ->
                val r = CollageLayouts.place(c, size.width.roundToInt(), size.height.roundToInt(), gap)
                drawRoundRect(
                    if (selected) Color.White else Color.White.copy(alpha = 0.45f),
                    androidx.compose.ui.geometry.Offset(r.l, r.t), androidx.compose.ui.geometry.Size(r.r - r.l, r.b - r.t),
                    androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
            }
        }
        Text(layout.name, color = if (selected) Color.White else Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
    }
}
