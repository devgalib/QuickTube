package com.edureminder.quicktube.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandingTest {
    @Test
    fun suffixIsAddedOnceAndSafeEverywhere() {
        assertEquals("Song(720p) - QuickTube edureminder.com", Branding.fileName("Song(720p)"))
        assertEquals("Song - QuickTube edureminder.com", Branding.fileName(Branding.fileName("Song")))
        // Nothing FAT32, Windows or Android forbids in a name.
        assertTrue(Branding.FILE_SUFFIX.none { it in "\\/:*?\"<>|" || it.code < 32 })
    }
}
