package com.edureminder.quicktube.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadStatsTest {
    @Test
    fun siteIsTheDomainOnly() {
        assertEquals("facebook.com", DownloadStats.site("https://m.facebook.com/watch?v=123&secret=1"))
        assertEquals("tiktok.com", DownloadStats.site("https://vt.tiktok.com/ZSabc/"))
        assertEquals("youtube.com", DownloadStats.site("https://www.youtube.com/watch?v=x"))
        assertEquals("bbc.co.uk", DownloadStats.site("https://www.bbc.co.uk/news/video"))
        assertEquals("x.com", DownloadStats.site("https://x.com/a/status/1"))
        assertNull(DownloadStats.site("not a link"))
    }

    @Test
    fun failuresBecomeShortCodes() {
        assertEquals("no_internet", DownloadStats.reason(java.net.UnknownHostException("rr1.googlevideo.com")))
        assertEquals("timeout", DownloadStats.reason(java.net.SocketTimeoutException("read timed out")))
        assertEquals("storage_full", DownloadStats.reason(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals("removed_or_private", DownloadStats.findReason("This video was removed or is private. It can't be downloaded."))
        assertEquals("no_video_found", DownloadStats.findReason("No video found. Try opening the page in the QuickTube browser and play the video."))
        assertEquals("drm", DownloadStats.findReason("Couldn't get this video. It may be protected (DRM)"))
        assertEquals("live", DownloadStats.findReason("Live streams can't be downloaded until they end."))
    }
}
