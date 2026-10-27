package com.edureminder.quicktube.core.downloadsheet

import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchPickTest {
    private fun v(h: Int) = DownloadOption(id = "v$h", kind = FormatKind.VIDEO, title = "${h}p", info = "MP4", ext = "mp4", mimeType = "video/mp4", url = "u$h", height = h)
    private fun a(ext: String, kbps: Int) = DownloadOption(id = "a$ext$kbps", kind = FormatKind.AUDIO, title = ext, info = "", ext = ext, mimeType = "audio/mp4", url = "a$ext$kbps", bitrate = kbps)
    private val options = listOf(a("webm", 160), a("m4a", 128), a("m4a", 48), v(144), v(360), v(720), v(1080))

    @Test
    fun picksClosestQuality() {
        assertEquals(720, BatchDownloader.pick(options, BatchQuality.P720)!!.height)
        assertEquals(1080, BatchDownloader.pick(options, BatchQuality.BEST)!!.height)
        // Nothing at or below: the smallest above.
        assertEquals(240, BatchDownloader.pick(listOf(v(144), v(240)), BatchQuality.P360)!!.height)
        assertEquals(480, BatchDownloader.pick(listOf(v(480), v(720)), BatchQuality.P360)!!.height)
    }

    @Test
    fun audioPrefersBestM4a() {
        assertEquals("am4a128", BatchDownloader.pick(options, BatchQuality.AUDIO)!!.id)
    }
}
