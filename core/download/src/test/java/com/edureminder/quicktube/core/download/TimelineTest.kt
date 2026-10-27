package com.edureminder.quicktube.core.download

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {
    @Test
    fun leavesNormalAndBFrameOrderAlone() {
        val t = Timeline()
        // I P B B in decode order: presentation times 0, 99, 33, 66 ms.
        assertEquals(listOf(0L, 99_000L, 33_000L, 66_000L), listOf(0L, 99_000L, 33_000L, 66_000L).map(t::next))
    }

    @Test
    fun continuesAfterADiscontinuity() {
        val t = Timeline()
        // A 3.5 s intro in 23 ms audio frames, then the talk restarting at 0 (TED's HLS playlists).
        val frame = 23_220L
        val intro = (0 until 152).map { it * frame }
        intro.forEach { t.next(it) }
        val talk = (0 until 5).map { it * frame }.map(t::next)
        assertEquals(listOf(152 * frame, 153 * frame, 154 * frame, 155 * frame, 156 * frame), talk)
    }

    @Test
    fun closesLargeForwardGaps() {
        val t = Timeline()
        listOf(0L, 40_000L, 80_000L).forEach { t.next(it) }
        assertEquals(120_000L, t.next(95_000_000L))
        assertEquals(160_000L, t.next(95_040_000L))
    }
}
