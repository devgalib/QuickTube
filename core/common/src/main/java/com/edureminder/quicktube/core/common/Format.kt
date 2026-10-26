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

    fun views(n: Long) = if (n < 0) "" else "${count(n)} views"
}
