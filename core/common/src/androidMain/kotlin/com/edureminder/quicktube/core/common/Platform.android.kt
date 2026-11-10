package com.edureminder.quicktube.core.common

import android.media.MediaMetadataRetriever
import android.util.Log

actual object QtLog {
    actual fun i(tag: String, message: String) { Log.i(tag, message) }
    actual fun w(tag: String, message: String, error: Throwable?) { Log.w(tag, message, error) }
}

actual object MediaProbe {
    actual fun durationSec(path: String): Long = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1000) / 1000
        } finally { r.release() }
    }.getOrDefault(-1)
}
