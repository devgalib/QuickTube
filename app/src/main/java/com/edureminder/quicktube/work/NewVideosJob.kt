package com.edureminder.quicktube.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.edureminder.quicktube.MainActivity
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.data.SubscriptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Settings → Notifications → New videos: a few times a day, when online, checks the channels followed here and
 * notifies about their new uploads (one notification per channel). Android's own job scheduler runs it, so the app
 * needn't be open and no extra library is needed.
 */
class NewVideosJob : JobService() {
    private val subs: SubscriptionRepository by inject()
    private val settings: SettingsRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val ok = runCatching { check() }.isSuccess
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    private suspend fun check() {
        if (!settings.current().notifyRecommended) return
        val news = subs.newUploads()
        if (news.isEmpty() || !canPost()) return
        val nm = NotificationManagerCompat.from(this)
        ensureChannel(this)
        news.forEach { (channel, videos) ->
            val first = videos.first()
            val open = PendingIntent.getActivity(
                this, channel.url.hashCode(),
                Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(first.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(if (videos.size == 1) "New from ${channel.name}" else "${videos.size} new videos from ${channel.name}")
                .setContentText(first.title)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            @Suppress("MissingPermission") nm.notify(channel.url.hashCode(), n)
        }
    }

    private fun canPost() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        private const val ID = 4201
        private const val CHANNEL = "new_videos"

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "New videos", NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** Every ~6 hours on any network; cancelled when the setting is off or nothing is followed. */
        fun schedule(context: Context, on: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!on) { js.cancel(ID); return }
            if (js.getPendingJob(ID) != null) return
            js.schedule(
                JobInfo.Builder(ID, ComponentName(context, NewVideosJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setPersisted(false)
                    .build(),
            )
        }
    }
}
