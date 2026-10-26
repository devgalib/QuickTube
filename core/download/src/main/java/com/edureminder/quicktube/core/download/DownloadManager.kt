package com.edureminder.quicktube.core.download

import com.edureminder.quicktube.core.model.DownloadRequest
import kotlinx.coroutines.flow.StateFlow

/** Entry point features use to start and control downloads. */
interface DownloadManager {
    /** Live bytes/sec per running task id. */
    val speeds: StateFlow<Map<Long, Long>>

    suspend fun enqueue(request: DownloadRequest): Long
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
