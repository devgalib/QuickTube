package com.edureminder.quicktube.core.download

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import org.koin.android.ext.android.inject

/** Keeps the process alive while downloads run. */
class DownloadService : LifecycleService() {
    private val notifier: DownloadNotifier by inject()
    private val manager: DownloadManager by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(
            this, DownloadNotifier.ID_PROGRESS, notifier.progressNotification(emptyList()),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        when (intent?.action) {
            ACTION_PAUSE_ALL -> manager.pauseAll()
        }
        manager.start()
        notifier.refresh(true)
        return START_STICKY
    }

    /** Android 15+: data-sync services get ~6h per day; pause cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.pauseAll()
        stopSelf()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "pause_all"
    }
}
