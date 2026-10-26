package com.edureminder.quicktube.core.model

import kotlinx.serialization.Serializable

enum class ItemKind { VIDEO, CHANNEL, PLAYLIST }

/** A list entry from search, trending, related, channel or playlist pages. */
@Serializable
data class MediaItem(
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val uploader: String = "",
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val durationSec: Long = -1,
    val viewCount: Long = -1,
    val uploadDate: String? = null,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val kind: ItemKind = ItemKind.VIDEO,
    val subscriberCount: Long = -1,
    val streamCount: Long = -1,
    val description: String? = null,
)

data class MediaSection(val title: String, val items: List<MediaItem>, val style: SectionStyle = SectionStyle.ROW)

enum class SectionStyle { ROW, LIST }

data class Page<T>(val items: List<T>, val nextPage: Any? = null) {
    val hasMore get() = nextPage != null
}

data class Comment(
    val author: String,
    val authorAvatar: String?,
    val text: String,
    val likeCount: Int,
    val date: String?,
    val replyCount: Int = 0,
)

enum class FormatKind { AUDIO, VIDEO }

/** How a stream is fetched: a plain file, or an HLS (.m3u8) playlist of segments. */
enum class StreamProtocol { HTTP, HLS }

/** One downloadable choice shown in the "Download video as" sheet. */
data class DownloadOption(
    val id: String,
    val kind: FormatKind,
    val title: String,
    val info: String,
    val ext: String,
    val mimeType: String,
    val url: String,
    /** When set the video stream has no audio and must be merged with this audio stream. */
    val audioUrl: String? = null,
    val sizeBytes: Long = -1,
    val audioSizeBytes: Long = -1,
    val bitrate: Int = 0,
    val height: Int = 0,
    val label: String? = null,
    val protocol: StreamProtocol = StreamProtocol.HTTP,
    /** Download the video, then keep only its audio track (music option for any website). */
    val extractAudio: Boolean = false,
) {
    val totalSize get() = if (sizeBytes < 0) -1 else sizeBytes + audioSizeBytes.coerceAtLeast(0)
}

data class VideoDetails(
    val item: MediaItem,
    val description: String,
    val likeCount: Long,
    val subscriberCount: Long,
    val uploadDate: String?,
    val playbackUrl: String?,
    val playbackMime: String?,
    val hlsUrl: String?,
    val dashUrl: String?,
    val audioPlaybackUrl: String?,
    val options: List<DownloadOption>,
    val related: List<MediaItem>,
    val commentsUrl: String?,
)

data class ChannelDetails(
    val item: MediaItem,
    val banner: String?,
    val description: String?,
    val videos: Page<MediaItem>,
)

data class PlaylistDetails(
    val item: MediaItem,
    val videos: Page<MediaItem>,
)

enum class SearchFilter { ALL, VIDEOS, PLAYLISTS, CHANNELS, MUSIC }
