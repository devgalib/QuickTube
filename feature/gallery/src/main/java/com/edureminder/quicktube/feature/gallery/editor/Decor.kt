package com.edureminder.quicktube.feature.gallery.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** Small drawn ornaments (flowers, leaves, blossoms, hearts) shared by frames, collage backgrounds and text. */
internal object Decor {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** A flower of [petals] rounded petals around a centre, [r] its radius, turned by [rot] degrees. */
    fun flower(c: Canvas, cx: Float, cy: Float, r: Float, petal: Int, centre: Int, petals: Int = 5, rot: Float = 0f) {
        c.save()
        c.rotate(rot, cx, cy)
        val pw = r * 0.62f; val ph = r * 0.5f
        for (i in 0 until petals) {
            c.save()
            c.rotate(360f / petals * i, cx, cy)
            // Each petal a soft oval reaching out from the centre, a shade darker at its base.
            fill.color = petal
            c.drawOval(RectF(cx - ph / 2, cy - r, cx + ph / 2, cy - r + pw * 1.25f), fill)
            fill.color = Color.argb(40, 0, 0, 0)
            c.drawOval(RectF(cx - ph / 5, cy - r * 0.55f, cx + ph / 5, cy - r * 0.15f), fill)
            c.restore()
        }
        fill.color = centre
        c.drawCircle(cx, cy, r * 0.26f, fill)
        fill.color = Color.argb(90, 255, 255, 255)
        c.drawCircle(cx - r * 0.07f, cy - r * 0.07f, r * 0.09f, fill)
        c.restore()
    }

    /** A pointed leaf [len] long from ([x], [y]) at [angle] degrees (0 = right), with a lighter middle vein. */
    fun leaf(c: Canvas, x: Float, y: Float, len: Float, angle: Float, color: Int, width: Float = 0.38f) {
        c.save()
        c.rotate(angle, x, y)
        val w = len * width
        val path = Path().apply {
            moveTo(x, y)
            cubicTo(x + len * 0.3f, y - w, x + len * 0.75f, y - w * 0.8f, x + len, y)
            cubicTo(x + len * 0.75f, y + w * 0.8f, x + len * 0.3f, y + w, x, y)
            close()
        }
        fill.color = color
        c.drawPath(path, fill)
        val vein = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = len * 0.03f; this.color = Color.argb(70, 255, 255, 255) }
        c.drawLine(x + len * 0.05f, y, x + len * 0.85f, y, vein)
        c.restore()
    }

    /** A five-petal blossom with notched petals (cherry blossom). */
    fun blossom(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, rot: Float = 0f) {
        c.save()
        c.rotate(rot, cx, cy)
        fill.color = color
        for (i in 0 until 5) {
            c.save()
            c.rotate(72f * i, cx, cy)
            val p = Path().apply {
                moveTo(cx, cy)
                cubicTo(cx - r * 0.55f, cy - r * 0.35f, cx - r * 0.45f, cy - r * 1.0f, cx - r * 0.1f, cy - r * 0.95f)
                lineTo(cx, cy - r * 0.8f)
                lineTo(cx + r * 0.1f, cy - r * 0.95f)
                cubicTo(cx + r * 0.45f, cy - r * 1.0f, cx + r * 0.55f, cy - r * 0.35f, cx, cy)
                close()
            }
            c.drawPath(p, fill)
            c.restore()
        }
        fill.color = Color.argb(220, 255, 214, 102)
        for (i in 0 until 5) {
            val a = Math.toRadians(72.0 * i - 90)
            c.drawCircle(cx + (cos(a) * r * 0.3f).toFloat(), cy + (sin(a) * r * 0.3f).toFloat(), r * 0.07f, fill)
        }
        c.restore()
    }

    fun heart(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 2
        fill.color = color
        c.drawPath(heartPath(cx, cy, s), fill)
    }

    /** A heart centred on ([cx], [cy]), [s] half its size; also used to cut photos into hearts. */
    fun heartPath(cx: Float, cy: Float, s: Float) = Path().apply {
        moveTo(cx, cy + s * 0.9f)
        cubicTo(cx - s * 1.6f, cy - s * 0.1f, cx - s * 0.6f, cy - s * 1.3f, cx, cy - s * 0.35f)
        cubicTo(cx + s * 0.6f, cy - s * 1.3f, cx + s * 1.6f, cy - s * 0.1f, cx, cy + s * 0.9f)
        close()
    }

    /** A heart that fills [r] (wider than tall, like the shape in a photo app). */
    fun heartIn(r: RectF): Path {
        val s = minOf(r.width() / 2.2f, r.height() / 1.95f)
        return heartPath(r.centerX(), r.centerY(), s)
    }
}
