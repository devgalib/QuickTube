package com.edureminder.quicktube.core.download

import com.edureminder.quicktube.core.model.DownloadLater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DownloadLaterTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun tonightIsTheNextOneAm() {
        // 22:00 → 01:00 the next day; 00:30 → 01:00 the same day; exactly 01:00 → the next night.
        assertEquals(day + 1 * hour, DownloadLater.tonight(22 * hour, utc))
        assertEquals(1 * hour, DownloadLater.tonight(30 * 60_000L, utc))
        assertEquals(day + 1 * hour, DownloadLater.tonight(1 * hour, utc))
        assertEquals(day + 7 * hour + 30 * 60_000L, DownloadLater.at(7, 30, 12 * hour, utc))
    }

    @Test
    fun waitsForTimeAndWifi() {
        assertTrue(DownloadLater.waiting(startAfter = 2_000, wifiOnly = false, onWifi = true, now = 1_000))
        assertFalse(DownloadLater.waiting(startAfter = 1_000, wifiOnly = false, onWifi = false, now = 2_000))
        assertTrue(DownloadLater.waiting(startAfter = 0, wifiOnly = true, onWifi = false, now = 2_000))
        assertFalse(DownloadLater.waiting(startAfter = 0, wifiOnly = true, onWifi = true, now = 2_000))
    }
}
