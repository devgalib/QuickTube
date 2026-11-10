package com.edureminder.quicktube.feature.gallery.editor

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Frame shelves: clean classics, cute ones, flowers and leaves, and luxe ones. */
enum class FrameGroup(val label: String) { CLASSIC("Classic"), CUTE("Cute"), FLORAL("Floral"), LUXE("Luxe") }

/**
 * Frames drawn around (or over) a photo. Each has a few colours to pick from; the first is its signature look. Sizes
 * follow the photo, so a frame looks the same on a small preview and on the full-size save.
 */
enum class PhotoFrame(val label: String, val group: FrameGroup, val colors: List<Int>) {
    NONE("None", FrameGroup.CLASSIC, emptyList()),
    POLAROID("Polaroid", FrameGroup.CLASSIC, listOf(0xFFFFFFFF, 0xFFF6EFE3, 0xFF1E1E22, 0xFFF7DCE0).map { it.toInt() }),
    BORDER("Border", FrameGroup.CLASSIC, listOf(0xFFFFFFFF, 0xFF111114, 0xFFF3EAD8, 0xFFE9EEF5).map { it.toInt() }),
    FILM("Film strip", FrameGroup.CLASSIC, listOf(0xFF141414, 0xFF3A2A1E).map { it.toInt() }),
    DATE("Date stamp", FrameGroup.CLASSIC, listOf(0xFFFF8A2A, 0xFFFFD34D).map { it.toInt() }),
    CIRCLE("Circle", FrameGroup.CLASSIC, listOf(0xFFFFFFFF, 0xFFF6EFE3, 0xFF15151A, 0xFFFFE3EC).map { it.toInt() }),
    ARCH("Arch", FrameGroup.CLASSIC, listOf(0xFFF3EAD8, 0xFFFFFFFF, 0xFFE8DCD3, 0xFF1C1C20).map { it.toInt() }),
    SOFT_CARD("Soft card", FrameGroup.CUTE, listOf(0xFFFFE3EC, 0xFFE4F0FF, 0xFFEAF7E6, 0xFFFFF1D9).map { it.toInt() }),
    HEARTS("Hearts", FrameGroup.CUTE, listOf(0xFFFFD1DC, 0xFFFFE6EE, 0xFFE9DDFF).map { it.toInt() }),
    POLKA("Polka dots", FrameGroup.CUTE, listOf(0xFFFFE08A, 0xFFBDE7FF, 0xFFFFC9D9, 0xFFC9F2D4).map { it.toInt() }),
    PASTEL("Pastel glow", FrameGroup.CUTE, listOf(0xFFFFB5C8, 0xFFB5E3FF, 0xFFD6C2FF).map { it.toInt() }),
    WASHI("Washi tape", FrameGroup.CUTE, listOf(0xFFFAF4E8, 0xFFFFFFFF, 0xFFF1E7F4).map { it.toInt() }),
    GARDEN("Flower garden", FrameGroup.FLORAL, listOf(0xFFFFF6EE, 0xFFFFE8EF, 0xFFEFF6FF, 0xFFF3FBEF).map { it.toInt() }),
    VINE("Leafy vine", FrameGroup.FLORAL, listOf(0xFFF7F3E8, 0xFFFFFFFF, 0xFFEAF3E6).map { it.toInt() }),
    WREATH("Wreath", FrameGroup.FLORAL, listOf(0xFFFFF4EA, 0xFFEAF2FF, 0xFFFDE7EF, 0xFF1F2A24).map { it.toInt() }),
    SAKURA("Cherry blossom", FrameGroup.FLORAL, listOf(0xFFFFF1F4, 0xFFF4F1FF, 0xFF2B2230).map { it.toInt() }),
    TROPICAL("Palm leaves", FrameGroup.FLORAL, listOf(0xFFFFF7E6, 0xFFFFFFFF, 0xFFFFE9DC).map { it.toInt() }),
    STARS("Starry", FrameGroup.LUXE, listOf(0xFF141A33, 0xFF2A1638, 0xFF0F2A2A).map { it.toInt() }),
    GOLD("Gold edge", FrameGroup.LUXE, listOf(0xFFFBF6EC, 0xFF15151A, 0xFFF4ECEF).map { it.toInt() }),
    NEON("Neon", FrameGroup.LUXE, listOf(0xFFFF4FD8, 0xFF4FE3FF, 0xFFB8FF4F).map { it.toInt() }),
    ;

