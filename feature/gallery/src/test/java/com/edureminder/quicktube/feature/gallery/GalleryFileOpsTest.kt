package com.edureminder.quicktube.feature.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GalleryFileOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun foldersAsStoragePaths() {
        assertEquals("DCIM/Camera/", GalleryFileOps.relativeDir("/storage/emulated/0/DCIM/Camera/a.jpg", "/storage/emulated/0"))
        assertEquals("Download/QuickTube/Fresh bulk/", GalleryFileOps.relativeDir("/storage/emulated/0/Download/QuickTube/Fresh bulk/v.mp4", "/storage/emulated/0"))
        // A file at the very top, or outside shared storage, has no folder to copy into.
        assertNull(GalleryFileOps.relativeDir("/storage/emulated/0/a.jpg", "/storage/emulated/0"))
        assertNull(GalleryFileOps.relativeDir("/data/data/x/a.jpg", "/storage/emulated/0"))
    }

    @Test
    fun copiesNeverOverwrite() {
        val dir = tmp.newFolder("d")
        assertEquals("a.jpg", GalleryFileOps.freeName(dir, "a.jpg").name)
        java.io.File(dir, "a.jpg").writeText("x")
        java.io.File(dir, "a (1).jpg").writeText("x")
        assertEquals("a (2).jpg", GalleryFileOps.freeName(dir, "a.jpg").name)
    }
}
