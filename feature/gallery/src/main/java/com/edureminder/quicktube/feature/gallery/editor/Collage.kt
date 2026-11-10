package com.edureminder.quicktube.feature.gallery.editor

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt

/** A cell of a collage layout, as fractions (0..1) of the collage. */
data class Cell(val l: Float, val t: Float, val r: Float, val b: Float)

/**
 * A layout: where each photo goes. [tilts] (degrees, one per cell) makes it a pile of prints: each photo drawn as a
 * tilted white-bordered print, overlapping, instead of a tidy grid.
 */
data class CollageLayout(val name: String, val cells: List<Cell>, val tilts: List<Float> = emptyList()) {
    val scattered get() = tilts.isNotEmpty()
}

/** The shape each photo is cut to. */
enum class CellShape(val label: String) { RECT("Square"), CIRCLE("Circle"), HEART("Heart"), ARCH("Arch") }

/** The collage's shape (width / height). */
enum class CollageShape(val label: String, val ratio: Float) { SQUARE("1:1", 1f), PORTRAIT("4:5", 0.8f), STORY("9:16", 9f / 16f), LANDSCAPE("3:2", 1.5f) }

/** Backgrounds behind the photos: plain and soft gradients (two colours, top to bottom). */
enum class CollageBackground(val label: String, val from: Int, val to: Int, val pattern: Pattern = Pattern.NONE) {
    WHITE("White", 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
    CREAM("Cream", 0xFFF8F1E4.toInt(), 0xFFF8F1E4.toInt()),
    BLACK("Black", 0xFF111114.toInt(), 0xFF111114.toInt()),
    BLUSH("Blush", 0xFFFFE1EA.toInt(), 0xFFFFF3E9.toInt()),
    SKY("Sky", 0xFFDDEBFF.toInt(), 0xFFF2E8FF.toInt()),
    MINT("Mint", 0xFFDFF6EA.toInt(), 0xFFFFF8DC.toInt()),
    PEACH("Peach", 0xFFFFD6BE.toInt(), 0xFFFFEFD6.toInt()),
    NIGHT("Night", 0xFF1A1F3D.toInt(), 0xFF3B1F45.toInt()),
    DOTS("Dots", 0xFFFFE6EE.toInt(), 0xFFFFE6EE.toInt(), Pattern.DOTS),
    HEARTS("Hearts", 0xFFFFD6E0.toInt(), 0xFFFFE9EF.toInt(), Pattern.HEARTS),
    FLOWERS("Flowers", 0xFFEFF6E9.toInt(), 0xFFFFF6EC.toInt(), Pattern.FLOWERS),
    STARS("Stars", 0xFF141A33.toInt(), 0xFF2A1638.toInt(), Pattern.STARS),
    ;

    enum class Pattern { NONE, DOTS, HEARTS, FLOWERS, STARS }
}

/** Layouts for 1–9 photos, a few each, all filling the frame. */
object CollageLayouts {
    private fun grid(cols: Int, rows: Int, n: Int = cols * rows): List<Cell> = (0 until n).map { i ->
        val c = i % cols; val r = i / cols
        Cell(c / cols.toFloat(), r / rows.toFloat(), (c + 1) / cols.toFloat(), (r + 1) / rows.toFloat())
    }

    /** A row of [n] equal cells between [t] and [b]. */
    private fun row(n: Int, t: Float, b: Float) = (0 until n).map { Cell(it / n.toFloat(), t, (it + 1) / n.toFloat(), b) }
    private fun col(n: Int, l: Float, r: Float) = (0 until n).map { Cell(l, it / n.toFloat(), r, (it + 1) / n.toFloat()) }

