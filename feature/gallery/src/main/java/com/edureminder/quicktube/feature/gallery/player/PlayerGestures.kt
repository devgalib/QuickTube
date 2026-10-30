package com.edureminder.quicktube.feature.gallery.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/** Where a double tap landed: the system player rewinds on the left third, toggles in the middle, skips on the right. */
enum class TapZone { LEFT, CENTER, RIGHT }

/** What the player does with each gesture; see the system player's gesture guide. */
interface PlayerGestureHandler {
    val enabled: Boolean
    val zoomed: Boolean
    fun onTap()
    fun onDoubleTap(zone: TapZone)
    fun onHoldStart()
    fun onHoldEnd()
    /** Vertical swipe on the [left] half (brightness) or right half (volume); [delta] is a fraction of the height, up = +. */
    fun onVerticalStart(left: Boolean)
    fun onVertical(left: Boolean, delta: Float)
    /** Horizontal swipe = seek; [delta] is a fraction of the width, right = +. */
    fun onSeekStart()
    fun onSeek(delta: Float)
    fun onGestureEnd()
    fun onTransform(zoom: Float, pan: Offset, centroid: Offset)
}

private enum class Drag { NONE, VERTICAL, SEEK, TRANSFORM }

/**
 * Taps (single, double by thirds, long-press hold) plus swipes and pinch in one place. Swipes only take over once the
 * finger passes the touch slop, so taps stay instant; two fingers always mean zoom, and one finger pans once zoomed.
 */
@Composable
fun Modifier.playerGestures(handler: PlayerGestureHandler): Modifier {
    val h by rememberUpdatedState(handler)
    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { h.onTap() },
                onDoubleTap = { pos ->
                    if (!h.enabled) return@detectTapGestures h.onTap()
                    val third = size.width / 3f
                    h.onDoubleTap(if (pos.x < third) TapZone.LEFT else if (pos.x > 2 * third) TapZone.RIGHT else TapZone.CENTER)
                },
                onLongPress = { if (h.enabled && !h.zoomed) h.onHoldStart() },
                onPress = {
                    tryAwaitRelease()
                    h.onHoldEnd()
                },
            )
        }
        .pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var mode = Drag.NONE
                var travel = Offset.Zero
                val left = down.position.x < size.width / 2f
                do {
                    val event = awaitPointerEvent()
                    if (!h.enabled) break
                    val fingers = event.changes.count { it.pressed }
                    if (fingers >= 2 && mode != Drag.SEEK && mode != Drag.VERTICAL) mode = Drag.TRANSFORM
                    when (mode) {
                        Drag.NONE -> {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            travel += change.positionChange()
                            if (travel.getDistance() > slop) {
                                mode = when {
                                    h.zoomed -> Drag.TRANSFORM
                                    abs(travel.x) > abs(travel.y) -> Drag.SEEK.also { h.onSeekStart() }
                                    else -> Drag.VERTICAL.also { h.onVerticalStart(left) }
                                }
                                change.consume()
                            }
                        }
                        Drag.VERTICAL -> event.changes.firstOrNull { it.id == down.id }?.let { c ->
                            h.onVertical(left, -c.positionChange().y / size.height)
                            c.consume()
                        }
                        Drag.SEEK -> event.changes.firstOrNull { it.id == down.id }?.let { c ->
                            h.onSeek(c.positionChange().x / size.width)
                            c.consume()
                        }
                        Drag.TRANSFORM -> {
                            h.onTransform(event.calculateZoom(), event.calculatePan(), event.calculateCentroid(useCurrent = false))
                            event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
                if (mode != Drag.NONE) h.onGestureEnd()
            }
        }
}
