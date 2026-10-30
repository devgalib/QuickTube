package com.edureminder.quicktube.feature.gallery.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.Player
import androidx.media3.ui.compose.PlayerSurface
import kotlin.math.max
import kotlin.math.roundToInt

/** Pinch zoom / pan of the video. Bounds come from the last layout so panning stops at the picture's edges. */
@Stable
class VideoZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    internal var maxX = 0f
    internal var maxY = 0f
    private var baseW = 0f
    private var baseH = 0f
    private var boxW = 0f
    private var boxH = 0f

    val zoomed: Boolean get() = scale > 1.02f

    internal fun bounds(baseW: Float, baseH: Float, boxW: Float, boxH: Float) {
        this.baseW = baseW; this.baseH = baseH; this.boxW = boxW; this.boxH = boxH
    }

    fun transform(zoom: Float, pan: Offset, centroid: Offset) {
        val s = (scale * zoom).coerceIn(1f, MAX)
        val z = s / scale
        val center = Offset(boxW / 2, boxH / 2)
        val o = offset * z + (centroid - center) * (1 - z) + pan
        val mx = max(0f, (baseW * s - boxW) / 2)
        val my = max(0f, (baseH * s - boxH) / 2)
        offset = Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        scale = s
    }

    fun reset() { scale = 1f; offset = Offset.Zero }

    private companion object { const val MAX = 4f }
}

/**
 * The video surface sized for [aspect] (fit, stretch or crop) and [zoom]. Only layout reads the zoom, so pinching
 * re-places the SurfaceView without recomposing anything.
 */
@Composable
fun VideoLayer(player: Player?, aspect: AspectMode, ratio: Float, zoom: VideoZoom, modifier: Modifier = Modifier) {
    Layout({ PlayerSurface(player = player) }, modifier) { measurables, c ->
        val w = c.maxWidth.toFloat()
        val h = c.maxHeight.toFloat()
        val r = if (ratio > 0f) ratio else 16f / 9f
        val (bw, bh) = when (aspect) {
            AspectMode.FIT -> if (w / h > r) h * r to h else w to w / r
            AspectMode.CROP -> if (w / h > r) w to w / r else h * r to h
            AspectMode.STRETCH -> w to h
        }
        zoom.bounds(bw, bh, w, h)
        val sw = (bw * zoom.scale).roundToInt().coerceAtLeast(1)
        val sh = (bh * zoom.scale).roundToInt().coerceAtLeast(1)
        val placeable = measurables.first().measure(Constraints.fixed(sw, sh))
        layout(c.maxWidth, c.maxHeight) {
            placeable.place(((w - sw) / 2 + zoom.offset.x).roundToInt(), ((h - sh) / 2 + zoom.offset.y).roundToInt())
        }
    }
}