    /** The colour at [i], wrapping (for a saved choice that no longer exists). */
    fun color(i: Int) = if (colors.isEmpty()) Color.WHITE else colors[Math.floorMod(i, colors.size)]
}

internal object Frames {
    fun draw(photo: Bitmap, frame: PhotoFrame, colorIndex: Int): Bitmap {
        val w = photo.width; val h = photo.height
        val s = min(w, h).toFloat()
        val color = frame.color(colorIndex)
        val aa = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        return when (frame) {
            PhotoFrame.NONE -> photo
            PhotoFrame.POLAROID -> padded(photo, (s * 0.06f), (s * 0.06f), (s * 0.06f), (s * 0.24f), color) { c, r ->
                // A hairline where the print meets the paper.
                c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.002f; this.color = Color.argb(40, 0, 0, 0) })
            }
            PhotoFrame.BORDER -> padded(photo, s * 0.045f, s * 0.045f, s * 0.045f, s * 0.045f, color)
            PhotoFrame.FILM -> {
                val band = s * 0.11f
                padded(photo, s * 0.025f, band, s * 0.025f, band, color) { c, r ->
                    val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(235, 245, 240, 230) }
                    val hw = band * 0.32f; val hh = band * 0.42f; val gap = hw * 1.2f
                    var x = gap / 2
                    while (x + hw < c.width) {
                        val radius = hw * 0.18f
                        c.drawRoundRect(RectF(x, (band - hh) / 2, x + hw, (band + hh) / 2), radius, radius, hole)
                        c.drawRoundRect(RectF(x, r.bottom + (band - hh) / 2, x + hw, r.bottom + (band + hh) / 2), radius, radius, hole)
                        x += hw + gap
                    }
                }
            }
            PhotoFrame.DATE -> {
                val out = padded(photo, s * 0.03f, s * 0.03f, s * 0.03f, s * 0.03f, Color.WHITE)
                val c = Canvas(out)
                val text = java.text.SimpleDateFormat("''yy  MM  dd", java.util.Locale.US).format(java.util.Date())
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color; textSize = s * 0.055f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setShadowLayer(s * 0.012f, 0f, 0f, Color.argb(200, Color.red(color), Color.green(color) / 2, 0))
                }
                c.drawText(text, out.width - s * 0.06f - p.measureText(text), out.height - s * 0.07f, p)
                out
            }
            PhotoFrame.SOFT_CARD -> card(photo, s * 0.09f, s * 0.05f, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h + s * 0.18f, color, lighten(color, 0.55f), Shader.TileMode.CLAMP)
            }, shadow = true)
            PhotoFrame.PASTEL -> card(photo, s * 0.08f, s * 0.06f, Paint().apply {
                shader = LinearGradient(0f, 0f, w + s * 0.16f, h + s * 0.16f, intArrayOf(color, lighten(rotateHue(color, 60f), 0.2f), lighten(rotateHue(color, 140f), 0.25f)), null, Shader.TileMode.CLAMP)
            }, shadow = true)
            PhotoFrame.HEARTS -> padded(photo, s * 0.1f, s * 0.1f, s * 0.1f, s * 0.1f, color) { c, r ->
                val rnd = Random(7)
                val tones = intArrayOf(Color.WHITE, darken(color, 0.18f), Color.argb(255, 255, 120, 150))
                scatterOnBorder(c, r, s * 0.1f, s * 0.05f, rnd) { cx, cy, size, k -> heart(c, cx, cy, size, aa.apply { this.color = tones[k % tones.size] }) }
                c.drawRoundRect(r, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.008f; this.color = Color.WHITE })
            }
            PhotoFrame.POLKA -> padded(photo, s * 0.09f, s * 0.09f, s * 0.09f, s * 0.09f, color) { c, r ->
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(150, 255, 255, 255) }
                val step = s * 0.055f
                var row = 0
                var y = step / 2
                while (y < c.height) {
                    var x = if (row % 2 == 0) step / 2 else step
                    while (x < c.width) {
                        if (!r.contains(x, y)) c.drawCircle(x, y, step * 0.2f, dot)
                        x += step
                    }
                    y += step * 0.86f; row++
                }
                c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.012f; this.color = Color.WHITE })
            }
            PhotoFrame.WASHI -> padded(photo, s * 0.08f, s * 0.11f, s * 0.08f, s * 0.08f, color) { c, r ->
                // Two strips of patterned tape holding the photo at its top corners.
                tape(c, r.left, r.top, s, -35f, Color.argb(190, 255, 170, 190))
                tape(c, r.right, r.top, s, 35f, Color.argb(190, 150, 210, 240))
            }
            PhotoFrame.STARS -> padded(photo, s * 0.1f, s * 0.1f, s * 0.1f, s * 0.1f, color) { c, r ->
                val rnd = Random(11)
                scatterOnBorder(c, r, s * 0.1f, s * 0.03f, rnd) { cx, cy, size, k ->
                    if (k % 3 == 0) sparkle(c, cx, cy, size * 1.4f, aa.apply { this.color = Color.argb(240, 255, 236, 170) })
                    else c.drawCircle(cx, cy, size * 0.18f, aa.apply { this.color = Color.argb(200, 255, 255, 255) })
                }
                c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.004f; this.color = Color.argb(160, 255, 236, 170) })
            }
            PhotoFrame.GOLD -> padded(photo, s * 0.09f, s * 0.09f, s * 0.09f, s * 0.09f, color) { c, r ->
                val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    shader = LinearGradient(0f, 0f, c.width.toFloat(), c.height.toFloat(),
                        intArrayOf(0xFFB8862F.toInt(), 0xFFF3D98B.toInt(), 0xFFB07A26.toInt(), 0xFFF0D27E.toInt()), null, Shader.TileMode.CLAMP)
                }
                gold.strokeWidth = s * 0.012f
                val o = s * 0.035f
                c.drawRect(RectF(r.left - o, r.top - o, r.right + o, r.bottom + o), gold)
                gold.strokeWidth = s * 0.004f
                val o2 = s * 0.06f
                c.drawRect(RectF(r.left - o2, r.top - o2, r.right + o2, r.bottom + o2), gold)
            }
            PhotoFrame.CIRCLE -> {
                // The photo's middle cut round, on a square of [color], with a thin ring.
                val side = s * 1.12f
                val out = Bitmap.createBitmap(side.roundToInt(), side.roundToInt(), Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(color)
                val rad = s / 2
                val cx = side / 2; val cy = side / 2
                c.save()
                c.clipPath(Path().apply { addCircle(cx, cy, rad, Path.Direction.CW) })
                c.drawBitmap(photo, cx - w / 2f, cy - h / 2f, aa)
                c.restore()
                c.drawCircle(cx, cy, rad + s * 0.02f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = s * 0.006f; this.color = contrastLine(color)
                })
                out
            }
            PhotoFrame.ARCH -> padded(photo, s * 0.1f, s * 0.1f, s * 0.1f, s * 0.14f, color) { c, r ->
                // A window with a round top: paint the frame colour back over the photo outside the arch.
                val arch = archPath(r)
                c.save()
                c.clipOutPathCompat(arch)
                c.drawRect(r, Paint().apply { this.color = color })
                c.restore()
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.005f; this.color = contrastLine(color) }
                val o = s * 0.03f
                c.drawPath(archPath(RectF(r.left - o, r.top - o, r.right + o, r.bottom + o)), line)
            }
            PhotoFrame.GARDEN -> padded(photo, s * 0.12f, s * 0.12f, s * 0.12f, s * 0.12f, color) { c, r ->
                c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.006f; this.color = Color.WHITE })
                // Two bouquets on opposite corners, little buds along the edges.
                bouquet(c, r.left, r.top, s, 0f)
                bouquet(c, r.right, r.bottom, s, 180f)
                val rnd = Random(5)
                val tones = intArrayOf(0xFFF7A1B5.toInt(), 0xFFFFD27A.toInt(), 0xFFB9A7F5.toInt(), 0xFFFFFFFF.toInt())
                scatterOnBorder(c, r, s * 0.12f, s * 0.03f, rnd) { cx, cy, size, k ->
                    // Leave the bouquet corners clear.
                    val nearTl = cx < r.left + s * 0.35f && cy < r.top + s * 0.35f
                    val nearBr = cx > r.right - s * 0.35f && cy > r.bottom - s * 0.35f
                    if (!nearTl && !nearBr && (k / 2) % 2 == 0) Decor.flower(c, cx, cy, size * 0.55f, tones[k % tones.size], 0xFFFFC94D.toInt(), rot = k * 23f)
                }
            }
            PhotoFrame.VINE -> padded(photo, s * 0.11f, s * 0.11f, s * 0.11f, s * 0.11f, color) { c, r ->
                val band = s * 0.11f
                val green = intArrayOf(0xFF6FA36B.toInt(), 0xFF8CBF7A.toInt(), 0xFF4F8A5B.toInt())
                val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.006f; this.color = 0xFF5E8F5A.toInt() }
                // A wavy stem all round the photo, leaves along it, a few small white flowers.
                val path = Path()
                val rect = RectF(band / 2, band / 2, c.width - band / 2, c.height - band / 2)
                var k = 0
                walkBorder(rect, s * 0.07f) { x, y, nx, ny, i ->
                    val wave = (sin(i * 0.9) * band * 0.18f).toFloat()
                    val px = x + nx * wave; val py = y + ny * wave
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    val along = Math.toDegrees(kotlin.math.atan2(ny.toDouble(), nx.toDouble())).toFloat()
                    Decor.leaf(c, px, py, band * 0.42f, along + if (i % 2 == 0) 50f else -130f, green[k++ % green.size])
                    if (i % 5 == 2) Decor.flower(c, px, py, band * 0.16f, Color.WHITE, 0xFFFFD25A.toInt(), rot = i * 17f)
                }
                path.close()
                c.drawPath(path, stem)
            }
            PhotoFrame.WREATH -> {
                // A round photo inside a ring of leaves and flowers, on a square card.
                val side = s * 1.3f
                val out = Bitmap.createBitmap(side.roundToInt(), side.roundToInt(), Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(color)
                val cx = side / 2; val cy = side / 2; val rad = s * 0.46f
                c.save()
                c.clipPath(Path().apply { addCircle(cx, cy, rad, Path.Direction.CW) })
                c.drawBitmap(photo, cx - w / 2f, cy - h / 2f, aa)
                c.restore()
                val ring = rad + s * 0.03f
                val greens = intArrayOf(0xFF5D9466.toInt(), 0xFF7FB37A.toInt(), 0xFF3F7653.toInt(), 0xFF9CC48B.toInt())
                // A thin stem round the photo, then two layers of leaves along it, all leaning the same way.
                c.drawCircle(cx, cy, ring, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.006f; this.color = 0xFF56805A.toInt() })
                val n = 64
                for (layer in 0..1) for (i in 0 until n) {
                    val a = Math.toRadians(360.0 / n * (i + layer * 0.5))
                    val x = cx + (cos(a) * ring).toFloat(); val y = cy + (sin(a) * ring).toFloat()
                    val tangent = Math.toDegrees(a).toFloat() + 90f
                    // Outer layer points outwards, inner layer inwards.
                    val out = if (layer == 0) -40f else 40f
                    Decor.leaf(c, x, y, s * (0.085f - layer * 0.015f), tangent + out, greens[(i + layer) % greens.size], width = 0.42f)
                }
                val blooms = intArrayOf(0xFFF6A5B8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFC98B.toInt())
                for ((i, deg) in listOf(200f, 228f, 250f, 20f, 45f).withIndex()) {
                    val a = Math.toRadians(deg.toDouble())
                    Decor.flower(c, cx + (cos(a) * ring).toFloat(), cy + (sin(a) * ring).toFloat(), s * (if (i % 2 == 0) 0.05f else 0.038f),
                        blooms[i % blooms.size], 0xFFFFC94D.toInt(), rot = deg)
                }
                out
            }
            PhotoFrame.SAKURA -> padded(photo, s * 0.1f, s * 0.13f, s * 0.1f, s * 0.1f, color) { c, r ->
                c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.005f; this.color = 0x66FFFFFF })
                // A branch over the top-left corner with blossoms; petals drifting down the right side.
                val branch = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.014f; strokeCap = Paint.Cap.ROUND; this.color = 0xFF6B4A3A.toInt() }
                val p = Path().apply {
                    moveTo(0f, s * 0.2f)
                    cubicTo(s * 0.2f, s * 0.05f, s * 0.42f, s * 0.12f, s * 0.62f, s * 0.04f)
                }
                c.drawPath(p, branch)
                branch.strokeWidth = s * 0.007f
                c.drawLine(s * 0.25f, s * 0.1f, s * 0.32f, s * 0.2f, branch)
                c.drawLine(s * 0.45f, s * 0.09f, s * 0.5f, 0f, branch)
                val pinks = intArrayOf(0xFFF8B4C8.toInt(), 0xFFFFD3DF.toInt(), 0xFFF28FAD.toInt())
                listOf(0.06f to 0.15f, 0.17f to 0.08f, 0.3f to 0.21f, 0.39f to 0.1f, 0.5f to 0.02f, 0.58f to 0.07f, 0.24f to 0.03f).forEachIndexed { i, (fx, fy) ->
                    Decor.blossom(c, s * fx, s * fy, s * (0.035f + (i % 3) * 0.007f), pinks[i % pinks.size], rot = i * 31f)
                }
                val rnd = Random(9)
                repeat(14) { i ->
                    val x = c.width - s * 0.05f - rnd.nextFloat() * s * 0.06f
                    val y = r.top + rnd.nextFloat() * (c.height - r.top)
                    Decor.blossom(c, x, y, s * 0.02f, pinks[i % pinks.size], rot = rnd.nextFloat() * 360f)
                }
            }
            PhotoFrame.TROPICAL -> padded(photo, s * 0.1f, s * 0.1f, s * 0.1f, s * 0.1f, color) { c, r ->
                // Big palm-style leaves peeking from two corners, over the photo's edge.
                fun frond(x: Float, y: Float, base: Float) {
                    val greens = intArrayOf(0xFF2F7D4F.toInt(), 0xFF3E9A5F.toInt(), 0xFF5DB070.toInt())
                    for (i in -4..4) Decor.leaf(c, x, y, s * (0.46f - kotlin.math.abs(i) * 0.04f), base + i * 11f, greens[(i + 4) % greens.size], width = 0.2f)
                }
                frond(-s * 0.02f, -s * 0.02f, 45f)
                frond(c.width + s * 0.02f, c.height + s * 0.02f, 225f)
                Decor.flower(c, s * 0.2f, s * 0.2f, s * 0.05f, 0xFFFF7A59.toInt(), 0xFFFFD25A.toInt(), rot = 10f)
                Decor.flower(c, c.width - s * 0.2f, c.height - s * 0.2f, s * 0.045f, 0xFFFF9E7A.toInt(), 0xFFFFD25A.toInt(), rot = 40f)
            }
            PhotoFrame.NEON -> padded(photo, s * 0.1f, s * 0.1f, s * 0.1f, s * 0.1f, 0xFF0B0B12.toInt()) { c, r ->
                val o = s * 0.035f
                val box = RectF(r.left - o, r.top - o, r.right + o, r.bottom + o)
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = s * 0.02f; this.color = color; maskFilter = BlurMaskFilter(s * 0.03f, BlurMaskFilter.Blur.NORMAL)
                }
                c.drawRoundRect(box, s * 0.03f, s * 0.03f, glow)
                c.drawRoundRect(box, s * 0.03f, s * 0.03f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.006f; this.color = lighten(color, 0.6f) })
            }
        }
    }

    /** [photo] on a canvas of [bg] with these margins; [extra] then draws on top (the photo's place is the RectF). */
    private fun padded(photo: Bitmap, l: Float, t: Float, r: Float, b: Float, bg: Int, extra: ((Canvas, RectF) -> Unit)? = null): Bitmap {
        val out = Bitmap.createBitmap((photo.width + l + r).roundToInt(), (photo.height + t + b).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bg)
        c.drawBitmap(photo, l, t, Paint(Paint.FILTER_BITMAP_FLAG))
        extra?.invoke(c, RectF(l, t, l + photo.width, t + photo.height))
        return out
    }

    /** [photo] with rounded corners and a soft shadow, on a [bg]-painted card. */
    private fun card(photo: Bitmap, margin: Float, radius: Float, bg: Paint, shadow: Boolean): Bitmap {
        val out = Bitmap.createBitmap((photo.width + margin * 2).roundToInt(), (photo.height + margin * 2).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), bg)
        val r = RectF(margin, margin, margin + photo.width, margin + photo.height)
        if (shadow) c.drawRoundRect(RectF(r.left, r.top + radius * 0.4f, r.right, r.bottom + radius * 0.4f), radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0); maskFilter = BlurMaskFilter(radius * 0.9f, BlurMaskFilter.Blur.NORMAL) })
        val clip = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        c.save(); c.clipPath(clip); c.drawBitmap(photo, margin, margin, Paint(Paint.FILTER_BITMAP_FLAG)); c.restore()
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = margin * 0.06f; color = Color.argb(200, 255, 255, 255) })
        return out
    }

    /** Small shapes spread evenly round the border band (deterministic, so the preview and the save match). */
    private fun scatterOnBorder(c: Canvas, photo: RectF, band: Float, size: Float, rnd: Random, draw: (Float, Float, Float, Int) -> Unit) {
        val step = size * 2.6f
        var k = 0
        fun at(x: Float, y: Float) { draw(x + (rnd.nextFloat() - 0.5f) * size * 0.6f, y + (rnd.nextFloat() - 0.5f) * size * 0.6f, size * (0.75f + rnd.nextFloat() * 0.5f), k++) }
        var x = band / 2
        while (x < c.width - band / 4) { at(x, band / 2); at(x, c.height - band / 2); x += step }
        var y = band / 2 + step
        while (y < c.height - band) { at(band / 2, y); at(c.width - band / 2, y); y += step }
    }

    private fun heart(c: Canvas, cx: Float, cy: Float, size: Float, p: Paint) {
        val s = size / 2
        val path = Path().apply {
            moveTo(cx, cy + s * 0.9f)
            cubicTo(cx - s * 1.6f, cy - s * 0.1f, cx - s * 0.6f, cy - s * 1.3f, cx, cy - s * 0.35f)
            cubicTo(cx + s * 0.6f, cy - s * 1.3f, cx + s * 1.6f, cy - s * 0.1f, cx, cy + s * 0.9f)
            close()
        }
        c.drawPath(path, p)
    }

    /** A four-point twinkle. */
    private fun sparkle(c: Canvas, cx: Float, cy: Float, size: Float, p: Paint) {
        val path = Path()
        for (i in 0 until 8) {
            val a = Math.PI / 4 * i - Math.PI / 2
            val r = if (i % 2 == 0) size / 2 else size * 0.12f
            val x = cx + (cos(a) * r).toFloat(); val y = cy + (sin(a) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    /** A strip of striped washi tape across the corner at ([x], [y]). */
    private fun tape(c: Canvas, x: Float, y: Float, s: Float, angle: Float, color: Int) {
        val w = s * 0.32f; val h = s * 0.085f
        c.save()
        c.rotate(angle, x, y)
        val r = RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(90, 255, 255, 255); strokeWidth = h * 0.18f }
        var sx = r.left
        while (sx < r.right) { c.drawLine(sx, r.top, sx + h, r.bottom, stripe); sx += h * 0.7f }
        c.restore()
    }

    /** A bunch of flowers and leaves fanning out of the corner at ([x], [y]); [turn] 180 for the opposite corner. */
    private fun bouquet(c: Canvas, x: Float, y: Float, s: Float, turn: Float) {
        c.save()
        c.rotate(turn, x, y)
        val greens = intArrayOf(0xFF6FA36B.toInt(), 0xFF4F8A5B.toInt(), 0xFF8CBF7A.toInt())
        listOf(-20f, 10f, 40f, 70f, 100f).forEachIndexed { i, a -> Decor.leaf(c, x, y, s * 0.2f, a, greens[i % greens.size]) }
        Decor.flower(c, x + s * 0.06f, y + s * 0.06f, s * 0.07f, 0xFFF7A1B5.toInt(), 0xFFFFC94D.toInt(), rot = 12f)
        Decor.flower(c, x + s * 0.15f, y + s * 0.02f, s * 0.045f, 0xFFFFFFFF.toInt(), 0xFFFFC94D.toInt(), rot = 40f)
        Decor.flower(c, x + s * 0.02f, y + s * 0.15f, s * 0.05f, 0xFFB9A7F5.toInt(), 0xFFFFE08A.toInt(), rot = 70f)
        Decor.flower(c, x + s * 0.13f, y + s * 0.13f, s * 0.03f, 0xFFFFD27A.toInt(), 0xFFF28F5B.toInt(), rot = 5f)
        c.restore()
    }

    /** Points every [step] round [r] (clockwise from the top left) with the outward normal at each. */
    private fun walkBorder(r: RectF, step: Float, at: (x: Float, y: Float, nx: Float, ny: Float, i: Int) -> Unit) {
        var i = 0
        var x = r.left
        while (x < r.right) { at(x, r.top, 0f, -1f, i++); x += step }
        var y = r.top
        while (y < r.bottom) { at(r.right, y, 1f, 0f, i++); y += step }
        x = r.right
        while (x > r.left) { at(x, r.bottom, 0f, 1f, i++); x -= step }
        y = r.bottom
        while (y > r.top) { at(r.left, y, -1f, 0f, i++); y -= step }
    }

    /** An arch: straight sides and bottom, a half-round top. */
    private fun archPath(r: RectF) = Path().apply {
        // Round on a tall photo; a flatter (elliptic) top on a wide one.
        val rad = minOf(r.width() / 2, r.height() / 2)
        moveTo(r.left, r.bottom)
        lineTo(r.left, r.top + rad)
        arcTo(RectF(r.left, r.top, r.right, r.top + rad * 2), 180f, 180f)
        lineTo(r.right, r.bottom)
        close()
    }

    /** Draw outside [path] only. */
    private fun Canvas.clipOutPathCompat(path: Path) {
        if (android.os.Build.VERSION.SDK_INT >= 26) clipOutPath(path)
        else @Suppress("DEPRECATION") clipPath(path, android.graphics.Region.Op.DIFFERENCE)
    }

    /** A thin line that shows on [bg]: dark on light colours, light on dark. */
    private fun contrastLine(bg: Int) = if ((Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000 > 140) Color.argb(110, 60, 45, 35) else Color.argb(150, 255, 236, 200)

    internal fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).roundToInt(), (Color.green(c) + (255 - Color.green(c)) * f).roundToInt(), (Color.blue(c) + (255 - Color.blue(c)) * f).roundToInt(),
    )

    internal fun darken(c: Int, f: Float) = Color.rgb((Color.red(c) * (1 - f)).roundToInt(), (Color.green(c) * (1 - f)).roundToInt(), (Color.blue(c) * (1 - f)).roundToInt())

    private fun rotateHue(c: Int, deg: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + deg) % 360f
        return Color.HSVToColor(hsv)
    }
}

/** Effects drawn over the coloured photo. */
internal object Effects {
    /** Corners darkened by [amount] 0..1, the middle untouched. */
    fun vignette(c: Canvas, w: Int, h: Int, amount: Float) {
        val r = kotlin.math.hypot(w / 2.0, h / 2.0).toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(w / 2f, h / 2f, r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((amount * 210).roundToInt(), 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    /** Film grain: fine noise, the same pattern each time (a tile drawn across the photo). */
    fun grain(c: Canvas, w: Int, h: Int, amount: Float) {
        val tile = 192
        val rnd = Random(3)
        val px = IntArray(tile * tile) {
            val v = rnd.nextInt(256)
            Color.argb((amount * 46 * rnd.nextFloat()).roundToInt(), v, v, v)
        }
        val noise = Bitmap.createBitmap(px, tile, tile, Bitmap.Config.ARGB_8888)
        // Grain the same size relative to the photo on the preview and the full save.
        val scale = maxOf(1f, min(w, h) / 1080f)
        val p = Paint().apply { shader = android.graphics.BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale) })
        } }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }
}
