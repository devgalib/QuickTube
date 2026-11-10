package com.edureminder.quicktube.core.common

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Format {
    fun bytes(bytes: Long): String {
        if (bytes < 0) return "--"
        if (bytes < 1024) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(4)
        val unit = "KMGT"[exp - 1]
        return String.format(Locale.US, "%.1f %sB", bytes / 1024.0.pow(exp), unit)
    }

    fun speed(bps: Long): String = if (bps <= 0) "0 KB/s" else bytes(bps) + "/s"

    fun duration(seconds: Long): String {
        if (seconds < 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun durationMs(ms: Long) = duration(ms / 1000)

    fun count(n: Long): String = when {
        n < 0 -> ""
        n < 1_000 -> n.toString()
        n < 1_000_000 -> trim(n / 1_000.0) + "K"
        n < 1_000_000_000 -> trim(n / 1_000_000.0) + "M"
        else -> trim(n / 1_000_000_000.0) + "B"
    }

    private fun trim(v: Double) =
        if (v >= 100) v.toLong().toString() else String.format(Locale.US, "%.1f", v).removeSuffix(".0")

    /** "1.2M views"; for a live stream its viewers right now ("8.7K watching"). */
    fun views(n: Long, live: Boolean = false) = if (n < 0) "" else "${count(n)} ${if (live) "watching" else "views"}"

    /**
     * "3 days ago" / "Streamed 2 hours ago" / "1 year ago" → about when (epoch ms, [now] by default); 0 when it
     * isn't such a text. Close enough to put uploads in order.
     */
    fun publishedAt(text: String?, now: Long = System.currentTimeMillis()): Long {
        val m = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE).find(text ?: return 0) ?: return 0
        val n = m.groupValues[1].toLong()
        val unit = when (m.groupValues[2].lowercase()) {
            "second" -> 1_000L; "minute" -> 60_000L; "hour" -> 3_600_000L; "day" -> 86_400_000L
            "week" -> 7 * 86_400_000L; "month" -> 30 * 86_400_000L; else -> 365 * 86_400_000L
        }
        return now - n * unit
    }
}
