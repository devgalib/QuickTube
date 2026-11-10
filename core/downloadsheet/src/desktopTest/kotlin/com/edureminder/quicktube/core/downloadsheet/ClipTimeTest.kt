package com.edureminder.quicktube.core.downloadsheet

import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.DownloadRequest
import com.edureminder.quicktube.core.model.FormatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipTimeTest {
    @Test
    fun formatsAndParsesTimes() {
        assertEquals("1:23", clipTime(83_000))
        assertEquals("1:02:03", clipTime(3_723_000))
        assertEquals(83_000L, parseClipTime("1:23"))
        assertEquals(3_723_000L, parseClipTime("1:02:03"))
        assertEquals(45_000L, parseClipTime("45"))
        assertNull(parseClipTime(""))
        assertNull(parseClipTime("1::2"))
        assertNull(parseClipTime("a:12"))
    }

    @Test
    fun cutRequestGetsItsOwnNameDurationAndScaledSize() {
        val option = DownloadOption("v", FormatKind.VIDEO, "720p", "", "mp4", "video/mp4", "u", audioUrl = "a", sizeBytes = 100_000, audioSizeBytes = 10_000, height = 720)
        val request = DownloadRequest(title = "Song", url = "u", audioUrl = "a", ext = "mp4", mimeType = "video/mp4", quality = "720p", sizeBytes = 100_000, audioSizeBytes = 10_000, durationSec = 200)
        val r = clipRequest(request, option, 83_000L..123_000L, 200)
        assertEquals("Song (cut 1m23s-2m3s)", r.title)
        assertEquals(83_000L, r.clipStartMs)
        assertEquals(123_000L, r.clipEndMs)
        assertEquals(40L, r.durationSec)
        assertEquals(20_000L, r.sizeBytes)
        assertEquals(2_000L, r.audioSizeBytes)
    }
}
