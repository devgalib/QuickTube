package com.edureminder.quicktube.feature.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.model.GalleryMedia

/** Column count and spacing measured from the system gallery: 4 columns, 2dp gaps, edge to edge. */
private const val COLUMNS = 4
private val SelectedScrim = Color.Black.copy(alpha = 0.3f)
private val GAP = 2.dp

/**
 * The photo grid with month headers. Tap opens (or toggles while selecting); long-press starts selecting and
 * dragging extends the selection across cells, auto-scrolling near the edges.
 * [selection] is null outside selection mode.
 */
@Composable
fun MediaGrid(
    ui: GalleryUi,
    state: LazyGridState,
    selection: Set<Long>?,
    onSelection: (Set<Long>?) -> Unit,
    onOpen: (GalleryMedia) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    top: (LazyGridScope.() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val currentUi by rememberUpdatedState(ui)
    // Grid keys of cells are media ids; this maps them back to positions in the flat item list.
    val indexById by rememberUpdatedState(remember(ui.items) { HashMap<Any, Int>(ui.items.size * 2).also { m -> ui.items.forEachIndexed { i, it -> m[it.id] = i } } })
    val currentSelection by rememberUpdatedState(selection)
    // Drag-select bookkeeping lives outside composition; only the resulting selection is state.
    val drag = remember { DragSelect() }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    val edge = with(LocalDensity.current) { 72.dp.toPx() }

    fun indexAt(pos: Offset): Int? = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        pos.x >= info.offset.x && pos.x < info.offset.x + info.size.width &&
            pos.y >= info.offset.y && pos.y < info.offset.y + info.size.height
    }?.let { indexById[it.key] }

    fun extendTo(pos: Offset) {
        val i = indexAt(pos) ?: return
        if (i == drag.last) return
        drag.last = i
        onSelection(GallerySelection.dragRange(drag.base, currentUi.items, drag.anchor, i))
    }

    LaunchedEffect(autoScroll) {
        if (autoScroll == 0f) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            state.scrollBy(autoScroll)
            extendTo(drag.pointer)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = state,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    // A no-op long press stops a held finger from also counting as a tap when it lifts.
                    onLongPress = {},
                    onTap = { pos ->
                        val i = indexAt(pos) ?: return@detectTapGestures
                        val media = currentUi.items[i]
                        val sel = currentSelection
                        if (sel != null) onSelection(GallerySelection.toggle(sel, media.id)) else onOpen(media)
                    },
                )
            }
            .pointerInput(Unit) {
                // Long press, then drag: claims the pointer in the Initial pass so the grid's own scrolling
                // never sees these moves (auto-scroll near the edges does the scrolling instead).
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val i = indexAt(held.position) ?: return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val sel = currentSelection.orEmpty()
                    drag.base = sel
                    drag.anchor = i
                    drag.last = i
                    drag.pointer = held.position
                    onSelection(sel + currentUi.items[i].id)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == held.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        change.consume()
                        drag.pointer = change.position
                        extendTo(change.position)
                        val h = size.height
                        val y = change.position.y
                        autoScroll = when {
                            y < edge -> -((edge - y) / edge * 28f).coerceAtLeast(4f)
                            y > h - edge -> ((y - (h - edge)) / edge * 28f).coerceAtLeast(4f)
                            else -> 0f
                        }
                    }
                    autoScroll = 0f
                }
            },
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        top?.invoke(this)
        items(
            ui.entries,
            key = { it.key },
            span = { if (it is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { if (it is GridEntry.Header) 0 else 1 },
        ) { entry ->
            when (entry) {
                is GridEntry.Header -> MonthHeader(entry, ui.items, selection, onSelection)
                is GridEntry.Cell -> MediaCell(entry.media, selection?.contains(entry.media.id))
            }
        }
    }
}

private class DragSelect {
    var base: Set<Long> = emptySet()
    var anchor = 0
    var last = -1
    var pointer = Offset.Zero
}

@Composable
private fun MonthHeader(header: GridEntry.Header, items: List<GalleryMedia>, selection: Set<Long>?, onSelection: (Set<Long>?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            header.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp, lineHeight = 18.sp, style = MaterialTheme.typography.bodyLarge,
        )
        if (selection != null) {
            val ids = remember(header, items) { items.subList(header.first, header.first + header.count).map { it.id } }
            val all = ids.isNotEmpty() && selection.containsAll(ids)
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Select month") { onSelection(GallerySelection.toggleSection(selection, ids)) },
                contentAlignment = Alignment.Center,
            ) {
                SelectBox(all, outline = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MediaCell(media: GalleryMedia, selected: Boolean?) {
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        GalleryThumbnail(media, Modifier.fillMaxSize())
        if (selected == true) Box(Modifier.fillMaxSize().background(SelectedScrim))
        if (media.isVideo) PlayBadge(Modifier.align(Alignment.Center), 28.dp)
        if (selected != null) SelectBox(selected, Modifier.align(Alignment.TopEnd).padding(7.dp))
    }
}

/** Translucent round play mark over video thumbnails. */
@Composable
fun PlayBadge(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp) {
    Box(modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Icon(QtIcons.Play, null, tint = Color.White, modifier = Modifier.size(size * 0.62f).graphicsLayer { translationX = size.toPx() * 0.03f })
    }
}

/** Rounded-square check: white outline when off, accent fill with a check when on. */
@Composable
fun SelectBox(checked: Boolean, modifier: Modifier = Modifier, outline: Color = Color.White) {
    val ex = LocalQtExtras.current
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier.size(20.dp).clip(shape)
            .then(if (checked) Modifier.background(ex.brand) else Modifier.background(Color.Black.copy(alpha = 0.12f)).border(1.8.dp, outline, shape)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(checked, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(QtIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}
