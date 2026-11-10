package com.edureminder.quicktube.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A header (search bar, chips) laid over a list that slides away as the list is scrolled down and slides back, animated,
 * as soon as it's scrolled up a little, like the YouTube and Play Store apps. Put [connection] on a parent of the list
 * with `Modifier.nestedScroll`, give the list [height] as top content padding, and move the header by [offset].
 */
@Stable
class HideOnScrollState internal constructor(private val scope: CoroutineScope) {
    /** The header's measured height in pixels. */
    var height by mutableIntStateOf(0)

    private val anim = Animatable(0f)

    /** How far the header is moved up, in pixels: 0 shown, -[height] hidden. */
    val offset: Float get() = anim.value

    /** Scroll in one direction since it last changed: a small move up shows it, a small move down hides it. */
    private var run = 0f
    private var target = 0f

    private fun animateTo(to: Float) {
        if (to == target) return
        target = to
        scope.launch { anim.animateTo(to, tween(220)) }
    }

    fun show() { run = 0f; animateTo(0f) }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            // Reset the run when the direction changes.
            if ((dy > 0f) != (run > 0f)) run = 0f
            run += dy
            if (abs(run) > Threshold) animateTo(if (run < 0f) -height.toFloat() else 0f)
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Pulled at the very top: the list can't go further, so the header belongs there.
            if (available.y > 0f) show()
            return Offset.Zero
        }
    }

    private companion object {
        const val Threshold = 12f
    }
}

@Composable
fun rememberHideOnScroll(): HideOnScrollState {
    val scope = rememberCoroutineScope()
    return remember { HideOnScrollState(scope) }
}
