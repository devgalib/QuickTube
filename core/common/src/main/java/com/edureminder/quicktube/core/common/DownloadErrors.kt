package com.edureminder.quicktube.core.common

/**
 * A short, human message for a failed download. Raw errors (file paths, exception text) wrap over many lines
 * on small screens and mean nothing to users; this keeps one line that says what to do.
 */
object DownloadErrors {
    fun friendly(raw: String?): String {
        val e = raw.orEmpty()
        return when {
            e.isBlank() -> "Download failed"
            "ENOSPC" in e || "No space" in e -> "Not enough storage"
            "ENAMETOOLONG" in e || "File name too long" in e -> "Name too long. Tap retry"
            "Link expired" in e || "HTTP 403" in e || "HTTP 410" in e -> "Link expired. Tap retry"
            "login" in e.lowercase() -> "Needs sign-in on this site"
            "HTTP 404" in e || "File not found" in e -> "Video was removed"
            "No internet" in e || "UnknownHost" in e || "Unable to resolve host" in e -> "No internet connection"
            "timed out" in e || "Timeout" in e -> "Connection timed out. Tap retry"
            "can't be saved" in e || "Unsupported" in e -> "Format not supported. Tap retry"
            "DRM" in e -> "Protected video (DRM)"
            "Live" in e -> "Live streams can't be downloaded"
            "Could not save" in e || "EACCES" in e || "ENOENT" in e -> "Couldn't save the file. Tap retry"
            e.length <= 40 && '/' !in e && ':' !in e -> e
            else -> "Download failed. Tap retry"
        }
    }
}
