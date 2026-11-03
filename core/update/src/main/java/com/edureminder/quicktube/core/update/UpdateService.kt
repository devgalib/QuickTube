package com.edureminder.quicktube.core.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.edureminder.quicktube.core.common.Format
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Keeps the app alive while an update downloads, so it goes on when the user leaves QuickTube, and shows the progress
 * in the notification bar. It follows [UpdateManager.state] and stops itself once the download is over (it is never
 * stopped from outside: a stop before startForeground() crashes the app).
 */
class UpdateService : LifecycleService() {
    private val manager: UpdateManager by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val info = (manager.state.value as? UpdateState.Downloading)?.info
        val started = runCatching {
            ServiceCompat.startForeground(
                this, UpdateNotifier.ID_PROGRESS, UpdateNotifier.progress(this, info?.versionName.orEmpty(), 0, -1),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.onFailure { Log.w("UpdateService", "Can't run in the foreground: $it") }.isSuccess
        if (!started) { stopSelf(); return START_NOT_STICKY }
        lifecycleScope.launch {
            var last = 0L
            manager.state.collect { s ->
                if (s is UpdateState.Downloading) {
                    val now = System.currentTimeMillis()
                    if (now - last >= 500 || s.bytes == s.total) {
                        last = now
                        UpdateNotifier.notify(this@UpdateService, UpdateNotifier.ID_PROGRESS, UpdateNotifier.progress(this@UpdateService, s.info.versionName, s.bytes, s.total))
                    }
                } else {
                    ServiceCompat.stopForeground(this@UpdateService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, UpdateService::class.java)) }
                .onFailure { Log.w("UpdateService", "Couldn't start: $it") }
        }
    }
}

/** The update's notifications: download progress, "ready to install" and "updated". */
internal object UpdateNotifier {
    const val ID_PROGRESS = 7101
    const val ID_READY = 7102
    const val ID_DONE = 7103
    private const val CH_PROGRESS = "app_update_progress"
    private const val CH_STATUS = "app_update"

    private fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "App update progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "App updates", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openApp(context: Context): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun progress(context: Context, version: String, bytes: Long, total: Long): Notification {
        channels(context)
        val percent = if (total > 0) (bytes * 100 / total).toInt() else 0
        return NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Updating QuickTube" + if (version.isNotBlank()) " to $version" else "")
            .setContentText(if (total > 0) "${Format.bytes(bytes)} of ${Format.bytes(total)} · $percent%" else "Starting download…")
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()
    }

    /** The download is done but Android needs a tap to install it (the app was in the background). */
    fun ready(context: Context, version: String, confirm: Intent) {
        channels(context)
        val tap = PendingIntent.getActivity(
            context, 1, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(context, ID_READY, NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("QuickTube $version is ready")
            .setContentText("Tap to install the update")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build())
    }

    /** Shown by the new version right after it was installed, in case Android didn't let it open by itself. */
    fun updated(context: Context, version: String) {
        channels(context)
        notify(context, ID_DONE, NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("QuickTube updated" + if (version.isNotBlank()) " to $version" else "")
            .setContentText("Tap to open")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build())
    }

    fun cancel(context: Context, id: Int) = context.getSystemService(NotificationManager::class.java).cancel(id)

    fun notify(context: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
