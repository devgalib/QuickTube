package com.edureminder.quicktube.core.download

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import org.koin.android.ext.android.inject

/**
 * Keeps the process alive while downloads run.
 *
 * Android kills the app if a service started with startForegroundService() is stopped before it calls
 * startForeground(), so it is never stopped from outside: [requestStop] asks it to stop itself once it is in the
 * foreground. And a restart Android does in the background (START_STICKY) may not be allowed to go foreground (Android
 * 12+): then it just stops, and the downloads carry on in the app's process.
 */
class DownloadService : LifecycleService() {
    private val notifier: DownloadNotifier by inject()
    private val manager: DownloadManager by inject()
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        inForeground = false
        notifier.serviceStopped()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        val started = try {
            ServiceCompat.startForeground(
                this, DownloadNotifier.ID_PROGRESS, notifier.progressNotification(emptyList()),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException and the like: not now (e.g. restarted in the background).
            Log.w(TAG, "Can't run in the foreground now: $e")
            false
        }
        manager.start()
        if (!started) {
            notifier.serviceStopped()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        inForeground = true
        if (stopRequested) {
            stopRequested = false
            stopNow()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE_ALL -> manager.pauseAll()
        }
        notifier.refresh(true)
        return START_STICKY
    }

    /**
     * Stops only if no newer start is on its way: stopping while a startForegroundService() call is still queued
     * kills the app ("did not then call startForeground()"). The newer start then runs and goes foreground again.
     */
    private fun stopNow(force: Boolean = false) {
        inForeground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (force) stopSelf() else stopSelf(lastStartId)
    }

    /** Android 15+: data-sync services get ~6h per day; pause cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.pauseAll()
        stopNow(force = true) // Must stop within seconds; no new foreground start is allowed now anyway.
    }

    companion object {
        const val ACTION_PAUSE_ALL = "pause_all"
        private const val TAG = "DownloadService"
        @Volatile private var instance: DownloadService? = null
        @Volatile private var inForeground = false
        @Volatile private var stopRequested = false
        private val main = Handler(Looper.getMainLooper())

        /** A start was just requested: forget an earlier stop request still waiting for that start. */
        internal fun startRequested() = main.post { stopRequested = false } // in order with requestStop's posts

        /** Stops the service once it's safe: now if it's in the foreground, else as soon as it gets there. */
        internal fun requestStop() = main.post {
            val s = instance
            if (s != null && inForeground) s.stopNow() else stopRequested = true
        }
    }
}
