package com.edureminder.quicktube.core.download

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

/**
 * Wakes the app for "Download later": at the chosen time (and on Wi-Fi when asked), even when the app was closed.
 * It starts the due downloads and stays up while they run (Android gives a job up to about ten minutes; longer
 * downloads go on in the download service).
 */
class DownloadLaterJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val manager = get<DownloadManager>(DownloadManager::class.java)
        manager.start()
        manager.reschedule()
        scope.launch {
            // Keep the app alive while the downloads it started run (checked every few seconds, up to ~9 minutes).
            val engine = manager as? DownloadEngine
            repeat(108) {
                delay(5_000)
                if (engine != null && !engine.busy()) { jobFinished(params, false); return@launch }
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val ID = 4202

        /** Plans the wake-up for the earliest waiting download ([at] epoch ms, or null for Wi-Fi-only ones); none when nothing waits. */
        fun schedule(context: Context, at: Long?, wifi: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (at == null && !wifi) { js.cancel(ID); return }
            val b = JobInfo.Builder(ID, ComponentName(context, DownloadLaterJob::class.java))
                .setRequiredNetworkType(if (wifi) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            if (at != null) b.setMinimumLatency((at - System.currentTimeMillis()).coerceAtLeast(0)).setOverrideDeadline((at - System.currentTimeMillis()).coerceAtLeast(0) + 15 * 60_000L)
            js.schedule(b.build())
        }
    }
}
