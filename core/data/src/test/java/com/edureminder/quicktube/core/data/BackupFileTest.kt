package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.core.model.ThemeMode
import com.edureminder.quicktube.core.model.VideoEndMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BackupFileTest {
    @Test
    fun roundTripKeepsEverything() {
        val b = BackupFile(
            createdAt = 5,
            history = listOf(BackupFile.HistoryRow("https://youtu.be/a", "A", 10, 100, 1)),
            playlists = listOf(BackupFile.PlaylistRow("Mix", true, 2, items = listOf(BackupFile.PlaylistItemRow("https://youtu.be/b", "B")))),
            subscriptions = listOf(BackupFile.SubscriptionRow("https://youtube.com/channel/x", "X")),
            settings = BackupFile.settingsOf(AppSettings(themeMode = ThemeMode.DARK, playbackSpeed = 1.5f, vaultPin = "1234")),
        )
        val back = BackupFile.decode(BackupFile.encode(b))
        assertEquals(b, back)
        // Never the vault PIN or the download folder.
        assertFalse(BackupFile.encode(b).contains("1234"))
        assertNull(back.settings["downloadDir"])
        assertEquals("1 watched · 1 playlists · 1 subscriptions", back.summary())
    }

    @Test
    fun settingsApplyAndIgnoreJunk() {
        val s = BackupFile.applySettings(AppSettings(), mapOf("themeMode" to "DARK", "playbackSpeed" to "1.25", "videoEndMode" to "nonsense", "sponsorBlock" to "false"))
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertEquals(1.25f, s.playbackSpeed)
        assertEquals(VideoEndMode.REPEAT_ONE, s.videoEndMode)
        assertEquals(false, s.sponsorBlock)
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherFilesAreRefused() {
        BackupFile.decode("""{"app":"SomethingElse"}""")
    }
}
