package com.edureminder.quicktube.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultHelpersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun pinIsHashedAndChecked() {
        val stored = PinHash.hash("1234")
        assertTrue(PinHash.isHashed(stored))
        assertFalse(stored.contains("1234"))
        assertTrue(PinHash.matches("1234", stored))
        assertFalse(PinHash.matches("4321", stored))
        // Two saves of the same PIN differ (salt).
        assertTrue(PinHash.hash("1234") != stored)
        // Old plain PINs still work until they're converted.
        assertTrue(PinHash.matches("1234", "1234"))
        assertFalse(PinHash.matches("1234", null))
    }

    @Test
    fun everyTempFileOfADownloadIsFound() {
        val dir = tmp.newFolder("dl")
        val parts = tmp.newFolder("parts")
        val path = "${dir.path}/Song.mp4"
        val made = listOf("$path.part", "$path.part.idx", "$path.part.chunks", "$path.part.merging", "$path.audio.part", "$path.audio.part.chunks")
            .map { java.io.File(it).apply { writeText("x") } } +
            listOf(java.io.File(parts, "task-7.video"), java.io.File(parts, "task-7.audio")).onEach { it.writeText("x") }
        val other = java.io.File(parts, "task-70.video").apply { writeText("x") }
        PartFiles.delete(path, 7, parts)
        assertTrue(made.none { it.exists() })
        assertTrue(other.exists())
        assertEquals(1, parts.listFiles()!!.size)
    }
}
