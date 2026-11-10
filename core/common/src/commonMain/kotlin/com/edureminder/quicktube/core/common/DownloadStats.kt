package com.edureminder.quicktube.core.common

/**
 * What download analytics may carry about a link and a failure: the site's domain ("tiktok.com", never the address
 * or title) and a short reason code. Values stay short and few, well inside Firebase's limits (100 characters a value,
 * 25 values an event).
 */
object DownloadStats {
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac")

    /** "https://m.facebook.com/watch?v=1" → "facebook.com"; "vt.tiktok.com" → "tiktok.com"; "bbc.co.uk" stays; null for non-links. */
    fun site(url: String?): String? {
        val host = Urls.host(url)?.lowercase()?.trimEnd('.') ?: return null
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return host
        if (parts.all { p -> p.all(Char::isDigit) }) return "ip"
        val keep = if (parts.size >= 3 && parts[parts.size - 2] in SECOND_LEVEL && parts.last().length == 2) 3 else 2
        return parts.takeLast(keep).joinToString(".").take(60)
    }

    /** A failure while downloading as a short code: "http_403", "timeout", "no_internet", "storage_full"… */
    fun reason(e: Throwable): String {
        val msg = (e.message ?: "").lowercase()
        val name = e.javaClass.simpleName
        return when {
            name == "HttpStatusException" -> Regex("""\b(\d{3})\b""").find(e.message ?: "")?.let { "http_${it.value}" } ?: "http_error"
            e is java.net.UnknownHostException -> "no_internet"
            e is java.net.SocketTimeoutException || "timeout" in msg -> "timeout"
            e is javax.net.ssl.SSLException -> "ssl"
            "enospc" in msg || "no space" in msg -> "storage_full"
            "eacces" in msg || "permission" in msg -> "storage_permission"
            name == "UnsupportedCodecException" -> "unsupported_codec"
            "merge" in msg || "muxer" in msg -> "merge_failed"
            "expired" in msg || "403" in msg -> "link_expired"
            e is java.io.IOException -> "io_error"
            else -> name.take(40).ifBlank { "other" }
        }
    }

    /** A link that gave no formats, from the message the sheet shows: "removed_or_private", "live", "drm"… */
    fun findReason(error: String?): String {
        val e = error.orEmpty().lowercase()
        return when {
            e.isBlank() -> "no_formats"
            "removed or is private" in e || "private" in e || "unavailable" in e || "deleted" in e -> "removed_or_private"
            "live" in e -> "live"
            "drm" in e || "protected" in e -> "drm"
            "sign in" in e || "login" in e || "log in" in e -> "login_required"
            "age" in e && "restrict" in e -> "age_restricted"
            "country" in e || "region" in e || "geo" in e -> "geo_blocked"
            "timeout" in e || "timed out" in e -> "timeout"
            "unable to resolve host" in e || "no internet" in e || "network" in e -> "no_internet"
            "no video found" in e || "couldn't get this video" in e || "no downloadable media" in e -> "no_video_found"
            "429" in e || "too many" in e -> "rate_limited"
            "403" in e -> "http_403"
            else -> "other"
        }
    }
}
