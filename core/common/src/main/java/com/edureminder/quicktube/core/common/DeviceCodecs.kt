package com.edureminder.quicktube.core.common

import android.media.MediaCodecList

/** What this phone can play, so downloads never offer a format the user couldn't watch. */
object DeviceCodecs {
    /** AV1 (Facebook's and YouTube's HD ladders): hardware on newer phones, software on most Android 10+. */
    val canDecodeAv1: Boolean by lazy { hasDecoder("video/av01") }

    private fun hasDecoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }.getOrDefault(false)
}
