package com.edureminder.quicktube.feature.gallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorMathTest {
    private fun near(a: Float, b: Float) = assertEquals(a, b, 0.6f)

    @Test fun mixAtZeroIsNoChangeAndAtOneIsTheLook() {
        val m = Cm.SEPIA
        assertTrue(Cm.mix(m, 0f).contentEquals(Cm.IDENTITY))
        Cm.mix(m, 1f).forEachIndexed { i, v -> assertEquals(m[i], v, 1e-5f) }
    }

    @Test fun chainingIdentityChangesNothing() {
        val (r, g, b) = Cm.apply(Cm.chain(Cm.IDENTITY, Cm.saturation(1f), Cm.contrast(1f), Cm.hue(0f)), 40f, 120f, 220f)
        near(40f, r); near(120f, g); near(220f, b)
    }

    @Test fun monoGivesGrey() {
        val (r, g, b) = Cm.apply(PhotoFilter.MONO.matrix!!, 200f, 50f, 90f)
        near(r, g); near(g, b)
    }

    @Test fun chainAppliesInOrder() {
        // Brighten then double ≠ double then brighten.
        val a = Cm.apply(Cm.chain(Cm.offset(10f, 10f, 10f), Cm.scale(2f, 2f, 2f)), 50f, 50f, 50f).first
        val b = Cm.apply(Cm.chain(Cm.scale(2f, 2f, 2f), Cm.offset(10f, 10f, 10f)), 50f, 50f, 50f).first
        near(120f, a); near(110f, b)
    }

    @Test fun everyFilterKeepsGreyNeutralEnoughAndInRange() {
        PhotoFilter.entries.mapNotNull { it.matrix }.forEach { m ->
            val (r, g, b) = Cm.apply(m, 128f, 128f, 128f)
            listOf(r, g, b).forEach { assertTrue(it in 0f..255f) }
        }
    }

    @Test fun everyCountHasLayoutsWithOneCellPerPhoto() {
        for (n in 1..9) {
            val layouts = CollageLayouts.forCount(n)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { l -> assertEquals("${l.name} for $n", n, l.cells.size) }
            // Prints in a pile overlap on purpose; every tidy layout fills the collage exactly.
            layouts.filter { !it.scattered }.forEach { l ->
                // The cells fill the collage exactly, without overlapping.
                val area = l.cells.sumOf { ((it.r - it.l) * (it.b - it.t)).toDouble() }
                assertEquals("${l.name} area", 1.0, area, 1e-4)
                l.cells.forEach { c -> assertTrue(c.l >= 0f && c.t >= 0f && c.r <= 1f && c.b <= 1f && c.l < c.r && c.t < c.b) }
            }
        }
    }

    @Test fun scatteredPilesTiltEveryPrintAndStayMostlyInside() {
        for (n in 2..9) {
            val l = CollageLayouts.forCount(n).single { it.scattered }
            assertEquals(n, l.tilts.size)
            l.cells.forEach { c -> assertTrue("$n", c.l > -0.1f && c.t > -0.1f && c.r < 1.1f && c.b < 1.1f) }
        }
        assertTrue(CollageLayouts.forCount(1).none { it.scattered })
    }

    @Test fun placeKeepsTheGapAtEdgesAndBetweenCells() {
        val left = CollageLayouts.place(Cell(0f, 0f, 0.5f, 1f), 1000, 800, 20f)
        val right = CollageLayouts.place(Cell(0.5f, 0f, 1f, 1f), 1000, 800, 20f)
        assertEquals(20f, left.l, 1e-3f); assertEquals(20f, left.t, 1e-3f); assertEquals(780f, left.b, 1e-3f)
        assertEquals(20f, right.l - left.r, 1e-3f)
        assertEquals(980f, right.r, 1e-3f)
        // Both halves are the same width.
        assertEquals(left.r - left.l, right.r - right.l, 1e-3f)
    }
}