    fun forCount(n: Int): List<CollageLayout> = when (n.coerceIn(1, 9)) {
        1 -> listOf(CollageLayout("Single", listOf(Cell(0f, 0f, 1f, 1f))))
        2 -> listOf(CollageLayout("Side by side", row(2, 0f, 1f)), CollageLayout("Stacked", col(2, 0f, 1f)),
            CollageLayout("Big & small", listOf(Cell(0f, 0f, 0.62f, 1f), Cell(0.62f, 0f, 1f, 1f))))
        3 -> listOf(CollageLayout("One + two", listOf(Cell(0f, 0f, 1f, 0.55f)) + row(2, 0.55f, 1f)),
            CollageLayout("Two + one", row(2, 0f, 0.45f) + Cell(0f, 0.45f, 1f, 1f)),
            CollageLayout("Left big", listOf(Cell(0f, 0f, 0.6f, 1f)) + col(2, 0.6f, 1f)),
            CollageLayout("Three rows", col(3, 0f, 1f)))
        4 -> listOf(CollageLayout("Grid", grid(2, 2)), CollageLayout("Hero + three", listOf(Cell(0f, 0f, 1f, 0.62f)) + row(3, 0.62f, 1f)),
            CollageLayout("Left + three", listOf(Cell(0f, 0f, 0.62f, 1f)) + col(3, 0.62f, 1f)))
        5 -> listOf(CollageLayout("Two + three", row(2, 0f, 0.5f) + row(3, 0.5f, 1f)), CollageLayout("Hero + four", listOf(Cell(0f, 0f, 1f, 0.5f)) + row(4, 0.5f, 1f)))
        6 -> listOf(CollageLayout("Grid 3×2", grid(3, 2)), CollageLayout("Grid 2×3", grid(2, 3)), CollageLayout("Hero + five", listOf(Cell(0f, 0f, 1f, 0.5f)) + row(5, 0.5f, 1f)))
        7 -> listOf(CollageLayout("Three + four", row(3, 0f, 0.5f) + row(4, 0.5f, 1f)), CollageLayout("Hero + six", listOf(Cell(0f, 0f, 1f, 0.4f)) + grid(3, 2).map { Cell(it.l, 0.4f + it.t * 0.6f, it.r, 0.4f + it.b * 0.6f) }))
        8 -> listOf(CollageLayout("Grid 4×2", grid(4, 2)), CollageLayout("Grid 2×4", grid(2, 4)))
        else -> listOf(CollageLayout("Grid 3×3", grid(3, 3)))
    } + listOfNotNull(scatter(n.coerceIn(1, 9)))

    /** Prints tossed on a table: a loose grid, each a bit larger than its spot, tilted left or right. */
    private fun scatter(n: Int): CollageLayout? {
        if (n < 2) return null
        val cols = when { n <= 2 -> 2; n <= 6 -> 2; else -> 3 }
        val rows = (n + cols - 1) / cols
        val cw = 1f / cols; val ch = 1f / rows
        val cells = (0 until n).map { i ->
            val c = i % cols; val r = i / cols
            // The last row's prints are centred when it isn't full.
            val inRow = if (r == rows - 1) n - r * cols else cols
            val x0 = (cols - inRow) * cw / 2
            val cx = x0 + (c + 0.5f) * cw + (if (i % 2 == 0) -0.02f else 0.02f)
            val cy = (r + 0.5f) * ch + (if (c % 2 == 0) 0.015f else -0.015f)
            // Inside a margin, each print a little bigger than its spot so neighbours overlap.
            val m = 0.07f; val k = 1f - 2 * m
            val px = m + cx * k; val py = m + cy * k
            val half = 0.54f * k
            Cell(px - cw * half, py - ch * half, px + cw * half, py + ch * half)
        }
        val tilts = (0 until n).map { i -> listOf(-7f, 5f, -3f, 8f, -6f, 4f, -8f, 3f, -5f)[i] }
        return CollageLayout("Scattered", cells, tilts)
    }

