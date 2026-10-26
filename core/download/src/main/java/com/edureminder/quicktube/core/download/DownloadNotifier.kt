package com.edureminder.quicktube.core.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.database.DownloadDao
import com.edureminder.quicktube.core.database.DownloadEntity
import com.edureminder.quicktube.core.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class DownloadNotifier(
    private val context: Context,
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
) {
    private val nm = NotificationManagerCompat.from(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serviceRunning = false
    @Volatile private var lastRefresh = 0L

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val m = context.getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
            m.createNotificationChannel(
                NotificationChannel(CH_DONE, "Download completed", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /** Starts/stops the foreground service and redraws the progress notification. */
    fun refresh(hasWork: Boolean) {
        val now = System.currentTimeMillis()
        if (hasWork && serviceRunning && now - lastRefresh < 700) return
        lastRefresh = now
        if (hasWork && !serviceRunning) {
            // Android 12+ refuses foreground-service starts while the app is in the background;
            // downloads keep running in-process and the service starts next time we're allowed.
            serviceRunning = runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }.isSuccess
        }
        scope.launch {
            val running = dao.byStatus(listOf(DownloadStatus.RUNNING, DownloadStatus.MERGING, DownloadStatus.QUEUED))
            if (running.isEmpty() && !hasWork) {
                serviceRunning = false
                context.stopService(Intent(context, DownloadService::class.java))
                nm.cancel(ID_PROGRESS)
                return@launch
            }
            if (canPost()) nm.notify(ID_PROGRESS, progressNotification(running))
        }
    }

    fun progressNotification(running: List<DownloadEntity>): android.app.Notification {
        val first = running.firstOrNull()
        val total = running.sumOf { (it.totalBytes + it.audioTotalBytes.coerceAtLeast(0)).coerceAtLeast(0) }
        val done = running.sumOf { it.downloadedBytes + it.audioDownloadedBytes }
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val title = when {
            first == null -> "Preparing downloads"
            running.size == 1 -> first.title
            else -> "Downloading ${running.size} files"
        }
        val text = if (first?.status == DownloadStatus.MERGING) "Processing video…"
        else "${Format.bytes(done)} / ${if (total > 0) Format.bytes(total) else "--"}"
        return NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp("downloads"))
            .addAction(0, "Pause all", actionIntent(DownloadService.ACTION_PAUSE_ALL))
            .build()
    }

    fun completed(task: com.edureminder.quicktube.core.model.DownloadTask) = scope.launch {
        if (!settings.current().notifyCompleted || !canPost()) return@launch
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(task.title)
            .setAutoCancel(true)
            .setContentIntent(openApp("play:${task.id}"))
            .build()
        nm.notify((task.id % Int.MAX_VALUE).toInt() + 1000, n)
    }

    fun failed(task: DownloadEntity) = scope.launch {
        if (!canPost()) return@launch
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed")
            .setContentText(task.title + (task.error?.let { " · $it" } ?: ""))
            .setAutoCancel(true)
            .setContentIntent(openApp("downloads"))
            .build()
        nm.notify((task.id % Int.MAX_VALUE).toInt() + 1000, n)
    }

    private fun canPost() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(target: String): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .putExtra(EXTRA_OPEN, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, target.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(action: String) = PendingIntent.getService(
        context, action.hashCode(), Intent(context, DownloadService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CH_PROGRESS = "downloads"
        const val CH_DONE = "downloads_done"
        const val ID_PROGRESS = 42
        const val EXTRA_OPEN = "open"
    }
}
