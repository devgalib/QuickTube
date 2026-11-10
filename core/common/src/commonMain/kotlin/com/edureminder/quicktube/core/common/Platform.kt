package com.edureminder.quicktube.core.common

/** Logging that goes to logcat on Android and to stderr on desktop. Never log URLs, titles or file names. */
expect object QtLog {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
}

/** Reads facts about a local media file. */
expect object MediaProbe {
    /** Length of the audio/video file at [path] in seconds, or -1 when it can't be read. */
    fun durationSec(path: String): Long
}
