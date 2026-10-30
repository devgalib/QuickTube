package com.edureminder.quicktube.feature.gallery.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Thin outline glyphs drawn to match the system video player's control row (Material has no close equivalents for
 * its picture-in-picture, aspect and rotate marks). 24-unit grid, 1.8 stroke, round caps.
 */
internal object PlayerIcons {
    private fun icon(name: String, fill: (PathBuilder.() -> Unit)? = null, stroke: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = stroke)
            if (fill != null) path(fill = SolidColor(Color.White), pathBuilder = fill)
        }.build()

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t); lineTo(r - rad, t); arcTo(rad, rad, 0f, false, true, r, t + rad)
        lineTo(r, b - rad); arcTo(rad, rad, 0f, false, true, r - rad, b)
        lineTo(l + rad, b); arcTo(rad, rad, 0f, false, true, l, b - rad)
        lineTo(l, t + rad); arcTo(rad, rad, 0f, false, true, l + rad, t); close()
    }

    val LockOpen by lazy {
        icon("LockOpen") {
            roundRect(5.5f, 11f, 18.5f, 21f, 2.5f)
            moveTo(8.5f, 11f); lineTo(8.5f, 7.5f); arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 7.5f)
        }
    }

    val Lock by lazy {
        icon("Lock") {
            roundRect(5.5f, 11f, 18.5f, 21f, 2.5f)
            moveTo(8.5f, 11f); lineTo(8.5f, 7.5f); arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 7.5f); lineTo(15.5f, 11f)
        }
    }

    private fun PathBuilder.speaker() {
        moveTo(3.5f, 9.5f); lineTo(7f, 9.5f); lineTo(11.5f, 5.5f); lineTo(11.5f, 18.5f); lineTo(7f, 14.5f); lineTo(3.5f, 14.5f); close()
    }

    val Volume by lazy {
        icon("Volume") {
            speaker()
            moveTo(15f, 9f); arcTo(4f, 4f, 0f, false, true, 15f, 15f)
            moveTo(17.5f, 6.5f); arcTo(7.5f, 7.5f, 0f, false, true, 17.5f, 17.5f)
        }
    }

    val Mute by lazy {
        icon("Mute") {
            speaker()
            moveTo(15.5f, 9.5f); lineTo(20.5f, 14.5f)
            moveTo(20.5f, 9.5f); lineTo(15.5f, 14.5f)
        }
    }

    val Pip by lazy {
        icon("Pip") {
            moveTo(11.5f, 6f); lineTo(7.5f, 6f); arcTo(2.5f, 2.5f, 0f, false, false, 5f, 8.5f)
            lineTo(5f, 18.5f); arcTo(2.5f, 2.5f, 0f, false, false, 7.5f, 21f)
            lineTo(14f, 21f); arcTo(2.5f, 2.5f, 0f, false, false, 16.5f, 18.5f); lineTo(16.5f, 12.5f)
            roundRect(13f, 3f, 19.5f, 9.5f, 1.6f)
        }
    }

    val AspectFit by lazy {
        icon("AspectFit") {
            roundRect(4f, 4f, 20f, 20f, 3.5f)
            moveTo(10f, 9.5f); lineTo(7.5f, 12f); lineTo(10f, 14.5f)
            moveTo(14f, 9.5f); lineTo(16.5f, 12f); lineTo(14f, 14.5f)
        }
    }

    val AspectStretch by lazy {
        icon("AspectStretch") {
            moveTo(4f, 9f); lineTo(4f, 6.5f); arcTo(2.5f, 2.5f, 0f, false, true, 6.5f, 4f); lineTo(9f, 4f)
            moveTo(15f, 4f); lineTo(17.5f, 4f); arcTo(2.5f, 2.5f, 0f, false, true, 20f, 6.5f); lineTo(20f, 9f)
            moveTo(20f, 15f); lineTo(20f, 17.5f); arcTo(2.5f, 2.5f, 0f, false, true, 17.5f, 20f); lineTo(15f, 20f)
            moveTo(9f, 20f); lineTo(6.5f, 20f); arcTo(2.5f, 2.5f, 0f, false, true, 4f, 17.5f); lineTo(4f, 15f)
        }
    }

    val AspectCrop by lazy {
        icon("AspectCrop", fill = { roundRect(7.5f, 7.5f, 16.5f, 16.5f, 1f) }) { roundRect(4f, 4f, 20f, 20f, 3.5f) }
    }

    val Speed by lazy {
        icon("Speed", fill = { moveTo(10f, 8.3f); lineTo(15.8f, 12f); lineTo(10f, 15.7f); close() }) {
            // Solid right half, dashed left half.
            moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, false, true, 12f, 20.5f)
            val r = 8.5f
            listOf(200f, 235f, 270f, 305f, 340f).forEach { deg ->
                val a1 = Math.toRadians((deg - 9).toDouble()); val a2 = Math.toRadians((deg + 9).toDouble())
                moveTo(12f + r * kotlin.math.sin(a1).toFloat(), 12f - r * kotlin.math.cos(a1).toFloat())
                arcTo(r, r, 0f, false, true, 12f + r * kotlin.math.sin(a2).toFloat(), 12f - r * kotlin.math.cos(a2).toFloat())
            }
        }
    }

    val Headphones by lazy {
        icon("Headphones") {
            moveTo(4.5f, 17f); lineTo(4.5f, 12.5f); arcTo(7.5f, 7.5f, 0f, false, true, 19.5f, 12.5f); lineTo(19.5f, 17f)
            roundRect(4.5f, 13.5f, 8.5f, 20.5f, 1.8f)
            roundRect(15.5f, 13.5f, 19.5f, 20.5f, 1.8f)
        }
    }

    val Rotate by lazy {
        icon("Rotate") {
            roundRect(4.5f, 4f, 13.5f, 20f, 2.5f)
            moveTo(16.5f, 4.5f); arcTo(6f, 6f, 0f, false, true, 20f, 9.5f)
            moveTo(20f, 9.5f); lineTo(17.9f, 8.6f)
            moveTo(20f, 9.5f); lineTo(20.7f, 7.4f)
            moveTo(20f, 14.5f); arcTo(6f, 6f, 0f, false, true, 16.5f, 19.5f)
        }
    }
}
