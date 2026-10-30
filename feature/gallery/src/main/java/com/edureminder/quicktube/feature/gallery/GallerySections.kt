package com.edureminder.quicktube.feature.gallery

import com.edureminder.quicktube.core.model.GalleryAlbum
import com.edureminder.quicktube.core.model.GalleryMedia
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

    /** Folders with their newest item as cover, most recently updated first. */
    fun albums(items: List<GalleryMedia>): List<GalleryAlbum> {
        val counts = LinkedHashMap<Long, Int>()
        val covers = HashMap<Long, GalleryMedia>()
        for (m in items) {
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