    /** [cell] in pixels on a [w]x[h] collage, with [gap] pixels between cells and round the edge. */
    fun place(cell: Cell, w: Int, h: Int, gap: Float): Cell {
        val half = gap / 2
        fun edge(f: Float, size: Int) = if (f <= 0f) gap else if (f >= 1f) size - gap else f * size
        return Cell(
            edge(cell.l, w) + if (cell.l > 0f) half else 0f, edge(cell.t, h) + if (cell.t > 0f) half else 0f,
            edge(cell.r, w) - if (cell.r < 1f) half else 0f, edge(cell.b, h) - if (cell.b < 1f) half else 0f,
        )
    }
}

data class CollageStyle(
    val layout: Int = 0,
    val shape: CollageShape = CollageShape.SQUARE,
    /** 0..1 of the largest gap. */
    val spacing: Float = 0.35f,
    /** 0..1 of the largest corner. */
    val corners: Float = 0.3f,
    val background: CollageBackground = CollageBackground.WHITE,
    val frame: PhotoFrame = PhotoFrame.NONE,
    val frameColor: Int = 0,
    val filter: PhotoFilter = PhotoFilter.NONE,
    val cellShape: CellShape = CellShape.RECT,
    /** Words over the collage, drawn last. */
    val texts: List<TextLayer> = emptyList(),
)

internal object CollageRenderer {
    /** The collage [width] pixels wide: each photo centre-cropped into its cell, then the frame round it all. */
    fun render(photos: List<Bitmap>, style: CollageStyle, width: Int, withText: Boolean = true): Bitmap {
        val layouts = CollageLayouts.forCount(photos.size)
        val layout = layouts[style.layout.coerceIn(0, layouts.lastIndex)]
        val w = width; val h = (width / style.shape.ratio).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), style.background.from, style.background.to, Shader.TileMode.CLAMP)
        })
        pattern(c, w, h, style.background.pattern)
        val side = minOf(w, h).toFloat()
        val gap = side * 0.045f * style.spacing
        val radius = side * 0.06f * style.corners
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style.filter.matrix?.let { colorFilter = android.graphics.ColorMatrixColorFilter(it) }
        }
        layout.cells.forEachIndexed { i, cell ->
            val photo = photos.getOrNull(i) ?: return@forEachIndexed
            if (layout.scattered) {
                // A print: white border (wider at the bottom), soft shadow, tilted.
                val spot = RectF(cell.l * w, cell.t * h, cell.r * w, cell.b * h)
                val b = side * 0.022f
                val pic = RectF(spot.left + b, spot.top + b, spot.right - b, spot.bottom - b * 3.2f)
                c.save()
                c.rotate(layout.tilts[i], spot.centerX(), spot.centerY())
                c.drawRect(RectF(spot.left, spot.top + b * 0.5f, spot.right, spot.bottom + b * 0.5f),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(80, 0, 0, 0); maskFilter = BlurMaskFilter(b * 1.2f, BlurMaskFilter.Blur.NORMAL) })
                c.drawRect(spot, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
                drawCropped(c, photo, pic, paint)
                c.restore()
                return@forEachIndexed
            }
            val dst = CollageLayouts.place(cell, w, h, gap).let { RectF(it.l, it.t, it.r, it.b) }
            if (dst.width() <= 1 || dst.height() <= 1) return@forEachIndexed
            val clip = when (style.cellShape) {
                CellShape.RECT -> if (radius > 0f) Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) } else null
                CellShape.CIRCLE -> Path().apply { addOval(squareIn(dst), Path.Direction.CW) }
                CellShape.HEART -> Decor.heartIn(dst)
                CellShape.ARCH -> Path().apply {
                    val rad = minOf(dst.width(), dst.height()) / 2
                    moveTo(dst.left, dst.bottom); lineTo(dst.left, dst.top + rad)
                    arcTo(RectF(dst.left, dst.top, dst.right, dst.top + rad * 2), 180f, 180f)
                    lineTo(dst.right, dst.bottom); close()
                }
            }
            c.save()
            clip?.let { c.clipPath(it) }
            drawCropped(c, photo, dst, paint)
            c.restore()
        }
        val framed = if (style.frame == PhotoFrame.NONE) out else Frames.draw(out, style.frame, style.frameColor)
        if (withText && style.texts.isNotEmpty()) TextRender.drawAll(Canvas(framed), style.texts, framed.width.toFloat(), framed.height.toFloat())
        return framed
    }

    /** The middle of [photo], in [dst]'s shape, filling it. */
    private fun drawCropped(c: Canvas, photo: Bitmap, dst: RectF, paint: Paint) {
        val cellRatio = dst.width() / dst.height()
        val pr = photo.width.toFloat() / photo.height
        val src = if (pr > cellRatio) {
            val sw = (photo.height * cellRatio).roundToInt(); val x = (photo.width - sw) / 2
            Rect(x, 0, x + sw, photo.height)
        } else {
            val sh = (photo.width / cellRatio).roundToInt(); val y = (photo.height - sh) / 2
            Rect(0, y, photo.width, y + sh)
        }
        c.drawBitmap(photo, src, dst, paint)
    }

    /** The largest square centred in [r] (circles stay round in wide or tall cells). */
    private fun squareIn(r: RectF): RectF {
        val d = minOf(r.width(), r.height()) / 2
        return RectF(r.centerX() - d, r.centerY() - d, r.centerX() + d, r.centerY() + d)
    }

    /** A soft repeating pattern over the background. */
    private fun pattern(c: Canvas, w: Int, h: Int, p: CollageBackground.Pattern) {
        if (p == CollageBackground.Pattern.NONE) return
        val step = minOf(w, h) * 0.09f
        var row = 0
        var y = step / 2
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        while (y < h + step) {
            var x = if (row % 2 == 0) step / 2 else step
            while (x < w + step) {
                when (p) {
                    CollageBackground.Pattern.DOTS -> { dot.color = android.graphics.Color.argb(120, 255, 160, 190); c.drawCircle(x, y, step * 0.13f, dot) }
                    CollageBackground.Pattern.HEARTS -> Decor.heart(c, x, y, step * 0.32f, android.graphics.Color.argb(150, 255, 255, 255))
                    CollageBackground.Pattern.FLOWERS -> if ((row + (x / step).toInt()) % 2 == 0)
                        Decor.flower(c, x, y, step * 0.2f, android.graphics.Color.argb(170, 247, 161, 181), android.graphics.Color.argb(200, 255, 210, 90), rot = x % 72f)
                        else Decor.leaf(c, x - step * 0.15f, y, step * 0.3f, -30f, android.graphics.Color.argb(150, 111, 163, 107))
                    CollageBackground.Pattern.STARS -> { dot.color = android.graphics.Color.argb(170, 255, 236, 170); c.drawCircle(x, y, step * 0.04f, dot) }
                    CollageBackground.Pattern.NONE -> Unit
                }
                x += step
            }
            y += step * 0.86f; row++
        }
    }
}
