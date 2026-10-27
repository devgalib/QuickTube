package com.edureminder.quicktube.core.download

import com.edureminder.quicktube.core.model.DownloadRequest
import com.edureminder.quicktube.core.model.DownloadTask
import kotlinx.coroutines.flow.StateFlow

/** Entry point features use to start and control downloads. */
interface DownloadManager {
    /** Live bytes/sec per running task id. */
    val speeds: StateFlow<Map<Long, Long>>

    suspend fun enqueue(request: DownloadRequest): Long

    /**
     * Fire-and-forget [enqueue] that runs in the download engine's own scope, so it completes even when the
     * screen that asked for it closes right away (the share sheet does, as soon as a format is tapped).
     */
    fun submit(request: DownloadRequest)

    /** The same video in the same quality, already downloaded (file still there) or still downloading; else null. */
    suspend fun findExisting(request: DownloadRequest): DownloadTask?
    fun pause(id: Long)
    fun resume(id: Long)
    fun pauseAll()
    fun resumeAll()
    /** Stops a task and moves it (and any partial file) to the recycle bin. */
    suspend fun remove(ids: List<Long>)
    /** Re-schedules after settings or network changes. */
    fun reschedule()
    fun start()
}
