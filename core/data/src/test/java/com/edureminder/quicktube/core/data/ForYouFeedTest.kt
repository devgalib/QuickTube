package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.PlayHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ForYouFeedTest {
    private val day = 86_400_000L
    private fun entry(id: String, ageDays: Double, progress: Float) = PlayHistoryEntry(
        key = "https://www.youtube.com/watch?v=$id", title = id, positionMs = (progress * 1000).toLong(), durationMs = 1000,
        openedAt = 10 * day - (ageDays * day).toLong(),
    )
    private fun video(id: String, short: Boolean = false) = MediaItem(url = "https://www.youtube.com/watch?v=$id", title = id, isShort = short)

    @Test
    fun recentAndFinishedVideosLeadTheSeeds() {
        val seeds = ForYouFeed.seeds(
            listOf(entry("old-done-123", 5.0, 1f), entry("new-start-12", 0.0, 0.05f), entry("new-done-123", 0.1, 1f)),
            now = 10 * day, count = 2,
        )
        assertEquals(listOf("new-done-123", "new-start-12"), seeds.map { it.title })
    }

    @Test
    fun topicsAreWordsThatRepeat() {
        val titles = listOf(
            "Pomeranian puppy first bath #pomeranian", "My Pomeranian meets a cat", "iPhone 17 review",
            "iPhone 17 vs Galaxy camera", "Ed Sheeran - Perfect (Official Music Video)", "Cooking pasta",
        )
        // Each counts once per title; ties go alphabetically. "17" has no letters, "official"/"music"/"video" are filler.
        assertEquals(listOf("iphone", "pomeranian"), ForYouFeed.topics(titles))
    }

    @Test
    fun mixTakesTurnsAndDropsWatchedDuplicatesAndShorts() {
        val a = listOf(video("aaaaaaaaaa1"), video("aaaaaaaaaa2"), video("watchedvid1"))
        val b = listOf(video("bbbbbbbbbb1", short = true), video("aaaaaaaaaa1"), video("bbbbbbbbbb2"))
        val out = ForYouFeed.mix(listOf(a, b), watched = setOf("watchedvid1"))
        assertEquals(listOf("aaaaaaaaaa1", "aaaaaaaaaa2", "bbbbbbbbbb2"), out.map { it.title })
    }
}
