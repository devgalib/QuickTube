package com.edureminder.quicktube.feature.gallery.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.designsystem.QtEasing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Speeds offered by holding on the right of the video, slowest (bottom row) first. */
internal val HoldSpeeds = listOf(1.25f, 1.5f, 2f, 3f)

private val HoldRow = 87.dp
private val HoldBand = 60.dp
private val HoldBandOn = 80.dp
private val HoldLabelStart = 101.dp
private val HoldPillStart = 80.dp
private val HoldPillWidth = 230.dp
/** Room the ▶▶ mark takes before the chosen speed's label. */
private val HoldMarkSpace = 41.dp

/**
 * Where the rows sit, like the system player: a fixed list centred on the screen (not on the finger), one row every
 * 87dp, shrunk to fit a short landscape screen. Row 0 (1.25X) is at the bottom.
 */
internal class HoldGeometry(private val height: Float, density: Density) {
    private val pitch = minOf(with(density) { HoldRow.toPx() }, height / 4.4f)
    fun center(i: Int) = height / 2 + (1.5f - i) * pitch

    /**
     * The row under [y]. Near a boundary the [current] row is kept until the finger is 8% of a row past it, so a
     * finger resting on the line between two speeds doesn't flicker between them.
     */
    fun rowAt(y: Float, current: Int = -1): Int {
        val r = 1.5f - (y - height / 2) / pitch
        val i = r.roundToInt().coerceIn(0, HoldSpeeds.lastIndex)
        return if (current in HoldSpeeds.indices && i != current && abs(r - current) < 0.58f) current else i
    }
}

internal fun holdLabel(speed: Float) = (if (speed * 100 % 10 == 0f) "%.1fX" else "%.2fX").format(Locale.US, speed)

private val LabelShadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.4f), blurRadius = 6f))

/**
 * The system player's hold-for-speed list: four bands across the picture ([left], [width] in px, so not over the black
 * bars beside a narrow video) (3.0X at the top, 1.25X at the bottom), each a soft
 * left-to-right glow. The chosen row's band grows and brightens, a pill fading out to the right springs to it, and its
 * label slides over for an animated ▶▶ and grows into "2.0X Speed". Everything that moves is animated in the draw and
 * layer phases only, so sliding the finger never recomposes or relayouts the list and stays smooth on slow phones.
 */
@Composable
internal fun HoldSpeedList(selected: Int, left: Float, width: Float) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val pictureWidth = if (width > 0f) width else constraints.maxWidth.toFloat()
        // 101dp in on a phone-wide picture; proportionally less on a narrow one so "▶▶ 1.25X Speed" still fits.
        val labelStart = left + with(density) { minOf(HoldLabelStart.toPx(), maxOf(16.dp.toPx(), pictureWidth * 0.28f)) }
        val rows = remember(height, density) { HoldGeometry(height, density) }
        val pillY = remember { Animatable(rows.center(selected)) }
        LaunchedEffect(selected, rows) { pillY.animateTo(rows.center(selected), spring(dampingRatio = 0.86f, stiffness = 520f)) }
        val glows = HoldSpeeds.indices.map { i ->
            animateFloatAsState(if (i == selected) 1f else 0f, tween(220, easing = QtEasing.Standard), label = "glow$i")
        }
        Box(
            Modifier.fillMaxSize().drawBehind {
                val off = HoldBand.toPx()
                val on = HoldBandOn.toPx()
                HoldSpeeds.indices.forEach { i ->
                    val g = glows[i].value
                    val h = off + (on - off) * g
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.White.copy(alpha = 0.03f + 0.07f * g),
                            1f to Color.White.copy(alpha = 0.13f + 0.09f * g),
                            startX = left, endX = left + pictureWidth,
                        ),
                        topLeft = Offset(left, rows.center(i) - h / 2),
                        size = Size(pictureWidth, h),
                    )
                }
                val pillLeft = maxOf(left, labelStart - (HoldLabelStart - HoldPillStart).toPx())
                val pillWidth = minOf(HoldPillWidth.toPx(), left + pictureWidth - pillLeft)
                drawRoundRect(
                    Brush.horizontalGradient(
                        0f to Color.White.copy(alpha = 0.2f), 0.45f to Color.White.copy(alpha = 0.12f), 1f to Color.Transparent,
                        startX = pillLeft, endX = pillLeft + pillWidth,
                    ),
                    topLeft = Offset(pillLeft, pillY.value - on / 2),
                    size = Size(pillWidth, on),
                    cornerRadius = CornerRadius(on / 2),
                )
            },
        )
        HoldSpeeds.forEachIndexed { i, speed -> HoldRowLabel(speed, i == selected, glows[i], labelStart, rows.center(i)) }
    }
}

@Composable
private fun HoldRowLabel(speed: Float, selected: Boolean, glow: State<Float>, startX: Float, centerY: Float) {
    Box(
        Modifier.height(HoldBandOn).offset { IntOffset(startX.roundToInt(), (centerY - HoldBandOn.toPx() / 2).roundToInt()) },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (selected) HoldChevrons(
            Modifier.offset(x = 6.dp).size(27.dp, 16.dp).graphicsLayer {
                alpha = glow.value
                val s = 0.6f + 0.4f * glow.value
                scaleX = s; scaleY = s
            },
        )
        Row(
            Modifier.graphicsLayer {
                val g = glow.value
                translationX = HoldMarkSpace.toPx() * g
                val s = 1f + 0.2f * g
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        ) {
            Text(holdLabel(speed), color = Color.White, fontSize = 15.sp, style = LabelShadow)
            Text(" Speed", color = Color.White, fontSize = 15.sp, style = LabelShadow, modifier = Modifier.graphicsLayer { alpha = glow.value })
        }
    }
}

/** ▶▶ whose two triangles light up in turn, left to right, like the system player's fast-forward mark. */
@Composable
internal fun HoldChevrons(modifier: Modifier = Modifier) {
    val phase = rememberInfiniteTransition(label = "chevrons")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        val round = 1.6.dp.toPx()
        val gap = 1.5.dp.toPx()
        val w = (size.width - gap) / 2
        repeat(2) { k ->
            // Each triangle peaks half a cycle after the other and dims to 40% in between.
            val d = abs(((phase.value - 0.5f * k) % 1f + 1f) % 1f - 0.25f).let { minOf(it, 1f - it) } * 2f
            val alpha = 1f - 0.6f * d.coerceIn(0f, 1f)
            val x = k * (w + gap)
            val path = Path().apply {
                moveTo(x + round, round)
                lineTo(x + w - round, size.height / 2)
                lineTo(x + round, size.height - round)
                close()
            }
            val color = Color.White.copy(alpha = alpha)
            drawPath(path, color)
            drawPath(path, color, style = Stroke(round * 2, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
