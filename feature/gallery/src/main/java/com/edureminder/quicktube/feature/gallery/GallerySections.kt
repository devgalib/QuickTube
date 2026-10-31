package com.edureminder.quicktube.feature.gallery

import com.edureminder.quicktube.core.model.GalleryAlbum
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.core.navigation.GALLERY_ALL
import com.edureminder.quicktube.core.navigation.GALLERY_AUDIOS
import com.edureminder.quicktube.core.navigation.GALLERY_PHOTOS
import com.edureminder.quicktube.core.navigation.GALLERY_VIDEOS
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row element of the photo grid: a month header spanning the row, or a photo/video cell. */
sealed interface GridEntry {
    val key: Any

    /** [first] and [count] address this month's media in the flat item list. */
    data class Header(val month: YearMonth, val label: String, val first: Int, val count: Int) : GridEntry {
        override val key: Any get() = "h$month"
    }

    data class Cell(val media: GalleryMedia, val index: Int, val month: YearMonth) : GridEntry {
        override val key: Any get() = media.id
    }
}

/** Month sections like the system gallery: "Oct" for this year, "Sep, 2025" for earlier years. */
object GallerySections {

    fun monthLabel(month: YearMonth, currentYear: Int, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(if (month.year == currentYear) "MMM" else "MMM, yyyy", locale).format(month)

    /** [items] must be newest first. Runs in linear time; the month is only recomputed when an item leaves the current one. */
    fun build(
        items: List<GalleryMedia>,
        zone: ZoneId = ZoneId.systemDefault(),
        currentYear: Int = YearMonth.now(zone).year,
        locale: Locale = Locale.getDefault(),
    ): List<GridEntry> {
        val out = ArrayList<GridEntry>(items.size + items.size / 8 + 1)
        var month: YearMonth? = null
        var monthStart = Long.MAX_VALUE
        var monthEnd = Long.MIN_VALUE
        var headerAt = -1
        var first = 0
        fun closeHeader(end: Int) {
            if (headerAt >= 0) out[headerAt] = (out[headerAt] as GridEntry.Header).copy(count = end - first)
        }
        items.forEachIndexed { i, m ->
            if (month == null || m.dateMs < monthStart || m.dateMs >= monthEnd) {
                closeHeader(i)
                val ym = YearMonth.from(Instant.ofEpochMilli(m.dateMs).atZone(zone))
                month = ym
                monthStart = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                first = i
                headerAt = out.size
                out += GridEntry.Header(ym, monthLabel(ym, currentYear, locale), i, 0)
            }
            out += GridEntry.Cell(m, i, month!!)
        }
        closeHeader(items.size)
        return out
    }

    /**
     * What one gallery screen shows: [GALLERY_ALL] is every photo and video (the Photos tab), the virtual albums one
     * kind from every folder, and a real folder its photos and videos. Audio lives only in the Audios album.
     */
    fun filter(items: List<GalleryMedia>, bucketId: Long): List<GalleryMedia> = when (bucketId) {
        GALLERY_ALL -> items.filter { !it.isAudio }
        GALLERY_PHOTOS -> items.filter { it.isPhoto }
        GALLERY_VIDEOS -> items.filter { it.isVideo }
        GALLERY_AUDIOS -> items.filter { it.isAudio }
        else -> items.filter { it.bucketId == bucketId && !it.isAudio }
    }

    /** The three albums that exist only in the gallery: all photos, all videos and all audio, newest item as cover. */
    fun virtualAlbums(items: List<GalleryMedia>): List<GalleryAlbum> {
        var photos = 0; var videos = 0; var audios = 0
        var photo: GalleryMedia? = null; var video: GalleryMedia? = null; var audio: GalleryMedia? = null
        for (m in items) when {
            m.isAudio -> { audios++; if (audio == null) audio = m }
            m.isVideo -> { videos++; if (video == null) video = m }
            else -> { photos++; if (photo == null) photo = m }
        }
        return listOf(
            GalleryAlbum(GALLERY_PHOTOS, "Photos", photos, photo, virtual = true),
            GalleryAlbum(GALLERY_VIDEOS, "Videos", videos, video, virtual = true),
            GalleryAlbum(GALLERY_AUDIOS, "Audios", audios, audio, virtual = true),
        )
    }

    /**
     * The player's queue for [media]: the other files of the same kind (video or audio) in the same folder, so opening a
     * video from the Videos album or the Photos tab still only lists that folder's videos.
     */
    fun folderQueue(items: List<GalleryMedia>, media: GalleryMedia): List<GalleryMedia> =
        items.filter { it.bucketId == media.bucketId && it.isAudio == media.isAudio && it.isVideo == media.isVideo }

    /** Folders of photos and videos with their newest item as cover, most recently updated first. */
    fun albums(items: List<GalleryMedia>): List<GalleryAlbum> {
        val counts = LinkedHashMap<Long, Int>()
        val covers = HashMap<Long, GalleryMedia>()
        for (m in items) {
            if (m.isAudio) continue
            counts[m.bucketId] = (counts[m.bucketId] ?: 0) + 1
            covers.getOrPut(m.bucketId) { m }
        }
        return counts.map { (id, n) -> covers.getValue(id).let { GalleryAlbum(id, it.bucketName, n, it) } }
    }
}

/** Selection rules shared by the photo grid and album pages. */
object GallerySelection {

    fun toggle(selection: Set<Long>, id: Long): Set<Long> = if (id in selection) selection - id else selection + id

    /** Header checkbox: selects the whole month, or clears it when it is already fully selected. */
    fun toggleSection(selection: Set<Long>, sectionIds: List<Long>): Set<Long> =
        if (sectionIds.isNotEmpty() && selection.containsAll(sectionIds)) selection - sectionIds.toSet() else selection + sectionIds

    /**
     * Drag-to-select: everything between where the long press started ([anchor]) and the finger ([current]),
     * on top of what was selected before the drag. Dragging back shrinks the range again.
     */
    fun dragRange(base: Set<Long>, items: List<GalleryMedia>, anchor: Int, current: Int): Set<Long> {
        if (items.isEmpty()) return base
        val from = minOf(anchor, current).coerceIn(0, items.lastIndex)
        val to = maxOf(anchor, current).coerceIn(0, items.lastIndex)
        val out = HashSet<Long>(base)
        for (i in from..to) out += items[i].id
        return out
    }
}
