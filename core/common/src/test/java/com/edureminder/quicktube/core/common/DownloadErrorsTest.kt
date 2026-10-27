package com.edureminder.quicktube.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadErrorsTest {
    @Test
    fun rawErrorsBecomeOneShortLine() {
        assertEquals("Name too long. Tap retry",
            DownloadErrors.friendly("/storage/emulated/0/Download/QuickTube/350K views … .mp4.part.chunks.tmp: open failed: ENAMETOOLONG (File name too long)"))
        assertEquals("Couldn't save the file. Tap retry", DownloadErrors.friendly("Could not save file: /storage/x.mp4: open failed: ENOENT"))
        assertEquals("Link expired. Tap retry", DownloadErrors.friendly("Link expired or login required"))
        assertEquals("Not enough storage", DownloadErrors.friendly("write failed: ENOSPC (No space left on device)"))
        assertEquals("Download failed. Tap retry", DownloadErrors.friendly("java.lang.IllegalStateException: something: odd"))
        assertEquals("Download failed", DownloadErrors.friendly(null))
    }
}
