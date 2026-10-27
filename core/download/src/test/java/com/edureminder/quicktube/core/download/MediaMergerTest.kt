package com.edureminder.quicktube.core.download

import android.media.MediaMuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMergerTest {
    @Test
    fun picksContainerFromCodecs() {
        val mp4 = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        val webm = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        assertEquals(mp4, MediaMerger.containerFor(listOf("video/avc", "audio/mp4a-latm"), sdk = 31))
        assertEquals(webm, MediaMerger.containerFor(listOf("video/x-vnd.on2.vp9", "audio/opus"), sdk = 31))
        assertNull(MediaMerger.containerFor(listOf("video/av01", "audio/mp4a-latm"), sdk = 31))
        assertEquals(mp4, MediaMerger.containerFor(listOf("video/av01", "audio/mp4a-latm"), sdk = 34))
        assertNull(MediaMerger.containerFor(listOf("video/x-vnd.on2.vp9", "audio/mp4a-latm"), sdk = 31))
        assertEquals(mp4, MediaMerger.containerFor(listOf("audio/mp4a-latm"), sdk = 24))
    }
}
