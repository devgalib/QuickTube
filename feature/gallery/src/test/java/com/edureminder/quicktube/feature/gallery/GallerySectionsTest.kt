package com.edureminder.quicktube.feature.gallery

import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.core.navigation.GALLERY_ALL
import com.edureminder.quicktube.core.navigation.GALLERY_AUDIOS
import com.edureminder.quicktube.core.navigation.GALLERY_PHOTOS
import com.edureminder.quicktube.core.navigation.GALLERY_VIDEOS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class GallerySectionsTest {
    private val zone = ZoneId.of("Asia/Dhaka")

    private fun media(
        id: Long, date: LocalDateTime, bucket: Long = 1, name: String = "Camera", video: Boolean = false, audio: Boolean = false,
    ) = GalleryMedia(
        id = id, isVideo = video, isAudio = audio, mimeType = "image/jpeg", dateMs = date.atZone(zone).toInstant().toEpochMilli(), modifiedSec = 0,
        durationMs = 0, width = 100, height = 100, bucketId = bucket, bucketName = name, name = "$id.jpg", sizeBytes = 1, path = "",
    )

    @Test
    fun `labels omit the year only for the current year`() {
        assertEquals("Oct", GallerySections.monthLabel(YearMonth.of(2026, 10), 2026, Locale.US))
        assertEquals("Sep, 2025", GallerySections.monthLabel(YearMonth.of(2025, 9), 2026, Locale.US))
    }

    @Test
    fun `groups newest-first media into month sections with counts`() {
        val items = listOf(
            media(1, LocalDateTime.of(2026, 10, 30, 9, 0)),
            media(2, LocalDateTime.of(2026, 10, 1, 0, 0)),
            media(3, LocalDateTime.of(2026, 9, 30, 23, 59)),
            media(4, LocalDateTime.of(2025, 12, 5, 12, 0)),
        )
        val entries = GallerySections.build(items, zone, currentYear = 2026, locale = Locale.US)
        val headers = entries.filterIsInstance<GridEntry.Header>()
        assertEquals(listOf("Oct", "Sep", "Dec, 2025"), headers.map { it.label })
        assertEquals(listOf(2, 1, 1), headers.map { it.count })
        assertEquals(listOf(0, 2, 3), headers.map { it.first })
        // Header, two cells, header, cell, header, cell.
        assertEquals(7, entries.size)
        assertEquals(listOf(0, 1, 2, 3), entries.filterIsInstance<GridEntry.Cell>().map { it.index })
    }

    @Test
    fun `month boundaries follow the local time zone`() {
        // 1 Oct 00:30 in Dhaka is still 30 Sep in UTC.
        val items = listOf(media(1, LocalDateTime.of(2026, 10, 1, 0, 30)))
        assertEquals("Oct", (GallerySections.build(items, zone, 2026, Locale.US).first() as GridEntry.Header).label)
    }

    @Test
    fun `empty list has no sections`() {
        assertTrue(GallerySections.build(emptyList(), zone, 2026, Locale.US).isEmpty())
    }

    @Test
    fun `albums use the newest item as cover and keep recency order`() {
        val items = listOf(
            media(1, LocalDateTime.of(2026, 10, 30, 9, 0), bucket = 7, name = "Telegram"),
            media(2, LocalDateTime.of(2026, 10, 29, 9, 0), bucket = 1),
            media(3, LocalDateTime.of(2026, 10, 28, 9, 0), bucket = 7, name = "Telegram"),
        )
        val albums = GallerySections.albums(items)
        assertEquals(listOf(7L, 1L), albums.map { it.bucketId })
        assertEquals(listOf(2, 1), albums.map { it.count })
        assertEquals(1L, albums[0].cover?.id)
    }

    @Test
    fun `section checkbox selects the month then clears it`() {
        val selected = GallerySelection.toggleSection(setOf(9L), listOf(1L, 2L))
        assertEquals(setOf(9L, 1L, 2L), selected)
        assertEquals(setOf(9L), GallerySelection.toggleSection(selected, listOf(1L, 2L)))
    }

    @Test
    fun `drag selects the range in either direction on top of the earlier selection`() {
        val items = (0L until 10L).map { media(it, LocalDateTime.of(2026, 10, 30, 9, 0)) }
        assertEquals(setOf(9L, 2L, 3L, 4L, 5L), GallerySelection.dragRange(setOf(9L), items, anchor = 2, current = 5))
        assertEquals(setOf(3L, 4L, 5L), GallerySelection.dragRange(emptySet(), items, anchor = 5, current = 3))
        // Dragging back towards the anchor shrinks the range again.
        assertEquals(setOf(5L), GallerySelection.dragRange(emptySet(), items, anchor = 5, current = 5))
        // Positions past the end are clamped.
        assertEquals(setOf(8L, 9L), GallerySelection.dragRange(emptySet(), items, anchor = 8, current = 42))
    }

    private val day = LocalDateTime.of(2026, 10, 30, 9, 0)
    private val mixed = listOf(
        media(1, day, bucket = 1),
        media(2, day, bucket = 1, video = true),
        media(3, day, bucket = 2, video = true),
        media(4, day, bucket = 1, audio = true),
        media(5, day, bucket = 3, audio = true),
    )

    @Test
    fun `photos tab and folders leave audio out, virtual albums take one kind from every folder`() {
        assertEquals(listOf(1L, 2L, 3L), GallerySections.filter(mixed, GALLERY_ALL).map { it.id })
        assertEquals(listOf(1L, 2L), GallerySections.filter(mixed, 1).map { it.id })
        assertEquals(listOf(1L), GallerySections.filter(mixed, GALLERY_PHOTOS).map { it.id })
        assertEquals(listOf(2L, 3L), GallerySections.filter(mixed, GALLERY_VIDEOS).map { it.id })
        assertEquals(listOf(4L, 5L), GallerySections.filter(mixed, GALLERY_AUDIOS).map { it.id })
    }

    @Test
    fun `virtual albums count each kind and audio-only folders are not albums`() {
        val virtual = GallerySections.virtualAlbums(mixed)
        assertEquals(listOf(GALLERY_PHOTOS, GALLERY_VIDEOS, GALLERY_AUDIOS), virtual.map { it.bucketId })
        assertEquals(listOf(1, 2, 2), virtual.map { it.count })
        assertTrue(virtual.all { it.virtual })
        assertEquals(listOf(1L, 2L), GallerySections.albums(mixed).map { it.bucketId })
    }

    @Test
    fun `player queue is the opened file's own folder and kind`() {
        val video = mixed.first { it.id == 2L }
        assertEquals(listOf(2L), GallerySections.folderQueue(mixed, video).map { it.id })
        val audio = mixed.first { it.id == 4L }
        assertEquals(listOf(4L), GallerySections.folderQueue(mixed, audio).map { it.id })
    }
}
