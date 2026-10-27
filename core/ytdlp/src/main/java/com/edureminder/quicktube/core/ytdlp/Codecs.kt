package com.edureminder.quicktube.core.ytdlp

/** yt-dlp codec strings ("avc1.64001F", "vp09.00.40.08", "av01.0.08M.08", "mp4a.40.2", "opus") sorted by how usable they are. */
internal object Codecs {
    const val UNUSABLE = 100

    /** H.264 plays everywhere, so it's preferred at equal height; the others still fill in heights it lacks. */
    enum class Video(val rank: Int) {
        H264(0), UNKNOWN(1), H265(2), AV1(3), VP9(4), AV1_UNPLAYABLE(UNUSABLE), OTHER(UNUSABLE),
    }

    enum class Audio { AAC, OPUS, UNKNOWN, OTHER }

    /**
     * Downloads are merged by Media3's MP4 muxer, which takes AV1 and VP9 on every Android version, so the
     * only limit is whether this phone can play AV1 ([av1Playable]).
     */
    fun video(codec: String?, av1Playable: Boolean): Video {
        val c = codec?.lowercase() ?: return Video.UNKNOWN
        return when {
            c.startsWith("avc") || c.startsWith("h264") -> Video.H264
            c.startsWith("hvc") || c.startsWith("hev") || c.startsWith("h265") -> Video.H265
            c.startsWith("av01") || c == "av1" -> if (av1Playable) Video.AV1 else Video.AV1_UNPLAYABLE
            c.startsWith("vp9") || c.startsWith("vp09") || c.startsWith("vp8") -> Video.VP9
            else -> Video.OTHER
        }
    }

    fun audio(codec: String?, ext: String): Audio {
        val c = codec?.lowercase() ?: return when (ext) { "m4a" -> Audio.AAC; "webm" -> Audio.OPUS; else -> Audio.UNKNOWN }
        return when {
            c.startsWith("mp4a") || c.startsWith("aac") -> Audio.AAC
            c.startsWith("opus") || c.startsWith("vorbis") -> Audio.OPUS
            else -> Audio.OTHER
        }
    }
}
