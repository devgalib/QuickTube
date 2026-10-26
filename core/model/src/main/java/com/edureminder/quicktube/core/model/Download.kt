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
) {
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
)

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
