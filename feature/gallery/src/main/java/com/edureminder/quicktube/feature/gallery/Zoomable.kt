package com.edureminder.quicktube.feature.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Pinch, pan and double-tap zoom for one viewer page. Panning is limited to the picture itself (not the black
 * bars around a fitted photo). At 1x single-finger drags are left alone so the pager can swipe.
 */
@Stable
class ZoomState(private val contentAspect: Float) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var box = Size.Zero
    val zoomed: Boolean get() = scale > 1.01f

    internal fun setBox(size: Size) { box = size }

    /** The fitted picture's size inside the page at 1x. */
    private fun fitted(): Size {
        if (box.width <= 0f || contentAspect <= 0f) return box
        return if (box.width / box.height > contentAspect) Size(box.height * contentAspect, box.height)
        else Size(box.width, box.width / contentAspect)
    }

    private fun clamp(o: Offset, s: Float): Offset {
        val f = fitted()
        val maxX = max(0f, (f.width * s - box.width) / 2)
        val maxY = max(0f, (f.height * s - box.height) / 2)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Zooms about [centroid] (page coordinates) so the point under the fingers stays put, then pans. */
    internal fun transform(zoom: Float, pan: Offset, centroid: Offset) {
        val newScale = (scale * zoom).coerceIn(0.8f, MAX)
        val z = newScale / scale
        val center = Offset(box.width / 2, box.height / 2)
        offset = clamp(offset * z + (centroid - center) * (1 - z) + pan, newScale)
        scale = newScale
    }

    /** True when a one-finger pan in this direction would move the picture (so it isn't the pager's swipe). */
    internal fun canPan(pan: Offset): Boolean {
        if (!zoomed) return false
        val c = clamp(offset + pan, scale)
        return abs(c.x - offset.x) > 0.5f || abs(c.y - offset.y) > 0.5f
    }

    internal suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
        val from = scale
        val fromOffset = offset
        val clamped = clamp(targetOffset, targetScale)
        val t = Animatable(0f)
        t.animateTo(1f, spring(stiffness = 600f)) {
            scale = from + (targetScale - from) * value
            offset = fromOffset + (clamped - fromOffset) * value
        }
    }

    internal suspend fun settle() { if (scale < 1f) animateTo(1f, Offset.Zero) }

    /** Double tap: 1x -> 2.5x centred on the tap, anything else -> back to 1x. */
    internal suspend fun toggle(at: Offset) {
        if (zoomed) animateTo(1f, Offset.Zero)
        else {
            val center = Offset(box.width / 2, box.height / 2)
            animateTo(DOUBLE_TAP, (at - center) * (1 - DOUBLE_TAP))
        }
    }

    fun reset() { scale = 1f; offset = Offset.Zero }

    private companion object {
        const val MAX = 6f
        const val DOUBLE_TAP = 2.5f
    }
}

@Composable
fun rememberZoomState(key: Any, contentAspect: Float) = remember(key) { ZoomState(contentAspect) }

fun Modifier.zoomable(state: ZoomState, scope: CoroutineScope, onTap: () -> Unit): Modifier = this
    .onSizeChanged { state.setBox(Size(it.width.toFloat(), it.height.toFloat())) }
    .pointerInput(state) {
        detectTapGestures(onTap = { onTap() }, onDoubleTap = { scope.launch { state.toggle(it) } })
    }
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (event.changes.any { it.isConsumed }) break
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (fingers >= 2 || state.canPan(pan)) {
                    state.transform(zoom, pan, event.calculateCentroid(useCurrent = false))
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            scope.launch { state.settle() }
        }
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }
