package com.edureminder.quicktube.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentedDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun plansChunksCoveringEveryByte() {
        val total = 10L * 1024 * 1024 + 123
        val n = SegmentPlan.chunkCount(total)
        assertEquals(3, n)
        assertEquals(0L..SegmentPlan.CHUNK - 1, SegmentPlan.range(0, total))
        assertEquals(total - 1, SegmentPlan.range(n - 1, total).last)
        val covered = (0 until n).sumOf { SegmentPlan.range(it, total).let { r -> r.last - r.first + 1 } }
        assertEquals(total, covered)
        assertEquals(1, SegmentPlan.connectionsFor(500_000))
        assertEquals(8, SegmentPlan.connectionsFor(500L * 1024 * 1024))
    }

    @Test
    fun stateSurvivesRestart() {
        val part = tmp.newFile("v.part")
        val total = 9L * 1024 * 1024
        val s = ChunkState.load(part, total)
        assertEquals(listOf(0, 1, 2), s.pending())
        s.markDone(1)
        val again = ChunkState.load(part, total)
        assertEquals(listOf(0, 2), again.pending())
        assertEquals(SegmentPlan.CHUNK, again.doneBytes())
        again.markDone(0); again.markDone(2)
        assertTrue(ChunkState.load(part, total).isComplete())
    }

    @Test
    fun sizeChangeResetsState() {
        val part = tmp.newFile("v.part")
        ChunkState.load(part, 9L * 1024 * 1024).markDone(0)
        assertEquals(3, ChunkState.load(part, 10L * 1024 * 1024).pending().size)
    }

    @Test
    fun legacyFrontToBackPartKeepsPrefix() {
        val part = tmp.newFile("old.part")
        part.writeBytes(ByteArray((SegmentPlan.CHUNK + 1000).toInt()))
        val s = ChunkState.load(part, 3 * SegmentPlan.CHUNK)
        assertTrue(s.isDone(0))
        assertFalse(s.isDone(1))
    }
}
