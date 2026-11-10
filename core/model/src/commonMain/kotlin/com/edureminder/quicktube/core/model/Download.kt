package com.edureminder.quicktube.core.model

enum class ContentType { VIDEO, AUDIO, IMAGE, OTHER;
    companion object {
        fun fromMime(mime: String?): ContentType = when {
            mime == null -> OTHER
            mime.startsWith("video") -> VIDEO
            mime.startsWith("audio") -> AUDIO
            mime.startsWith("image") -> IMAGE
            else -> OTHER
        }
    }
}

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, WAITING_NETWORK, MERGING, COMPLETED, FAILED;
    val isActive get() = this == QUEUED || this == RUNNING || this == MERGING || this == WAITING_NETWORK
}

data class DownloadTask(
    val id: Long = 0,
    val title: String,
    val pageUrl: String?,
    val sourceHost: String?,
    val thumbnail: String?,
    val url: String,
    val audioUrl: String?,
    val filePath: String,
    val mimeType: String,
    val type: ContentType,
    val quality: String?,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val audioTotalBytes: Long = -1,
    val audioDownloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
    val durationSec: Long = -1,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val inVault: Boolean = false,
    val deletedAt: Long = 0,
    val lastPlayedPositionMs: Long = 0,
    val speedBps: Long = 0,
    val protocol: StreamProtocol = StreamProtocol.HTTP,
    /** A cut of the video: only [clipStartMs] to [clipEndMs] is downloaded. 0 / 0 = the whole video. */
    val clipStartMs: Long = 0,
    val clipEndMs: Long = 0,
    /** "Download later": not before this time (epoch ms), 0 = now. */
    val startAfter: Long = 0,
    /** Waits for Wi-Fi. */
    val wifiOnly: Boolean = false,
) {
    val isClip get() = clipEndMs > clipStartMs
    val progress: Float
        get() {
            val total = totalBytes + audioTotalBytes.coerceAtLeast(0)
            return if (totalBytes <= 0) 0f else ((downloadedBytes + audioDownloadedBytes).toFloat() / total).coerceIn(0f, 1f)
        }
    val fileName get() = filePath.substringAfterLast('/')
}

/** What a screen asks the download engine to fetch. */
data class DownloadRequest(
    val title: String,
    val url: String,
    val audioUrl: String? = null,
    val ext: String,
    val mimeType: String,
    val pageUrl: String? = null,
    val thumbnail: String? = null,
    val quality: String? = null,
    val sizeBytes: Long = -1,
    val audioSizeBytes: Long = -1,
    val durationSec: Long = -1,
    val headers: Map<String, String> = emptyMap(),
    val protocol: StreamProtocol = StreamProtocol.HTTP,
    val extractAudio: Boolean = false,
    /** Download only this part of the video (both 0 = all of it). */
    val clipStartMs: Long = 0,
    val clipEndMs: Long = 0,
    /** A photo post with music: its pictures, made into a video over [url] (the music). */
    val images: List<String> = emptyList(),
    /** A folder inside the download folder (a bulk download's name); null = the download folder itself. */
    val folder: String? = null,
    /** "Download later": not before this time (epoch ms), 0 = now. */
    val startAfter: Long = 0,
    /** Waits for Wi-Fi even when mobile data is allowed. */
    val wifiOnly: Boolean = false,
)

/** When a download sheet's "Later" choice starts the download. */
object DownloadLater {
    /** The next 1:00 AM after [now] (local time). */
    fun tonight(now: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
        java.util.Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 1); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

    /** The next [hour]:[minute] after [now]. */
    fun at(hour: Int, minute: Int, now: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
        java.util.Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

    /** True while [task] has to wait (its time hasn't come, or it wants Wi-Fi and the phone isn't on Wi-Fi). */
    fun waiting(startAfter: Long, wifiOnly: Boolean, onWifi: Boolean, now: Long = System.currentTimeMillis()) =
        startAfter > now || (wifiOnly && !onWifi)
}

/** A download the user deleted permanently; kept so it can be downloaded again. */
data class DeletedDownload(
    val id: Long,
    val title: String,
    val pageUrl: String?,
    val url: String,
    val thumbnail: String?,
    val mimeType: String,
    val quality: String?,
    val sizeBytes: Long,
    val durationSec: Long,
    val deletedAt: Long,
)
